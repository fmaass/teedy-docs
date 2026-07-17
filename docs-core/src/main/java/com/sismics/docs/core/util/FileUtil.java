package com.sismics.docs.core.util;

import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.io.CharStreams;
import com.sismics.docs.core.constant.ConfigType;
import com.sismics.docs.core.constant.Constants;
import com.sismics.docs.core.dao.ConfigDao;
import com.sismics.docs.core.dao.FileDao;
import com.sismics.docs.core.dao.UserDao;
import com.sismics.docs.core.event.DocumentUpdatedAsyncEvent;
import com.sismics.docs.core.event.FileCreatedAsyncEvent;
import com.sismics.docs.core.model.context.AppContext;
import com.sismics.docs.core.model.jpa.File;
import com.sismics.docs.core.model.jpa.User;
import com.sismics.util.ImageDeskew;
import com.sismics.util.Scalr;
import com.sismics.util.context.ThreadLocalContext;
import com.sismics.util.io.InputStreamReaderThread;
import com.sismics.util.mime.MimeTypeUtil;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.input.CountingInputStream;
import org.apache.commons.io.output.NullOutputStream;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * File entity utilities.
 * 
 * @author bgamard
 */
public class FileUtil {
    /**
     * Logger.
     */
    private static final Logger log = LoggerFactory.getLogger(FileUtil.class);

    /**
     * File ID of files currently being processed.
     */
    private static final Set<String> processingFileSet = Collections.synchronizedSet(new HashSet<>());
    
    /**
     * Optical character recognition on an image.
     *
     * @param language Language to OCR
     * @param image Buffered image
     * @return Content extracted
     * @throws Exception e
     */
    public static String ocrFile(String language, BufferedImage image) throws Exception {
        // Upscale, grayscale and deskew the image
        BufferedImage resizedImage = Scalr.resize(image, Scalr.Method.AUTOMATIC, Scalr.Mode.AUTOMATIC, 3500, Scalr.OP_ANTIALIAS, Scalr.OP_GRAYSCALE);
        image.flush();
        ImageDeskew imageDeskew = new ImageDeskew(resizedImage);
        BufferedImage deskewedImage = Scalr.rotate(resizedImage, - imageDeskew.getSkewAngle(), Scalr.OP_ANTIALIAS, Scalr.OP_GRAYSCALE);
        resizedImage.flush();
        Path tmpFile = AppContext.getInstance().getFileService().createTemporaryFile();
        try {
            ImageIO.write(deskewedImage, "tiff", tmpFile.toFile());

            List<String> result = Lists.newLinkedList(Arrays.asList("tesseract", tmpFile.toAbsolutePath().toString(), "stdout", "-l", language));
            ProcessBuilder pb = new ProcessBuilder(result);
            Process process = pb.start();

            // Consume the process error stream
            final String commandName = pb.command().get(0);
            new InputStreamReaderThread(process.getErrorStream(), commandName).start();

            // Consume the data as text
            try (InputStream is = process.getInputStream()) {
                return CharStreams.toString(new InputStreamReader(is, StandardCharsets.UTF_8));
            }
        } finally {
            // Delete the plaintext TIFF as soon as tesseract has consumed it
            Files.deleteIfExists(tmpFile);
        }
    }

    /**
     * Remove a file from the storage filesystem.
     * 
     * @param fileId ID of file to delete
     */
    public static void delete(String fileId) throws IOException {
        Path storedFile = DirectoryUtil.getStorageDirectory().resolve(fileId);
        Path webFile = DirectoryUtil.getStorageDirectory().resolve(fileId + "_web");
        Path thumbnailFile = DirectoryUtil.getStorageDirectory().resolve(fileId + "_thumb");
        
        if (Files.exists(storedFile)) {
            Files.delete(storedFile);
        }
        if (Files.exists(webFile)) {
            Files.delete(webFile);
        }
        if (Files.exists(thumbnailFile)) {
            Files.delete(thumbnailFile);
        }
    }

