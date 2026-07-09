package com.sismics.docs.core.util;

import com.sismics.docs.BaseTransactionalTest;
import com.sismics.docs.core.dao.DocumentDao;
import com.sismics.docs.core.dao.FileDao;
import com.sismics.docs.core.dao.UserDao;
import com.sismics.docs.core.model.jpa.Document;
import com.sismics.docs.core.model.jpa.File;
import com.sismics.docs.core.model.jpa.User;
import com.sismics.util.mime.MimeType;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;

/**
 * Tests the synchronous storage-quota reclamation helpers used by the file/document delete producers
 * (Option B): {@link FileUtil#resolveReclaimableSize} and {@link FileUtil#reclaimUserQuota}.
 */
public class TestFileUtilQuota extends BaseTransactionalTest {

    @Test
    public void reclaimKnownSizeDecrementsOnce() throws Exception {
        User user = createUser("reclaimKnown");
        UserDao userDao = new UserDao();
        user = userDao.getById(user.getId());
        user.setStorageCurrent(10_000L);
        userDao.updateQuota(user);

        long size = FileUtil.resolveReclaimableSize("no-such-file", FILE_JPG_SIZE, user);
        Assertions.assertEquals(FILE_JPG_SIZE.longValue(), size,
                "a known file size must be used directly, without touching the disk");

        FileUtil.reclaimUserQuota(user.getId(), size);
        Assertions.assertEquals(Long.valueOf(10_000L - FILE_JPG_SIZE),
                userDao.getById(user.getId()).getStorageCurrent());
    }

    @Test
    public void reclaimUnknownSizeResolvesFromDisk() throws Exception {
        User user = createUser("reclaimUnknown");
        // createFile writes the encrypted content to storage, so an UNKNOWN_SIZE row can be sized from disk.
        File file = createFile(user, File.UNKNOWN_SIZE);
        UserDao userDao = new UserDao();
        user = userDao.getById(user.getId());
        user.setStorageCurrent(10_000L);
        userDao.updateQuota(user);

        long size = FileUtil.resolveReclaimableSize(file.getId(), File.UNKNOWN_SIZE, userDao.getById(user.getId()));
        Assertions.assertTrue(size > 0L, "an UNKNOWN_SIZE file must be sized from its on-disk content");

        FileUtil.reclaimUserQuota(user.getId(), size);
        Assertions.assertEquals(Long.valueOf(10_000L - size),
                userDao.getById(user.getId()).getStorageCurrent());
    }

    @Test
    public void reclaimSumsMultipleFilesOnce() throws Exception {
        User user = createUser("reclaimSum");
        UserDao userDao = new UserDao();
        user = userDao.getById(user.getId());
        user.setStorageCurrent(10_000L);
        userDao.updateQuota(user);

        // Simulate a multi-file document delete: sum the reclaimable sizes, decrement once.
        long total = FileUtil.resolveReclaimableSize("f1", 100L, user)
                + FileUtil.resolveReclaimableSize("f2", 250L, user)
                + FileUtil.resolveReclaimableSize("f3", File.UNKNOWN_SIZE, null); // unknown + no user -> 0
        Assertions.assertEquals(350L, total);

        FileUtil.reclaimUserQuota(user.getId(), total);
        Assertions.assertEquals(Long.valueOf(10_000L - 350L),
                userDao.getById(user.getId()).getStorageCurrent());
    }

    @Test
    public void reclaimClampsAtZeroAndIgnoresNonPositive() throws Exception {
        User user = createUser("reclaimClamp");
        UserDao userDao = new UserDao();
        user = userDao.getById(user.getId());
        user.setStorageCurrent(100L);
        userDao.updateQuota(user);

        // Over-reclaim must clamp at zero, not go negative.
        FileUtil.reclaimUserQuota(user.getId(), 500L);
        Assertions.assertEquals(Long.valueOf(0L), userDao.getById(user.getId()).getStorageCurrent());

        // Zero/negative is a no-op.
        FileUtil.reclaimUserQuota(user.getId(), 0L);
        Assertions.assertEquals(Long.valueOf(0L), userDao.getById(user.getId()).getStorageCurrent());
    }

