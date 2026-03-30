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
import com.sismics.docs.core.model.jpa.File;
import com.sismics.docs.core.util.TransactionUtil;
import com.sismics.util.context.ThreadLocalContext;

/**
 * Service that periodically purges expired documents from the trash.
 */
public class TrashPurgeService extends AbstractScheduledService {
    private static final Logger log = LoggerFactory.getLogger(TrashPurgeService.class);

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

    private void purgeExpiredTrash() {
        int retentionDays = getRetentionDays();
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
                List<File> fileList = fileDao.getAllByDocumentId(documentId);
                for (File file : fileList) {
                    FileDeletedAsyncEvent event = new FileDeletedAsyncEvent();
                    event.setUserId("admin");
                    event.setFileId(file.getId());
                    event.setFileSize(file.getSize());
                    ThreadLocalContext.get().addAsyncEvent(event);
                }

                DocumentDeletedAsyncEvent event = new DocumentDeletedAsyncEvent();
                event.setUserId("admin");
                event.setDocumentId(documentId);
                ThreadLocalContext.get().addAsyncEvent(event);

                documentDao.permanentDelete(documentId);
            });
            purged++;
        }

        log.info("Purged {} expired trashed documents", purged);
    }

    private static int getRetentionDays() {
        String envValue = System.getenv(ENV_RETENTION_DAYS);
        if (envValue != null) {
            try {
                return Integer.parseInt(envValue);
            } catch (NumberFormatException e) {
                log.warn("Invalid value for {}: {}, using default {}", ENV_RETENTION_DAYS, envValue, DEFAULT_RETENTION_DAYS);
            }
        }
        return DEFAULT_RETENTION_DAYS;
    }
}
