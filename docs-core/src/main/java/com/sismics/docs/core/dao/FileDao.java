package com.sismics.docs.core.dao;

import com.sismics.docs.core.constant.AuditLogType;
import com.sismics.docs.core.model.jpa.File;
import com.sismics.docs.core.util.AuditLogUtil;
import com.sismics.util.context.ThreadLocalContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.Query;
import jakarta.persistence.TypedQuery;

import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * File DAO.
 * 
 * @author bgamard
 */
public class FileDao {
    /**
     * Creates a new file.
     * 
     * @param file File
     * @param userId User ID
     * @return New ID
     */
    public String create(File file, String userId) {
        // Create the UUID
        file.setId(UUID.randomUUID().toString());
        
        // Create the file
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        file.setCreateDate(new Date());
        em.persist(file);
        
        // Create audit log
        AuditLogUtil.create(file, AuditLogType.CREATE, userId);
        
        return file.getId();
    }
    
    /**
     * Returns the list of all files.
     *
     * @param offset Offset
     * @param limit Limit
     * @return List of files
     */
    public List<File> findAll(int offset, int limit) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        TypedQuery<File> q = em.createQuery("select f from File f where f.deleteDate is null", File.class);
        q.setFirstResult(offset);
        q.setMaxResults(limit);
        return q.getResultList();
    }
    
    /**
     * Returns the list of all files from a user.
     * 
     * @param userId User ID
     * @return List of files
     */
    public List<File> findByUserId(String userId) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        TypedQuery<File> q = em.createQuery("select f from File f where f.userId = :userId and f.deleteDate is null", File.class);
        q.setParameter("userId", userId);
        return q.getResultList();
    }

    /**
     * Returns a list of active files.
     *
     * @param ids Files IDs
     * @return List of files
     */
    public List<File> getFiles(List<String> ids) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        TypedQuery<File> q = em.createQuery("select f from File f where f.id in :ids and f.deleteDate is null", File.class);
        q.setParameter("ids", ids);
        return q.getResultList();
    }
    
    /**
     * Returns the subset of the given file IDs whose row still PHYSICALLY exists in T_FILE, regardless
     * of soft-delete state (no {@code deleteDate} filter). Only a committed hard-delete removes the row.
     * Used by {@link com.sismics.docs.core.util.FileUtil#reclaimQuotaForDeletedDocumentFiles} to re-read,
     * under the global quota lock, which of a purge's snapshotted files a concurrent purge has not already
     * hard-deleted — so their quota is reclaimed exactly once.
     *
     * @param ids File IDs to test
     * @return the subset of {@code ids} that still have a row
     */
    public Set<String> getExistingIds(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptySet();
        }
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        TypedQuery<String> q = em.createQuery("select f.id from File f where f.id in :ids", String.class);
        q.setParameter("ids", ids);
        return new HashSet<>(q.getResultList());
    }

    /**
     * Returns an active file or null.
     *
     * @param id File ID
     * @return File
     */
    public File getFile(String id) {
        List<File> files = getFiles(List.of(id));
        if (files.isEmpty()) {
            return null;
        } else {
            return files.get(0);
        }
    }
    
    /**
     * Returns an active file.
     * 
     * @param id File ID
     * @param userId User ID
     * @return File
     */
    public File getFile(String id, String userId) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        TypedQuery<File> q = em.createQuery("select f from File f where f.id = :id and f.userId = :userId and f.deleteDate is null", File.class);
        q.setParameter("id", id);
        q.setParameter("userId", userId);
        try {
            return q.getSingleResult();
        } catch (NoResultException e) {
            return null;
        }
    }
    
    /**
     * Soft-deletes a file and, when it was the current latest version of a chain, promotes its
     * immediately-prior active version to latest — under the same affected-row compare-and-swap the
     * new-version path uses — so the chain never ends up with zero (or two) latest rows. Deleting a latest
     * row alone would strip the file from {@link #getByDocumentId}, hiding it even though older versions
     * remain; folding the repair into delete makes that invariant intrinsic to file deletion.
     *
     * <p>The gate is a conditional {@code UPDATE ... where latestVersion = true and deleteDate is null}: its
     * affected-row count decides ownership of the promotion. A returned 1 means THIS delete observed the row
     * as the current active latest (it wins the race against a concurrent new-version create demoting the
     * same row); a returned 0 means the row was already not the current latest (a concurrent create demoted
     * it, or a concurrent delete handled it), so there is nothing to promote and the row is simply
     * soft-deleted. The target chain is re-read AFTER the gate — the caller's originally-fetched entity may
     * be stale, since every DAO access flushes and clears the persistence context.</p>
     *
     * @param id File ID
     * @param userId User ID
     */
    public void delete(String id, String userId) {
        // CAS gate: clear the latest flag iff this row is still the current active latest. The bulk update
        // acquires a row write lock, serializing this delete against a concurrent new-version create.
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        int wonLatest = em.createQuery("update File f set f.latestVersion = false" +
                        " where f.id = :id and f.latestVersion = true and f.deleteDate is null")
                .setParameter("id", id)
                .executeUpdate();

        // Soft-delete the row (+ audit). It is still active (the gate only cleared the latest flag), so this
        // re-fetches it fresh and stamps the delete date.
        softDelete(id, userId);

        if (wonLatest == 0) {
            // The row was not the current active latest, so the chain's latest lives elsewhere: nothing to
            // promote.
            return;
        }

        // This delete removed the current latest: promote the immediately-prior active version so the file
        // stays visible with its remaining history.
        promotePriorVersion(id);
    }

    /**
     * Soft-deletes an active file row (stamp its delete date) and records the delete audit log. The raw
     * primitive behind {@link #delete(String, String)}; kept private so file deletion always goes through
     * the version-aware path.
     *
     * @param id File ID
     * @param userId User ID
     */
    private void softDelete(String id, String userId) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();

        // Get the file
        TypedQuery<File> q = em.createQuery("select f from File f where f.id = :id and f.deleteDate is null", File.class);
        q.setParameter("id", id);
        File fileDb = q.getSingleResult();

        // Delete the file
        Date dateNow = new Date();
        fileDb.setDeleteDate(dateNow);

        // Create audit log
        AuditLogUtil.create(fileDb, AuditLogType.DELETE, userId);
    }

    /**
     * Atomically demote the expected current-latest version of a chain as a compare-and-swap: clears the
     * latest flag on the row IFF it is still the current active latest, and stamps the resolved version-chain
     * id in the same statement (idempotent when already set). The affected-row count is the stale-base
     * signal: a returned 0 means the named base is no longer the current latest (a concurrent writer already
     * replaced or deleted it), and the caller must NOT insert a successor. The single {@code executeUpdate}
     * takes a row write lock, so two concurrent replacements of the same base serialize and exactly one
     * observes 1.
     *
     * @param expectedLatestId ID of the predecessor the caller believes is the current latest version
     * @param versionId Resolved version-chain id to stamp on the predecessor (and share with the successor)
     * @return the number of rows updated: 1 when the swap succeeded, 0 when the base was stale
     */
    public int demoteCurrentLatestVersion(String expectedLatestId, String versionId) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        Query q = em.createQuery("update File f set f.latestVersion = false, f.versionId = :versionId" +
                " where f.id = :id and f.latestVersion = true and f.deleteDate is null");
        q.setParameter("versionId", versionId);
        q.setParameter("id", expectedLatestId);
        return q.executeUpdate();
    }

    /**
     * Promote the immediately-prior active version of a just-deleted current-latest row to latest, retrying
     * across predecessors a concurrent delete removes underneath us.
     *
     * <p>Deleting the current latest of {@code v0 -> v1 -> v2} promotes {@code v1}. But a concurrent
     * {@code delete(v1)} (v1 is non-latest, so ITS side does not promote) may commit between this method
     * selecting {@code v1} as the candidate and its promotion UPDATE — the UPDATE then affects 0 rows. Giving
     * up there would leave the chain with ZERO latest rows. Instead, on a 0-affected promotion, re-select the
     * next active version below the deleted one and retry. The active-version set only shrinks (each failed
     * candidate is now committed-deleted), so this terminates; when no active version remains the chain is
     * legitimately empty (the file was fully deleted) and there is nothing to promote.</p>
     *
     * @param deletedId ID of the row that was the current latest and has just been soft-deleted
     */
    private void promotePriorVersion(String deletedId) {
        File deleted = ThreadLocalContext.get().getEntityManager()
                .createQuery("select f from File f where f.id = :id", File.class)
                .setParameter("id", deletedId)
                .getSingleResult();
        String versionId = deleted.getVersionId();
        if (versionId == null) {
            // Single-version file (no chain): nothing to promote.
            return;
        }
        Integer deletedVersion = deleted.getVersion();
        while (true) {
            File candidate = getHighestActiveVersionBelow(versionId, deletedVersion);
            if (candidate == null) {
                // No active version remains: the chain is legitimately empty.
                return;
            }
            int promoted = ThreadLocalContext.get().getEntityManager().createQuery(
                            "update File f set f.latestVersion = true where f.id = :id and f.deleteDate is null")
                    .setParameter("id", candidate.getId())
                    .executeUpdate();
            if (promoted > 0) {
                return;
            }
            // The candidate was concurrently deleted between selection and promotion; re-select the
            // next-lower active version and retry.
        }
    }

    /**
     * Find the highest-versioned still-active version below {@code version} within a chain, or null when
     * none remains.
     *
     * @param versionId Version-chain id
     * @param version Exclusive upper bound (the deleted row's version)
     * @return the highest active version below {@code version}, or null
     */
    private File getHighestActiveVersionBelow(String versionId, Integer version) {
        List<File> prior = ThreadLocalContext.get().getEntityManager().createQuery(
                        "select f from File f where f.versionId = :versionId" +
                        " and f.deleteDate is null and f.version < :version order by f.version desc", File.class)
                .setParameter("versionId", versionId)
                .setParameter("version", version)
                .setMaxResults(1)
                .getResultList();
        return prior.isEmpty() ? null : prior.get(0);
    }

    /**
     * Update a file.
     * 
     * @param file File to update
     * @return Updated file
     */
    public File update(File file) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        
        // Get the file
        TypedQuery<File> q = em.createQuery("select f from File f where f.id = :id and f.deleteDate is null", File.class);
        q.setParameter("id", file.getId());
        File fileDb = q.getSingleResult();

        // Update the file. The version-chain identity columns (FIL_IDVERSION_C, FIL_LATESTVERSION_B) are
        // deliberately NOT copied here: they may change ONLY through the compare-and-swap paths
        // (new-version create and current-version delete). Copying them from a caller's possibly-stale
        // entity would let a rename/processing update that loaded a row as latest, then paused across a
        // concurrent version-create that demoted it, resurrect latestVersion=true and yield two latest rows.
        // The reconciliation columns (FIL_PROCESSED_D, FIL_PROCESSINGAT_D, FIL_PROCESSINGTOKEN_C,
        // FIL_PROCATTEMPTS_N, #159) are likewise NOT copied: they change ONLY through the fenced claim /
        // completion compare-and-swap methods below — the file-processing pipeline calls this update to save
        // content in the SAME transaction it may hold a stale copy of those columns, so copying them back
        // would clobber a concurrent claim.
        fileDb.setDocumentId(file.getDocumentId());
        fileDb.setName(file.getName());
        fileDb.setContent(file.getContent());
        fileDb.setOrder(file.getOrder());
        fileDb.setMimeType(file.getMimeType());
        fileDb.setSize(file.getSize());
        fileDb.setRotation(file.getRotation());

        return file;
    }

    /**
     * Gets a file by its ID.
     * 
     * @param id File ID
     * @return File
     */
    public File getActiveById(String id) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        TypedQuery<File> q = em.createQuery("select f from File f where f.id = :id and f.deleteDate is null", File.class);
        q.setParameter("id", id);
        try {
            return q.getSingleResult();
        } catch (NoResultException e) {
            return null;
        }
    }

    /**
     * Locks and re-reads a single ACTIVE file row FOR UPDATE (PESSIMISTIC_WRITE) — the non-mutating
     * re-validation hop of the #119 content-dedup no-op. The lock is on ONLY this one predecessor row and
     * NO quota lock is ever taken on the no-op path, so it cannot invert the canonical GLOBAL->user->T_FILE
     * order. Returns {@code null} when the row does not exist or was concurrently soft-deleted (its active
     * filter matched nothing, so nothing is locked), letting the caller treat a superseded base as a
     * version conflict rather than a spurious success.
     *
     * @param id File ID
     * @return the locked, freshly re-read active file, or {@code null} if absent/soft-deleted
     */
    public File getActiveByIdForUpdate(String id) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        TypedQuery<File> q = em.createQuery("select f from File f where f.id = :id and f.deleteDate is null", File.class);
        q.setParameter("id", id);
        q.setLockMode(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        try {
            return q.getSingleResult();
        } catch (NoResultException e) {
            return null;
        }
    }

    /**
     * Read-only lookup of another ACTIVE latest file in the same document carrying an identical content MAC
     * (#119) — the source of the advisory "identical to an existing file" upload hint. Returns the
     * oldest-created match so the hint is stable, or {@code null} when no other current file matches. Never
     * mutates and never locks; the returned id is advisory only (no server-side reparent/replace in v1).
     *
     * @param documentId Owning document id
     * @param contentMac Lowercase-hex content MAC to match
     * @param excludeFileId File id to exclude (the just-created file itself)
     * @return an existing active latest file with the same content, or {@code null}
     */
    public File findActiveByDocumentIdAndMac(String documentId, String contentMac, String excludeFileId) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        TypedQuery<File> q = em.createQuery("select f from File f where f.documentId = :documentId" +
                " and f.contentMac = :contentMac and f.id <> :excludeFileId" +
                " and f.latestVersion = true and f.deleteDate is null order by f.createDate asc", File.class);
        q.setParameter("documentId", documentId);
        q.setParameter("contentMac", contentMac);
        q.setParameter("excludeFileId", excludeFileId);
        q.setMaxResults(1);
        List<File> result = q.getResultList();
        return result.isEmpty() ? null : result.get(0);
    }

    /**
     * A batch of ACTIVE, document-attached files whose content MAC is still null — the #119 backfill work
     * set. Excludes orphans (no document to key a MAC on) and rows already stamped (a real MAC or a terminal
     * skip marker), so the scan drains and never re-selects a processed row forever.
     *
     * @param limit Batch size
     * @return up to {@code limit} null-MAC active document-attached files
     */
    public List<File> getActiveFilesWithNullMac(int limit) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        TypedQuery<File> q = em.createQuery("select f from File f where f.contentMac is null" +
                " and f.documentId is not null and f.deleteDate is null order by f.createDate asc", File.class);
        q.setMaxResults(limit);
        return q.getResultList();
    }

    /**
     * Conditionally set a file's content MAC only while it is still null (#119 backfill). The {@code where
     * FIL_CONTENTMAC_C is null} guard makes the write idempotent across instances: a second backfiller that
     * already lost the race updates zero rows instead of overwriting.
     *
     * @param id File ID
     * @param contentMac MAC (or terminal skip marker) to store
     * @return number of rows updated (1 when it filled a null cell, 0 when already set)
     */
    public int setContentMacIfNull(String id, String contentMac) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        Query q = em.createQuery("update File f set f.contentMac = :contentMac" +
                " where f.id = :id and f.contentMac is null");
        q.setParameter("contentMac", contentMac);
        q.setParameter("id", id);
        return q.executeUpdate();
    }
    
    /**
     * Get files by document ID or all orphan files of a user.
     * 
     * @param userId User ID
     * @param documentId Document ID
     * @return List of files
     */
    public List<File> getByDocumentId(String userId, String documentId) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        if (documentId == null) {
            TypedQuery<File> q = em.createQuery("select f from File f where f.documentId is null and f.deleteDate is null and f.latestVersion = true and f.userId = :userId order by f.createDate asc", File.class);
            q.setParameter("userId", userId);
            return q.getResultList();
        } else {
            return getByDocumentsIds(Collections.singleton(documentId));
        }
    }

    /**
     * Get files by documents IDs.
     *
     * @param documentIds Documents IDs
     * @return List of files
     */
    public List<File> getByDocumentsIds(Iterable<String> documentIds) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        TypedQuery<File> q = em.createQuery("select f from File f where f.documentId in :documentIds and f.latestVersion = true and f.deleteDate is null order by f.order asc, f.createDate asc, f.id asc", File.class);
        q.setParameter("documentIds", documentIds);
        return q.getResultList();
    }

    /**
     * Get all files by document ID, including soft-deleted files.
     * Used for permanent deletion to clean up file storage.
     *
     * @param documentId Document ID
     * @return List of files
     */
    public List<File> getAllByDocumentId(String documentId) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        TypedQuery<File> q = em.createQuery("select f from File f where f.documentId = :documentId", File.class);
        q.setParameter("documentId", documentId);
        return q.getResultList();
    }

    /**
     * Get files count by documents IDs.
     *
     * @param documentIds Documents IDs
     * @return the number of files per document id
     */
    public Map<String, Long> countByDocumentsIds(Iterable<String> documentIds) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        Query q = em.createQuery("select f.documentId, count(*) from File f where f.documentId in :documentIds and f.latestVersion = true and f.deleteDate is null group by (f.documentId)");
        q.setParameter("documentIds", documentIds);
        Map<String, Long> result = new HashMap<>();
        q.getResultList().forEach(o -> {
            Object[] resultLine = (Object[]) o;
            result.put((String) resultLine[0], (Long) resultLine[1]);
        });
        return result;
    }

    /**
     * Get all files from a version.
     *
     * @param versionId Version ID
     * @return List of files
     */
    public List<File> getByVersionId(String versionId) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        TypedQuery<File> q = em.createQuery("select f from File f where f.versionId = :versionId and f.deleteDate is null order by f.order asc, f.version asc", File.class);
        q.setParameter("versionId", versionId);
        return q.getResultList();
    }

    public List<File> getFilesWithUnknownSize(int limit) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        TypedQuery<File> q = em.createQuery("select f from File f where f.size = :size and f.deleteDate is null order by f.order asc", File.class);
        q.setParameter("size", File.UNKNOWN_SIZE);
        q.setMaxResults(limit);
        return q.getResultList();
    }

    /**
     * Reads the database server's current wall-clock time (#159). ALL reconciliation lease timing derives
     * from this one clock — never a per-JVM {@code new Date()} — so leases written and compared against a
     * timezone-less timestamp column stay consistent regardless of the JVM's zone or clock skew.
     *
     * @return the database clock's "now"
     */
    public Date getDatabaseTime() {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        Object now = em.createNativeQuery("select localtimestamp").getSingleResult();
        return toDate(now);
    }

    /**
     * Coerce the assorted JDBC/JSR-310 shapes {@code select localtimestamp} can return (H2 and PostgreSQL,
     * across driver versions) into a {@link Date}. A {@code timestamp without time zone} is read in the
     * JVM's own zone on both engines, which is fine here because every lease value is round-tripped through
     * that same conversion — only their RELATIVE order matters.
     *
     * @param value Raw native-query result
     * @return the value as a {@link Date}
     */
    private static Date toDate(Object value) {
        if (value instanceof java.sql.Timestamp ts) {
            return new Date(ts.getTime());
        }
        if (value instanceof java.time.LocalDateTime ldt) {
            return java.sql.Timestamp.valueOf(ldt);
        }
        if (value instanceof java.time.OffsetDateTime odt) {
            return Date.from(odt.toInstant());
        }
        if (value instanceof java.time.Instant instant) {
            return Date.from(instant);
        }
        if (value instanceof Date date) {
            return new Date(date.getTime());
        }
        throw new IllegalStateException("Unexpected database time type: "
                + (value == null ? "null" : value.getClass().getName()));
    }

    /**
     * A batch of ACTIVE files still awaiting post-upload processing (#159): marker unset and either never
     * claimed or their claim lease expired (older than {@code cutoff}). Ordered oldest-first so the longest
     * unsearchable files recover first; a live in-flight claim (lease newer than {@code cutoff}) is excluded
     * so the reconciler does not pile onto work already running.
     *
     * @param cutoff Lease-expiry cutoff (DB now minus the lease TTL)
     * @param limit Batch size
     * @return up to {@code limit} reconcilable files
     */
    public List<File> getFilesToReconcile(Date cutoff, int limit) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        TypedQuery<File> q = em.createQuery("select f from File f" +
                " where f.processed is null and f.deleteDate is null" +
                " and (f.processingToken is null or f.processingAt < :cutoff)" +
                " order by f.createDate asc", File.class);
        q.setParameter("cutoff", cutoff);
        q.setMaxResults(limit);
        return q.getResultList();
    }

    /**
     * The live count of ACTIVE files whose processing is not yet proven complete (#159), whether unclaimed,
     * leased-in-flight, or expired. The reconciliation service self-stops ONLY when this reaches zero, so a
     * still-running replay (its row still unprocessed) or a lease that will expire is never stranded without
     * a reconciler left to resolve it.
     *
     * @return the number of unprocessed active files
     */
    public long countUnprocessedActiveFiles() {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        Query q = em.createQuery("select count(f) from File f where f.processed is null and f.deleteDate is null");
        return (Long) q.getSingleResult();
    }

    /**
     * Atomically claim a file for reprocessing (#159) as a compare-and-swap: stamp a fresh fencing token,
     * the DB-clock lease time, and bump the attempt counter IFF the file is still active, unprocessed, and
     * either unclaimed or its lease has expired. The single bulk UPDATE takes a row write lock, so two
     * racing claimers serialize and exactly one observes a returned 1.
     *
     * @param id File ID
     * @param newToken Fresh per-claim fencing token the caller generated
     * @param dbNow Database-clock now (the new lease time)
     * @param cutoff Lease-expiry cutoff (DB now minus the lease TTL)
     * @return 1 when this caller won the claim, 0 when it was already processed or a live claim holds it
     */
    public int claimForReprocess(String id, String newToken, Date dbNow, Date cutoff) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        Query q = em.createQuery("update File f" +
                " set f.processingAt = :dbNow, f.processingToken = :newToken," +
                " f.procAttempts = coalesce(f.procAttempts, 0) + 1" +
                " where f.id = :id and f.processed is null and f.deleteDate is null" +
                " and (f.processingToken is null or f.processingAt < :cutoff)");
        q.setParameter("dbNow", dbNow);
        q.setParameter("newToken", newToken);
        q.setParameter("id", id);
        q.setParameter("cutoff", cutoff);
        return q.executeUpdate();
    }

    /**
     * Record processing completion FENCED to a reconciliation claim token (#159): set the marker and clear
     * the lease IFF the row is still active, unprocessed, and still owned by {@code token}. A re-claim
     * writes a NEW token, so a stale claimant past its lease matches 0 rows and cannot clear a successor's
     * lease or mark a successor's work complete.
     *
     * @param id File ID
     * @param token The claim's fencing token this worker holds
     * @param dbNow Database-clock now (the completion marker value)
     * @return 1 when the marker was written, 0 when the claim was superseded or the row processed/deleted
     */
    public int markProcessedIfClaimant(String id, String token, Date dbNow) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        Query q = em.createQuery("update File f" +
                " set f.processed = :dbNow, f.processingToken = null, f.processingAt = null" +
                " where f.id = :id and f.processed is null and f.deleteDate is null" +
                " and f.processingToken = :token");
        q.setParameter("dbNow", dbNow);
        q.setParameter("id", id);
        q.setParameter("token", token);
        return q.executeUpdate();
    }

    /**
     * Record processing completion for the LIVE (un-reconciled) path (#159): set the marker IFF the row is
     * still active, unprocessed, and UNCLAIMED. If a reconciler has meanwhile claimed the row (token set),
     * this is a clean no-op and that reconciler's replay owns completion — so a slow live upload racing a
     * reconciliation cycle converges to a single marker without either clobbering the other.
     *
     * @param id File ID
     * @param dbNow Database-clock now (the completion marker value)
     * @return 1 when the marker was written, 0 when the row was already claimed/processed/deleted
     */
    public int markProcessedIfUnclaimed(String id, Date dbNow) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        Query q = em.createQuery("update File f" +
                " set f.processed = :dbNow, f.processingToken = null, f.processingAt = null" +
                " where f.id = :id and f.processed is null and f.deleteDate is null" +
                " and f.processingToken is null");
        q.setParameter("dbNow", dbNow);
        q.setParameter("id", id);
        return q.executeUpdate();
    }
}
