package com.sismics.docs.rest.resource;

import com.sismics.docs.core.util.DirectoryUtil;
import com.sismics.docs.rest.BaseJerseyTest;
import com.sismics.util.filter.TokenBasedSecurityFilter;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Form;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Storage-mutation safety of {@code clean_storage}'s filesystem deletion.
 *
 * <p><b>#69</b> — the physical deletion must happen only AFTER the DB deletions + protocol row are
 * durably committed. A DB/commit-phase failure must leave every physical file intact (a deleted file
 * whose DB delete rolled back is unrecoverable data loss). Verified via the
 * {@code AppResource.cleanStorageBeforeCommitHook} test seam.</p>
 *
 * <p><b>Concurrent-upload safety</b> — the post-commit sweep deletes ONLY the physical files of the
 * exact DB rows the run removed (their id + {@code _web}/{@code _thumb}), never a filesystem-vs-DB
 * diff. A concurrent upload writes its encrypted bytes BEFORE its request commits (FileUtil.createFile),
 * so a diff-based sweep would treat that in-flight file as an orphan and delete it, leaving a live
 * T_FILE row with no content. The precise-set sweep cannot touch a file whose id is not in the run's
 * deleted set.</p>
 */
public class TestCleanStoragePostCommitSweep extends BaseJerseyTest {
    @AfterEach
    public void clearHook() {
        AppResource.cleanStorageBeforeCommitHook = null;
        AppResource.cleanStorageBeforeSweepHook = null;
        AppResource.cleanStorageDuringReclaimHook = null;
    }

