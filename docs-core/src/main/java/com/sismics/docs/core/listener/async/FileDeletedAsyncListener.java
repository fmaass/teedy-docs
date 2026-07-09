package com.sismics.docs.core.listener.async;

import com.google.common.eventbus.AllowConcurrentEvents;
import com.google.common.eventbus.Subscribe;
import com.sismics.docs.core.event.FileDeletedAsyncEvent;
import com.sismics.docs.core.model.context.AppContext;
import com.sismics.docs.core.util.FileUtil;
import com.sismics.docs.core.util.TransactionUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Listener on file deleted.
 * <p>
 * This listener is idempotent, so it is safe under the optional async retry
 * ({@code RetryingSubscriberExceptionHandler}). It performs only repeatable work: removing the file
 * from storage ({@code FileUtil.delete} no-ops on an already-absent file) and deleting the file from
 * the Lucene index (delete-by-id). It deliberately does NOT touch the storage quota — the quota is
 * reclaimed synchronously by the producer, in the same transaction as the delete (see
 * {@code FileUtil.resolveReclaimableSize} / {@code FileUtil.reclaimUserQuota}), so a re-delivered
 * event can never double-subtract.
 *
 * @author bgamard
 */
public class FileDeletedAsyncListener {
    /**
     * Logger.
     */
    private static final Logger log = LoggerFactory.getLogger(FileDeletedAsyncListener.class);

    /**
     * File deleted.
     *
     * @param event File deleted event
     * @throws Exception e
     */
    @Subscribe
    @AllowConcurrentEvents
    public void on(final FileDeletedAsyncEvent event) throws Exception {
        if (log.isInfoEnabled()) {
            log.info("File deleted event: " + event.toString());
        }

        // Delete the file from storage (idempotent: no-ops if already gone)
        FileUtil.delete(event.getFileId());

        TransactionUtil.handle(() -> {
            // Update index (idempotent: delete-by-id)
            AppContext.getInstance().getIndexingHandler().deleteDocument(event.getFileId());
        });
    }
}
