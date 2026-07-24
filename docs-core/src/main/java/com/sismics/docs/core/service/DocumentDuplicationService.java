package com.sismics.docs.core.service;

import com.sismics.docs.core.constant.ConfigType;
import com.sismics.docs.core.dao.ConfigDao;
import com.sismics.docs.core.dao.DocumentDao;
import com.sismics.docs.core.dao.DocumentMetadataDao;
import com.sismics.docs.core.dao.FileDao;
import com.sismics.docs.core.dao.TagDao;
import com.sismics.docs.core.dao.UserDao;
import com.sismics.docs.core.dao.criteria.TagCriteria;
import com.sismics.docs.core.dao.dto.DocumentMetadataDto;
import com.sismics.docs.core.dao.dto.TagDto;
import com.sismics.docs.core.event.DocumentCreatedAsyncEvent;
import com.sismics.docs.core.model.context.AppContext;
import com.sismics.docs.core.model.jpa.Document;
import com.sismics.docs.core.model.jpa.File;
import com.sismics.docs.core.model.jpa.User;
import com.sismics.docs.core.util.DirectoryUtil;
import com.sismics.docs.core.util.DocumentUtil;
import com.sismics.docs.core.util.EncryptionUtil;
import com.sismics.docs.core.util.FileUtil;
import com.sismics.docs.core.util.MetadataUtil;
import com.sismics.util.context.ThreadLocalContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Duplicates a document into a fresh copy owned by the requester.
 */
public class DocumentDuplicationService {
    private static final Logger log = LoggerFactory.getLogger(DocumentDuplicationService.class);

    private static final String COPY_SUFFIX = " (copy)";

    /** Validated title length: the {@code DOC_TITLE_C} column bound the copied title must fit. */
    private static final int TITLE_MAX_LENGTH = 100;

    /**
     * Test seam: runs after the GLOBAL and requester-user locks are held and immediately before the source
     * document row is locked, so a lock-order test can observe that ordering. Null in production.
     */
    private static volatile Runnable beforeSourceDocumentLockHook;

    private DocumentDuplicationService() {
        // Utility class
    }

    /**
     * Installs (or, with null, clears) the pre-document-lock test seam.
     *
     * @param hook The hook, or null to clear
     */
    public static void setBeforeSourceDocumentLockHookForTest(Runnable hook) {
        beforeSourceDocumentLockHook = hook;
    }

