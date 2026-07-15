package com.sismics.docs.core.util;

import com.sismics.docs.core.dao.DocumentDao;
import com.sismics.docs.core.dao.FileDao;
import com.sismics.docs.core.dao.UserDao;
import com.sismics.docs.core.model.jpa.Document;
import com.sismics.docs.core.model.jpa.File;
import com.sismics.docs.core.model.jpa.User;
import com.sismics.util.context.ThreadLocalContext;
import com.sismics.util.jpa.EMF;
import com.sismics.util.mime.MimeType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityTransaction;
import jakarta.persistence.NoResultException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Deterministic two-transaction concurrency tests for the atomic storage-quota accounting (Phase D).
 * Each scenario runs two threads, each on its OWN EntityManager + transaction against the shared test
 * database (all EMs from {@link EMF} connect to the same DB — H2 {@code mem:docs} locally, real
 * PostgreSQL in CI), interleaved with latches (never sleeps). The canonical quota lock order is the
 * GLOBAL sentinel row first, then the user's own row; both are {@code PESSIMISTIC_WRITE} and portable
 * across H2 and PostgreSQL. The interleaves were validated by temporarily removing the locks / the
 * {@code @DynamicUpdate} and watching each authoritative assertion fail.
 */
public class TestQuotaConcurrency {

    /** Bound so a lock that never releases fails the test instead of hanging the suite forever. */
    private static final long TIMEOUT_SECONDS = 20;

    /**
     * How long the holder keeps its lock after signalling it acquired it, so the peer provably contends.
     * The peer, once released to run, blocks inside its own locking call and cannot signal completion
     * until the holder commits — so the holder's bounded wait on that signal always times out, and the
     * timeout IS the happens-before proof the peer was blocked on the lock (the peer would only fail to
     * block if the locking were broken, which is exactly what these tests must catch).
     */
    private static final long CONTENTION_SECONDS = 3;