    /**
     * Create a new file.
     *
     * @param name File name, can be null
     * @param previousFileId ID of the previous version of the file, if the new file is a new version
     * @param unencryptedFile Path to the unencrypted file
     * @param fileSize File size
     * @param language File language, can be null if associated to no document
     * @param userId User ID creating the file
     * @param documentId Associated document ID or null if no document
     * @return File ID
     * @throws Exception e
     */
    public static String createFile(String name, String previousFileId, Path unencryptedFile, long fileSize, String language, String userId, String documentId) throws Exception {
        // Validate mime type
        String mimeType;
        try {
            mimeType = MimeTypeUtil.guessMimeType(unencryptedFile, name);
        } catch (IOException e) {
            throw new IOException("ErrorGuessMime", e);
        }

        // Reserve the storage BEFORE any T_FILE mutation or blob I/O. This atomically validates the
        // per-user AND global quota and increments the reservation under the canonical lock order
        // (GLOBAL sentinel first, then the user's own row). Acquiring the locks here — before the
        // fileDao writes below — keeps the lock ordering consistent (a pending T_FILE change flushed by
        // a later pessimistic query would otherwise invert the T_FILE/GLOBAL order and could deadlock).
        // A losing reservation throws here, so no row is created and no blob byte is ever written.
        UserDao userDao = new UserDao();
        User user = userDao.getById(userId);
        reserveStorage(userId, fileSize);

        // Prepare the file
        File file = new File();
        file.setOrder(0);
        file.setVersion(0);
        file.setLatestVersion(true);
        file.setDocumentId(documentId);
        file.setName(StringUtils.abbreviate(name, 200));
        file.setMimeType(mimeType);
        file.setUserId(userId);
        file.setSize(fileSize);

        // Get files of this document
        FileDao fileDao = new FileDao();
        if (documentId != null) {
            if (previousFileId == null) {
                // It's not a new version, so put it in last order
                file.setOrder(fileDao.getByDocumentId(userId, documentId).size());
            } else {
                // It's a new version replacing an existing one. Validate the base BEFORE dereferencing it:
                // an unknown/inactive predecessor, or one belonging to another document, is a client error.
                // documentId is non-null in this branch, so comparing from it never dereferences a null base
                // (an orphan predecessor with a null document id would otherwise NPE into a 500).
                File previousFile = fileDao.getActiveById(previousFileId);
                if (previousFile == null || !documentId.equals(previousFile.getDocumentId())) {
                    throw new PreviousVersionMismatchException("The previous file version is unknown or belongs to another document");
                }

                // Resolve the version-chain id (a first replacement mints one shared by both rows).
                String versionId = previousFile.getVersionId() == null
                        ? UUID.randomUUID().toString() : previousFile.getVersionId();

                // Atomic compare-and-swap: demote the predecessor IFF it is still the current active latest.
                // Zero affected rows means a concurrent writer already replaced or deleted it (a stale base):
                // fail with a typed conflict BEFORE inserting any successor, so a lost race can never leave
                // two latest rows in the chain.
                if (fileDao.demoteCurrentLatestVersion(previousFileId, versionId) == 0) {
                    throw new VersionConcurrencyException("The file version was modified concurrently");
                }

                // Copy the predecessor metadata onto the successor, including its display rotation so the new
                // version renders the same way.
                file.setOrder(previousFile.getOrder());
                file.setVersionId(versionId);
                file.setVersion(previousFile.getVersion() + 1);
                file.setRotation(previousFile.getRotation());
            }
        }

        // Create the file
        String fileId = fileDao.create(file, userId);

        // Register an after-ROLLBACK deletion of the encrypted blob as soon as its path is known,
        // BEFORE the copy runs so a partial Files.copy is covered too. A DB rollback undoes the T_FILE
        // row and the storageCurrent reservation but NOT the bytes written to disk, which would then
        // orphan. This is critical for a multi-attachment transaction (e.g. an inbox import creating
        // several files in one tx): if a later attachment loses quota and the whole tx rolls back, the
        // earlier attachments' blobs must not be left behind. Not registered on IN_DOUBT (the row may
        // have committed) — the clean_storage age-thresholded sweep is the net for that ambiguous case.
        Path path = DirectoryUtil.getStorageDirectory().resolve(file.getId());
        ThreadLocalContext.get().getCompletionRegistry().registerAfterRollback(() -> {
            try {
                Files.deleteIfExists(path);
            } catch (IOException e) {
                log.warn("Unable to delete the encrypted blob of a rolled-back upload: " + path, e);
            }
        });

        // Save the file
        Cipher cipher = EncryptionUtil.getEncryptionCipher(user.getPrivateKey());
        try (InputStream inputStream = Files.newInputStream(unencryptedFile)) {
            Files.copy(new CipherInputStream(inputStream, cipher), path);
        }

        // The storage reservation was already applied atomically by reserveStorage above (before this
        // blob write). Do NOT decrement storageCurrent on rollback here: the DB rollback already undoes
        // the in-tx reservation increment, so a manual decrement would double-count.

        // Raise a new file created event and document updated event if we have a document
        markProcessingWithRollbackCleanup(fileId, unencryptedFile);
        FileCreatedAsyncEvent fileCreatedAsyncEvent = new FileCreatedAsyncEvent();
        fileCreatedAsyncEvent.setUserId(userId);
        fileCreatedAsyncEvent.setLanguage(language);
        fileCreatedAsyncEvent.setFileId(file.getId());
        fileCreatedAsyncEvent.setUnencryptedFile(unencryptedFile);
        ThreadLocalContext.get().addAsyncEvent(fileCreatedAsyncEvent);

        if (documentId != null) {
            DocumentUpdatedAsyncEvent documentUpdatedAsyncEvent = new DocumentUpdatedAsyncEvent();
            documentUpdatedAsyncEvent.setUserId(userId);
            documentUpdatedAsyncEvent.setDocumentId(documentId);
            ThreadLocalContext.get().addAsyncEvent(documentUpdatedAsyncEvent);
        }

        return fileId;
    }