    /**
     * Duplicate a readable document into a new document owned by the requester (the caller must already
     * hold READ on the source). All-or-nothing: it runs in the caller's transaction.
     *
     * @param sourceDocumentId Document to duplicate
     * @param requesterUserId User that will own the copy and whose key re-encrypts the copied blobs
     * @param requesterTargetIdList Requester's ACL target ids, used to copy only the tags they can read
     * @return the new document id
     * @throws IOException {@code "QuotaReached"} over quota, {@code "SourceNotFound"} if the source is gone,
     *         {@code "FileError"} if a source blob cannot be read (all fail the whole duplicate closed)
     * @throws Exception on any other failure
     */
    public static String duplicate(String sourceDocumentId, String requesterUserId, List<String> requesterTargetIdList) throws Exception {
        DocumentDao documentDao = new DocumentDao();
        FileDao fileDao = new FileDao();
        UserDao userDao = new UserDao();

        // Canonical lock order (ADR-0023): GLOBAL_QUOTA_LOCK -> USER -> DOCUMENT -> TAG. The upload path
        // takes GLOBAL, then the uploader's user row, then key-share-locks the document through its T_FILE
        // insert; duplication must therefore take GLOBAL and the requester's user row BEFORE the source
        // document row it snapshots under lock. Taking the document row first inverts against a concurrent
        // upload holding GLOBAL and waiting on that document row, and deadlocks. GLOBAL is taken
        // unconditionally: the file count that would otherwise gate it is only known after the document row
        // is locked, which is already too late to order GLOBAL ahead of it.
        lockGlobalQuota();

        User requester = userDao.getActiveByIdForUpdate(requesterUserId);
        if (requester == null) {
            throw new IllegalStateException("Cannot duplicate for an inactive requester: " + requesterUserId);
        }
        long requesterCurrent = requester.getStorageCurrent() == null ? 0L : requester.getStorageCurrent();
        long requesterQuota = requester.getStorageQuota() == null ? 0L : requester.getStorageQuota();

        Runnable hook = beforeSourceDocumentLockHook;
        if (hook != null) {
            hook.run();
        }

        Document source = documentDao.getActiveByIdForUpdate(sourceDocumentId);
        if (source == null) {
            throw new IOException("SourceNotFound");
        }
        List<File> sourceFiles = fileDao.getByDocumentId(null, sourceDocumentId);
        List<TagDto> visibleTags = new TagDao().findByCriteria(
                new TagCriteria().setDocumentId(sourceDocumentId).setTargetIdList(requesterTargetIdList), null);
        List<DocumentMetadataDto> sourceMetadata = new DocumentMetadataDao().getByDocumentId(sourceDocumentId);

        // A file whose bytes cannot be measured fails the whole duplicate closed — never silently excluded.
        Map<String, User> ownerById = new HashMap<>();
        Map<String, Long> resolvedSizeById = new LinkedHashMap<>();
        long totalSize = 0L;
        for (File file : sourceFiles) {
            User owner = ownerById.computeIfAbsent(file.getUserId(), userDao::getById);
            long size = file.getSize() == null ? File.UNKNOWN_SIZE : file.getSize();
            if (size == File.UNKNOWN_SIZE) {
                size = FileUtil.getFileSize(file.getId(), owner);
            }
            if (size == File.UNKNOWN_SIZE) {
                throw new IOException("FileError");
            }
            resolvedSizeById.put(file.getId(), size);
            totalSize = Math.addExact(totalSize, size);
        }

        // Check only — never reserve here; createFile's per-file reservation is the sole charge, so
        // reserving here too would double-count the copy.
        if (requesterQuota < 0L || requesterCurrent < 0L || totalSize > requesterQuota - requesterCurrent) {
            throw new IOException("QuotaReached");
        }

        // Source description is already sanitized; the create date is reset to now, not inherited.
        Document copy = new Document();
        copy.setUserId(requesterUserId);
        copy.setTitle(buildCopyTitle(source.getTitle()));
        copy.setDescription(source.getDescription());
        copy.setSubject(source.getSubject());
        copy.setIdentifier(source.getIdentifier());
        copy.setPublisher(source.getPublisher());
        copy.setFormat(source.getFormat());
        copy.setSource(source.getSource());
        copy.setType(source.getType());
        copy.setCoverage(source.getCoverage());
        copy.setRights(source.getRights());
        copy.setLanguage(source.getLanguage());
        copy.setCreateDate(new Date());
        copy = DocumentUtil.createDocument(copy, requesterUserId);
        String copyId = copy.getId();

        // Copied tags carry their inherited tag-ACL visibility to the copy; no fresh direct ACLs are minted
        // for those principals, so the copy is not made private.
        Set<String> tagIdSet = new HashSet<>();
        for (TagDto tag : visibleTags) {
            tagIdSet.add(tag.getId());
        }
        if (!tagIdSet.isEmpty()) {
            new TagDao().updateTagList(copyId, tagIdSet);
        }

        // Non-null values under active definitions only (getByDocumentId already excludes inactive ones),
        // written through the validated metadata path.
        List<String> metadataIdList = new ArrayList<>();
        List<String> metadataValueList = new ArrayList<>();
        for (DocumentMetadataDto metadata : sourceMetadata) {
            if (metadata.getValue() != null) {
                metadataIdList.add(metadata.getMetadataId());
                metadataValueList.add(metadata.getValue());
            }
        }
        MetadataUtil.updateMetadata(copyId, metadataIdList, metadataValueList);

        Map<String, String> newFileIdBySourceId = copyFiles(
                sourceFiles, resolvedSizeById, ownerById, requesterUserId, copyId, copy.getLanguage());

        String sourceCover = source.getIdFileCover();
        if (sourceCover != null && newFileIdBySourceId.containsKey(sourceCover)) {
            documentDao.updateCoverFileId(copyId, newFileIdBySourceId.get(sourceCover));
        }
        documentDao.reconcileServingCover(copyId);

        DocumentCreatedAsyncEvent createdEvent = new DocumentCreatedAsyncEvent();
        createdEvent.setUserId(requesterUserId);
        createdEvent.setDocumentId(copyId);
        ThreadLocalContext.get().addAsyncEvent(createdEvent);

        return copyId;
    }

