package com.sismics.docs.core.util;

import com.sismics.docs.BaseTransactionalTest;
import com.sismics.docs.core.dao.FileDao;
import com.sismics.docs.core.model.jpa.File;
import com.sismics.docs.core.model.jpa.User;
import com.sismics.util.context.ThreadLocalContext;
import com.sismics.util.jpa.EMF;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import jakarta.persistence.EntityManager;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;

/**
 * Cross-thread visibility guarantee for the rotate path (BLOCKING-A). The writer thread drives the
 * ACTUAL production commit path — {@link RasterGenerationUtil#commitRotation} (the same method
 * {@code FileResource.rotation} uses as its {@link RasterGenerationUtil.PersistStep}) — under the
 * per-file lock, mirroring the request transaction model (open tx → load → commitRotation). It then
 * proves the consequence: the committed rotation is visible to a SECOND thread that reads the rotation
 * only after acquiring the SAME per-file lock.
 *
 * <p>Because the writer uses the real production method, NEUTERING that method's in-lock
 * {@code TransactionUtil.commit} (the BLOCKING-A production mutation) leaves the writer's request
 * transaction uncommitted when the lock releases, so the reader observes the stale 0 instead of 90 and
 * this test goes RED — proving the test exercises the real path, not a re-implementation.
 */
public class TestRasterRotationCommitVisibility extends BaseTransactionalTest {

    private String createdFileId;
    private String createdUserId;

    @Test
    public void committedRotationIsVisibleToNextLockHolder() throws Exception {
        // Seed a file at rotation 0 and COMMIT it so both worker threads (each with their own fresh
        // request transaction) can see the baseline row.
        User user = createUser("rotate_vis");
        File file = createFile(user, 0L);
        createdFileId = file.getId();
        createdUserId = user.getId();
        final String fileId = file.getId();
        com.sismics.docs.core.util.TransactionUtil.commit();

        // Deterministic ordering latches. The reader queues on the lock only after the writer holds it;
        // the writer then, AFTER releasing the lock, blocks until the reader has finished its read
        // BEFORE the writer performs its late request-transaction commit. This reproduces the exact
        // production window: the request transaction commits LATER, in RequestContextFilter, OUTSIDE
        // the lock. If the rotation is committed INSIDE the lock (production), the reader sees 90; if it
        // is not (the mutation), the reader reads in this window and sees the stale 0.
        CountDownLatch writerHoldsLock = new CountDownLatch(1);
        CountDownLatch readerQueued = new CountDownLatch(1);
        CountDownLatch readerObserved = new CountDownLatch(1);
        AtomicInteger observedByReader = new AtomicInteger(-1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread writer = new Thread(() -> {
            // Mirror the production request transaction model: RequestContextFilter opens an EM +
            // transaction for the request, the endpoint runs under it, and it commits at end-of-request.
            EntityManager em = EMF.get().createEntityManager();
            ThreadLocalContext.get().setEntityManager(em);
            em.getTransaction().begin();
            try {
                Lock lock = RasterGenerationUtil.lockFile(fileId);
                try {
                    writerHoldsLock.countDown();
                    // Wait until the reader is queued on the lock so the ordering is deterministic.
                    readerQueued.await(5, TimeUnit.SECONDS);
                    // Drive the REAL production persist step used by FileResource.rotation. It updates
                    // the rotation and commits INSIDE the lock, which is what makes the value visible to
                    // the next lock holder.
                    File fresh = new FileDao().getActiveById(fileId);
                    RasterGenerationUtil.commitRotation(fresh, 90);
                } finally {
                    lock.unlock();
                }
                // Lock released. Block until the reader has performed its read, so the reader's read
                // happens in the window BEFORE this "request" commits below. Under the production
                // in-lock commit the value is already durable here; under the mutation it is not.
                readerObserved.await(5, TimeUnit.SECONDS);
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            } finally {
                // End-of-"request": commit whatever transaction is still open (the empty one re-begun
                // by commitRotation, OR — under the mutation — the still-open uncommitted one carrying
                // the rotation, faithfully modelling "the request commits LATER, outside the lock").
                try {
                    if (em.getTransaction().isActive()) {
                        em.getTransaction().commit();
                    }
                } catch (Exception ignored) {
                    // ignore
                }
                em.close();
                ThreadLocalContext.cleanup();
            }
        });

        Thread reader = new Thread(() -> {
            try {
                writerHoldsLock.await(5, TimeUnit.SECONDS);
                // Signal we are about to queue on the lock, then block until the writer releases.
                readerQueued.countDown();
                Lock lock = RasterGenerationUtil.lockFile(fileId);
                try {
                    // Read the rotation FRESH after acquiring the lock — exactly what the reprocess path
                    // does inside RasterGenerationUtil.
                    com.sismics.docs.core.util.TransactionUtil.handle(() -> {
                        File fresh = new FileDao().getActiveById(fileId);
                        observedByReader.set(fresh.getRotation());
                    });
                } finally {
                    lock.unlock();
                    ThreadLocalContext.cleanup();
                    // Unblock the writer's late request commit only AFTER the read is done.
                    readerObserved.countDown();
                }
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
                readerObserved.countDown();
            }
        });

        writer.start();
        reader.start();
        writer.join(10_000);
        reader.join(10_000);

        if (failure.get() != null) {
            throw new AssertionError("worker thread failed", failure.get());
        }
        Assertions.assertEquals(90, observedByReader.get(),
                "a rotation committed inside the per-file lock must be visible to the next lock holder "
                        + "(the reprocess path); a stale 0 means the in-lock commit was lost");
    }

    /**
     * Explicitly delete the rows + on-disk file the worker threads COMMITTED (the base teardown only
     * rolls back the re-begun empty transaction, so committed data would otherwise persist across
     * tests). Uses an INDEPENDENT entity manager so it is unaffected by the base teardown's rollback
     * of the main-thread transaction (AfterEach ordering is not guaranteed).
     */
    @AfterEach
    public void cleanupCommittedRows() {
        if (createdFileId == null && createdUserId == null) {
            return;
        }
        EntityManager em = EMF.get().createEntityManager();
        try {
            em.getTransaction().begin();
            if (createdFileId != null) {
                em.createNativeQuery("delete from T_FILE where FIL_ID_C = :id")
                        .setParameter("id", createdFileId).executeUpdate();
            }
            if (createdUserId != null) {
                em.createNativeQuery("delete from T_USER where USE_ID_C = :id")
                        .setParameter("id", createdUserId).executeUpdate();
            }
            em.getTransaction().commit();
        } finally {
            em.close();
        }
        try {
            java.nio.file.Files.deleteIfExists(
                    DirectoryUtil.getStorageDirectory().resolve(createdFileId));
        } catch (Exception ignored) {
            // best effort
        }
        createdFileId = null;
        createdUserId = null;
    }
}