    /**
     * Start processing a file.
     *
     * @param fileId File ID
     */
    public static void startProcessingFile(String fileId) {
        processingFileSet.add(fileId);
        log.info("Processing started for file: " + fileId);
    }

    /**
     * Mark a file as processing AND register rollback compensation on the current transaction frame,
     * returning a one-shot local-compensation handle a swallowing producer can run itself.
     *
     * <p>Every producer marks a file as processing and queues a {@code FileProcessing} async event
     * BEFORE its request transaction commits. That event fires only on a durable commit and is discarded
     * on rollback (issue #63): so on rollback the async listener never runs, and without this
     * compensation the in-memory processing marker would stay set until the JVM restarts (blocking
     * rotation / reprocessing of that file) and the decrypted plaintext temp file the event carried would
     * never be deleted (a leak of decrypted content). The registered callback releases the marker and
     * deletes that plaintext temp file on rollback. On the commit path the listener owns both; the commit
     * and rollback paths are mutually exclusive, so the marker is released exactly once.</p>
     *
     * <p>The returned {@link Runnable} and the registered rollback callback SHARE one {@link
     * AtomicBoolean} guard, so the release runs at most once no matter how many times either fires: a
     * batch producer that swallows a failure after marking (its request may still commit, so no rollback
     * fires) runs the returned handle to compensate immediately, and if the outer transaction later rolls
     * back anyway the registered callback is a no-op. This prevents a double release — which, if a
     * concurrent operation had re-acquired the marker in between, would clear that other operation's live
     * marker — and a double temp delete.</p>
     *
     * <p>The mark is set first and the compensation registered IMMEDIATELY after (neither step throws),
     * so a producer failure after this call still has its rollback compensated.</p>
     *
     * <p>Known pre-existing limitations (predate this compensation, not addressed here): if the async bus
     * is configured to RETRY a failed subscriber the marker may be released before a redelivery, and two
     * operations racing on the same file id share this single in-memory marker.</p>
     *
     * @param fileId File ID
     * @param unencryptedFile Decrypted plaintext temp file the queued event owns (may be null)
     * @return a one-shot local-compensation handle; a producer that SWALLOWS a post-mark failure (so no
     *         rollback will fire) runs it to release the marker + delete the temp exactly once
     */
    public static Runnable markProcessingWithRollbackCleanup(String fileId, Path unencryptedFile) {
        startProcessingFile(fileId);
        AtomicBoolean released = new AtomicBoolean(false);
        Runnable release = () -> {
            if (released.compareAndSet(false, true)) {
                endProcessingFile(fileId);
                deleteTempGuarded(fileId, unencryptedFile);
            }
        };
        ThreadLocalContext.get().getCompletionRegistry().registerAfterRollback(release);
        return release;
    }