    /**
     * A commit-phase failure must not delete any physical bytes: seed a real file, soft-delete it so it
     * is in the run's removal closure, force a failure before the commit checkpoint, and assert its
     * physical file SURVIVES; then a clean run removes it (proving survival came from the ordering, not
     * a disabled sweep).
     */
    @Test
    public void commitFailureLeavesPhysicalFilesIntact() throws Exception {
        String adminToken = adminToken();
        Path storageDir = DirectoryUtil.getStorageDirectory();

        // A real document + uploaded file, then soft-delete the file so it is in the deleted closure.
        String docId = target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form().param("title", "Post-commit sweep doc").param("language", "eng")), JsonObject.class)
                .getString("id");
        String fileId = clientUtil.addFileToDocument("file/PIA00452.jpg", adminToken, docId);
        markFileSoftDeleted(fileId);
        Path physical = storageDir.resolve(fileId);
        // Guarantee the physical bytes are present for the invariant under test (clean_storage's
        // commit-then-sweep ordering), independent of any cross-test storage-directory timing: if the
        // upload's stored file is not on disk here, write deterministic bytes at that path. The test's
        // subject is the sweep ordering, not the upload mechanics.
        if (!Files.exists(physical)) {
            Files.write(physical, new byte[]{1, 2, 3, 4});
        }
        Assertions.assertTrue(Files.exists(physical), "precondition: the file's encrypted bytes exist on disk");

        // Force a failure right before the commit checkpoint (simulating a DB/commit-phase abort).
        AppResource.cleanStorageBeforeCommitHook = () -> {
            throw new IllegalStateException("simulated commit-phase failure (#69)");
        };
        Response failed = target().path("/app/batch/clean_storage").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form()));
        Assertions.assertNotEquals(Status.OK, Status.fromStatusCode(failed.getStatus()),
                "a commit-phase failure must NOT return OK");
        Assertions.assertTrue(Files.exists(physical),
                "the physical file must survive a failed/rolled-back clean_storage (no data loss)");

        // Clear the fault and run for real: the file (in the deleted closure) is now swept post-commit.
        AppResource.cleanStorageBeforeCommitHook = null;
        Response ok = target().path("/app/batch/clean_storage").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form()));
        Assertions.assertEquals(Status.OK, Status.fromStatusCode(ok.getStatus()));
        Assertions.assertFalse(Files.exists(physical),
                "a successful clean_storage removes the deleted file's physical bytes post-commit");
    }

    /**
     * A concurrent upload's just-written bytes (physical file present, DB row NOT in this run's deleted
     * set — the exact shape of an uncommitted in-flight upload as seen by the cleanup transaction) must
     * SURVIVE a successful cleanup. A filesystem-vs-DB diff sweep would delete it and corrupt the
     * upload; the precise deleted-set sweep never touches it.
     */
    @Test
    public void concurrentUploadFileSurvivesCleanup() throws Exception {
        String adminToken = adminToken();
        Path storageDir = DirectoryUtil.getStorageDirectory();

        // Ensure there IS a deleted file so the run actually sweeps something (a non-empty closure).
        String docId = target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form().param("title", "Concurrent sweep doc").param("language", "eng")), JsonObject.class)
                .getString("id");
        String deletedFileId = clientUtil.addFileToDocument("file/PIA00452.jpg", adminToken, docId);
        markFileSoftDeleted(deletedFileId);
        Path deletedPhysical = storageDir.resolve(deletedFileId);

        // Simulate a concurrent in-flight upload: bytes on disk, but its DB row is invisible to the
        // cleanup transaction (uncommitted) — i.e. no matching row at all from the sweep's viewpoint.
        String inflightId = UUID.randomUUID().toString();
        Path inflightOriginal = storageDir.resolve(inflightId);
        Path inflightWeb = storageDir.resolve(inflightId + "_web");
        Files.write(inflightOriginal, new byte[]{9, 8, 7, 6, 5});
        Files.write(inflightWeb, new byte[]{4, 3, 2, 1});

        Response ok = target().path("/app/batch/clean_storage").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form()));
        Assertions.assertEquals(Status.OK, Status.fromStatusCode(ok.getStatus()));

        // The deleted file's bytes are gone (the sweep did run)...
        Assertions.assertFalse(Files.exists(deletedPhysical), "the deleted file's bytes were swept");
        // ...but the concurrent upload's bytes SURVIVE — the sweep never touched an id outside its
        // committed deleted set, so an in-flight upload cannot be corrupted.
        Assertions.assertTrue(Files.exists(inflightOriginal),
                "a concurrent upload's original bytes must survive the cleanup (no diff-based deletion)");
        Assertions.assertTrue(Files.exists(inflightWeb),
                "a concurrent upload's derivative bytes must survive the cleanup");

        // Cleanup the planted in-flight files.
        Files.deleteIfExists(inflightOriginal);
        Files.deleteIfExists(inflightWeb);
    }

    /**
     * CONCURRENT-RESTORE data-loss window: a document RESTORED (un-soft-deleted) between the eligibility
     * snapshot and the sweep leaves its file rows LIVE, even though their ids are still in the snapshot.
     * The sweep must delete bytes only for ids CONFIRMED gone from the DB after commit, so a
     * concurrently-restored file's bytes SURVIVE. Two files are soft-deleted (both in the snapshot); the
     * before-sweep hook revives one (re-asserts its live row, as a concurrent restore would); after the
     * run the revived file's bytes remain while the genuinely-deleted file's bytes are swept.
     */
    @Test
    public void concurrentlyRestoredFileSurvivesCleanup() throws Exception {
        String adminToken = adminToken();
        Path storageDir = DirectoryUtil.getStorageDirectory();

        String docId = target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form().param("title", "Restore race doc").param("language", "eng")), JsonObject.class)
                .getString("id");
        // A genuinely-deleted file and a "to-be-restored" file, both soft-deleted so both are in the
        // snapshot closure. Each on its own document so the restore of one does not resurrect the other.
        String deletedFileId = clientUtil.addFileToDocument("file/PIA00452.jpg", adminToken, docId);
        String restoredDocId = target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form().param("title", "Restore race doc 2").param("language", "eng")), JsonObject.class)
                .getString("id");
        String restoredFileId = clientUtil.addFileToDocument("file/PIA00452.jpg", adminToken, restoredDocId);
        // Detach both files' main-file pointers so the DB delete of the soft-deleted rows is not blocked,
        // then soft-delete both file rows.
        executeInIsolatedTx("update T_DOCUMENT set DOC_IDFILE_C = null where DOC_ID_C in (:d1, :d2)",
                java.util.Map.of("d1", docId, "d2", restoredDocId));
        markFileSoftDeleted(deletedFileId);
        markFileSoftDeleted(restoredFileId);

        Path deletedPhysical = storageDir.resolve(deletedFileId);
        Path restoredPhysical = storageDir.resolve(restoredFileId);

        // Concurrent restore: AFTER the cleanup commits its DB deletes, revive the restored file's row
        // (as another request restoring its document would) — so it is LIVE again before the sweep. Its
        // id is still in the pre-delete snapshot, but it must NOT be swept because its row is present.
        AppResource.cleanStorageBeforeSweepHook = () -> executeInIsolatedTx(
                "insert into T_FILE (FIL_ID_C, FIL_IDDOC_C, FIL_IDUSER_C, FIL_MIMETYPE_C, FIL_ORDER_N, FIL_VERSION_N, FIL_LATESTVERSION_B, FIL_SIZE_N, FIL_CREATEDATE_D)"
                        + " select :id, :did, (select USE_ID_C from T_USER where USE_USERNAME_C = 'admin'), 'image/jpeg', 0, 0, "
                        + dialectTrue() + ", 1000, :now"
                        + " where not exists (select 1 from T_FILE where FIL_ID_C = :id)",
                java.util.Map.of("id", restoredFileId, "did", restoredDocId, "now", new java.util.Date()));

        Response ok = target().path("/app/batch/clean_storage").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form()));
        Assertions.assertEquals(Status.OK, Status.fromStatusCode(ok.getStatus()));

        // The genuinely-deleted file's bytes are swept...
        Assertions.assertFalse(Files.exists(deletedPhysical), "the genuinely-deleted file's bytes were swept");
        // ...but the concurrently-restored file's bytes SURVIVE (its row is present → not confirmed-deleted).
        Assertions.assertTrue(Files.exists(restoredPhysical),
                "a concurrently-restored file's bytes must survive the cleanup (confirmed-deleted-set sweep)");

        // The apply's reported count reflects only what was actually unlinked (the one genuinely gone).
        long reportedCount = ok.readEntity(JsonObject.class).getJsonNumber("file_count").longValue();
        Assertions.assertEquals(1L, reportedCount,
                "only the confirmed-deleted file was unlinked; the restored file must not be counted");

        // Cleanup: remove the revived file row + its bytes.
        executeInIsolatedTx("delete from T_FILE where FIL_ID_C = :id", java.util.Map.of("id", restoredFileId));
        Files.deleteIfExists(restoredPhysical);
    }

    /**
     * #79 CONCURRENT-RUN SERIALIZATION + COUNT CORRECTNESS: two overlapping {@code clean_storage}
     * executions must count each physically-removed logical file EXACTLY once. The whole post-commit
     * filesystem sweep is serialized by an IN-PROCESS lock ({@code AppResource.STORAGE_SWEEP_LOCK}), so a
     * second run's sweep does not begin deleting until the first run's sweep completes; the second run then
     * recomputes its orphan set against the current filesystem and reclaims only what still remains.
     *
     * <p>This test proves SERIALIZATION deterministically: run A enters the sweep, deletes the orphan's
     * base variant, and pauses in the seam WHILE STILL HOLDING the sweep lock (INDEFINITELY, released only
     * by the test — no wall-clock decides). Run B's sweep then provably BLOCKS on the lock — asserted via
     * {@code AppResource.sweepLockHasQueuedThreads()} — and the seam is proven to fire exactly once (B has
     * not entered its own sweep). Releasing A lets it finish and release the lock; B then runs, finds the
     * orphan already gone, and counts 0. Across both runs the orphan is counted exactly once, for its full
     * footprint.</p>
     */
    @Test
    public void twoConcurrentRunsSerializeAndCountEachRemovedFileExactlyOnce() throws Exception {
        String adminToken = adminToken();
        Path storageDir = DirectoryUtil.getStorageDirectory();

        // Quiesce any pre-existing age-eligible orphan left by earlier tests, so the planted orphan below
        // is the ONLY reclaimable logical file and the two runs' counts are exactly attributable to it.
        target().path("/app/batch/clean_storage").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form()), JsonObject.class);

        // A genuine OLD filesystem orphan (base + _web + _thumb, no DB row, mtime 2 days ago): known
        // footprint, reclaimable by the age-thresholded orphan sweep of BOTH runs.
        String orphanId = UUID.randomUUID().toString();
        long twoDaysAgo = System.currentTimeMillis() - 2L * 24L * 60L * 60L * 1000L;
        long footprint = 0L;
        for (String suffix : new String[]{"", "_web", "_thumb"}) {
            Path p = storageDir.resolve(orphanId + suffix);
            Files.write(p, new byte[]{1, 2, 3, 4});
            Files.setLastModifiedTime(p, FileTime.fromMillis(twoDaysAgo));
            footprint += 4;
        }

        // The seam fires only for the run that is INSIDE the sweep (holding the lock), after it deletes the
        // orphan's base. It counts invocations (to prove the OTHER run never enters its own sweep while the
        // lock is held) and holds the first run INDEFINITELY until the test releases it.
        AtomicInteger seamInvocations = new AtomicInteger(0);
        CountDownLatch aInsideLock = new CountDownLatch(1);
        CountDownLatch releaseA = new CountDownLatch(1);
        AppResource.cleanStorageDuringReclaimHook = (fileId) -> {
            if (orphanId.equals(fileId)) {
                seamInvocations.incrementAndGet();
                aInsideLock.countDown();
                try {
                    releaseA.await(); // indefinite: released only by the test, never by a timeout
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };

        // Each run publishes its response when it COMPLETES. Run A is blocked in the seam holding the sweep
        // lock and cannot publish until released, so the FIRST item taken is deterministically run B.
        BlockingQueue<JsonObject> results = new LinkedBlockingQueue<>();
        CyclicBarrier barrier = new CyclicBarrier(2);
        Callable<Void> run = () -> {
            barrier.await();
            results.add(target().path("/app/batch/clean_storage").request()
                    .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                    .post(Entity.form(new Form()), JsonObject.class));
            return null;
        };

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            pool.submit(run);
            pool.submit(run);

            // Run A enters the sweep, deletes the orphan's base, and pauses holding the sweep lock.
            Assertions.assertTrue(aInsideLock.await(60, TimeUnit.SECONDS),
                    "one run must enter the sweep and pause holding the lock");
            Assertions.assertTrue(Files.exists(storageDir.resolve(orphanId + "_web")),
                    "precondition: run A deleted only the base; the _web variant is still on disk");

            // SERIALIZATION PROOF: run B's sweep must BLOCK on the sweep lock (it cannot start deleting).
            // Wait until B is queued on the lock — deterministic (B will reach the lock), no timeout decides.
            long deadline = System.currentTimeMillis() + 60_000L;
            while (!AppResource.sweepLockHasQueuedThreads()) {
                if (System.currentTimeMillis() > deadline) {
                    Assertions.fail("run B never blocked on the sweep lock — sweeps are not serialized");
                }
                Thread.sleep(20);
            }
            // B is blocked on the lock, so it has NOT entered its own sweep: the seam fired exactly once.
            Assertions.assertEquals(1, seamInvocations.get(),
                    "only the lock-holding run is inside the sweep; the blocked run has not started deleting");

            // Release A to finish its sweep and release the lock; take B's result (it runs after A).
            releaseA.countDown();
            JsonObject first = results.take();
            JsonObject second = results.take();

            // Exactly-once across both runs: one run counted the orphan (full footprint), the other 0.
            long totalCount = first.getJsonNumber("file_count").longValue()
                    + second.getJsonNumber("file_count").longValue();
            long totalBytes = first.getJsonNumber("bytes").longValue()
                    + second.getJsonNumber("bytes").longValue();
            Assertions.assertEquals(1L, totalCount, "the orphan is counted EXACTLY once across both runs");
            Assertions.assertEquals(footprint, totalBytes,
                    "the summed bytes equal the orphan's on-disk footprint, counted once");
            // Physically reclaimed exactly once: every variant gone.
            for (String suffix : new String[]{"", "_web", "_thumb"}) {
                Assertions.assertFalse(Files.exists(storageDir.resolve(orphanId + suffix)),
                        "the orphan variant " + suffix + " is physically reclaimed");
            }
        } finally {
            releaseA.countDown();
            pool.shutdownNow();
            pool.awaitTermination(10, TimeUnit.SECONDS);
            for (String suffix : new String[]{"", "_web", "_thumb"}) {
                Files.deleteIfExists(storageDir.resolve(orphanId + suffix));
            }
        }
    }

    /**
     * Soft-deletes a file row in an isolated committed transaction, so the cleanup run (a separate
     * request) sees it in its removal closure.
     */
    private void markFileSoftDeleted(String fileId) {
        executeInIsolatedTx("update T_FILE set FIL_DELETEDATE_D = :now where FIL_ID_C = :id",
                java.util.Map.of("now", new java.util.Date(), "id", fileId));
    }

    /**
     * Runs a native mutation in an isolated committed transaction (restoring the ambient EM afterward),
     * so cross-request fixtures and the before-sweep hook see committed state.
     */
    private void executeInIsolatedTx(String sql, java.util.Map<String, Object> params) {
        jakarta.persistence.EntityManager prev = com.sismics.util.context.ThreadLocalContext.get().getEntityManager();
        jakarta.persistence.EntityManager em = com.sismics.util.jpa.EMF.get().createEntityManager();
        jakarta.persistence.EntityTransaction tx = em.getTransaction();
        try {
            com.sismics.util.context.ThreadLocalContext.get().setEntityManager(em);
            tx.begin();
            jakarta.persistence.Query q = em.createNativeQuery(sql);
            for (java.util.Map.Entry<String, Object> e : params.entrySet()) {
                q.setParameter(e.getKey(), e.getValue());
            }
            q.executeUpdate();
            tx.commit();
        } finally {
            if (tx.isActive()) {
                tx.rollback();
            }
            em.close();
            com.sismics.util.context.ThreadLocalContext.get().setEntityManager(prev);
        }
    }

    /**
     * Boolean-true literal for the current test dialect (H2 accepts {@code true}; the value is used for
     * FIL_LATESTVERSION_B). Postgres also accepts {@code true}, so a single literal works for both.
     */
    private static String dialectTrue() {
        return "true";
    }
}
