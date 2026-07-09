package com.sismics.docs.core.service;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.util.concurrent.AbstractScheduledService;
import com.sismics.docs.core.dao.DocumentDao;
import com.sismics.docs.core.dao.FileDao;
import com.sismics.docs.core.event.DocumentDeletedAsyncEvent;
import com.sismics.docs.core.event.FileDeletedAsyncEvent;
import com.sismics.docs.core.model.context.AppContext;
import com.sismics.docs.core.model.jpa.Document;
import com.sismics.docs.core.model.jpa.File;
import com.sismics.docs.core.util.FileUtil;
import com.sismics.docs.core.util.TransactionUtil;
import com.sismics.util.EnvironmentUtil;
import com.sismics.util.context.ThreadLocalContext;

/**
 * Service that periodically purges expired documents from the trash.
 */
public class TrashPurgeService extends AbstractScheduledService {
    private static final Logger log = LoggerFactory.getLogger(TrashPurgeService.class);

    private static final String RETENTION_DAYS_PROPERTY = "docs.trash_retention_days";
    private static final String ENV_RETENTION_DAYS = "DOCS_TRASH_RETENTION_DAYS";
    private static final int DEFAULT_RETENTION_DAYS = 30;

    @Override
    protected void startUp() {
        log.info("Trash purge service starting up (retention: {} days)", getRetentionDays());
    }

    @Override
    protected void shutDown() {
        log.info("Trash purge service shutting down");
    }

    @Override
    protected void runOneIteration() {
        try {
            purgeExpiredTrash();
        } catch (Throwable e) {
            log.error("Exception during trash purge", e);
        }
    }

    @Override
    protected Scheduler scheduler() {
        return Scheduler.newFixedDelaySchedule(1, 60, TimeUnit.MINUTES);
    }

    void purgeExpiredTrash() {
        purgeExpiredTrash(getRetentionDays());
    }

    /**
     * Purge trashed documents whose deletion is older than the given retention window.
     * Package-private with an explicit retention so tests are deterministic and decoupled
     * from the DOCS_TRASH_RETENTION_DAYS environment.
     *
     * @param retentionDays Retention window in days; a value &lt;= 0 disables purging
     */
    void purgeExpiredTrash(int retentionDays) {
        if (retentionDays <= 0) {
            return;
        }

        List<String> expiredIds = new java.util.ArrayList<>();
        TransactionUtil.handle(() -> {
            expiredIds.addAll(new DocumentDao().findExpiredTrash(retentionDays));
        });

        if (expiredIds.isEmpty()) {
            return;
        }

        log.info("Purging {} expired trashed documents (retention: {} days)", expiredIds.size(), retentionDays);

        int purged = 0;
        for (String documentId : expiredIds) {
            TransactionUtil.handle(() -> {
                FileDao fileDao = new FileDao();
                DocumentDao documentDao = new DocumentDao();
                // Attribute the deletion/quota events to the document's real owner, not "admin",
                // so the owner's storage is released instead of admin's (matches per-doc permanentDelete).
                Document document = documentDao.getDeletedByIdSystem(documentId);
                if (document == null) {
                    // Already purged or restored between the scan and now.
                    return;
                }
                String ownerId = document.getUserId();
                List<File> fileList = fileDao.getAllByDocumentId(documentId);

                // Reclaim quota synchronously, atomically with the permanent delete below (same
                // transaction). The async FileDeletedAsyncEvent listener no longer touches the quota,
                // so a retried event cannot double-subtract. reclaimQuotaForDeletedDocumentFiles
                // reclaims only the files cascade-trashed with this document (matching document
                // deleteDate — not files individually deleted earlier and already reclaimed) and
                // charges each file to its own uploader.
                FileUtil.reclaimQuotaForDeletedDocumentFiles(fileList, document.getDeleteDate());

                for (File file : fileList) {
                    FileDeletedAsyncEvent event = new FileDeletedAsyncEvent();
                    event.setUserId(ownerId);
                    event.setFileId(file.getId());
                    event.setFileSize(file.getSize());
                    ThreadLocalContext.get().addAsyncEvent(event);
                }

                DocumentDeletedAsyncEvent event = new DocumentDeletedAsyncEvent();
                event.setUserId(ownerId);
                event.setDocumentId(documentId);
                ThreadLocalContext.get().addAsyncEvent(event);

                documentDao.permanentDelete(documentId);
            });
            purged++;
        }

        log.info("Purged {} expired trashed documents", purged);
    }

    /**
     * Returns the configured trash retention window in days.
     * Reads the {@code docs.trash_retention_days} system property, falling back to the
     * {@code DOCS_TRASH_RETENTION_DAYS} environment variable, then to
     * {@link #DEFAULT_RETENTION_DAYS}. A malformed value falls back to the default; a
     * valid value &lt;= 0 is returned as-is and disables purging (see {@link #purgeExpiredTrash(int)}).
     * Single source of truth for the retention window: the purge scheduler and the
     * /app REST endpoint (which surfaces it to the SPA countdown) both call this.
     *
     * @return Retention window in days
     */
    public static int getRetentionDays() {
        return EnvironmentUtil.getIntConfig(RETENTION_DAYS_PROPERTY, ENV_RETENTION_DAYS, DEFAULT_RETENTION_DAYS);
    }
}