    /**
     * Delete a decrypted plaintext temp file, swallowing and logging any failure, and NEVER deleting the
     * stored-file alias. {@link EncryptionUtil#decryptFile} returns the ORIGINAL stored file path when the
     * private key is null (its unit-test seam), so a producer's "unencrypted temp" can actually BE the
     * encrypted stored file at {@code storageDir/<fileId>}; deleting it would destroy real content. This
     * mirrors the alias guard in {@code FileResource.deleteOrphanTemp}. A delete failure during rollback
     * compensation must not abort the remaining completion callbacks, so it is swallowed and logged.
     *
     * @param fileId File ID (used to recognise the stored-file alias)
     * @param unencryptedFile Plaintext temp file (may be null)
     */
    public static void deleteTempGuarded(String fileId, Path unencryptedFile) {
        if (unencryptedFile == null) {
            return;
        }
        if (fileId != null && unencryptedFile.equals(DirectoryUtil.getStorageDirectory().resolve(fileId))) {
            // The stored-file alias (null private key). Never delete the real stored content.
            return;
        }
        try {
            Files.deleteIfExists(unencryptedFile);
        } catch (Exception e) {
            log.warn("Unable to delete temporary file: " + unencryptedFile, e);
        }
    }

    /**
     * End processing a file.
     *
     * @param fileId File ID
     */
    public static void endProcessingFile(String fileId) {
        processingFileSet.remove(fileId);
        log.info("Processing ended for file: " + fileId);
    }

    /**
     * Return true if a file is currently processing.
     *
     * @param fileId File ID
     * @return True if the file is processing
     */
    public static boolean isProcessingFile(String fileId) {
        return processingFileSet.contains(fileId);
    }

    /**
     * Return the number of files currently marked in-flight for processing. Lets a caller observe when
     * asynchronous, post-response file processing (content extraction, raster generation) has drained.
     *
     * @return Count of files currently processing
     */
    public static int getProcessingFileCount() {
        return processingFileSet.size();
    }

    /**
     * Get the size of a file on disk.
     *
     * @param fileId the file id
     * @param user   the file owner
     * @return the size or -1 if something went wrong
     */
    public static long getFileSize(String fileId, User user) {
        // To get the size we copy the decrypted content into a null output stream
        // and count the copied byte size.
        Path storedFile = DirectoryUtil.getStorageDirectory().resolve(fileId);
        if (! Files.exists(storedFile)) {
            log.debug("File does not exist " + fileId);
            return File.UNKNOWN_SIZE;
        }
        try (InputStream fileInputStream = Files.newInputStream(storedFile);
             InputStream inputStream = EncryptionUtil.decryptInputStream(fileInputStream, user.getPrivateKey());
             CountingInputStream countingInputStream = new CountingInputStream(inputStream);
        ) {
            IOUtils.copy(countingInputStream, NullOutputStream.NULL_OUTPUT_STREAM);
            return countingInputStream.getByteCount();
        } catch (Exception e) {
            log.debug("Can't find size of file " + fileId, e);
            return File.UNKNOWN_SIZE;
        }
    }

    /**
     * Resolve the storage bytes a file occupies for quota accounting. Uses the size recorded on the
     * file row when known; otherwise (legacy {@link File#UNKNOWN_SIZE} rows) reads it from the still-
     * present on-disk encrypted content. Returns 0 when the size cannot be determined, so a caller can
     * safely add it to a running total without corrupting the quota.
     *
     * <p>Intended to be called synchronously, within the same transaction that deletes the file, while
     * the file still exists on disk. Making the quota decrement part of the delete transaction is what
     * keeps storage accounting correct: it commits or rolls back atomically with the delete, and the
     * async {@code FileDeletedAsyncEvent} path no longer touches the quota (so an event retry cannot
     * double-subtract).
     *
     * @param fileId File ID
     * @param knownSize Size recorded on the file row (may be {@link File#UNKNOWN_SIZE})
     * @param user Owning user (its private key is needed to size an UNKNOWN_SIZE file)
     * @return Bytes to reclaim, never negative
     */
    public static long resolveReclaimableSize(String fileId, Long knownSize, User user) {
        long size = knownSize == null ? File.UNKNOWN_SIZE : knownSize;
        if (size == File.UNKNOWN_SIZE && user != null) {
            // Legacy file with no stored size: measure the still-present on-disk content.
            size = getFileSize(fileId, user);
        }
        return size == File.UNKNOWN_SIZE ? 0L : size;
    }