    private static <T> T inTx(Supplier<T> supplier) {
        EntityManager em = EMF.get().createEntityManager();
        ThreadLocalContext context = ThreadLocalContext.get();
        context.setEntityManager(em);
        EntityTransaction tx = em.getTransaction();
        tx.begin();
        try {
            T result = supplier.get();
            tx.commit();
            return result;
        } catch (RuntimeException e) {
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

    /**
     * Raise this session's lock wait above the coordination windows (dialect-aware). H2's default
     * LOCK_TIMEOUT (1000ms) is too short — the blocked thread legitimately waits on the global lock;
     * PostgreSQL's default is 0 (wait forever) — the explicit bound keeps a broken interleave from
     * hanging to the join timeout. Session-scoped; the SET syntax differs (H2 bare value, PG {@code =}).
     */
    private static void raiseLockTimeout() {
        String sql = EMF.isDriverPostgresql() ? "SET lock_timeout = 30000" : "SET LOCK_TIMEOUT 30000";
        ThreadLocalContext.get().getEntityManager().createNativeQuery(sql).executeUpdate();
    }

    private static String shortId(String prefix) {
        return prefix + UUID.randomUUID().toString().substring(0, 8);
    }

    private String createUser(long quota) {
        String username = shortId("q");
        return inTx(() -> {
            UserDao userDao = new UserDao();
            User user = new User();
            user.setUsername(username);
            user.setPassword("12345678");
            user.setEmail(username + "@docs.com");
            user.setRoleId("admin");
            user.setStorageQuota(quota);
            try {
                userDao.create(user, username);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return user.getId();
        });
    }

    private void seedStorageCurrent(String userId, long value) {
        inTx(() -> {
            ThreadLocalContext.get().getEntityManager()
                    .createNativeQuery("update T_USER set USE_STORAGECURRENT_N = :v where USE_ID_C = :id")
                    .setParameter("v", value)
                    .setParameter("id", userId)
                    .executeUpdate();
            return null;
        });
    }

    private long storageCurrent(String userId) {
        return inTx(() -> new UserDao().getById(userId).getStorageCurrent());
    }

    private long globalStorageCurrent() {
        return inTx(() -> new UserDao().getGlobalStorageCurrent());
    }

    /**
     * Two concurrent reservations for the SAME user must not lose an update: the second blocks on the
     * global lock until the first commits, re-reads the now-committed value, and adds to it. Result is
     * 2000, not 1000. Without the lock the second reads the pre-commit value and its write lands last,
     * silently discarding the first reservation.
     */
    @Test
    public void twoSameUserReservationsDoNotLoseAnUpdate() throws Exception {
        String userId = createUser(1_000_000L);
        seedStorageCurrent(userId, 0L);

        CountDownLatch aHoldsLock = new CountDownLatch(1);
        CountDownLatch bUnblocked = new CountDownLatch(1);
        AtomicReference<Throwable> errorA = new AtomicReference<>();
        AtomicReference<Throwable> errorB = new AtomicReference<>();

        Thread threadA = new Thread(() -> runReserveHolding(userId, 1000L, null, aHoldsLock, bUnblocked, errorA));
        Thread threadB = new Thread(() -> runReserveAfter(userId, 1000L, null, aHoldsLock, bUnblocked, errorB));

        threadA.start();
        threadB.start();
        joinBoth(threadA, threadB);

        Assertions.assertNull(errorA.get(), "reservation A failed: " + errorA.get());
        Assertions.assertNull(errorB.get(), "reservation B failed: " + errorB.get());
        Assertions.assertEquals(2000L, storageCurrent(userId),
                "both same-user reservations must be counted (no lost update)");
    }

    /**
     * A reclaim (delete decrement) racing a reservation stays consistent: the two deltas (+1000 and
     * -2000) both apply to the seeded 5000, landing on 4000 regardless of order. Without the locks one
     * side reads a stale base and clobbers the other's delta.
     */
    @Test
    public void reclaimRacingReservationIsConsistent() throws Exception {
        String userId = createUser(1_000_000L);
        seedStorageCurrent(userId, 5000L);

        CountDownLatch aHoldsLock = new CountDownLatch(1);
        CountDownLatch bUnblocked = new CountDownLatch(1);
        AtomicReference<Throwable> errorA = new AtomicReference<>();
        AtomicReference<Throwable> errorB = new AtomicReference<>();

        // A reserves +1000 and holds the lock; B reclaims -2000 and blocks until A commits.
        Thread threadA = new Thread(() -> runReserveHolding(userId, 1000L, null, aHoldsLock, bUnblocked, errorA));
        Thread threadB = new Thread(() -> runReclaimAfter(userId, 2000L, aHoldsLock, bUnblocked, errorB));

        threadA.start();
        threadB.start();
        joinBoth(threadA, threadB);

        Assertions.assertNull(errorA.get(), "reservation failed: " + errorA.get());
        Assertions.assertNull(errorB.get(), "reclaim failed: " + errorB.get());
        Assertions.assertEquals(4000L, storageCurrent(userId),
                "reserve(+1000) and reclaim(-2000) must both apply to 5000 -> 4000");
    }

    /**
     * Two DIFFERENT users' reservations serialized by the GLOBAL lock can never exceed the global quota:
     * the second reservation blocks until the first commits, re-computes the cross-user SUM, and is
     * rejected because there is no longer room. Without the lock both read the same pre-commit SUM and
     * both succeed, overrunning the global quota.
     */
    @Test
    public void twoDifferentUserReservationsCannotExceedGlobalQuota() throws Exception {
        String userA = createUser(1_000_000L);
        String userB = createUser(1_000_000L);
        seedStorageCurrent(userA, 0L);
        seedStorageCurrent(userB, 0L);

        // Only room for one more 800-byte reservation across ALL users.
        long globalQuota = globalStorageCurrent() + 1000L;

        CountDownLatch aHoldsLock = new CountDownLatch(1);
        CountDownLatch bUnblocked = new CountDownLatch(1);
        AtomicReference<Throwable> errorA = new AtomicReference<>();
        AtomicReference<Throwable> errorB = new AtomicReference<>();

        // A reserves 800 under the global quota and holds the lock; B blocks on the global lock until A
        // commits, then re-computes the SUM (now including A's 800) and is rejected — only 1000 of
        // headroom existed. Deterministic contention: B is guaranteed to attempt while A still holds.
        Thread threadA = new Thread(() -> runReserveHolding(userA, 800L, globalQuota, aHoldsLock, bUnblocked, errorA));
        Thread threadB = new Thread(() -> runReserveAfter(userB, 800L, globalQuota, aHoldsLock, bUnblocked, errorB));

        threadA.start();
        threadB.start();
        joinBoth(threadA, threadB);

        Assertions.assertNull(errorA.get(), "first reservation must succeed: " + errorA.get());
        Assertions.assertNotNull(errorB.get(),
                "second reservation must be rejected — the global lock forbids overrunning the quota");
        Assertions.assertEquals(800L, storageCurrent(userA), "A reserved 800");
        Assertions.assertEquals(0L, storageCurrent(userB), "B's rejected reservation reserved nothing");
    }

    /**
     * A concurrent generic PROFILE update (email change) must NOT erase an upload's reservation. This
     * reproduces the exact window {@code UserDao.update} has: it re-reads the user row fresh
     * (storageCurrent == 0 here) and later FLUSHES a dirty change (via the audit-log write / commit). The
     * uploader reserves +500 and commits in between; then the profile change flushes. With
     * {@code @DynamicUpdate} on {@code User}, the flush emits an email-only UPDATE, so the reservation
     * survives (final 500). WITHOUT it the full-row UPDATE re-binds the stale storageCurrent (0) and
     * clobbers the reservation — this assertion FAILS without the annotation, which is the point.
     *
     * <p>The email change is loaded and flushed directly against a captured EntityManager (rather than
     * through {@code UserDao.update}) only to hold the flush open across the concurrent reservation
     * deterministically: {@code UserDao.update} flushes as part of its audit-log write microseconds after
     * its own SELECT, so a latch cannot be placed inside that window. The Hibernate dirty-update
     * mechanism under test — a single changed column on {@code User} — is identical.</p>
     */
    @Test
    public void profileUpdateDoesNotEraseReservation() throws Exception {
        String userId = createUser(1_000_000L);
        seedStorageCurrent(userId, 0L);

        CountDownLatch profileLoaded = new CountDownLatch(1);
        CountDownLatch uploadCommitted = new CountDownLatch(1);
        AtomicReference<Throwable> errorUpload = new AtomicReference<>();
        AtomicReference<Throwable> errorProfile = new AtomicReference<>();

        Thread profileThread = new Thread(() -> {
            EntityManager em = EMF.get().createEntityManager();
            ThreadLocalContext.get().setEntityManager(em);
            EntityTransaction tx = em.getTransaction();
            tx.begin();
            try {
                raiseLockTimeout();
                // Load the row fresh (storageCurrent == 0) — mirrors UserDao.update's SELECT — and stage
                // an email-only change on the managed instance WITHOUT flushing yet (a plain SELECT under
                // MVCC takes no write lock, so the uploader's row lock is not blocked here).
                User managed = em.createQuery(
                                "select u from User u where u.id = :id and u.deleteDate is null", User.class)
                        .setParameter("id", userId)
                        .getSingleResult();
                managed.setEmail("changed@docs.com");
                profileLoaded.countDown();
                // Let the upload reserve +500 and commit BEFORE this profile change flushes.
                Assertions.assertTrue(uploadCommitted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
                // Flush the dirty email now (mirrors UserDao.update's audit-log/commit flush) — this is
                // where a full-row UPDATE would re-bind the stale storageCurrent and clobber +500.
                em.flush();
                tx.commit();
            } catch (Throwable t) {
                errorProfile.set(t);
                if (tx.isActive()) {
                    tx.rollback();
                }
            } finally {
                if (em.isOpen()) {
                    em.close();
                }
                ThreadLocalContext.cleanup();
            }
        });

        Thread uploadThread = new Thread(() -> {
            try {
                Assertions.assertTrue(profileLoaded.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                errorUpload.set(e);
                return;
            }
            EntityManager em = EMF.get().createEntityManager();
            ThreadLocalContext.get().setEntityManager(em);
            EntityTransaction tx = em.getTransaction();
            tx.begin();
            try {
                raiseLockTimeout();
                FileUtil.reserveStorage(userId, 500L, null);
                tx.commit();
            } catch (Throwable t) {
                errorUpload.set(t);
                if (tx.isActive()) {
                    tx.rollback();
                }
            } finally {
                if (em.isOpen()) {
                    em.close();
                }
                ThreadLocalContext.cleanup();
                uploadCommitted.countDown();
            }
        });

        profileThread.start();
        uploadThread.start();
        joinBoth(profileThread, uploadThread);

        Assertions.assertNull(errorUpload.get(), "upload reservation failed: " + errorUpload.get());
        Assertions.assertNull(errorProfile.get(), "profile update failed: " + errorProfile.get());
        Assertions.assertEquals(500L, storageCurrent(userId),
                "the concurrent profile update must NOT erase the reservation (@DynamicUpdate)");
    }

    /**
     * The HOLDER thread body: reserve (acquiring GLOBAL + user lock), signal that the lock is held, then
     * hold it while the peer provably contends. The peer can only count down {@code peerUnblocked} AFTER
     * its own (blocked) locking call returns — which cannot happen until this commit — so the bounded
     * wait always times out; the timeout is the happens-before proof the peer blocked. If the locking
     * were broken the peer would NOT block, would commit and signal early, and the correctness assertion
     * would fail — which is what the tests must catch.
     */
    private void runReserveHolding(String userId, long bytes, Long globalQuota, CountDownLatch heldSignal,
                                   CountDownLatch peerUnblocked, AtomicReference<Throwable> error) {
        EntityManager em = EMF.get().createEntityManager();
        ThreadLocalContext.get().setEntityManager(em);
        EntityTransaction tx = em.getTransaction();
        tx.begin();
        try {
            raiseLockTimeout();
            FileUtil.reserveStorage(userId, bytes, globalQuota);
            heldSignal.countDown();
            peerUnblocked.await(CONTENTION_SECONDS, TimeUnit.SECONDS);
            tx.commit();
        } catch (Throwable t) {
            error.set(t);
            if (tx.isActive()) {
                tx.rollback();
            }
        } finally {
            if (em.isOpen()) {
                em.close();
            }
            ThreadLocalContext.cleanup();
        }
    }

    /**
     * The reserve-AFTER-the-holder thread body: wait until the holder holds the lock, then attempt the
     * reservation — which BLOCKS on the global lock until the holder commits. Signals {@code peerUnblocked}
     * only after the (blocked) call returns, so the holder's bounded wait times out and it commits.
     */
    private void runReserveAfter(String userId, long bytes, Long globalQuota, CountDownLatch heldSignal,
                                 CountDownLatch peerUnblocked, AtomicReference<Throwable> error) {
        try {
            Assertions.assertTrue(heldSignal.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "the holder must acquire the lock before this thread attempts to reserve");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            error.set(e);
            peerUnblocked.countDown();
            return;
        }
        EntityManager em = EMF.get().createEntityManager();
        ThreadLocalContext.get().setEntityManager(em);
        EntityTransaction tx = em.getTransaction();
        tx.begin();
        try {
            raiseLockTimeout();
            FileUtil.reserveStorage(userId, bytes, globalQuota);
            tx.commit();
        } catch (Throwable t) {
            error.set(t);
            if (tx.isActive()) {
                tx.rollback();
            }
        } finally {
            if (em.isOpen()) {
                em.close();
            }
            ThreadLocalContext.cleanup();
            peerUnblocked.countDown();
        }
    }

    /**
     * The reclaim-AFTER-the-holder thread body: wait until the holder holds the lock, then attempt the
     * reclaim — which BLOCKS on the global lock until the holder commits. Mirrors {@link #runReserveAfter}.
     */
    private void runReclaimAfter(String userId, long bytes, CountDownLatch heldSignal,
                                 CountDownLatch peerUnblocked, AtomicReference<Throwable> error) {
        try {
            Assertions.assertTrue(heldSignal.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "the holder must acquire the lock before this thread attempts to reclaim");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            error.set(e);
            peerUnblocked.countDown();
            return;
        }
        EntityManager em = EMF.get().createEntityManager();
        ThreadLocalContext.get().setEntityManager(em);
        EntityTransaction tx = em.getTransaction();
        tx.begin();
        try {
            raiseLockTimeout();
            FileUtil.reclaimUserQuota(userId, bytes);
            tx.commit();
        } catch (Throwable t) {
            error.set(t);
            if (tx.isActive()) {
                tx.rollback();
            }
        } finally {
            if (em.isOpen()) {
                em.close();
            }
            ThreadLocalContext.cleanup();
            peerUnblocked.countDown();
        }
    }

    /**
     * Two concurrent permanent-deletes (purges) of the SAME document must reclaim its files' quota EXACTLY
     * ONCE. Both purges snapshot the document's files BEFORE serializing; the second must not apply its
     * stale snapshot after the first committed its hard-deletes. Unrelated bytes remain so the zero clamp
     * cannot mask a double-decrement. Reproduced deterministically: both share a pre-purge file snapshot,
     * the first purge commits its reclaim + hard-delete, then the second purge runs with the SAME stale
     * snapshot — the reclaim must re-read (under the global lock) that the rows are gone and reclaim nothing.
     */
    @Test
    public void concurrentPurgesReclaimSharedFilesExactlyOnce() throws Exception {
        String userId = createUser(1_000_000L);
        String documentId = createTrashedDocumentWithTwoFiles(userId, 1000L, 1000L);
        // 2000 for the two files + 5000 UNRELATED, so a double reclaim (down to 3000) is visible and the
        // zero clamp cannot hide it.
        seedStorageCurrent(userId, 7000L);

        // The stale snapshot both concurrent purges hold: the document's files, read BEFORE either purge.
        List<File> snapshot = inTx(() -> new FileDao().getAllByDocumentId(documentId));
        Date documentDeleteDate = inTx(() -> new DocumentDao().getDeletedByIdSystem(documentId).getDeleteDate());

        // First purge: reclaim (re-reads existence under the lock — all present) + hard-delete + commit.
        inTx(() -> {
            FileUtil.reclaimQuotaForDeletedDocumentFiles(snapshot, documentDeleteDate);
            new DocumentDao().permanentDelete(documentId);
            return null;
        });

        // Second purge with the SAME stale snapshot (the rows are already hard-deleted): the reclaim must
        // re-read under the lock, find them gone, and subtract nothing.
        inTx(() -> {
            FileUtil.reclaimQuotaForDeletedDocumentFiles(snapshot, documentDeleteDate);
            new DocumentDao().permanentDelete(documentId);
            return null;
        });

        Assertions.assertEquals(5000L, storageCurrent(userId),
                "two purges of the same document must reclaim its 2000 bytes exactly once (7000 -> 5000)");
    }

    /**
     * Two concurrent single-file deletes of the SAME file must reclaim its bytes EXACTLY ONCE. Both
     * requests pass their initial active-file check before either commits; the loser must reclaim nothing.
     * Unrelated usage remains so the zero clamp cannot mask a double-decrement. Deterministic contention:
     * the winner holds the global lock (having re-read the file active and reclaimed) while the loser
     * provably blocks; after the winner commits its soft-delete, the loser re-reads the file as already
     * gone and reclaims nothing.
     */
    @Test
    public void concurrentSingleFileDeletesReclaimExactlyOnce() throws Exception {
        String userId = createUser(1_000_000L);
        String fileId = createActiveFile(userId, 1000L);
        // 1000 for the file + 5000 UNRELATED, so a double reclaim (down to 4000) is visible.
        seedStorageCurrent(userId, 6000L);

        CountDownLatch winnerHoldsLock = new CountDownLatch(1);
        CountDownLatch loserUnblocked = new CountDownLatch(1);
        AtomicReference<Throwable> errorWinner = new AtomicReference<>();
        AtomicReference<Throwable> errorLoser = new AtomicReference<>();

        Thread winner = new Thread(() ->
                runSingleDeleteHolding(fileId, userId, winnerHoldsLock, loserUnblocked, errorWinner));
        Thread loser = new Thread(() ->
                runSingleDeleteAfter(fileId, userId, winnerHoldsLock, loserUnblocked, errorLoser));

        winner.start();
        loser.start();
        joinBoth(winner, loser);

        Assertions.assertNull(errorWinner.get(), "the winning delete failed: " + errorWinner.get());
        Assertions.assertNull(errorLoser.get(), "the losing delete failed: " + errorLoser.get());
        Assertions.assertEquals(5000L, storageCurrent(userId),
                "two concurrent deletes of the same file must reclaim its 1000 bytes exactly once (6000 -> 5000)");
    }

    /** Creates an ACTIVE file row owned by {@code userId} with the given size. Returns the file ID. */
    private String createActiveFile(String userId, long size) {
        return inTx(() -> {
            File file = new File();
            file.setUserId(userId);
            file.setVersion(0);
            file.setLatestVersion(true);
            file.setMimeType(MimeType.IMAGE_JPEG);
            file.setSize(size);
            return new FileDao().create(file, userId);
        });
    }

    /** The WINNING single-delete: reclaim (re-reads the file active under the lock) + soft-delete; hold. */
    private void runSingleDeleteHolding(String fileId, String userId, CountDownLatch heldSignal,
                                        CountDownLatch peerUnblocked, AtomicReference<Throwable> error) {
        EntityManager em = EMF.get().createEntityManager();
        ThreadLocalContext.get().setEntityManager(em);
        EntityTransaction tx = em.getTransaction();
        tx.begin();
        try {
            raiseLockTimeout();
            FileUtil.reclaimSingleFileOnDelete(fileId);
            new FileDao().delete(fileId, userId);
            heldSignal.countDown();
            peerUnblocked.await(CONTENTION_SECONDS, TimeUnit.SECONDS);
            tx.commit();
        } catch (Throwable t) {
            error.set(t);
            if (tx.isActive()) {
                tx.rollback();
            }
        } finally {
            if (em.isOpen()) {
                em.close();
            }
            ThreadLocalContext.cleanup();
        }
    }

    /**
     * The LOSING single-delete: waits until the winner holds the lock, then attempts the same delete —
     * its reclaim BLOCKS on the global lock until the winner commits, then re-reads the file as already
     * soft-deleted and reclaims nothing. The concurrent soft-delete of an already-deleted file surfaces
     * as {@code NoResultException}, the realistic outcome (unchanged from before this fix) — tolerated
     * here as "the winner already deleted it", since it is orthogonal to the quota-once property tested.
     */
    private void runSingleDeleteAfter(String fileId, String userId, CountDownLatch heldSignal,
                                      CountDownLatch peerUnblocked, AtomicReference<Throwable> error) {
        try {
            Assertions.assertTrue(heldSignal.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "the winner must acquire the lock before this thread attempts to delete");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            error.set(e);
            peerUnblocked.countDown();
            return;
        }
        EntityManager em = EMF.get().createEntityManager();
        ThreadLocalContext.get().setEntityManager(em);
        EntityTransaction tx = em.getTransaction();
        tx.begin();
        try {
            raiseLockTimeout();
            FileUtil.reclaimSingleFileOnDelete(fileId);
            try {
                new FileDao().delete(fileId, userId);
            } catch (NoResultException alreadyDeleted) {
                // The winner already soft-deleted the file — expected for the losing concurrent delete.
            }
            tx.commit();
        } catch (Throwable t) {
            error.set(t);
            if (tx.isActive()) {
                tx.rollback();
            }
        } finally {
            if (em.isOpen()) {
                em.close();
            }
            ThreadLocalContext.cleanup();
            peerUnblocked.countDown();
        }
    }

    /**
     * Creates a document owned by {@code userId} with two files of the given sizes, then trashes it so the
     * files carry {@code deleteDate == document.deleteDate} (cascade-trashed), the state a purge reclaims.
     *
     * @return the document ID
     */
    private String createTrashedDocumentWithTwoFiles(String userId, long size1, long size2) {
        return inTx(() -> {
            DocumentDao documentDao = new DocumentDao();
            Document document = new Document();
            document.setUserId(userId);
            document.setLanguage("eng");
            document.setTitle("purge doc");
            document.setCreateDate(new Date());
            String documentId = documentDao.create(document, userId);

            FileDao fileDao = new FileDao();
            for (long size : new long[] {size1, size2}) {
                File file = new File();
                file.setDocumentId(documentId);
                file.setUserId(userId);
                file.setVersion(0);
                file.setLatestVersion(true);
                file.setMimeType(MimeType.IMAGE_JPEG);
                file.setSize(size);
                fileDao.create(file, userId);
            }
            // Trash the document — soft-deletes it and its active files at the same instant.
            documentDao.delete(documentId, userId);
            return documentId;
        });
    }

    private static void joinBoth(Thread a, Thread b) throws InterruptedException {
        a.join(TIMEOUT_SECONDS * 1000 + 5000);
        b.join(TIMEOUT_SECONDS * 1000 + 5000);
        Assertions.assertFalse(a.isAlive(), "thread A must terminate within the timeout");
        Assertions.assertFalse(b.isAlive(), "thread B must terminate within the timeout");
    }
}
