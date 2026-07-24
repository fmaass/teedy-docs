package com.sismics.docs.core.listener.async;

import com.google.common.eventbus.AllowConcurrentEvents;
import com.google.common.eventbus.Subscribe;
import com.sismics.docs.core.dao.FileDao;
import com.sismics.docs.core.event.FileReindexAsyncEvent;
import com.sismics.docs.core.model.context.AppContext;
import com.sismics.docs.core.model.jpa.File;
import com.sismics.docs.core.util.TransactionUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Re-indexes a single file after a move re-parented it, keyed on the file id so the index entry's
 * {@code document_id} follows the file to its new document. It re-reads the committed row and issues the
 * upsert index write only — no raster/OCR reprocessing — and is a no-op if the file was deleted since.
 */
public class FileReindexAsyncListener {
    private static final Logger log = LoggerFactory.getLogger(FileReindexAsyncListener.class);

    @Subscribe
    @AllowConcurrentEvents
    public void on(final FileReindexAsyncEvent event) {
        if (log.isInfoEnabled()) {
            log.info("File reindex event: " + event.toString());
        }

        TransactionUtil.handle(() -> {
            File file = new FileDao().getActiveById(event.getFileId());
            if (file == null) {
                return;
            }
            AppContext.getInstance().getIndexingHandler().updateFile(file);
        });
    }
}
