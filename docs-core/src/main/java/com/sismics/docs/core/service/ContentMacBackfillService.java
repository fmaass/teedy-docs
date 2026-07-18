package com.sismics.docs.core.service;

import com.google.common.util.concurrent.AbstractScheduledService;
import com.sismics.docs.core.dao.FileDao;
import com.sismics.docs.core.dao.UserDao;
import com.sismics.docs.core.model.jpa.File;
import com.sismics.docs.core.model.jpa.User;
import com.sismics.docs.core.util.ContentMacUtil;
import com.sismics.docs.core.util.DirectoryUtil;
import com.sismics.docs.core.util.EncryptionUtil;
import com.sismics.docs.core.util.TransactionUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Backfills {@code T_FILE.FIL_CONTENTMAC_C} for legacy files that predate content-hash duplicate detection
 * (#119), modelled on {@link FileSizeService}: small throttled batches, self-stops on drain, restartable,
 * and idempotent across instances. Each iteration decrypts a batch of null-MAC document-attached files ONCE
 * with the pinned digest and writes the MAC with a conditional {@code UPDATE ... WHERE FIL_CONTENTMAC_C IS
 * NULL} — multi-instance safe (the loser's update touches zero rows), NOT an exactly-once guarantee.
 *
 * <p><b>Degrade to off:</b> when no master secret is configured the service is a no-op and stops on its
 * first iteration (no rows are ever touched). <b>Terminal skip:</b> a corrupt / undecryptable / owner-less
 * row is stamped with {@link #SKIP_MARKER} (a non-hex sentinel that can never equal a real lowercase-hex
 * MAC, so it neither re-selects forever nor can spoof a duplicate match) instead of being left NULL.</p>
 */
public class ContentMacBackfillService extends AbstractScheduledService {
    private static final Logger log = LoggerFactory.getLogger(ContentMacBackfillService.class);

    private static final int BATCH_SIZE = 30;

    /**
     * Terminal marker for a row whose content cannot be hashed (missing/corrupt blob, undecryptable, or an
     * absent owner key). It is non-null so the null-MAC scan stops re-selecting it, and it contains a colon
     * so it can NEVER collide with a 64-char lowercase-hex MAC (which would otherwise risk a false match).
     */
    static final String SKIP_MARKER = "skip:undecryptable";

    @Override
    protected void startUp() {
        log.info("Content MAC backfill service starting up");
    }

    @Override
    protected void shutDown() {
        log.info("Content MAC backfill service shutting down");
    }

    @Override
    protected void runOneIteration() {
        try {
            // Degrade to off: with no master secret there is nothing to backfill — stop immediately.
            if (!ContentMacUtil.isEnabled()) {
                stopAsync();
                return;
            }
            TransactionUtil.handle(() -> {
                FileDao fileDao = new FileDao();
                List<File> files = fileDao.getActiveFilesWithNullMac(BATCH_SIZE);
                for (File file : files) {
                    processFile(file);
                }
                if (files.size() < BATCH_SIZE) {
                    log.info("No more file to backfill with a content MAC, stopping the service");
                    stopAsync();
                }
            });
        } catch (Throwable e) {
            log.error("Exception during content MAC backfill iteration", e);
        }
    }

    /**
     * Compute and persist the content MAC for one legacy file, or stamp a terminal skip marker when its
     * plaintext cannot be recovered. The conditional write only fills a still-null cell, so a concurrent
     * instance that already wrote it is a clean no-op.
     *
     * @param file The null-MAC file row to backfill
     */
    void processFile(File file) {
        String mac = null;
        try {
            User user = new UserDao().getById(file.getUserId());
            if (user != null) {
                Path storedFile = DirectoryUtil.getStorageDirectory().resolve(file.getId());
                if (Files.exists(storedFile)) {
                    try (InputStream raw = Files.newInputStream(storedFile);
                         InputStream plaintext = user.getPrivateKey() == null
                                 ? raw : EncryptionUtil.decryptInputStream(raw, user.getPrivateKey())) {
                        mac = ContentMacUtil.computeMac(file.getDocumentId(), plaintext);
                    }
                }
            }
        } catch (Throwable t) {
            // Any failure (missing key, corrupt blob, decrypt error) -> terminal skip, never retried forever.
            log.warn("Content MAC backfill could not hash file {}, marking terminal skip", file.getId(), t);
            mac = null;
        }
        FileDao fileDao = new FileDao();
        if (mac != null) {
            fileDao.setContentMacIfNull(file.getId(), mac);
        } else if (ContentMacUtil.isEnabled()) {
            // Enabled but the plaintext could not be recovered (missing/corrupt blob, absent owner): stamp a
            // terminal skip so the null-MAC scan stops re-selecting it forever. When the feature is OFF the
            // null is expected and the row is left untouched (runOneIteration also never calls this path off).
            fileDao.setContentMacIfNull(file.getId(), SKIP_MARKER);
        }
    }

    @Override
    protected Scheduler scheduler() {
        return Scheduler.newFixedDelaySchedule(0, 1, TimeUnit.MINUTES);
    }
}
