package com.sismics.docs.core.util;

import com.sismics.BaseTest;
import com.sismics.docs.core.dao.FileDao;
import com.sismics.docs.core.dao.UserDao;
import com.sismics.docs.core.model.jpa.Document;
import com.sismics.docs.core.model.jpa.File;
import com.sismics.docs.core.model.jpa.User;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * The single-writer, stale-base-safe file-version contract that #73 and #117 build on: the affected-row
 * compare-and-swap that keeps EXACTLY ONE latest version per chain across both new-version creation and
 * current-version deletion, plus the orphan/cross-document validation and rotation inheritance. Concurrency
 * tests assert INVARIANTS (exactly one latest row), never a specific race winner. Runs on H2 and PostgreSQL.
 */
public class TestFileVersionContract extends BaseTest {

    private static final long TIMEOUT_SECONDS = 30;

    private interface TxWork<T> {
        T run() throws Exception;
    }

    /** Run a unit of work in its own committed transaction, letting checked exceptions propagate. */
    private static <T> T inTx(TxWork<T> work) throws Exception {
        EntityManager em = EMF.get().createEntityManager();
        ThreadLocalContext context = ThreadLocalContext.get();
        context.setEntityManager(em);
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

    /** Raise this session's lock wait so a blocked competitor waits (not a premature timeout). */
    private static void raiseLockTimeout() {
        String sql = EMF.isDriverPostgresql() ? "SET lock_timeout = 30000" : "SET LOCK_TIMEOUT 30000";
        ThreadLocalContext.get().getEntityManager().createNativeQuery(sql).executeUpdate();
    }

    private String createUser() throws Exception {
        String username = "ver_" + UUID.randomUUID().toString().substring(0, 12);
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

    private String createDocument(String userId) throws Exception {
        return inTx(() -> {
            com.sismics.docs.core.dao.DocumentDao documentDao = new com.sismics.docs.core.dao.DocumentDao();
            Document document = new Document();
            document.setUserId(userId);
            document.setLanguage("eng");
            document.setTitle("version doc");
            document.setCreateDate(new Date());
            return documentDao.create(document, userId);
        });
    }

    /** A small plaintext source file that createFile encrypts and stores; each caller gets its own. */
    private Path writeSource() throws Exception {
        Path src = Files.createTempFile("version-src", ".txt");
        Files.writeString(src, "version contract " + UUID.randomUUID());
        return src;
    }

    /** Run FileUtil.createFile in its own transaction and return the new file id. */
    private String createFileTx(String userId, String documentId, String previousFileId) throws Exception {
        Path src = writeSource();
        return inTx(() -> FileUtil.createFile("doc.txt", previousFileId, src, Files.size(src), "eng", userId, documentId));
    }

    private File activeFile(String fileId) throws Exception {
        return inTx(() -> new FileDao().getActiveById(fileId));
    }

    /** Number of active (non-deleted) latest rows in a version chain — the invariant target is exactly 1. */
    private long latestActiveCount(String versionId) throws Exception {
        return inTx(() -> {
            Number n = (Number) ThreadLocalContext.get().getEntityManager().createQuery(
                            "select count(f) from File f where f.versionId = :versionId" +
                                    " and f.latestVersion = true and f.deleteDate is null")
                    .setParameter("versionId", versionId)
                    .getSingleResult();
            return n.longValue();
        });
    }

    // --- rotation inheritance -------------------------------------------------------------------------

    @Test
    public void newVersionInheritsRotation() throws Exception {
        String userId = createUser();
        String docId = createDocument(userId);
        String v0 = createFileTx(userId, docId, null);

        // Rotate the base version to 90 degrees.
        inTx(() -> {
            FileDao dao = new FileDao();
            File f = dao.getActiveById(v0);
            f.setRotation(90);
            dao.update(f);
            return null;
        });

        String v1 = createFileTx(userId, docId, v0);
        Assertions.assertEquals(90, activeFile(v1).getRotation(),
                "a new version must inherit the previous version's display rotation");
    }

    // --- orphan / cross-document base validation (typed rejection, never an NPE/500) -------------------

    @Test
    public void crossDocumentPreviousFileIsRejected() throws Exception {
        String userId = createUser();
        String docA = createDocument(userId);
        String docB = createDocument(userId);
        String fileInA = createFileTx(userId, docA, null);

        Assertions.assertThrows(PreviousVersionMismatchException.class,
                () -> createFileTx(userId, docB, fileInA),
                "a previousFileId from another document must be rejected as a typed mismatch");
    }

    @Test
    public void orphanPreviousFileIsRejected() throws Exception {
        String userId = createUser();
        String docId = createDocument(userId);
        String orphan = createFileTx(userId, null, null); // no document association -> documentId null

        Assertions.assertThrows(PreviousVersionMismatchException.class,
                () -> createFileTx(userId, docId, orphan),
                "an orphan previousFileId (null document) must be a typed mismatch, not an NPE");
    }

    // --- stale base (sequential) rejected with the concurrency signal, no duplicate latest -------------

    @Test
    public void staleBaseReplacementIsRejected() throws Exception {
        String userId = createUser();
        String docId = createDocument(userId);
        String v0 = createFileTx(userId, docId, null);
        String v1 = createFileTx(userId, docId, v0); // v0 demoted, v1 is latest

        // A second replacement from the SAME (now stale) base v0 must fail — v0 is no longer the latest.
        Assertions.assertThrows(VersionConcurrencyException.class,
                () -> createFileTx(userId, docId, v0),
                "replacing a stale base must raise the version-concurrency signal");

        String versionId = activeFile(v1).getVersionId();
        Assertions.assertEquals(1L, latestActiveCount(versionId),
                "a stale-base replacement must not create a second latest version");
    }

    // --- a generic update from a stale entity must not resurrect the latest flag (R1) -----------------

    @Test
    public void renameFromStaleEntityDoesNotResurrectLatest() throws Exception {
        String userId = createUser();
        String docId = createDocument(userId);
        String v0 = createFileTx(userId, docId, null);

        // Snapshot v0 while it is still the latest (latestVersion=true, versionId=null) — a rename would load
        // it exactly this way, then pause across a concurrent version-create.
        File staleV0 = activeFile(v0);
        Assertions.assertTrue(staleV0.isLatestVersion());

        // A concurrent version-create demotes v0 and makes v1 the latest.
        String v1 = createFileTx(userId, docId, v0);

        // Saving the rename through the generic update from the STALE entity must NOT copy its
        // latestVersion=true (nor its now-stale null versionId) back over the row.
        inTx(() -> {
            staleV0.setName("renamed.txt");
            new FileDao().update(staleV0);
            return null;
        });

        List<File> latest = inTx(() -> new FileDao().getByDocumentId(userId, docId));
        Assertions.assertEquals(1, latest.size(),
                "a rename from a stale entity must not resurrect a second latest row");
        Assertions.assertEquals(v1, latest.get(0).getId(), "the real latest is still the concurrently-created version");
        Assertions.assertEquals("renamed.txt", activeFile(v0).getName(), "the rename itself must still apply");
        Assertions.assertEquals(activeFile(v1).getVersionId(), activeFile(v0).getVersionId(),
                "the stale update must not null out the CAS-stamped version-chain id");
    }

    // --- delete of the current version promotes the prior one (file stays visible) --------------------

    @Test
    public void deleteCurrentVersionPromotesPriorVersion() throws Exception {
        String userId = createUser();
        String docId = createDocument(userId);
        String v0 = createFileTx(userId, docId, null);
        String v1 = createFileTx(userId, docId, v0); // v1 is latest, v0 demoted

        inTx(() -> {
            new FileDao().delete(v1, userId);
            return null;
        });

        List<File> latest = inTx(() -> new FileDao().getByDocumentId(userId, docId));
        Assertions.assertEquals(1, latest.size(), "the document must still show one file after deleting the latest version");
        Assertions.assertEquals(v0, latest.get(0).getId(), "the prior version must be promoted to latest");
        Assertions.assertTrue(latest.get(0).isLatestVersion(), "the promoted version must carry the latest flag");
    }

    @Test
    public void deleteNonLatestVersionDoesNotPromoteOrHide() throws Exception {
        String userId = createUser();
        String docId = createDocument(userId);
        String v0 = createFileTx(userId, docId, null);
        String v1 = createFileTx(userId, docId, v0); // v1 latest, v0 non-latest

        // Deleting the NON-latest v0 must leave v1 as the single latest (no spurious promotion).
        inTx(() -> {
            new FileDao().delete(v0, userId);
            return null;
        });

        List<File> latest = inTx(() -> new FileDao().getByDocumentId(userId, docId));
        Assertions.assertEquals(1, latest.size());
        Assertions.assertEquals(v1, latest.get(0).getId(), "deleting a non-latest version must not change the latest");
    }

    // --- concurrency: promotion must retry when its target is deleted underneath it (R2) --------------

    /**
     * Chain v0 -> v1 -> v2. Deleting the current latest (v2) selects v1 to promote, but a concurrent delete
     * of the non-latest v1 commits inside that window (forced deterministically via a held row lock). The
     * promotion of v1 then affects zero rows and must RETRY onto v0 rather than leave the chain with zero
     * latest rows. Latch-ordered so it deterministically drives the vulnerable interleave (on code without
     * the retry it leaves zero latest rows).
     */
    @Test
    public void deletePromotionRetriesWhenTargetConcurrentlyDeleted() throws Exception {
        String userId = createUser();
        String docId = createDocument(userId);
        String v0 = createFileTx(userId, docId, null);
        String v1 = createFileTx(userId, docId, v0);
        String v2 = createFileTx(userId, docId, v1);
        String versionId = activeFile(v2).getVersionId();

        CountDownLatch bHoldsV1Lock = new CountDownLatch(1);
        CountDownLatch bMayCommit = new CountDownLatch(1);
        AtomicReference<Throwable> errorA = new AtomicReference<>();
        AtomicReference<Throwable> errorB = new AtomicReference<>();

        // B: soft-delete the NON-latest middle version v1 and HOLD its row lock (deleteDate flushed but not
        // yet committed), so A's promotion selects v1 (still appears active to A) and then blocks on it.
        Thread b = new Thread(() -> runInHeldTx(errorB, (em) -> {
            new FileDao().delete(v1, userId);
            em.flush();                 // push the deleteDate UPDATE -> acquire v1's row write lock
            bHoldsV1Lock.countDown();
            await(bMayCommit);
        }));

        // A: delete the current latest v2. Its promotion selects v1, blocks on B's lock, then (once B commits
        // v1's deletion) sees v1 gone -> 0 affected -> retries onto v0.
        Thread a = new Thread(() -> runInHeldTx(errorA, (em) ->
                new FileDao().delete(v2, userId)));

        b.start();
        await(bHoldsV1Lock);
        a.start();
        awaitBlockedSession();          // A is genuinely blocked on v1's row lock inside its promotion
        bMayCommit.countDown();
        joinAll(a, b);

        Assertions.assertNull(errorB.get(), "delete(v1) failed: " + errorB.get());
        Assertions.assertNull(errorA.get(), "delete(v2) failed: " + errorA.get());
        Assertions.assertEquals(1L, latestActiveCount(versionId),
                "a promotion whose target is concurrently deleted must retry and leave exactly one latest (never zero)");
        List<File> latest = inTx(() -> new FileDao().getByDocumentId(userId, docId));
        Assertions.assertEquals(1, latest.size());
        Assertions.assertEquals(v0, latest.get(0).getId(), "promotion must fall back to v0 after v1 is deleted");
    }

    // --- concurrency: two creates against the same base, exactly one wins -----------------------------

    @Test
    public void concurrentCreatesLeaveExactlyOneLatest() throws Exception {
        String userId = createUser();
        String docId = createDocument(userId);
        String v0 = createFileTx(userId, docId, null);

        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Object> result1 = new AtomicReference<>();
        AtomicReference<Object> result2 = new AtomicReference<>();

        Thread t1 = new Thread(() -> result1.set(racingCreate(userId, docId, v0, start)));
        Thread t2 = new Thread(() -> result2.set(racingCreate(userId, docId, v0, start)));
        t1.start();
        t2.start();
        start.countDown();
        joinAll(t1, t2);

        int successes = 0;
        int conflicts = 0;
        for (Object r : new Object[]{result1.get(), result2.get()}) {
            if (r instanceof String) {
                successes++;
            } else if (r instanceof VersionConcurrencyException) {
                conflicts++;
            } else {
                Assertions.fail("unexpected racing-create outcome: " + r);
            }
        }
        Assertions.assertEquals(1, successes, "exactly one concurrent create against the same base must succeed");
        Assertions.assertEquals(1, conflicts, "the other concurrent create must get the version-concurrency signal");

        String versionId = activeFile(v0).getVersionId();
        Assertions.assertEquals(1L, latestActiveCount(versionId), "exactly one latest row must survive");
    }

    /** One racing create: returns the new file id on success, or the VersionConcurrencyException on conflict. */
    private Object racingCreate(String userId, String documentId, String previousFileId, CountDownLatch start) {
        try {
            Path src = writeSource();
            await(start);
            return inTx(() -> {
                raiseLockTimeout();
                return FileUtil.createFile("doc.txt", previousFileId, src, Files.size(src), "eng", userId, documentId);
            });
        } catch (VersionConcurrencyException e) {
            return e;
        } catch (Exception e) {
            return e;
        }
    }

    // --- concurrency: a create racing a delete of its base loses deterministically -------------------

    /**
     * A create racing a delete of the SAME current-latest base must lose with a version conflict, never a
     * duplicate latest row. Latch-ordered to force the vulnerable interleave: the delete holds v1's row lock
     * (after CAS-demoting it and promoting v0) while the create's CAS on v1 blocks; when the delete commits,
     * the create's CAS affects 0 -> conflict. On code without the create CAS this ordering yields two latest
     * rows, so the test deterministically detects it (not only when the create happens to win the race).
     */
    @Test
    public void createLosingToConcurrentDeleteOfItsBaseConflicts() throws Exception {
        String userId = createUser();
        String docId = createDocument(userId);
        String v0 = createFileTx(userId, docId, null);
        String v1 = createFileTx(userId, docId, v0); // chain: v0 (non-latest), v1 (latest)
        String versionId = activeFile(v1).getVersionId();

        CountDownLatch deleteHoldsLock = new CountDownLatch(1);
        CountDownLatch deleteMayCommit = new CountDownLatch(1);
        AtomicReference<Throwable> deleteError = new AtomicReference<>();
        AtomicReference<Object> createOutcome = new AtomicReference<>();

        // DELETE: remove the current latest v1 (CAS-demotes + locks v1, promotes v0), then HOLD before commit.
        Thread deleter = new Thread(() -> runInHeldTx(deleteError, em -> {
            new FileDao().delete(v1, userId);
            deleteHoldsLock.countDown();
            await(deleteMayCommit);
        }));

        // CREATE: replace v1. Its CAS on v1 blocks on the delete's lock; once the delete commits v1's
        // demotion, the CAS affects 0 -> VersionConcurrencyException, and no successor row is inserted.
        Thread creator = new Thread(() -> {
            try {
                await(deleteHoldsLock);
                Path src = writeSource();
                inTx(() -> {
                    raiseLockTimeout();
                    return FileUtil.createFile("doc.txt", v1, src, Files.size(src), "eng", userId, docId);
                });
                createOutcome.set("created");
            } catch (VersionConcurrencyException expected) {
                createOutcome.set(expected);
            } catch (Throwable t) {
                createOutcome.set(t);
            }
        });

        deleter.start();
        await(deleteHoldsLock);
        creator.start();
        awaitBlockedSession();          // the create's CAS is genuinely blocked on v1's row lock
        deleteMayCommit.countDown();
        joinAll(deleter, creator);

        Assertions.assertNull(deleteError.get(), "the delete failed: " + deleteError.get());
        Assertions.assertTrue(createOutcome.get() instanceof VersionConcurrencyException,
                "a create racing a delete of its base must lose with a version conflict, got: " + createOutcome.get());
        Assertions.assertEquals(1L, latestActiveCount(versionId),
                "exactly one latest row (v0) must remain, never two");
        List<File> latest = inTx(() -> new FileDao().getByDocumentId(userId, docId));
        Assertions.assertEquals(1, latest.size());
        Assertions.assertEquals(v0, latest.get(0).getId());
    }

    /** A unit of work run in its own transaction, given the entity manager for explicit flush/lock control. */
    private interface TxBody {
        void run(EntityManager em) throws Exception;
    }

    /**
     * Run {@code body} in its own transaction on this thread, committing on success and recording any failure
     * into {@code error}. The body controls its own timing (holding a row lock, awaiting a latch) before this
     * commits. The lock wait is raised so a deliberately blocked competitor waits rather than timing out.
     */
    private void runInHeldTx(AtomicReference<Throwable> error, TxBody body) {
        EntityManager em = EMF.get().createEntityManager();
        ThreadLocalContext.get().setEntityManager(em);
        EntityTransaction tx = em.getTransaction();
        tx.begin();
        try {
            raiseLockTimeout();
            body.run(em);
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
     * True when some database session is currently blocked waiting on a lock, read from the engine's own
     * lock introspection (never a sleep): {@code pg_locks.granted = false} on PostgreSQL, a non-null
     * {@code INFORMATION_SCHEMA.SESSIONS.BLOCKER_ID} on H2.
     */
    private boolean someSessionIsBlocked() {
        Supplier<Boolean> probe = () -> {
            try {
                return inTx(() -> {
                    String sql = EMF.isDriverPostgresql()
                            ? "select count(*) from pg_locks where not granted"
                            : "select count(*) from information_schema.sessions where blocker_id is not null";
                    Number n = (Number) ThreadLocalContext.get().getEntityManager().createNativeQuery(sql).getSingleResult();
                    return n.longValue() > 0;
                });
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
        return probe.get();
    }

    /** Bounded poll until a competitor genuinely blocks on a row lock; fails (times out) if none ever does. */
    private void awaitBlockedSession() {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            if (someSessionIsBlocked()) {
                return;
            }
            try {
                Thread.sleep(15);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
        throw new AssertionError("no database session blocked on a row lock within 20s");
    }

    private static void await(CountDownLatch latch) {
        try {
            Assertions.assertTrue(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "the start barrier must release in time");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    private static void joinAll(Thread... threads) throws InterruptedException {
        for (Thread t : threads) {
            t.join(TIMEOUT_SECONDS * 1000 + 5000);
        }
        for (Thread t : threads) {
            Assertions.assertFalse(t.isAlive(), "every worker thread must finish");
        }
    }
}
