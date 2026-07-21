package com.sismics.docs.core.service;

import com.google.common.util.concurrent.Service;
import com.sismics.BaseTest;
import com.sismics.docs.core.dao.FileDao;
import com.sismics.docs.core.dao.UserDao;
import com.sismics.docs.core.event.FileCreatedAsyncEvent;
import com.sismics.docs.core.event.FileEvent;
import com.sismics.docs.core.model.context.AppContext;
import com.sismics.docs.core.model.jpa.File;
import com.sismics.docs.core.model.jpa.User;
import com.sismics.docs.core.util.DirectoryUtil;
import com.sismics.docs.core.util.FileUtil;
import com.sismics.util.context.ThreadLocalContext;
import com.sismics.util.jpa.EMF;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityTransaction;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * The reconciliation service (#159) self-completing / claim behaviour: it self-stops only when NO
 * unprocessed active file remains (the live-lease-stranding fix — an empty work-set with work still in
 * flight must NOT stop it), it terminal-skips a file that has exhausted its attempt cap, and a claimed
 * file whose replay does not complete is left unmarked and re-selected on a later (expired-lease) cycle.
 */
public class TestFileReconciliationService extends BaseTest {

    private interface TxWork<T> {
        T run() throws Exception;
    }

    private static <T> T inTx(TxWork<T> work) throws Exception {
        EntityManager em = EMF.get().createEntityManager();
        ThreadLocalContext.get().setEntityManager(em);
        EntityTransaction tx = em.getTransaction();
        tx.begin();
        try {
            T result = work.run();
            tx.commit();
            return result;
        } catch (Exception e) {
            if (tx.isActive()) {
                tx.rollback();
            }
            throw e;
        } finally {
            if (em.isOpen()) {
                em.close();
            }
            ThreadLocalContext.cleanup();
        }
    }

    private String createUserId() throws Exception {
        String username = "recsvc_" + UUID.randomUUID().toString().substring(0, 10);
        return inTx(() -> {
            User user = new User();
            user.setUsername(username);
            user.setPassword("12345678");
            user.setEmail(username + "@docs.com");
            user.setRoleId("admin");
            user.setStorageQuota(100_000_000L);
            return new UserDao().create(user, username);
        });
    }

    private String createFileRow(String userId) throws Exception {
        return inTx(() -> {
            File file = new File();
            file.setUserId(userId);
            file.setName("recon.txt");
            file.setMimeType("text/plain");
            file.setVersion(0);
            file.setLatestVersion(true);
            file.setSize(10L);
            return new FileDao().create(file, userId);
        });
    }

    private File readFile(String fileId) throws Exception {
        return inTx(() -> new FileDao().getActiveById(fileId));
    }

    // --- self-stop ONLY when nothing unprocessed remains ----------------------------------------------

    @Test
    public void selfStopsWhenNoUnprocessedFileRemains() {
        FileReconciliationService service = new FileReconciliationService() {
            @Override
            List<File> fetchReconcileBatch() {
                return List.of();
            }

            @Override
            long countUnprocessed() {
                return 0;
            }
        };
        service.runOneIteration();
        Assertions.assertEquals(Service.State.TERMINATED, service.state(),
                "the service self-stops once no unprocessed active file remains");
    }

    /**
     * The live-lease-stranding fix (ADR BLOCKING-1): an EMPTY claimable work-set does NOT mean the service
     * may stop — an in-flight replay keeps its row unprocessed, so while any unprocessed row remains the
     * service must stay scheduled. Mutation proof: a self-stop keyed on the (empty) work-set instead of the
     * unprocessed count would TERMINATE here and strand the in-flight file.
     */
    @Test
    public void staysScheduledWhileUnprocessedRemainsEvenWithEmptyWorkSet() {
        FileReconciliationService service = new FileReconciliationService() {
            @Override
            List<File> fetchReconcileBatch() {
                return List.of();
            }

            @Override
            long countUnprocessed() {
                return 1; // an in-flight (live-leased) file, not claimable this cycle
            }
        };
        service.runOneIteration();
        Assertions.assertNotEquals(Service.State.TERMINATED, service.state(),
                "an empty work-set with an unprocessed file still in flight must NOT stop the service");
    }

    // --- bounded retry: a file over the attempt cap is terminal-skipped, not re-enqueued ---------------

    @Test
    public void terminalSkipsAfterExceedingAttemptCap() throws Exception {
        String userId = createUserId();
        String fileId = createFileRow(userId);
        // Pre-set the attempt counter AT the cap; the claim's own increment pushes it OVER, so this claim
        // terminal-skips instead of enqueuing.
        int cap = new FileReconciliationService().maxAttempts();
        inTx(() -> ThreadLocalContext.get().getEntityManager()
                .createNativeQuery("update T_FILE set FIL_PROCATTEMPTS_N = :n where FIL_ID_C = :id")
                .setParameter("n", cap)
                .setParameter("id", fileId)
                .executeUpdate());

        int[] posts = {0};
        FileReconciliationService service = new FileReconciliationService() {
            @Override
            boolean postReprocessEvent(FileEvent event) {
                posts[0]++;
                return true;
            }
        };
        FileReconciliationService.IterationCounters counters = new FileReconciliationService.IterationCounters();
        service.reconcileFile(readFile(fileId), counters);

        Assertions.assertEquals(0, posts[0], "a file over the attempt cap is NOT re-enqueued");
        Assertions.assertEquals(1, counters.terminalSkipped, "it is counted as a terminal skip");
        Assertions.assertNotNull(readFile(fileId).getProcessed(),
                "it is terminal-skip-stamped so the scan stops re-selecting it");
    }

    // --- blocker 1: a transient lookup failure is RETRYABLE, never terminal (no permanent loss) --------

    /**
     * A transient owner-lookup failure (a DB hiccup) must NOT terminal-stamp the row — that would be
     * permanent loss for a recoverable file. Only owner-gone / blob-missing / decrypt-failure are terminal.
     * Mutation proof: terminal-stamping on any lookup exception makes {@code terminalSkipped} 1 and stamps
     * the row, so this test fails.
     */
    @Test
    public void transientOwnerLookupFailureIsRetryableNotTerminal() throws Exception {
        String userId = createUserId();
        String fileId = createFileRow(userId);

        FileReconciliationService service = new FileReconciliationService() {
            @Override
            User fetchUser(String uid) {
                throw new RuntimeException("simulated transient owner-lookup failure");
            }

            @Override
            boolean postReprocessEvent(FileEvent event) {
                throw new AssertionError("a transiently-failed reconcile must not enqueue");
            }
        };
        FileReconciliationService.IterationCounters counters = new FileReconciliationService.IterationCounters();
        service.reconcileFile(readFile(fileId), counters);

        Assertions.assertEquals(0, counters.terminalSkipped,
                "a transient owner-lookup failure must NOT terminal-skip the row");
        Assertions.assertEquals(0, counters.enqueued, "and it must not enqueue");
        Assertions.assertNull(readFile(fileId).getProcessed(),
                "the row is left unprocessed (retryable) so a later cycle recovers it");
    }

    // --- blocker 2: indeterminate blob presence is RETRYABLE, not a terminal missing blob -------------

    /**
     * A transient filesystem failure makes {@code Files.exists} return false without proving the blob is
     * gone. That INDETERMINATE case must be retryable, not terminal. Mutation proof: treating indeterminate
     * as a missing blob terminal-stamps the row and this test fails.
     */
    @Test
    public void indeterminateBlobPresenceIsRetryableNotTerminal() throws Exception {
        String userId = createUserId();
        String fileId = createFileRow(userId);

        FileReconciliationService service = new FileReconciliationService() {
            @Override
            BlobPresence blobPresence(Path storedFile) {
                return BlobPresence.INDETERMINATE;
            }

            @Override
            boolean postReprocessEvent(FileEvent event) {
                throw new AssertionError("an indeterminate blob must not enqueue");
            }
        };
        FileReconciliationService.IterationCounters counters = new FileReconciliationService.IterationCounters();
        service.reconcileFile(readFile(fileId), counters);

        Assertions.assertEquals(0, counters.terminalSkipped,
                "an indeterminate blob presence must NOT terminal-skip the row");
        Assertions.assertNull(readFile(fileId).getProcessed(),
                "the row is left unprocessed (retryable) so a later cycle recovers it");
    }

    // --- blocker 4: an Error after decrypt must not strand the decrypted plaintext temp ---------------

    /**
     * The per-step catches are Exception-scoped; an Error during the language lookup would otherwise unwind
     * to the iteration's Throwable catch and strand the decrypted plaintext. A try/finally deletes the temp
     * on every exit unless it was handed off to a successful enqueue. Mutation proof: reverting to the
     * Exception-scoped cleanup leaves the temp on disk and this test fails.
     */
    @Test
    public void errorAfterDecryptLeavesNoPlaintextTemp() throws Exception {
        String userId = createUserId();
        String fileId = createFileRow(userId);
        Files.write(DirectoryUtil.getStorageDirectory().resolve(fileId), new byte[]{1, 2, 3, 4});
        Path plaintext = Files.createTempFile("recon-error-", ".tmp");
        Files.write(plaintext, "secret".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        try {
            FileReconciliationService service = new FileReconciliationService() {
                @Override
                Path decrypt(Path storedFile, String privateKey) {
                    return plaintext;
                }

                @Override
                String fetchLanguage(String documentId) {
                    throw new AssertionError("simulated Error (not an Exception) after decrypt");
                }
            };
            FileReconciliationService.IterationCounters counters = new FileReconciliationService.IterationCounters();

            Assertions.assertThrows(Error.class, () -> service.reconcileFile(readFile(fileId), counters),
                    "the Error propagates out of reconcileFile");
            Assertions.assertFalse(Files.exists(plaintext),
                    "the decrypted plaintext temp must be deleted even when an Error unwinds through the method");
        } finally {
            Files.deleteIfExists(plaintext);
            Files.deleteIfExists(DirectoryUtil.getStorageDirectory().resolve(fileId));
            FileUtil.endProcessingFile(fileId);
        }
    }

    // --- blocker 5: a post after teardown drops via peekInstance, never resurrecting a context ---------

    /**
     * The pause-across-teardown timing: an iteration observed {@code stopping==false}, paused, and resumed
     * after shutdown cleared the singleton. The post must resolve the context via the NON-instantiating
     * accessor, see null, and drop — never call the constructing getInstance() and rebuild a context.
     * Mutation proof: using getInstance() rebuilds a context (peekInstance becomes non-null) and returns
     * posted=true, so both the drop and the no-resurrection assertions fail.
     */
    @Test
    public void pausedPostAfterTeardownDropsAndDoesNotResurrectContext() {
        AppContext existing = AppContext.peekInstance();
        if (existing != null) {
            existing.shutDown();
        }
        Assertions.assertNull(AppContext.peekInstance(), "precondition: the singleton is torn down");

        FileReconciliationService service = new FileReconciliationService();
        FileCreatedAsyncEvent event = new FileCreatedAsyncEvent();
        event.setFileId("post-after-teardown");
        boolean posted = service.postReprocessEvent(event);

        Assertions.assertFalse(posted, "a post after teardown is dropped, not sent");
        Assertions.assertEquals(1, service.getPostsDroppedOnShutdown(), "the drop is counted");
        Assertions.assertNull(AppContext.peekInstance(),
                "the reconciler must NOT resurrect a context during shutdown");
    }

    // --- shutdown: a pending enqueue is dropped (counted) and never resurrects a torn-down context -----

    /**
     * Once shutdown is requested, {@link FileReconciliationService#postReprocessEvent} must DROP the event
     * (counting it) instead of touching {@link com.sismics.docs.core.model.context.AppContext#getInstance()}
     * — so a slow iteration resuming during shutdown can neither post into a closing executor nor
     * resurrect a cleared singleton. Mutation proof: removing the stop guard makes it call getInstance()
     * (booting a context) and return true, so the drop assertion fails.
     */
    @Test
    public void shutdownDropsPendingEnqueueWithoutResurrectingContext() {
        FileReconciliationService service = new FileReconciliationService();
        service.requestStop();

        FileCreatedAsyncEvent event = new FileCreatedAsyncEvent();
        event.setFileId("shutdown-drop-file");
        boolean posted = service.postReprocessEvent(event);

        Assertions.assertFalse(posted, "a pending enqueue is dropped once shutdown is requested");
        Assertions.assertEquals(1, service.getPostsDroppedOnShutdown(), "the shutdown drop is counted");
        Assertions.assertNotEquals(Service.State.RUNNING, service.state(),
                "the service is not running after a stop request");
    }

    // --- a claimed replay that does not complete leaves the file unmarked and is retried ---------------

    /**
     * When a claimed replay runs but ends RETRYABLE (e.g. an index write failure), the pipeline does NOT
     * write the completion marker. This test simulates that by intercepting the enqueue with a replay that
     * releases the in-memory marker/temp (as the real processing finally does) but records no completion.
     * The file must stay unprocessed and, once its lease is treated as expired, be re-selected by the scan.
     */
    @Test
    public void claimedButUncompletedReplayIsNotMarkedAndIsRetried() throws Exception {
        String userId = createUserId();
        String fileId = createFileRow(userId);
        // A blob must exist for the reconciler to proceed to enqueue (else it terminal-skips a missing blob).
        Files.write(DirectoryUtil.getStorageDirectory().resolve(fileId), new byte[]{1, 2, 3, 4});

        try {
            FileReconciliationService service = new FileReconciliationService() {
                @Override
                boolean postReprocessEvent(FileEvent event) {
                    // Simulate a replay that ran but ended RETRYABLE (no completion recorded), releasing the
                    // in-memory marker + temp exactly as the real processEvent finally would.
                    FileUtil.endProcessingFile(event.getFileId());
                    FileUtil.deleteTempGuarded(event.getFileId(), event.getUnencryptedFile());
                    return true;
                }
            };
            FileReconciliationService.IterationCounters counters = new FileReconciliationService.IterationCounters();
            service.reconcileFile(readFile(fileId), counters);

            Assertions.assertEquals(1, counters.enqueued, "the file was claimed and enqueued");
            File afterClaim = readFile(fileId);
            Assertions.assertNull(afterClaim.getProcessed(),
                    "a replay that did not complete must NOT write the completion marker");
            Assertions.assertNotNull(afterClaim.getProcessingToken(), "the claim lease is held");

            // Retried: with the lease treated as expired (a cutoff after the stored lease time) the scan
            // re-selects the still-unprocessed file.
            boolean reselected = inTx(() -> {
                FileDao fileDao = new FileDao();
                Date futureCutoff = new Date(fileDao.getDatabaseTime().getTime() + 3_600_000);
                return fileDao.getFilesToReconcile(futureCutoff, 1000).stream()
                        .anyMatch(f -> fileId.equals(f.getId()));
            });
            Assertions.assertTrue(reselected, "the unmarked file is re-selected once its lease expires");
        } finally {
            Files.deleteIfExists(DirectoryUtil.getStorageDirectory().resolve(fileId));
            FileUtil.endProcessingFile(fileId);
        }
    }
}