    /**
     * Acquire the GLOBAL storage-quota lock — the first hop of the canonical quota lock order (GLOBAL
     * sentinel row, then the user's own row). A global quota is a cross-user {@code SUM} that no
     * per-user row can serialize, so every quota-mutating path takes a {@code PESSIMISTIC_WRITE} row
     * lock on this single sentinel first; that both serializes the SUM read and gives one consistent
     * lock order everywhere, so no two quota paths can deadlock on the user rows. Fails CLOSED like
     * {@code CLEAN_STORAGE_LOCK}: if the sentinel row is absent (a database that never ran migration
     * 053) the lock cannot be evaluated, so the whole quota mutation is refused and its transaction
     * rolls back rather than proceeding unlocked. The row is always seeded by migration 053.
     */
    private static void lockGlobalQuotaOrThrow() {
        if (!new ConfigDao().lockForUpdate(ConfigType.GLOBAL_QUOTA_LOCK)) {
            throw new IllegalStateException(
                    "The global storage-quota lock row (GLOBAL_QUOTA_LOCK) is missing — is the database migrated?");
        }
    }

    /**
     * Atomically reserve storage for a new upload under the canonical quota lock order, or throw
     * {@code IOException("QuotaReached")} without touching anything if the reservation would exceed the
     * per-user OR the global quota. On success it has incremented the user's {@code storageCurrent}
     * exactly once on the freshly-locked row; the caller then writes the blob. A DB rollback later
     * undoes this increment, so there is never a manual decrement.
     *
     * <p>Lock order: GLOBAL sentinel first, then the user's own row (both {@code PESSIMISTIC_WRITE}).
     * The global {@code SUM} is read BEFORE the user row is locked on purpose: the SUM query flushes and
     * clears the persistence context, which would detach a user instance fetched earlier and silence its
     * reservation write. Reading the SUM first, then locking + re-reading the user row last, keeps that
     * managed instance live for the increment. It is consistent because the global lock is already held,
     * so no other path can change any {@code storageCurrent} between the SUM read and this commit.</p>
     *
     * <p>Comparisons are overflow-safe ({@code fileSize > quota - current}, never
     * {@code current + fileSize > quota}): {@code quota - current} may be negative (already over quota →
     * reject) but cannot overflow, whereas {@code current + fileSize} can.</p>
     *
     * @param userId Uploading user ID (must be active)
     * @param fileSize Bytes to reserve
     * @throws IOException {@code "QuotaReached"} if the per-user or global quota would be exceeded, or if
     *         the user row is not active
     */
    public static void reserveStorage(String userId, long fileSize) throws IOException {
        reserveStorage(userId, fileSize, readGlobalQuota());
    }

    /**
     * Reads the configured global storage quota from the environment.
     *
     * @return the global quota in bytes, or {@code null} when the env var is unset/empty (no global limit)
     * @throws IOException if {@code DOCS_GLOBAL_QUOTA} is set but non-numeric or negative (fail closed)
     */
    private static Long readGlobalQuota() throws IOException {
        return parseGlobalQuota(System.getenv(Constants.GLOBAL_QUOTA_ENV));
    }

    /**
     * Parses and validates a raw {@code DOCS_GLOBAL_QUOTA} value, distinguishing UNSET from
     * SET-BUT-INVALID (package-private so tests can drive the parse without mutating the environment):
     * <ul>
     *   <li>{@code null}/empty — legitimately UNSET → {@code null} (no global limit);</li>
     *   <li>a valid non-negative long — that limit;</li>
     *   <li>anything else (non-numeric, or negative) — a MISCONFIGURATION → {@code IOException} (fail
     *       CLOSED). It must NEVER be silently treated as "no limit": a typo or a negative value would
     *       otherwise disable the global cap entirely (fail open). This mirrors the pre-existing
     *       {@code createFile} behaviour, where {@code Long.parseLong} threw on a non-numeric value and a
     *       negative quota made the check always reject.</li>
     * </ul>
     *
     * @param raw the raw environment value (may be {@code null})
     * @return the parsed non-negative quota, or {@code null} when unset/empty
     * @throws IOException {@code "ErrorGlobalQuotaConfig"} when set but non-numeric or negative
     */
    static Long parseGlobalQuota(String raw) throws IOException {
        if (Strings.isNullOrEmpty(raw)) {
            return null;
        }
        long globalQuota;
        try {
            globalQuota = Long.parseLong(raw);
        } catch (NumberFormatException e) {
            log.error("{} is set but not a valid number ('{}'); refusing uploads until it is corrected or unset",
                    Constants.GLOBAL_QUOTA_ENV, raw);
            throw new IOException("ErrorGlobalQuotaConfig");
        }
        if (globalQuota < 0L) {
            log.error("{} is set to a negative value ({}); refusing uploads until it is corrected or unset",
                    Constants.GLOBAL_QUOTA_ENV, globalQuota);
            throw new IOException("ErrorGlobalQuotaConfig");
        }
        return globalQuota;
    }

