package com.sismics.docs.core.util;

import com.sismics.docs.core.dao.dto.CleanupFileDto;
import com.sismics.util.context.ThreadLocalContext;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Side-effect-free computation of what a {@code clean_storage} run would reclaim (#60).
 *
 * <p>This is the single source of truth shared by BOTH the dry-run preview endpoint and the real
 * cleanup's apply/protocol path, so the preview cannot drift from what a real run deletes. It performs
 * ONLY SELECTs and read-only {@link Files#size}/{@link Files#isRegularFile} probes on a storage path
 * resolved via {@link DirectoryUtil#getStorageDirectoryReadOnly()} (which never creates the directory)
 * — it never mutates the database or the filesystem.</p>
 *
 * <h3>File COUNT — the complete multi-pass closure</h3>
 * <p>A real run is MULTI-PASS: it first soft-deletes orphan documents (owner gone), then soft-deletes
 * orphan files, then (the #54 structural fix) soft-deletes every still-live file attached to any
 * soft-deleted document, and finally hard-deletes all soft-deleted files. So the set of file ROWS a
 * run removes is the transitive closure over those passes. This computation models that whole
 * closure, so the reported count equals what a same-state apply actually deletes:</p>
 * <ol>
 *   <li><b>Already soft-deleted files</b> — {@code FIL_DELETEDATE_D is not null}.</li>
 *   <li><b>Orphan-uploader files</b> — live rows whose uploader is gone AND that back no live
 *       document.</li>
 *   <li><b>Files attached to a doomed document</b> — live rows whose {@code FIL_IDDOC_C} points at a
 *       document that is (or will become) soft-deleted: already-trashed documents PLUS documents
 *       whose owner is gone (the orphan-document pass soft-deletes those first). This is the #54
 *       class — a collaborator's live file on a trashed/orphaned document.</li>
 * </ol>
 * <p>The DB reclaim set is EXACTLY these rows. The apply deletes the physical files of the exact DB
 * rows it removed (via their ids + {@code _web}/{@code _thumb} derivatives), never a naive
 * filesystem-vs-DB diff — a diff would delete a concurrent upload's just-written bytes whose row has
 * not yet committed and corrupt that upload.</p>
 *
 * <h3>Set 4 — AGE-THRESHOLDED FILESYSTEM ORPHANS (#72)</h3>
 * <p>Genuine filesystem orphans — bytes on disk whose base id has NO {@code T_FILE} row at all (any
 * state), e.g. from a crashed mid-upload — are ALSO reclaimed, but ONLY when the file is OLDER than
 * {@link #ORPHAN_MIN_AGE_MS}. This age gate is the safety invariant that keeps the orphan sweep
 * concurrency-safe: {@link com.sismics.docs.core.util.FileUtil#createFile} writes an upload's encrypted
 * bytes seconds BEFORE its request commits, so a fresh no-DB-row file may be an in-flight upload — it
 * is NEVER reclaimed. A file older than the threshold cannot be an in-flight upload (whose commit
 * window is seconds), so it is a true crash-orphan and is safe to unlink. An orphan's derivatives
 * ({@code {id}_web}/{@code {id}_thumb}) are reclaimed together with its base id.</p>
 *
 * <h3>BYTES — the ACTUAL on-disk footprint, measured identically in dry-run and apply</h3>
 * <p>{@link #getReclaimedBytes()} sums the ACTUAL on-disk sizes of each reclaimed file's physical
 * paths (original {@code {id}} + {@code _web} + {@code _thumb}), for BOTH the DB closure and the
 * age-eligible orphans. Because the real apply calls this same {@code computeManifest()} over the same
 * DB+filesystem state before it mutates, the dry-run and the apply report byte-identical
 * {@code reclaimed_bytes} in the quiescent case — honest parity, not a logical estimate.</p>
 */
public class CleanupStorageUtil {
    /**
     * Minimum age a no-DB-row on-disk file must reach before the orphan sweep (#72) may reclaim it.
     * 24h is comfortably longer than the seconds-long write-bytes-then-commit window of an upload, so
     * an in-flight upload's fresh file is NEVER caught, while a genuine crash-orphan is always old
     * enough. This is the load-bearing safety threshold of the filesystem-orphan reclaim.
     */
    public static final long ORPHAN_MIN_AGE_MS = 24L * 60L * 60L * 1000L;

    private CleanupStorageUtil() {
    }

    /**
     * Immutable result of {@link #computeManifest()}.
     */
    public static class Manifest {
        private final List<CleanupFileDto> files;
        private final long reclaimedBytes;
        private final long primaryPointerClearedCount;

        Manifest(List<CleanupFileDto> files, long reclaimedBytes, long primaryPointerClearedCount) {
            this.files = files;
            this.reclaimedBytes = reclaimedBytes;
            this.primaryPointerClearedCount = primaryPointerClearedCount;
        }

        /**
         * The full, unpaginated list of file resources the run removes (DB rows + filesystem orphans).
         */
        public List<CleanupFileDto> getFiles() {
            return files;
        }

        /**
         * Total number of file resources the run removes (the complete closure).
         */
        public long getTotalCount() {
            return files.size();
        }

        /**
         * Disk bytes actually freed by the run in this state: the summed on-disk footprint (original +
         * {@code _web}/{@code _thumb}) of every file in the removal closure. Measured identically in the
         * dry-run and the apply, so the two agree exactly.
         */
        public long getReclaimedBytes() {
            return reclaimedBytes;
        }

        /**
         * Number of document main-file pointers ({@code DOC_IDFILE_C}) the run would clear.
         */
        public long getPrimaryPointerClearedCount() {
            return primaryPointerClearedCount;
        }
    }

    /**
     * Computes the manifest of everything a {@code clean_storage} run would reclaim, without mutating
     * anything (no DB rows, no filesystem entries, and no directory creation).
     *
     * @return the manifest
     */
    public static Manifest computeManifest() {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        List<CleanupFileDto> files = new ArrayList<>();
        long reclaimedBytes = 0L;

        // Sets (1)–(3): every DB file row the run's closure will hard-delete. The reason column
        // encodes WHICH pass removes it (and therefore whether its bytes free NOW or NEXT run):
        //   soft_deleted    — already soft-deleted at run start → bytes freed THIS run by the sweep
        //   orphan_uploader — uploader gone, backs no live doc → newly soft-deleted → bytes NEXT run
        //   attached_to_deleted_document — #54: live file on a doomed doc → newly soft-deleted → NEXT run
        // A document is "doomed" if it is already soft-deleted OR its owner is gone (the orphan-document
        // pass soft-deletes it before the file passes). Precedence: an already-soft-deleted FILE is
        // labelled soft_deleted even if it also matches a later predicate.
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                "select f.FIL_ID_C, f.FIL_IDDOC_C, doc.DOC_TITLE_C, f.FIL_SIZE_N,"
                        + " case"
                        + "   when f.FIL_DELETEDATE_D is not null then 'soft_deleted'"
                        + "   when liveu.USE_ID_C is null and lived.DOC_ID_C is null then 'orphan_uploader'"
                        + "   else 'attached_to_deleted_document'"
                        + " end as reason"
                        + " from T_FILE f"
                        // uploader still live?
                        + " left join T_USER liveu on liveu.USE_ID_C = f.FIL_IDUSER_C and liveu.USE_DELETEDATE_D is null"
                        // backs a still-live document?
                        + " left join T_DOCUMENT lived on lived.DOC_ID_C = f.FIL_IDDOC_C and lived.DOC_DELETEDATE_D is null"
                        // the file's document (any state), for its title
                        + " left join T_DOCUMENT doc on doc.DOC_ID_C = f.FIL_IDDOC_C"
                        // the file's document's owner (to decide if the doc is doomed by the orphan-doc pass)
                        + " left join T_USER docowner on docowner.USE_ID_C = doc.DOC_IDUSER_C and docowner.USE_DELETEDATE_D is null"
                        + " where f.FIL_DELETEDATE_D is not null"
                        // orphan-uploader files (uploader gone AND not backing a live doc)
                        + "    or (liveu.USE_ID_C is null and lived.DOC_ID_C is null)"
                        // #54: live file attached to a doomed document — already soft-deleted, OR its
                        // owner is gone (so the orphan-document pass will soft-delete it this run)
                        + "    or (doc.DOC_ID_C is not null and (doc.DOC_DELETEDATE_D is not null or docowner.USE_ID_C is null))")
                .getResultList();

        // Resolve the storage path READ-ONLY (never creates the directory) so we can measure the
        // ACTUAL on-disk bytes each deleted file occupies — its encrypted original plus the derivative
        // variants ({id}, {id}_web, {id}_thumb), exactly the set FileUtil.delete removes. This same
        // measurement runs in the real apply (it calls computeManifest before mutating), so the dry-run
        // and the apply report byte-identical reclaimed_bytes for the same state — honest parity.
        Path storageDir = DirectoryUtil.getStorageDirectoryReadOnly();

        Set<String> dbFileIds = new HashSet<>();
        for (Object[] row : rows) {
            String id = (String) row[0];
            String documentId = (String) row[1];
            String documentTitle = (String) row[2];
            String reason = (String) row[4];
            long onDiskSize = onDiskSizeForFile(storageDir, id);
            dbFileIds.add(id);
            files.add(new CleanupFileDto(id, documentId, documentTitle, onDiskSize, reason));
            reclaimedBytes += onDiskSize;
        }

        // Set 4 — AGE-THRESHOLDED FILESYSTEM ORPHANS (#72): on-disk base ids with NO T_FILE row at all
        // (any state) AND older than ORPHAN_MIN_AGE_MS. The age gate is the safety invariant: a fresh
        // no-DB-row file may be a concurrent upload whose row has not yet committed (FileUtil.createFile
        // writes the bytes before commit), so it is NEVER reclaimed; only files too old to be an
        // in-flight upload are unlinked. Derivatives ({id}_web/{id}_thumb) are grouped under their base
        // id and reclaimed together. Same read-only enumeration runs in the apply (which re-checks the
        // age + no-row conditions at unlink time), so the preview matches what will be reclaimed.
        for (OrphanCandidate orphan : findAgeEligibleOrphans(em, storageDir, System.currentTimeMillis())) {
            files.add(new CleanupFileDto(orphan.baseId, null, null, orphan.footprint,
                    CleanupFileDto.REASON_FILESYSTEM_ORPHAN));
            reclaimedBytes += orphan.footprint;
        }

        // Document main-file pointers the run would clear: any document whose DOC_IDFILE_C points at a
        // file that will be gone after the hard-delete — i.e. any DB-eligible file id in the closure.
        long primaryPointerClearedCount = 0L;
        if (!dbFileIds.isEmpty()) {
            Query q = em.createNativeQuery(
                    "select count(*) from T_DOCUMENT d where d.DOC_IDFILE_C in (:ids)");
            q.setParameter("ids", dbFileIds);
            primaryPointerClearedCount = ((Number) q.getSingleResult()).longValue();
        }

        return new Manifest(files, reclaimedBytes, primaryPointerClearedCount);
    }

    /**
     * The exact set of physical paths a cleanup sweep removes for one file id: the encrypted original
     * plus its {@code _web}/{@code _thumb} derivatives. Mirrors {@code FileUtil.delete}'s path set, so
     * the dry-run's byte total and the apply's byte total measure the identical files.
     *
     * @param storageDir storage directory (may be null or non-existent)
     * @param fileId file id
     * @return the candidate on-disk paths (some may not exist)
     */
    public static List<Path> physicalPathsForFile(Path storageDir, String fileId) {
        if (storageDir == null) {
            return List.of();
        }
        return List.of(
                storageDir.resolve(fileId),
                storageDir.resolve(fileId + "_web"),
                storageDir.resolve(fileId + "_thumb"));
    }

    /**
     * Sum of the actual on-disk byte sizes of a file's original + derivative variants (missing files
     * contribute 0). Read-only. Used both by the dry-run manifest (over the read-only storage path) and
     * by the apply's post-commit sweep to measure the footprint it is about to unlink.
     *
     * @param storageDir storage directory (may be null or non-existent)
     * @param fileId file id
     * @return summed on-disk footprint of the file's variants
     */
    public static long onDiskSizeForFile(Path storageDir, String fileId) {
        if (storageDir == null || !Files.isDirectory(storageDir)) {
            return 0L;
        }
        long total = 0L;
        for (Path p : physicalPathsForFile(storageDir, fileId)) {
            try {
                if (Files.isRegularFile(p)) {
                    total += Files.size(p);
                }
            } catch (IOException e) {
                // Unreadable variant contributes 0 rather than aborting the whole manifest.
            }
        }
        return total;
    }

    /**
     * True if at least one of a file id's physical variants (original / {@code _web} / {@code _thumb})
     * currently exists on disk. Used by the apply to count a reclaim ONLY when this run actually saw the
     * bytes present — so two concurrent clean_storage runs do not both count the same already-gone file
     * ({@code FileUtil.delete} is exists-guarded and does not throw on a missing file).
     *
     * @param storageDir storage directory (may be null/non-existent)
     * @param fileId file id
     * @return true if a variant exists on disk
     */
    public static boolean physicalFileExists(Path storageDir, String fileId) {
        if (storageDir == null || !Files.isDirectory(storageDir)) {
            return false;
        }
        for (Path p : physicalPathsForFile(storageDir, fileId)) {
            if (Files.isRegularFile(p)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A genuine filesystem orphan eligible for the age-thresholded reclaim (#72): a base id with no
     * {@code T_FILE} row (any state) whose on-disk variants are all older than {@link #ORPHAN_MIN_AGE_MS}.
     */
    public static class OrphanCandidate {
        public final String baseId;
        public final long footprint;

        OrphanCandidate(String baseId, long footprint) {
            this.baseId = baseId;
            this.footprint = footprint;
        }
    }

    /**
     * True if {@code baseId} has NO {@code T_FILE} row (any state) AND every on-disk variant it owns is
     * older than {@link #ORPHAN_MIN_AGE_MS} relative to {@code nowMs}. This is the shared safety check
     * used by both the dry-run manifest and the apply's re-check at unlink time, so a fresh (possibly
     * in-flight-upload) file is never reclaimed by either path.
     *
     * @param storageDir storage directory
     * @param baseId candidate base id
     * @param knownFileIds set of all {@code T_FILE.FIL_ID_C} (any delete state)
     * @param nowMs current epoch millis
     * @return true if the base id is a reclaimable age-eligible orphan
     */
    public static boolean isAgeEligibleOrphan(Path storageDir, String baseId, Set<String> knownFileIds, long nowMs) {
        if (knownFileIds.contains(baseId)) {
            return false; // has a DB row (live or soft-deleted) — never a filesystem orphan
        }
        boolean anyPresent = false;
        for (Path p : physicalPathsForFile(storageDir, baseId)) {
            try {
                if (Files.isRegularFile(p)) {
                    anyPresent = true;
                    long ageMs = nowMs - Files.getLastModifiedTime(p).toMillis();
                    if (ageMs < ORPHAN_MIN_AGE_MS) {
                        // A fresh variant → may be an in-flight upload → spare the whole group.
                        return false;
                    }
                }
            } catch (IOException e) {
                // Can't determine age → fail safe: do NOT reclaim.
                return false;
            }
        }
        return anyPresent;
    }

    /**
     * Enumerates the storage directory for age-eligible filesystem orphans (#72): on-disk base ids with
     * no {@code T_FILE} row (any state) whose variants are all older than {@link #ORPHAN_MIN_AGE_MS}.
     * Read-only. Groups {@code {id}}/{@code {id}_web}/{@code {id}_thumb} under the base id.
     *
     * @param em entity manager (to read all file ids)
     * @param storageDir storage directory (may be null/non-existent → empty)
     * @param nowMs current epoch millis (for the age comparison)
     * @return the age-eligible orphan candidates
     */
    public static List<OrphanCandidate> findAgeEligibleOrphans(EntityManager em, Path storageDir, long nowMs) {
        List<OrphanCandidate> result = new ArrayList<>();
        if (storageDir == null || !Files.isDirectory(storageDir)) {
            return result;
        }
        @SuppressWarnings("unchecked")
        List<String> allIds = em.createNativeQuery("select f.FIL_ID_C from T_FILE f").getResultList();
        Set<String> knownFileIds = new HashSet<>(allIds);

        // Collect the distinct base ids present on disk (derivative suffixes stripped). File ids are
        // UUIDs (no underscores), so the base id is the segment before the first underscore.
        Set<String> baseIdsOnDisk = new HashSet<>();
        try (java.nio.file.DirectoryStream<Path> stored = Files.newDirectoryStream(storageDir)) {
            for (Path p : stored) {
                if (!Files.isRegularFile(p)) {
                    continue;
                }
                baseIdsOnDisk.add(p.getFileName().toString().split("_")[0]);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Error listing storage directory for orphan reclaim", e);
        }

        for (String baseId : baseIdsOnDisk) {
            if (isAgeEligibleOrphan(storageDir, baseId, knownFileIds, nowMs)) {
                long footprint = onDiskSizeForFile(storageDir, baseId);
                result.add(new OrphanCandidate(baseId, footprint));
            }
        }
        return result;
    }
}
