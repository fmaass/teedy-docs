package com.sismics.docs.core.util;

import com.sismics.docs.core.constant.AuditLogType;
import com.sismics.docs.core.dao.DocumentDao;
import com.sismics.docs.core.dao.FileDao;
import com.sismics.docs.core.dao.UserDao;
import com.sismics.docs.core.event.FileReindexAsyncEvent;
import com.sismics.docs.core.model.jpa.Document;
import com.sismics.docs.core.model.jpa.File;
import com.sismics.docs.core.model.jpa.User;
import com.sismics.util.context.ThreadLocalContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Moves a file — every active row of its version chain — from one document to another atomically, within the
 * caller's transaction. Soft-deleted historical rows keep their original document (their storage is gone).
 * The file owner and its quota attribution never change; no cross-owner quota transfer happens.
 *
 * <p>The move holds a pessimistic write lock on BOTH document rows (in deterministic id order to avoid a
 * lock-order cycle with a concurrent move of the reverse pair) and re-parents the chain rows with one guarded
 * bulk UPDATE constrained to the source document and active rows. Two guards keep a racing replacement,
 * first-replacement (version-id minting) or delete from splitting the chain across documents: the bulk's
 * affected-row count must equal the enumerated set, and a chain-growth-aware post-update re-verification then
 * counts residual active rows left at the source matching the moved file id or any moved row's current
 * version id — any residual rolls the whole move back. The search index is re-pointed AFTER commit, per moved
 * row, through a dedicated index-only event.</p>
 */
public class FileMoveService {
    private static final Logger log = LoggerFactory.getLogger(FileMoveService.class);

    private FileMoveService() {
    }