    /**
     * Reserve implementation with the global quota supplied explicitly. The public {@link
     * #reserveStorage(String, long)} reads it from the environment; this overload lets tests drive the
     * global-limit path deterministically without mutating process environment. See that method's
     * contract for the locking and overflow-safety guarantees.
     *
     * @param userId Uploading user ID (must be active)
     * @param fileSize Bytes to reserve
     * @param globalQuota Global quota in bytes, or {@code null} for no global limit
     * @throws IOException {@code "QuotaReached"} if a per-user or global limit would be exceeded, or the
     *         user row is not active
     */
    static void reserveStorage(String userId, long fileSize, Long globalQuota) throws IOException {
        // GLOBAL sentinel lock first (canonical order, fail closed).
        lockGlobalQuotaOrThrow();

        UserDao userDao = new UserDao();

        // Read the global SUM (if a global quota is configured) BEFORE locking the user row, so the
        // clear it triggers cannot detach the user instance we mutate below. Safe under the held global
        // lock: no other path can change any storageCurrent until we commit.
        long globalStorageCurrent = 0L;
        if (globalQuota != null) {
            globalStorageCurrent = userDao.getGlobalStorageCurrent();
        }

        // Lock + re-read the user's OWN row fresh (never a stale pre-lock value), and mutate THAT
        // managed instance. No getEntityManager() call may follow before the increment, or the clear
        // would detach it and drop the reservation.
        User user = userDao.getActiveByIdForUpdate(userId);
        if (user == null) {
            throw new IOException("QuotaReached");
        }
        long current = user.getStorageCurrent() == null ? 0L : user.getStorageCurrent();
        long quota = user.getStorageQuota() == null ? 0L : user.getStorageQuota();

        // Fail closed on any non-sensical operand before the subtraction: a NEGATIVE quota (a stored
        // quota < 0 is invalid — the input boundary rejects it, but a legacy/extreme value must still be
        // safe here), or a negative current / fileSize. Rejecting these first keeps {@code quota -
        // current} between two non-negative longs, so it cannot underflow into a large positive value that
        // would wrongly accept the upload (the overflow-safe form only holds for non-negative operands).
        if (quota < 0L || current < 0L || fileSize < 0L || fileSize > quota - current) {
            throw new IOException("QuotaReached");
        }
        if (globalQuota != null
                && (globalQuota < 0L || globalStorageCurrent < 0L || fileSize > globalQuota - globalStorageCurrent)) {
            throw new IOException("QuotaReached");
        }

        user.setStorageCurrent(current + fileSize);
    }

    /**
     * Decrement a user's current storage by the given number of bytes, within the current transaction,
     * under the canonical quota lock order (GLOBAL sentinel first, then the user's own row). Clamps at
     * zero so accounting cannot go negative. A no-op for a zero/negative amount or an unknown user.
     *
     * <p>The user row is locked and re-read via {@link UserDao#getByIdForUpdate(String)}, which — unlike
     * the active-only variant — finds a RETAINED soft-deleted uploader (the #55 ghost key-holder) and
     * returns {@code null} rather than throwing when the row is absent. This keeps BOTH clean_storage AND
     * the retention purge safe: a quota reclaim can never throw AFTER destructive work has removed the
     * bytes. A reclaim performs NO quota-limit or global-SUM validation — a delete never checks limits.</p>
     *
     * @param userId Owning user ID
     * @param bytes Bytes to reclaim (from {@link #resolveReclaimableSize})
     */
    public static void reclaimUserQuota(String userId, long bytes) {
        if (bytes <= 0L || userId == null) {
            return;
        }
        lockGlobalQuotaOrThrow();
        reclaimLocked(userId, bytes);
    }