    /**
     * reclaimQuotaForDeletedDocumentFiles: charges each file to ITS OWN owner (per-owner grouping),
     * and reclaims ONLY files whose deleteDate matches the document's (cascade-trashed), skipping a
     * file individually deleted earlier (different deleteDate = already reclaimed).
     */
    @Test
    public void reclaimGroupsByOwnerAndSkipsAlreadyDeleted() throws Exception {
        User owner = createUser("reclaimOwnerA");
        User collab = createUser("reclaimOwnerB");
        UserDao userDao = new UserDao();

        User o = userDao.getById(owner.getId());
        o.setStorageCurrent(1_000L);
        userDao.updateQuota(o);
        User c = userDao.getById(collab.getId());
        c.setStorageCurrent(1_000L);
        userDao.updateQuota(c);

        Date trashInstant = new Date();
        Date earlierInstant = new Date(trashInstant.getTime() - 60_000L);

        // Cascade-trashed file owned by the doc owner (deleteDate == document deleteDate).
        File ownerFile = new File();
        ownerFile.setId("owner-file");
        ownerFile.setUserId(owner.getId());
        ownerFile.setSize(100L);
        ownerFile.setDeleteDate(trashInstant);

        // Cascade-trashed file owned by a collaborator (uploaded to the same document).
        File collabFile = new File();
        collabFile.setId("collab-file");
        collabFile.setUserId(collab.getId());
        collabFile.setSize(250L);
        collabFile.setDeleteDate(trashInstant);

        // A file individually deleted earlier (different deleteDate) -> already reclaimed, must be skipped.
        File alreadyDeleted = new File();
        alreadyDeleted.setId("already-deleted-file");
        alreadyDeleted.setUserId(owner.getId());
        alreadyDeleted.setSize(400L);
        alreadyDeleted.setDeleteDate(earlierInstant);

        FileUtil.reclaimQuotaForDeletedDocumentFiles(
                java.util.List.of(ownerFile, collabFile, alreadyDeleted), trashInstant);

        // Owner reclaimed only their cascade file (100), NOT the already-deleted 400.
        Assertions.assertEquals(Long.valueOf(1_000L - 100L), userDao.getById(owner.getId()).getStorageCurrent());
        // Collaborator reclaimed their own file (250) from THEIR quota, not the owner's.
        Assertions.assertEquals(Long.valueOf(1_000L - 250L), userDao.getById(collab.getId()).getStorageCurrent());
    }

    /**
     * BL-021 regression: a file a collaborator (B) uploaded into another user's (A) document is
     * left stranded with {@code deleteDate == null} when A is deleted — {@code UserDao.delete}
     * soft-deletes only A's OWN files, not B's uploads. At purge, the document's own deleteDate does
     * not equal the stranded file's null deleteDate, so the old {@code documentDeleteDate.equals(...)}
     * discriminator skipped it: B's bytes were hard-deleted but B's quota was never reclaimed. The
     * relaxed discriminator must reclaim the stranded {@code deleteDate == null} file to B's quota.
     *
     * <p>Uses the REAL user-deletion path ({@code UserDao.delete}) so the collaborator file is
     * genuinely stranded, then reproduces the purge reclaim exactly as {@code TrashPurgeService} and
     * {@code DocumentResourceHelper} do (getAllByDocumentId + reclaim against the document deleteDate).
     */
    @Test
    public void reclaimsStrandedCollaboratorFileWhenOwnerDeleted() throws Exception {
        UserDao userDao = new UserDao();
        DocumentDao documentDao = new DocumentDao();
        FileDao fileDao = new FileDao();

        User owner = createUser("bl021Owner");
        User collab = createUser("bl021Collab");

        // The collaborator's quota reflects their single upload; assert it returns to 0 after reclaim.
        long collabFileSize = 250L;
        User c = userDao.getById(collab.getId());
        c.setStorageCurrent(collabFileSize);
        userDao.updateQuota(c);

        // A document owned by A.
        Document document = new Document();
        document.setUserId(owner.getId());
        document.setLanguage("eng");
        document.setTitle("BL-021 doc");
        document.setCreateDate(new Date());
        String documentId = documentDao.create(document, owner.getId());

        // Collaborator B uploads a file INTO A's document (charged to B's quota).
        File collabFile = new File();
        collabFile.setDocumentId(documentId);
        collabFile.setUserId(collab.getId());
        collabFile.setVersion(0);
        collabFile.setLatestVersion(true);
        collabFile.setMimeType(MimeType.IMAGE_JPEG);
        collabFile.setSize(collabFileSize);
        String collabFileId = fileDao.create(collabFile, collab.getId());

        // Delete owner A via the REAL user-deletion path. This soft-deletes A's OWN files and A's
        // document, but leaves B's file with deleteDate == null (the stranded-file hole).
        userDao.delete(owner.getUsername(), owner.getId());

        // Sanity: the document is soft-deleted, but B's file remains un-deleted (stranded).
        Document deletedDocument = documentDao.getDeletedByIdSystem(documentId);
        Assertions.assertNotNull(deletedDocument, "owner's document must be soft-deleted");
        Assertions.assertNotNull(deletedDocument.getDeleteDate());
        List<File> allFiles = fileDao.getAllByDocumentId(documentId);
        File strandedFile = allFiles.stream().filter(f -> f.getId().equals(collabFileId)).findFirst().orElseThrow();
        Assertions.assertNull(strandedFile.getDeleteDate(),
                "collaborator's file must be stranded with deleteDate == null after owner deletion");

        // Reproduce the purge reclaim exactly as TrashPurgeService/DocumentResourceHelper do.
        FileUtil.reclaimQuotaForDeletedDocumentFiles(allFiles, deletedDocument.getDeleteDate());

        // The stranded collaborator file's bytes must be reclaimed to B's quota (back to 0).
        Assertions.assertEquals(Long.valueOf(0L), userDao.getById(collab.getId()).getStorageCurrent(),
                "stranded collaborator file must reclaim quota to its own uploader on purge");
    }
}