    /**
     * Move the active chain of {@code fileId} into {@code targetDocumentId}. The caller has already validated
     * WRITE on both documents and that the target differs from the source; this method owns the atomicity
     * guarantees and rolls back closed (via {@link FileMoveConflictException}) on any lost race.
     *
     * @param fileId File to move
     * @param targetDocumentId Destination document
     * @param userId Acting user (audit attribution; never becomes the file owner)
     */
    public static void moveFile(String fileId, String targetDocumentId, String userId) {
        FileDao fileDao = new FileDao();
        DocumentDao documentDao = new DocumentDao();

        File file = fileDao.getActiveById(fileId);
        if (file == null) {
            throw new FileMoveConflictException("The file no longer exists");
        }
        String sourceDocumentId = file.getDocumentId();
        if (sourceDocumentId == null) {
            throw new FileMoveConflictException("The file is not attached to a document");
        }

        // Lock BOTH document rows FOR UPDATE in id order, and re-validate both ACTIVE under the lock — a move
        // must never commit files into a trashed document, and the lock serializes this move against any
        // concurrent move/cover reconcile on either document.
        String firstId = sourceDocumentId.compareTo(targetDocumentId) <= 0 ? sourceDocumentId : targetDocumentId;
        String secondId = firstId.equals(sourceDocumentId) ? targetDocumentId : sourceDocumentId;
        Document firstDocument = documentDao.getActiveByIdForUpdate(firstId);
        Document secondDocument = documentDao.getActiveByIdForUpdate(secondId);
        if (firstDocument == null || secondDocument == null) {
            throw new FileMoveConflictException("A document involved in the move is no longer available");
        }

        // Re-read the file under the document locks. If it was re-parented away from the source between the
        // first read and the lock, our locks cover the wrong source: lose closed and let the caller retry.
        File lockedFile = fileDao.getActiveById(fileId);
        if (lockedFile == null || !sourceDocumentId.equals(lockedFile.getDocumentId())) {
            throw new FileMoveConflictException("The file was moved concurrently");
        }
        String versionId = lockedFile.getVersionId();

        // Enumerate the move set: the single active row for a null version id, else every active row sharing
        // the version chain that currently sits in the source document.
        List<String> movedIds = new ArrayList<>();
        if (versionId == null) {
            movedIds.add(fileId);
        } else {
            for (File chainRow : fileDao.getByVersionId(versionId)) {
                if (sourceDocumentId.equals(chainRow.getDocumentId())) {
                    movedIds.add(chainRow.getId());
                }
            }
        }
        int expectedCount = movedIds.size();

        // Appended order shared by every moved row (a later delete-promote keeps position).
        int targetOrder = fileDao.getByDocumentId(userId, targetDocumentId).size();

        // Single guarded bulk UPDATE. Any count drift (a row added or removed between enumeration and the
        // locked UPDATE) means the set changed concurrently: roll back.
        int affected = fileDao.relinkActiveRowsToDocument(fileId, versionId, sourceDocumentId, targetDocumentId, targetOrder);
        if (affected != expectedCount) {
            throw new FileMoveConflictException("The file set changed concurrently during the move");
        }

        // Chain-growth-aware post-update re-verification (fresh statements, same transaction): re-read the
        // moved rows' CURRENT version ids, then count residual ACTIVE source rows matching the moved file id
        // or any of those version ids. A first-replacement that minted a version id and inserted a successor
        // at the source after enumeration leaves such a residual — roll back so the chain never splits.
        List<File> movedRows = fileDao.getFiles(movedIds);
        Set<String> movedVersionIds = new LinkedHashSet<>();
        for (File movedRow : movedRows) {
            if (movedRow.getVersionId() != null) {
                movedVersionIds.add(movedRow.getVersionId());
            }
        }
        if (fileDao.countActiveMatchingInDocument(sourceDocumentId, fileId, movedVersionIds) > 0) {
            throw new FileMoveConflictException("A concurrent replacement left rows in the source document");
        }

        // Re-key each moved row's content MAC to the TARGET document (ADR-0021): with the feature on, recompute
        // and overwrite; otherwise (or if the plaintext cannot be recomputed) clear it. A MAC keyed to the
        // source document must never survive the move in either mode.
        boolean macEnabled = ContentMacUtil.isEnabled();
        for (File movedRow : movedRows) {
            String newMac = macEnabled ? recomputeTargetMac(movedRow, targetDocumentId) : null;
            fileDao.setContentMacForMovedRow(movedRow.getId(), newMac, targetDocumentId);
        }

        // Reconcile each document's served cover synchronously under the held locks: the source falls back off
        // a now-dangling cover that pointed at a moved file; a previously empty target gains the moved file.
        Document reconciledSource = documentDao.reconcileServingCover(sourceDocumentId);
        Document reconciledTarget = documentDao.reconcileServingCover(targetDocumentId);

        // Audit both documents.
        if (reconciledSource != null) {
            AuditLogUtil.create(reconciledSource, AuditLogType.UPDATE, userId);
        }
        if (reconciledTarget != null) {
            AuditLogUtil.create(reconciledTarget, AuditLogType.UPDATE, userId);
        }

        // Re-point each moved row's search entry AFTER commit — index-only, no reprocessing.
        for (String movedId : movedIds) {
            FileReindexAsyncEvent event = new FileReindexAsyncEvent();
            event.setUserId(userId);
            event.setFileId(movedId);
            ThreadLocalContext.get().addAsyncEvent(event);
        }
    }

    /**
     * Recompute a moved row's content MAC keyed to the target document by decrypting its stored blob with the
     * owner's key. Returns null on any failure so the caller clears the cell rather than leaving a MAC keyed
     * to the previous document.
     */
    private static String recomputeTargetMac(File movedRow, String targetDocumentId) {
        User owner = new UserDao().getById(movedRow.getUserId());
        if (owner == null) {
            return null;
        }
        Path storedFile = DirectoryUtil.getStorageDirectory().resolve(movedRow.getId());
        try (InputStream rawInputStream = Files.newInputStream(storedFile);
             InputStream plaintext = EncryptionUtil.decryptInputStream(rawInputStream, owner.getPrivateKey())) {
            return ContentMacUtil.computeMac(targetDocumentId, plaintext);
        } catch (Exception e) {
            log.warn("Unable to recompute the content MAC on move for file " + movedRow.getId()
                    + "; clearing it instead", e);
            return null;
        }
    }
}
