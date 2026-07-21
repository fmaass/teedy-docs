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
import java.util.concurrent.atomic.AtomicInteger;
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

    /**
     * Set once shutdown has been requested. The running iteration checks it between steps and drops any
     * pending enqueue, so a slow decrypt cannot resume and post into a closing executor or resurrect a
     * torn-down {@link AppContext}. Volatile so the shutdown thread's write is seen by the iteration thread.
     */
    private volatile boolean stopping;

    /**
     * The thread currently running an iteration, captured so {@link #requestStop()} can interrupt a blocked
     * decrypt/IO. Null when no iteration is running.
     */
    private volatile Thread iterationThread;

    /**
     * Count of reprocess enqueues dropped because shutdown was in progress (their leases simply expire and a
     * later boot retries). Surfaced so a shutdown that raced live work is observable rather than silent.
     */
    private final AtomicInteger postsDroppedOnShutdown = new AtomicInteger();

    @Override
    protected void startUp() {
        log.info("File reconciliation service starting up");
    }

    /**
     * Request an orderly stop: flag it so the running iteration cooperatively stops between steps and drops
     * any pending enqueue, interrupt a thread blocked in a decrypt/IO, then stop scheduling further
     * iterations. Called by {@link AppContext#shutDown()} BEFORE the async executors and index are torn down.
     */
    public void requestStop() {
        stopping = true;
        Thread thread = iterationThread;
        if (thread != null) {
            thread.interrupt();
        }
        stopAsync();
    }

    /**
     * @return the number of reprocess enqueues dropped because shutdown was in progress
     */
    int getPostsDroppedOnShutdown() {
        return postsDroppedOnShutdown.get();
    }

    @Override
    protected void shutDown() {
        log.info("File reconciliation service shutting down");
    }

    @Override
    protected void runOneIteration() {
        iterationThread = Thread.currentThread();
        try {
            if (stopping) {
                return;
            }
            List<File> batch = fetchReconcileBatch();
            IterationCounters counters = new IterationCounters();
            counters.selected = batch.size();
            for (File file : batch) {
                if (stopping) {
                    // Stop promptly on shutdown rather than draining the whole batch; the unclaimed rows
                    // stay reconcilable and any lease we already took expires and is retried next boot.
                    break;
                }
                reconcileFile(file, counters);
            }
            if (stopping) {
                return;
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
        } finally {
            iterationThread = null;
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

        // Stop-aware: do not begin the (possibly long) decrypt during shutdown. The claim lease expires and
        // a later boot retries.
        if (stopping) {
            return;
        }

        // Resolve the owner + blob. ONLY three causes are TERMINAL — the content can never be recovered:
        // the owner is gone, the blob is missing, or decryption fails. A transient LOOKUP/INFRASTRUCTURE
        // failure (a DB hiccup in the owner or language read) must NOT terminal-stamp the row — that would be
        // permanent data loss for a recoverable file — so it is left RETRYABLE (return; the lease expires and
        // a later cycle retries, its attempt counter already bumped by the claim).
        User user;
        try {
            user = fetchUser(claim.userId);
        } catch (Exception e) {
            log.warn("File {} owner lookup failed transiently; leaving it to a later cycle", fileId, e);
            return;
        }
        if (user == null) {
            terminalSkip(fileId, token);
            counters.terminalSkipped++;
            log.warn("File {} has no owner; marking it terminally skipped", fileId);
            return;
        }
        Path storedFile = DirectoryUtil.getStorageDirectory().resolve(fileId);
        // Distinguish DETERMINATE absence from an INDETERMINATE result: a transient mount/permission failure
        // also makes exists() return false. Only a determinate "not there" is terminal; an indeterminate
        // result is retryable, so a transient filesystem glitch cannot permanently exclude a recoverable file.
        BlobPresence presence = blobPresence(storedFile);
        if (presence == BlobPresence.ABSENT) {
            terminalSkip(fileId, token);
            counters.terminalSkipped++;
            log.warn("File {} blob is determinately missing; marking it terminally skipped", fileId);
            return;
        }
        if (presence == BlobPresence.INDETERMINATE) {
            log.warn("File {} blob presence is indeterminate (transient filesystem failure?); leaving it to a "
                    + "later cycle", fileId);
            return;
        }
        Path unencryptedFile;
        try {
            unencryptedFile = decrypt(storedFile, user.getPrivateKey());
        } catch (Exception e) {
            terminalSkip(fileId, token);
            counters.terminalSkipped++;
            log.warn("File {} could not be decrypted; marking it terminally skipped", fileId, e);
            return;
        }

        // From here a decrypted plaintext temp exists and is OWNED by this method until it is handed off to a
        // SUCCESSFUL enqueue (after which the async listener's processEvent finally owns it). Delete it — and
        // release the in-memory processing marker — in a finally on EVERY other exit, INCLUDING an Error: the
        // per-step catches below are Exception-scoped, so without this finally an Error in the language lookup
        // or enqueue would unwind to the iteration's Throwable catch and strand the decrypted plaintext.
        boolean marked = false;
        boolean handedOff = false;
        try {
            String language;
            try {
                language = fetchLanguage(claim.documentId);
            } catch (Exception e) {
                log.warn("File {} language lookup failed transiently; leaving it to a later cycle", fileId, e);
                return;
            }

            FileUtil.startProcessingFile(fileId);
            marked = true;
            FileCreatedAsyncEvent event = new FileCreatedAsyncEvent();
            event.setUserId(claim.userId);
            event.setLanguage(language);
            event.setFileId(fileId);
            event.setUnencryptedFile(unencryptedFile);
            event.setReprocess(true);
            event.setProcessingToken(token);
            boolean posted;
            try {
                posted = postReprocessEvent(event);
            } catch (Exception e) {
                log.error("Failed to enqueue the reprocess event for file {}; the lease will expire and retry", fileId, e);
                return;
            }
            if (posted) {
                // Handed off: the async listener's processEvent finally now owns the marker + temp.
                handedOff = true;
                counters.enqueued++;
            }
            // posted == false is a shutdown-drop: not handed off, so the finally releases the marker + temp.
        } finally {
            if (!handedOff) {
                if (marked) {
                    FileUtil.endProcessingFile(fileId);
                }
                FileUtil.deleteTempGuarded(fileId, unencryptedFile);
            }
        }
    }

    /**
     * Post the reprocess event onto the async bus, or drop it if shutdown has begun. Two guards, in order:
     * the {@link #stopping} flag is a fast-path drop, and — closing the check/use race where an iteration
     * observes {@code stopping==false}, pauses, and resumes after teardown cleared the singleton — the post
     * resolves the context through the NON-instantiating {@link AppContext#peekInstance()} and drops when it
     * is null. It therefore never calls the constructing {@code getInstance()}, so it can neither post into a
     * closing executor (which CallerRunsPolicy would silently discard while we counted it enqueued) nor
     * resurrect a fresh context. Package-private so a test can intercept it. The event carries {@code
     * reprocess=true}, so the processing listener takes the keyed index write and records completion fenced to
     * the claim token, while the webhook listener skips the duplicate notification.
     *
     * @param event The reprocess event
     * @return true if the event was posted, false if it was dropped because shutdown was in progress
     */
    boolean postReprocessEvent(FileEvent event) {
        if (stopping) {
            return dropOnShutdown(event);
        }
        AppContext context = AppContext.peekInstance();
        if (context == null) {
            // The singleton was torn down after our stopping check; drop rather than reconstruct a context.
            return dropOnShutdown(event);
        }
        context.getAsyncEventBus().post(event);
        return true;
    }

    /**
     * Count and log a reprocess enqueue dropped because shutdown was in progress.
     *
     * @param event The dropped event
     * @return false, always (the caller propagates "not posted")
     */
    private boolean dropOnShutdown(FileEvent event) {
        postsDroppedOnShutdown.incrementAndGet();
        log.warn("Shutdown in progress; dropping the reprocess enqueue for file {} (lease will expire and retry)",
                event.getFileId());
        return false;
    }

    /**
     * The three-valued presence of a stored blob. {@link #INDETERMINATE} is the case a plain
     * {@code Files.exists} collapses into "false": the filesystem could not decide (a transient mount or
     * permission failure), which must be treated as retryable, not as a terminal missing blob.
     */
    enum BlobPresence {
        PRESENT,
        ABSENT,
        INDETERMINATE
    }

    /**
     * Resolve the tri-state presence of a stored blob. Package-private so a test can inject the
     * INDETERMINATE case (which is otherwise hard to provoke deterministically).
     *
     * @param storedFile Stored blob path
     * @return PRESENT, ABSENT (determinate), or INDETERMINATE (undecidable — transient failure)
     */
    BlobPresence blobPresence(Path storedFile) {
        if (Files.exists(storedFile)) {
            return BlobPresence.PRESENT;
        }
        if (Files.notExists(storedFile)) {
            return BlobPresence.ABSENT;
        }
        return BlobPresence.INDETERMINATE;
    }

    /**
     * Decrypt the stored blob to a plaintext temp. Package-private so a test can control the temp path and
     * drive the ownership/cleanup paths (including an Error after decrypt).
     *
     * @param storedFile Stored (encrypted) blob path
     * @param privateKey Owner private key
     * @return the decrypted plaintext temp
     * @throws Exception on a decryption failure
     */
    Path decrypt(Path storedFile, String privateKey) throws Exception {
        return EncryptionUtil.decryptFile(storedFile, privateKey);
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