    /**
     * Reclaim a single file's storage on a soft-delete, EXACTLY once even under two concurrent
     * {@code DELETE /file/{id}} for the same file. Both requests pass their initial active-file check
     * before either commits, so a naive "reclaim the size" would double-subtract. This takes the GLOBAL
     * sentinel lock FIRST (canonical order, before the caller dirties the {@code T_FILE} row) and then
     * RE-READS the file ACTIVE under the lock: only the delete that still sees it active — the winner —
     * reclaims; a delete that lost the race sees it already soft-deleted (or gone) and reclaims nothing.
     * The winner reclaims against the file's OWN uploader ({@code FIL_IDUSER_C}), which owns the bytes
     * (a collaborator's upload counts against the collaborator). Must be called BEFORE the caller's
     * {@code fileDao.delete}, within the same transaction, while the file still exists on disk (needed to
     * size a legacy {@link File#UNKNOWN_SIZE} row).
     *
     * @param fileId File ID being deleted
     */
    public static void reclaimSingleFileOnDelete(String fileId) {
        if (fileId == null) {
            return;
        }
        lockGlobalQuotaOrThrow();
        // Fresh, active-only read under the lock: null when a concurrent delete already soft-deleted or
        // hard-deleted it (this call is the loser and reclaims nothing).
        File active = new FileDao().getFile(fileId);
        if (active == null) {
            return;
        }
        User owner = new UserDao().getById(active.getUserId());
        long bytes = resolveReclaimableSize(active.getId(), active.getSize(), owner);
        reclaimLocked(active.getUserId(), bytes);
    }

    /**
     * Subtract {@code bytes} (zero-clamped) from a user's {@code storageCurrent} on its freshly-locked
     * row. The GLOBAL sentinel lock MUST already be held by the caller (canonical lock order). A no-op
     * if the row is absent (the ghost-holder path).
     *
     * @param userId Owning user ID
     * @param bytes Bytes to reclaim (already known positive)
     */
    private static void reclaimLocked(String userId, long bytes) {
        User user = new UserDao().getByIdForUpdate(userId);
        if (user == null) {
            return;
        }
        long current = user.getStorageCurrent() == null ? 0L : user.getStorageCurrent();
        user.setStorageCurrent(Math.max(0L, current - bytes));
    }

