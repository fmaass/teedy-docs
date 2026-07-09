package com.sismics.docs.core.util;

import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.io.CharStreams;
import com.sismics.docs.core.constant.Constants;
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

        // Validate user quota
        UserDao userDao = new UserDao();
        User user = userDao.getById(userId);
        if (user.getStorageCurrent() + fileSize > user.getStorageQuota()) {
            throw new IOException("QuotaReached");
        }

        // Validate global quota
        String globalStorageQuotaStr = System.getenv(Constants.GLOBAL_QUOTA_ENV);
        if (!Strings.isNullOrEmpty(globalStorageQuotaStr)) {
            long globalStorageQuota = Long.parseLong(globalStorageQuotaStr);
            long globalStorageCurrent = userDao.getGlobalStorageCurrent();
            if (globalStorageCurrent + fileSize > globalStorageQuota) {
                throw new IOException("QuotaReached");
            }
        }

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
                // It's a new version, update the previous version
                File previousFile = fileDao.getActiveById(previousFileId);
                if (previousFile == null || !previousFile.getDocumentId().equals(documentId)) {
                    throw new IOException("Previous version mismatch");
                }

                if (previousFile.getVersionId() == null) {
                    previousFile.setVersionId(UUID.randomUUID().toString());
                }

                // Copy the previous file metadata
                file.setOrder(previousFile.getOrder());
                file.setVersionId(previousFile.getVersionId());
                file.setVersion(previousFile.getVersion() + 1);

                // Update the previous file
                previousFile.setLatestVersion(false);
                fileDao.update(previousFile);
            }
        }

        // Create the file
        String fileId = fileDao.create(file, userId);

        // Save the file
        Cipher cipher = EncryptionUtil.getEncryptionCipher(user.getPrivateKey());
        Path path = DirectoryUtil.getStorageDirectory().resolve(file.getId());
        try (InputStream inputStream = Files.newInputStream(unencryptedFile)) {
            Files.copy(new CipherInputStream(inputStream, cipher), path);
        }

        // Update the user quota
        user.setStorageCurrent(user.getStorageCurrent() + fileSize);
        userDao.updateQuota(user);

        // Raise a new file created event and document updated event if we have a document
        startProcessingFile(fileId);
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
     * Decrement a user's current storage by the given number of bytes, within the current
     * transaction. Clamps at zero so accounting cannot go negative. A no-op for a zero/negative
     * amount or an unknown user.
     *
     * @param userId Owning user ID
     * @param bytes Bytes to reclaim (from {@link #resolveReclaimableSize})
     */
    public static void reclaimUserQuota(String userId, long bytes) {
        if (bytes <= 0L || userId == null) {
            return;
        }
        UserDao userDao = new UserDao();
        User user = userDao.getById(userId);
        if (user == null) {
            return;
        }
        long current = user.getStorageCurrent() == null ? 0L : user.getStorageCurrent();
        user.setStorageCurrent(Math.max(0L, current - bytes));
        userDao.updateQuota(user);
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
        UserDao userDao = new UserDao();
        Map<String, User> ownerCache = new HashMap<>();
        Map<String, Long> reclaimByOwner = new LinkedHashMap<>();

        for (File file : files) {
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

        for (Map.Entry<String, Long> entry : reclaimByOwner.entrySet()) {
            reclaimUserQuota(entry.getKey(), entry.getValue());
        }
    }
}
