package com.sismics.docs.core.listener.async;

import com.sismics.docs.BaseTransactionalTest;
import com.sismics.docs.core.dao.UserDao;
import com.sismics.docs.core.event.FileDeletedAsyncEvent;
import com.sismics.docs.core.model.jpa.File;
import com.sismics.docs.core.model.jpa.User;
import com.sismics.docs.core.util.TransactionUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Under Option B the storage-quota decrement lives in the synchronous delete transaction at each
 * producer, NOT in this async listener. The listener performs only idempotent work (filesystem
 * delete + Lucene delete-by-id) and must be quota-neutral, so a re-delivered event can never
 * double-subtract. These tests assert exactly that neutrality.
 */
public class FileDeletedAsyncListenerTest extends BaseTransactionalTest {

    /**
     * Delivering the event does NOT change the user's quota (known size). RED against the old code,
     * which decremented here.
     */
    @Test
    public void listenerDoesNotTouchQuotaSizeKnown() throws Exception {
        User user = createUser("listenerNeutralSizeKnown");
        File file = createFile(user, FILE_JPG_SIZE);
        UserDao userDao = new UserDao();
        user = userDao.getById(user.getId());
        user.setStorageCurrent(10_000L);
        userDao.updateQuota(user);

        FileDeletedAsyncListener fileDeletedAsyncListener = new FileDeletedAsyncListener();
        TransactionUtil.commit();
        FileDeletedAsyncEvent event = new FileDeletedAsyncEvent();
        event.setFileSize(FILE_JPG_SIZE);
        event.setFileId(file.getId());
        event.setUserId(user.getId());
        fileDeletedAsyncListener.on(event);

        Assertions.assertEquals(Long.valueOf(10_000L), userDao.getById(user.getId()).getStorageCurrent(),
                "the async listener must not touch the quota (reclamation is synchronous at the producer)");
    }

    /**
     * Same neutrality for a legacy UNKNOWN_SIZE file: the listener must not read the disk and
     * decrement. RED against the old code, which resolved the size from disk and decremented here.
     */
    @Test
    public void listenerDoesNotTouchQuotaSizeUnknown() throws Exception {
        User user = createUser("listenerNeutralSizeUnknown");
        File file = createFile(user, File.UNKNOWN_SIZE);
        UserDao userDao = new UserDao();
        user = userDao.getById(user.getId());
        user.setStorageCurrent(10_000L);
        userDao.updateQuota(user);

        FileDeletedAsyncListener fileDeletedAsyncListener = new FileDeletedAsyncListener();
        TransactionUtil.commit();
        FileDeletedAsyncEvent event = new FileDeletedAsyncEvent();
        event.setFileSize(File.UNKNOWN_SIZE);
        event.setFileId(file.getId());
        event.setUserId(user.getId());
        fileDeletedAsyncListener.on(event);

        Assertions.assertEquals(Long.valueOf(10_000L), userDao.getById(user.getId()).getStorageCurrent(),
                "the async listener must not touch the quota, even for a legacy UNKNOWN_SIZE file");
    }

    /**
     * Re-delivery (the retry path) is a no-op for the quota: delivering the same event twice leaves
     * the quota exactly where it started. Proves the listener is now safe under the async retry knob.
     */
    @Test
    public void redeliveryIsQuotaNeutral() throws Exception {
        User user = createUser("listenerRedeliveryNeutral");
        File file = createFile(user, FILE_JPG_SIZE);
        UserDao userDao = new UserDao();
        user = userDao.getById(user.getId());
        user.setStorageCurrent(10_000L);
        userDao.updateQuota(user);

        FileDeletedAsyncListener fileDeletedAsyncListener = new FileDeletedAsyncListener();
        TransactionUtil.commit();
        FileDeletedAsyncEvent event = new FileDeletedAsyncEvent();
        event.setFileSize(FILE_JPG_SIZE);
        event.setFileId(file.getId());
        event.setUserId(user.getId());

        fileDeletedAsyncListener.on(event);
        fileDeletedAsyncListener.on(event);

        Assertions.assertEquals(Long.valueOf(10_000L), userDao.getById(user.getId()).getStorageCurrent(),
                "re-delivering the event must not change the quota");
    }
}