    /**
     * Reclaim the storage quota for a document's files on a permanent delete/purge, within the
     * current transaction. This is the multi-file counterpart of {@link #reclaimUserQuota}, and it is
     * careful to reproduce exactly-once single-run accounting:
     * <ul>
     *   <li><b>Only the files this document delete owns:</b> the multi-file delete paths all operate on
     *       an already-trashed document, and trashing ({@code DocumentDao.delete}) soft-deletes every
     *       then-active file in the SAME transaction — so those cascade-trashed files carry
     *       {@code file.deleteDate == document.deleteDate}. A file that was instead individually deleted
     *       earlier (via {@code FileResource.delete}) was already reclaimed then and carries an earlier,
     *       different {@code deleteDate}; summing it here would double-subtract. And a file left
     *       <i>stranded</i> with {@code deleteDate == null} — a WRITE collaborator's upload into a
     *       document whose owner was later deleted, since {@code UserDao.delete} soft-deletes only the
     *       owner's own files (BL-021) — is hard-deleted at purge and so must be reclaimed here too, or
     *       the collaborator's storage leaks forever. So a file is reclaimed here iff its
     *       {@code deleteDate} is null OR equals the document's {@code deleteDate} (or the document
     *       delete date is unknown, in which case we fall back to reclaiming all still-linked files —
     *       matching the historical all-files behaviour); a file is skipped ONLY when it carries a
     *       non-null {@code deleteDate} that differs from the document's (already reclaimed earlier).
     *       This reuses the same {@code file.deleteDate == document.deleteDate} invariant that
     *       {@code DocumentDao.restore} already relies on to restore exactly the files cascade-trashed
     *       with a document.</li>
     *   <li><b>Per-owner:</b> a file is charged to its own uploader ({@code file.getUserId()}), not
     *       the document owner — a WRITE collaborator can upload a file to another user's document, and
     *       that file counts against the collaborator's quota. Sizes are grouped by file owner and each
     *       owner is decremented by their own files' bytes. Each owner's private key is used to size any
     *       legacy {@code UNKNOWN_SIZE} file they own.</li>
     * </ul>
     *
     * @param files All files linked to the document (as returned by {@code getAllByDocumentId})
     * @param documentDeleteDate The document's own deletion timestamp; a file is reclaimed if its own
     *        deletion timestamp is null or matches it, and skipped only when it is non-null and differs.
     *        If {@code null}, all still-linked files are reclaimed.
     */
    public static void reclaimQuotaForDeletedDocumentFiles(List<File> files, Date documentDeleteDate) {
        if (files == null || files.isEmpty()) {
            return;
        }

        // Acquire the GLOBAL sentinel lock FIRST — before deciding WHAT to reclaim. The callers snapshot
        // the document's files BEFORE this call (DocumentResourceHelper / TrashPurgeService), so two
        // concurrent purges of the same document hold the SAME stale snapshot. Locking first, then
        // re-reading which of those files STILL EXIST under the lock, makes the reclaim set fresh: a file
        // a concurrent purge already hard-deleted (and reclaimed) is gone from the re-read here and is not
        // subtracted a second time. Without this the second purge blocks on the lock, then applies its
        // stale snapshot after the first committed its deletes — a double-decrement (the zero clamp masks
        // it only when the document held all of the user's usage).
        lockGlobalQuotaOrThrow();

        // Re-read, UNDER the lock, which of the snapshotted files still physically exist as rows (no
        // deleteDate filter — a soft-deleted trashed file is still present; only a committed hard-delete
        // removes the row). Only those are reclaimed.
        List<String> fileIds = new ArrayList<>();
        for (File file : files) {
            if (file.getId() != null) {
                fileIds.add(file.getId());
            }
        }
        Set<String> stillPresent = new FileDao().getExistingIds(fileIds);

        UserDao userDao = new UserDao();
        Map<String, User> ownerCache = new HashMap<>();
        Map<String, Long> reclaimByOwner = new LinkedHashMap<>();

        for (File file : files) {
            // Skip a file whose row a concurrent purge already hard-deleted (and reclaimed) — the fresh,
            // lock-serialized existence set is the guard against a double reclaim.
            if (!stillPresent.contains(file.getId())) {
                continue;
            }
            // Reclaim the files this document delete is responsible for: those cascade-trashed with the
            // document (same deleteDate) AND any file left stranded with a null deleteDate. A stranded
            // file arises when a WRITE collaborator uploaded into another user's document and that owner
            // was later deleted: UserDao.delete soft-deletes only the owner's OWN files, so the
            // collaborator's upload keeps deleteDate == null (BL-021). Its bytes are hard-deleted at
            // purge, so its quota must be reclaimed here or the collaborator's storage leaks forever.
            //
            // Skip ONLY a file that was individually deleted earlier (via FileResource.delete, which
            // unconditionally stamps a non-null deleteDate) and thus already reclaimed then — such a
            // file carries a non-null deleteDate that differs from the document's. A null deleteDate
            // can never mark an already-reclaimed file, so relaxing the filter cannot double-reclaim.
            if (file.getDeleteDate() != null && documentDeleteDate != null
                    && !documentDeleteDate.equals(file.getDeleteDate())) {
                continue;
            }
            String ownerId = file.getUserId();
            if (ownerId == null) {
                continue;
            }
            User owner = ownerCache.computeIfAbsent(ownerId, userDao::getById);
            long size = resolveReclaimableSize(file.getId(), file.getSize(), owner);
            if (size > 0L) {
                reclaimByOwner.merge(ownerId, size, Long::sum);
            }
        }

        if (reclaimByOwner.isEmpty()) {
            return;
        }

        // Reclaim each owner's row in a deterministic (owner-id-sorted) order. The GLOBAL lock is already
        // held (above), so only one quota path runs at a time — the per-user lock order can never form a
        // cross-path cycle — but sorting keeps this method's own multi-owner acquisition deterministic.
        List<String> owners = new ArrayList<>(reclaimByOwner.keySet());
        Collections.sort(owners);
        for (String ownerId : owners) {
            reclaimLocked(ownerId, reclaimByOwner.get(ownerId));
        }
    }
}