    /**
     * Copy the source files into the new document, returning the source-file-id to new-file-id map used to
     * remap the cover.
     */
    private static Map<String, String> copyFiles(List<File> sourceFiles, Map<String, Long> resolvedSizeById,
            Map<String, User> ownerById, String requesterUserId, String copyId, String language) throws Exception {
        FileDao fileDao = new FileDao();
        Path storageDirectory = DirectoryUtil.getStorageDirectory();
        Map<String, String> newFileIdBySourceId = new LinkedHashMap<>();

        // A plaintext temp's ownership transfers to the processing event queued by createFile only once that
        // call returns; a temp not handed off (a failure before or during createFile) is deleted here.
        List<Path> createdTemps = new ArrayList<>();
        Set<Path> handedOffTemps = new HashSet<>();
        try {
            for (File file : sourceFiles) {
                Path storedFile = storageDirectory.resolve(file.getId());
                if (!Files.exists(storedFile)) {
                    // Blob gone after the snapshot: fail closed.
                    throw new IOException("FileError");
                }

                User owner = ownerById.get(file.getUserId());
                Path plaintext = AppContext.getInstance().getFileService().createTemporaryFile();
                createdTemps.add(plaintext);
                try (InputStream storedStream = Files.newInputStream(storedFile);
                     InputStream decryptedStream = EncryptionUtil.decryptInputStream(storedStream, owner.getPrivateKey())) {
                    Files.copy(decryptedStream, plaintext, StandardCopyOption.REPLACE_EXISTING);
                }

                String newFileId = FileUtil.createFile(file.getName(), null, plaintext,
                        resolvedSizeById.get(file.getId()), language, requesterUserId, copyId);
                handedOffTemps.add(plaintext);

                // createFile carries rotation only onto a new VERSION, so a plain copy must carry it itself
                // to keep the regenerated rasters upright.
                if (file.getRotation() != 0) {
                    File newFile = fileDao.getActiveById(newFileId);
                    newFile.setRotation(file.getRotation());
                    fileDao.update(newFile);
                }

                newFileIdBySourceId.put(file.getId(), newFileId);
            }
        } finally {
            for (Path temp : createdTemps) {
                if (!handedOffTemps.contains(temp)) {
                    try {
                        Files.deleteIfExists(temp);
                    } catch (IOException e) {
                        log.warn("Unable to delete a duplicate plaintext temp: " + temp, e);
                    }
                }
            }
        }
        return newFileIdBySourceId;
    }

    /**
     * Build the copied title: the base is truncated so {@code <base> (copy)} fits the validated title length.
     */
    private static String buildCopyTitle(String title) {
        int maxBase = TITLE_MAX_LENGTH - COPY_SUFFIX.length();
        String base = title.length() > maxBase ? title.substring(0, maxBase) : title;
        return base + COPY_SUFFIX;
    }

    /**
     * Acquire the GLOBAL storage-quota lock, failing closed if the sentinel row is absent (an un-migrated
     * database) rather than proceeding unlocked.
     */
    private static void lockGlobalQuota() {
        if (!new ConfigDao().lockForUpdate(ConfigType.GLOBAL_QUOTA_LOCK)) {
            throw new IllegalStateException(
                    "The global storage-quota lock row (GLOBAL_QUOTA_LOCK) is missing — is the database migrated?");
        }
    }
}
