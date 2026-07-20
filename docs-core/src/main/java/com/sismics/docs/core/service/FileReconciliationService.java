package com.sismics.docs.core.service;

import com.google.common.util.concurrent.AbstractScheduledService;
import com.sismics.docs.core.dao.DocumentDao;
import com.sismics.docs.core.dao.FileDao;
import com.sismics.docs.core.dao.UserDao;
import com.sismics.docs.core.event.FileCreatedAsyncEvent;
import com.sismics.docs.core.event.FileEvent;
import com.sismics.docs.core.model.context.AppContext;
import com.sismics.docs.core.model.jpa.Document;
import com.sismics.docs.core.model.jpa.File;
import com.sismics.docs.core.model.jpa.User;
import com.sismics.docs.core.util.DirectoryUtil;
import com.sismics.docs.core.util.EncryptionUtil;
import com.sismics.docs.core.util.FileUtil;
import com.sismics.docs.core.util.TransactionUtil;
import com.sismics.util.EnvironmentUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Startup reconciliation of lost first-time post-upload file processing (#159).
 *
 * <p>Derived file data — OCR text into {@code FIL_CONTENT_C}, the search index entry, the thumbnail/web
 * rasters, and auto-tags — is produced asynchronously AFTER the upload transaction commits. A hard JVM
 * stop (kill -9, OOM, docker stop past grace) in the commit->process window loses the in-memory event
 * with no automatic backfill, leaving the file silently unsearchable until a human re-uploads it. This
 * service, modelled on {@link ContentMacBackfillService}'s self-completing-scan shell, replays that
 * processing for any active file whose durable completion marker ({@code FIL_PROCESSED_D}) is unset.</p>
 *
 * <p><b>Scope:</b> FIRST-TIME post-upload processing (the {@code FileCreatedAsyncEvent} a fresh upload
 * would have fired). Re-processing obligations from attach / manual reprocess are out of scope (the file
 * stays searchable across those), a residual documented in #159.</p>
 *
 * <p><b>Safety.</b> Each file is claimed with a fenced compare-and-swap (a fresh token + a DB-clock lease):
 * exactly one worker wins, replay is idempotent (keyed index write + document-locked auto-tag dedup in the
 * processing pipeline), and completion is fenced to the claim token so a claimant whose lease expired and
 * was reclaimed cannot mark a successor's work complete. ALL lease timing comes from the database clock,
 * never a per-JVM one. A file that repeatedly fails is bounded by an attempt cap and then terminal-skipped
 * so it cannot keep the service alive forever.</p>
 *
 * <p><b>Drain.</b> The service self-stops ONLY when NO active file remains unprocessed — while any
 * unprocessed row exists (unclaimed, leased-in-flight, or expired) it stays scheduled, so a still-running
 * replay or a lease that will expire is never stranded without a reconciler to resolve it.</p>
 */
public class FileReconciliationService extends AbstractScheduledService {
    private static final Logger log = LoggerFactory.getLogger(FileReconciliationService.class);

    private static final int BATCH_SIZE = 30;

    /**
     * Lease TTL: conservatively above the longest realistic single-file OCR, so a live in-flight replay is
     * never falsely reclaimed. Configurable via {@code docs.reconciliation_lease_ttl_minutes} /
     * {@code DOCS_RECONCILIATION_LEASE_TTL_MINUTES} (default 30, minimum 1).
     */
    static final String LEASE_TTL_MINUTES_PROPERTY = "docs.reconciliation_lease_ttl_minutes";
    static final String LEASE_TTL_MINUTES_ENV = "DOCS_RECONCILIATION_LEASE_TTL_MINUTES";
    static final int DEFAULT_LEASE_TTL_MINUTES = 30;

    /**
     * Attempt cap after which a persistently-retryable-failing file is terminal-skipped (so it cannot keep
     * the service alive forever). Configurable via {@code docs.reconciliation_max_attempts} /
     * {@code DOCS_RECONCILIATION_MAX_ATTEMPTS} (default 10, minimum 1).
     */
    static final String MAX_ATTEMPTS_PROPERTY = "docs.reconciliation_max_attempts";
    static final String MAX_ATTEMPTS_ENV = "DOCS_RECONCILIATION_MAX_ATTEMPTS";
    static final int DEFAULT_MAX_ATTEMPTS = 10;

    @Override
    protected void startUp() {
        log.info("File reconciliation service starting up");
    }

    @Override
    protected void shutDown() {
        log.info("File reconciliation service shutting down");
    }

    @Override
    protected void runOneIteration() {
        try {
            List<File> batch = fetchReconcileBatch();
            IterationCounters counters = new IterationCounters();
            counters.selected = batch.size();
            for (File file : batch) {
                reconcileFile(file, counters);
            }

            // Drain gauge: while ANY active file is still unprocessed the service MUST stay scheduled. Self-
            // stopping on an empty work-set (unclaimed/expired only) would strand a file whose live claim is
            // still in flight — reboot, scan excludes the live lease, sees empty, stops, then the lease
            // expires with no service left to reclaim it. Stop only when nothing unprocessed remains.
            long remaining = countUnprocessed();
            log.info("File reconciliation iteration: selected={}, claimed={}, enqueued={}, terminalSkipped={}, "
                            + "expiredReclaimed={}, unprocessedRemaining={} (per-file COMPLETE/RETRYABLE "
                            + "outcomes are logged by the processing listener)",
                    counters.selected, counters.claimed, counters.enqueued, counters.terminalSkipped,
                    counters.expiredReclaimed, remaining);
            if (remaining == 0) {
                log.info("No file left to reconcile, stopping the reconciliation service");
                stopAsync();
            }
        } catch (Throwable e) {
            log.error("Exception during file reconciliation iteration", e);
        }
    }

    /**
     * Read one batch of reconcilable files in its own short read transaction, using the DB clock for the
     * lease-expiry cutoff. Package-private so a test can feed a controlled batch and isolate the stop
     * decision from the shared test database.
     *
     * @return up to {@link #BATCH_SIZE} active files whose processing is unproven and claimable
     */
    List<File> fetchReconcileBatch() {
        List<File> files = new ArrayList<>();
        TransactionUtil.handle(() -> {
            FileDao fileDao = new FileDao();
            Date cutoff = new Date(fileDao.getDatabaseTime().getTime() - leaseTtlMillis());
            files.addAll(fileDao.getFilesToReconcile(cutoff, BATCH_SIZE));
        });
        return files;
    }

    /**
     * The live count of unprocessed active files. Package-private so a test can drive the drain/self-stop
     * decision deterministically without depending on shared-database state.
     *
     * @return the number of active files still awaiting completion
     */
    long countUnprocessed() {
        AtomicReference<Long> count = new AtomicReference<>(0L);
        TransactionUtil.handle(() -> count.set(new FileDao().countUnprocessedActiveFiles()));
        return count.get();
    }

    /**
     * Reconcile one scanned file: claim it (fenced CAS), then — outside the claim transaction — decrypt its
     * stored blob and enqueue a reprocess event, or terminal-skip it when it can never be processed
     * (owner-less, missing blob, decrypt error) or has exhausted its attempt cap.
     *
     * @param scanned The scanned candidate (a snapshot from {@link #fetchReconcileBatch()})
     * @param counters Per-iteration observability counters to update
     */
    void reconcileFile(File scanned, IterationCounters counters) {
        String fileId = scanned.getId();
        boolean hadLease = scanned.getProcessingToken() != null;
        String token = UUID.randomUUID().toString();

        // Claim in its own short transaction so the (possibly long) decrypt/OCR below runs with the lease
        // already durable. The affected-row count is ownership.
        ClaimResult claim = new ClaimResult();
        TransactionUtil.handle(() -> {
            FileDao fileDao = new FileDao();
            Date dbNow = fileDao.getDatabaseTime();
            Date cutoff = new Date(dbNow.getTime() - leaseTtlMillis());
            if (fileDao.claimForReprocess(fileId, token, dbNow, cutoff) != 1) {
                return;
            }
            claim.won = true;
            // Re-read AFTER the bulk claim so the attempt count reflects this claim's increment.
            File claimed = fileDao.getActiveById(fileId);
            if (claimed == null) {
                return;
            }
            claim.userId = claimed.getUserId();
            claim.documentId = claimed.getDocumentId();
            int attempts = claimed.getProcAttempts() == null ? 0 : claimed.getProcAttempts();
            if (attempts > maxAttempts()) {
                // Bounded retry exhausted: terminal-skip (fenced to this token) so it stops being selected.
                fileDao.markProcessedIfClaimant(fileId, token, dbNow);
                claim.terminal = true;
            }
        });

        if (!claim.won) {
            return;
        }
        counters.claimed++;
        if (hadLease) {
            counters.expiredReclaimed++;
        }
        if (claim.terminal) {
            counters.terminalSkipped++;
            log.warn("File {} exceeded the reprocess attempt cap ({}); marking it terminally skipped",
                    fileId, maxAttempts());
            return;
        }

        // Resolve the owner key + blob and decrypt. Any failure here is TERMINAL: the content can never be
        // recovered, so terminal-skip it rather than re-select it forever.
        Path unencryptedFile;
        String language;
        try {
            User user = fetchUser(claim.userId);
            Path storedFile = DirectoryUtil.getStorageDirectory().resolve(fileId);
            if (user == null || !Files.exists(storedFile)) {
                terminalSkip(fileId, token);
                counters.terminalSkipped++;
                log.warn("File {} cannot be reprocessed (owner or blob missing); marking it terminally skipped",
                        fileId);
                return;
            }
            unencryptedFile = EncryptionUtil.decryptFile(storedFile, user.getPrivateKey());
            language = fetchLanguage(claim.documentId);
        } catch (Exception e) {
            terminalSkip(fileId, token);
            counters.terminalSkipped++;
            log.warn("File {} could not be decrypted for reprocessing; marking it terminally skipped", fileId, e);
            return;
        }

        // Enqueue the reprocess replay. A post failure is NOT terminal (the lease expires and a later cycle
        // retries), so it must not terminal-skip; release the in-memory marker + temp we took ownership of.
        FileUtil.startProcessingFile(fileId);
        FileCreatedAsyncEvent event = new FileCreatedAsyncEvent();
        event.setUserId(claim.userId);
        event.setLanguage(language);
        event.setFileId(fileId);
        event.setUnencryptedFile(unencryptedFile);
        event.setReprocess(true);
        event.setProcessingToken(token);
        try {
            postReprocessEvent(event);
            counters.enqueued++;
        } catch (Exception e) {
            log.error("Failed to enqueue the reprocess event for file {}; the lease will expire and retry", fileId, e);
            FileUtil.endProcessingFile(fileId);
            FileUtil.deleteTempGuarded(fileId, unencryptedFile);
        }
    }

    /**
     * Post the reprocess event onto the async bus. Package-private so a test can intercept it. The event
     * carries {@code reprocess=true}, so the processing listener takes the keyed index write and records
     * completion fenced to the claim token, while the webhook listener skips the duplicate notification.
     *
     * @param event The reprocess event
     */
    void postReprocessEvent(FileEvent event) {
        AppContext.getInstance().getAsyncEventBus().post(event);
    }

    /**
     * Terminal-skip a claimed file: stamp its completion marker (fenced to the claim token) so the row
     * stops being re-selected. In its own transaction because it runs outside the claim transaction.
     *
     * @param fileId File ID
     * @param token The claim's fencing token
     */
    private void terminalSkip(String fileId, String token) {
        TransactionUtil.handle(() -> {
            FileDao fileDao = new FileDao();
            fileDao.markProcessedIfClaimant(fileId, token, fileDao.getDatabaseTime());
        });
    }

    /**
     * Load a user in its own transaction (for its private key).
     *
     * @param userId User ID
     * @return the user, or null if it no longer exists
     */
    User fetchUser(String userId) {
        AtomicReference<User> user = new AtomicReference<>();
        TransactionUtil.handle(() -> user.set(new UserDao().getById(userId)));
        return user.get();
    }

    /**
     * Recover a document's language (for content extraction) in its own transaction; null for an orphan
     * file (no document) or a document that no longer exists.
     *
     * @param documentId Document ID (may be null for an orphan file)
     * @return the document language, or null
     */
    String fetchLanguage(String documentId) {
        if (documentId == null) {
            return null;
        }
        AtomicReference<String> language = new AtomicReference<>();
        TransactionUtil.handle(() -> {
            Document document = new DocumentDao().getById(documentId);
            if (document != null) {
                language.set(document.getLanguage());
            }
        });
        return language.get();
    }

    /**
     * Lease TTL in milliseconds, resolved at call time from configuration.
     *
     * @return the lease TTL in milliseconds
     */
    long leaseTtlMillis() {
        return TimeUnit.MINUTES.toMillis(EnvironmentUtil.getIntConfig(
                LEASE_TTL_MINUTES_PROPERTY, LEASE_TTL_MINUTES_ENV, DEFAULT_LEASE_TTL_MINUTES, 1));
    }

    /**
     * Attempt cap, resolved at call time from configuration.
     *
     * @return the maximum number of reprocess attempts before a terminal skip
     */
    int maxAttempts() {
        return EnvironmentUtil.getIntConfig(
                MAX_ATTEMPTS_PROPERTY, MAX_ATTEMPTS_ENV, DEFAULT_MAX_ATTEMPTS, 1);
    }

    @Override
    protected Scheduler scheduler() {
        return Scheduler.newFixedDelaySchedule(0, 1, TimeUnit.MINUTES);
    }

    /**
     * Outcome of a claim attempt captured inside the claim transaction for use after it commits.
     */
    private static final class ClaimResult {
        private boolean won;
        private boolean terminal;
        private String userId;
        private String documentId;
    }

    /**
     * Per-iteration observability counters. The service knows what it selected / claimed / enqueued /
     * terminal-skipped / expired-reclaimed; the later COMPLETE vs RETRYABLE outcome happens on the bus
     * thread and is logged there (#159 observability).
     */
    static final class IterationCounters {
        int selected;
        int claimed;
        int enqueued;
        int terminalSkipped;
        int expiredReclaimed;
    }
}
