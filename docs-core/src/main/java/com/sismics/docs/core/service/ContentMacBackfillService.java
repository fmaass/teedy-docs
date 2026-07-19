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
import java.util.ArrayList;
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
            // Fetch the batch (for drain detection) in its OWN short read transaction, then process each
            // file in its OWN transaction below — the backfill therefore never holds more than a single
            // T_FILE row lock at a time.
            List<File> files = fetchNullMacBatch();
            int failures = processBatch(files);
            // Drain detection. A partial batch (< BATCH_SIZE) normally means the null-MAC scan is exhausted,
            // BUT only when every file in it was actually processed. "Processed" = committed a real MAC OR
            // terminal-skip-marked (an undecryptable row stamped with SKIP_MARKER — a DONE row, not a
            // failure). A swallowed TRANSIENT failure (a caught DB/commit hiccup) leaves that row's MAC
            // still null, so work REMAINS: we must NOT stop, and the next scheduled iteration retries the
            // still-null row. Stop only on a partial batch with ZERO swallowed failures.
            if (files.size() < BATCH_SIZE && failures == 0) {
                log.info("No more file to backfill with a content MAC, stopping the service");
                stopAsync();
            } else if (failures > 0) {
                log.info("{} file(s) failed transiently this iteration, keeping the backfill service running to retry", failures);
            }
        } catch (Throwable e) {
            log.error("Exception during content MAC backfill iteration", e);
        }
    }

    /**
     * Read one batch of null-MAC work in its own short transaction, closed before any per-file write — the
     * fetch holds no row locks, and its size drives drain detection ({@code < BATCH_SIZE} means exhausted).
     *
     * @return up to {@link #BATCH_SIZE} null-MAC active document-attached files
     */
    List<File> fetchNullMacBatch() {
        List<File> files = new ArrayList<>();
        TransactionUtil.handle(() -> files.addAll(new FileDao().getActiveFilesWithNullMac(BATCH_SIZE)));
        return files;
    }

    /**
     * Backfill every file of the batch, each in its OWN transaction, so the service never holds more than a
     * single {@code T_FILE} row lock at a time. A single-row lock can never be one half of a deadlock cycle,
     * so this can no longer deadlock a concurrent (unordered) user-deletion bulk update that touches the same
     * rows. A per-file failure is isolated — its transaction rolls back on its own and is logged — so one bad
     * row neither rolls back nor blocks the rest of the batch. The conditional {@code where contentMac is
     * null} write keeps each per-file commit restartable and multi-instance idempotent, and naturally absorbs
     * a row that became ineligible (already stamped) between the fetch and its per-file transaction.
     *
     * <p>Returns the number of files that failed with a swallowed TRANSIENT error so the caller can tell a
     * genuinely-drained partial batch (every file processed) from one that still has work. Note the
     * distinction: a terminal skip (undecryptable content stamped with {@link #SKIP_MARKER}) is handled
     * INSIDE {@link #processFile} and does NOT throw — it is a processed/DONE row and is NOT counted here.
     * Only a transaction that throws out of {@code processFile} (a DB/commit hiccup) is a transient failure:
     * that row's MAC is still null, so work remains and the count is incremented.</p>
     *
     * @param files The batch of null-MAC files to backfill
     * @return the count of files whose per-file transaction threw a (caught) transient failure — {@code 0}
     *         means every file was processed (committed or terminal-skip-marked)
     */
    int processBatch(List<File> files) {
        int failures = 0;
        for (File file : files) {
            try {
                TransactionUtil.handle(() -> processFile(file));
            } catch (Throwable e) {
                // Swallowed TRANSIENT failure: the per-file transaction rolled back with this row's MAC
                // still null, so work REMAINS. (A terminal skip never reaches here — processFile stamps
                // SKIP_MARKER without throwing, so it is a DONE row and is not counted as a failure.)
                failures++;
                log.error("Content MAC backfill failed for file {}, skipping to the next", file.getId(), e);
            }
        }
        return failures;
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
