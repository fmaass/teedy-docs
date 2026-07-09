package com.sismics.docs.core.util;

import com.sismics.docs.BaseTransactionalTest;
import com.sismics.docs.core.dao.UserDao;
import com.sismics.docs.core.model.jpa.File;
import com.sismics.docs.core.model.jpa.User;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Date;

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
}
