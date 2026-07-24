package com.sismics.docs.core.util;

import com.sismics.BaseTest;
import com.sismics.docs.core.dao.DocumentDao;
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
 * The file-move concurrency contract (#175): moving a file's whole active version chain from one document to
 * another is atomic and fails closed against every interleaving with replacement, first-replacement
 * (version-id minting), and delete-promote. The governing invariant checked in each cell: after ANY
 * interleave, every active row of the logical file resides in exactly ONE document and the losing operation
 * rolls back fully. Concurrency cells assert INVARIANTS, never a specific race winner, and are driven
 * deterministically via engine lock introspection (never a sleep). Runs on H2 and PostgreSQL.
 */
public class TestFileMoveContract extends BaseTest {

    private static final long TIMEOUT_SECONDS = 30;

    private interface TxWork<T> {
        T run() throws Exception;
    }

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

    private static void raiseLockTimeout() {
        String sql = EMF.isDriverPostgresql() ? "SET lock_timeout = 30000" : "SET LOCK_TIMEOUT 30000";
        ThreadLocalContext.get().getEntityManager().createNativeQuery(sql).executeUpdate();
    }

    private String createUser() throws Exception {
        String username = "mv_" + UUID.randomUUID().toString().substring(0, 12);
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

    private String createDocument(String userId, String title) throws Exception {
        return inTx(() -> {
            Document document = new Document();
            document.setUserId(userId);
            document.setLanguage("eng");
            document.setTitle(title);
            document.setCreateDate(new Date());
            return new DocumentDao().create(document, userId);
        });
    }

    private Path writeSource() throws Exception {
        Path src = Files.createTempFile("move-src", ".txt");
        Files.writeString(src, "move contract " + UUID.randomUUID());
        return src;
    }

    private String createFileTx(String userId, String documentId, String previousFileId) throws Exception {
        Path src = writeSource();
        return inTx(() -> FileUtil.createFile("doc.txt", previousFileId, src, Files.size(src), "eng", userId, documentId));
    }

    // --- invariant probes -----------------------------------------------------------------------------

    /** All active rows of the logical file that {@code memberFileId} belongs to (its whole version chain). */
    private List<File> activeChainRows(String memberFileId) throws Exception {
        return inTx(() -> {
            EntityManager em = ThreadLocalContext.get().getEntityManager();
            String versionId = (String) em.createNativeQuery(
                            "select FIL_IDVERSION_C from T_FILE where FIL_ID_C = :id")
                    .setParameter("id", memberFileId).getSingleResult();
            if (versionId == null) {
                return em.createQuery("select f from File f where f.id = :id and f.deleteDate is null", File.class)
                        .setParameter("id", memberFileId).getResultList();
            }
            return em.createQuery("select f from File f where f.versionId = :v and f.deleteDate is null" +
                            " order by f.version", File.class)
                    .setParameter("v", versionId).getResultList();
        });
    }

    /** Assert the whole logical file's active rows all live in {@code expectedDoc} with exactly one latest. */
    private void assertChainInOneDocument(String memberFileId, String expectedDoc, int expectedActiveRows) throws Exception {
        List<File> rows = activeChainRows(memberFileId);
        Assertions.assertEquals(expectedActiveRows, rows.size(), "unexpected number of active chain rows");
        long latest = rows.stream().filter(File::isLatestVersion).count();
        Assertions.assertEquals(1L, latest, "exactly one active row must be the latest version");
        for (File row : rows) {
            Assertions.assertEquals(expectedDoc, row.getDocumentId(),
                    "every active row of the logical file must reside in one document (no split)");
        }
    }

    private String documentOf(String fileId) throws Exception {
        return inTx(() -> (String) ThreadLocalContext.get().getEntityManager()
                .createNativeQuery("select FIL_IDDOC_C from T_FILE where FIL_ID_C = :id")
                .setParameter("id", fileId).getSingleResult());
    }

    private boolean isSoftDeleted(String fileId) throws Exception {
        return inTx(() -> ThreadLocalContext.get().getEntityManager()
                .createNativeQuery("select FIL_DELETEDATE_D from T_FILE where FIL_ID_C = :id")
                .setParameter("id", fileId).getSingleResult() != null);
    }

    private Object move(String fileId, String targetDocumentId, String userId) {
        try {
            inTx(() -> {
                raiseLockTimeout();
                FileMoveService.moveFile(fileId, targetDocumentId, userId);
                return null;
            });
            return "ok";
        } catch (Throwable t) {
            return t;
        }
    }

    // === subsequent replacement (already-versioned chain) =============================================

    @Test
    public void replacement_before_move_movesGrownChain() throws Exception {
        String userId = createUser();
        String docA = createDocument(userId, "A");
        String docB = createDocument(userId, "B");
        String v0 = createFileTx(userId, docA, null);
        String v1 = createFileTx(userId, docA, v0);
        String v2 = createFileTx(userId, docA, v1); // replacement commits BEFORE the move

        Assertions.assertEquals("ok", move(v2, docB, userId));
        assertChainInOneDocument(v0, docB, 3);
        Assertions.assertEquals(v2, activeChainRows(v0).stream().filter(File::isLatestVersion).findFirst().orElseThrow().getId());
    }

    @Test
    public void replacement_after_move_replacesAtTarget() throws Exception {
        String userId = createUser();
        String docA = createDocument(userId, "A");
        String docB = createDocument(userId, "B");
        String v0 = createFileTx(userId, docA, null);
        String v1 = createFileTx(userId, docA, v0);

        Assertions.assertEquals("ok", move(v1, docB, userId));
        // A legitimate replacement at the file's new home extends the chain there — never a split.
        String v2 = createFileTx(userId, docB, v1);
        assertChainInOneDocument(v0, docB, 3);
        Assertions.assertEquals(docB, documentOf(v2));
    }

    /**
     * Move loses to a concurrent replacement that GROWS the chain during the move: the guarded bulk relink's
     * affected-row count no longer equals the enumerated set, so the whole move rolls back. (Neuter target:
     * the affected-count mismatch guard.)
     */
    @Test
    public void replacement_during_move_movesLosesOnCountMismatch() throws Exception {
        String userId = createUser();
        String docA = createDocument(userId, "A");
        String docB = createDocument(userId, "B");
        String v0 = createFileTx(userId, docA, null);
        String v1 = createFileTx(userId, docA, v0); // chain {v0, v1(latest)} in A

        CountDownLatch replHoldsLock = new CountDownLatch(1);
        CountDownLatch replMayCommit = new CountDownLatch(1);
        AtomicReference<Throwable> replError = new AtomicReference<>();
        AtomicReference<Object> moveOutcome = new AtomicReference<>();

        Path replSrc = writeSource();
        Thread repl = new Thread(() -> runInHeldTx(replError, em -> {
            FileUtil.createFile("v2.txt", v1, replSrc, Files.size(replSrc), "eng", userId, docA);
            replHoldsLock.countDown();
            await(replMayCommit);
        }));

        Thread mover = new Thread(() -> moveOutcome.set(move(v1, docB, userId)));

        repl.start();
        await(replHoldsLock);
        mover.start();
        awaitBlockedSession(); // the move's bulk relink is genuinely blocked on the replacement's row lock
        replMayCommit.countDown();
        joinAll(repl, mover);

        Assertions.assertNull(replError.get(), "the replacement failed: " + replError.get());
        Assertions.assertTrue(moveOutcome.get() instanceof FileMoveConflictException,
                "the move must lose closed on a chain grown during the move, got: " + moveOutcome.get());
        assertChainInOneDocument(v0, docA, 3);
        Assertions.assertNull(inTx(() -> firstFileIdInDocument(docB)), "the target must have gained no rows");
    }

    // === first replacement minting a version id on a null-version file ================================

    @Test
    public void firstReplacement_before_move_movesBothRows() throws Exception {
        String userId = createUser();
        String docA = createDocument(userId, "A");
        String docB = createDocument(userId, "B");
        String f = createFileTx(userId, docA, null);
        String g = createFileTx(userId, docA, f); // first replacement mints a version id, {f,g} in A

        Assertions.assertEquals("ok", move(g, docB, userId));
        assertChainInOneDocument(f, docB, 2);
    }

    @Test
    public void firstReplacement_after_move_mintsChainAtTarget() throws Exception {
        String userId = createUser();
        String docA = createDocument(userId, "A");
        String docB = createDocument(userId, "B");
        String f = createFileTx(userId, docA, null); // null version id

        Assertions.assertEquals("ok", move(f, docB, userId));

        // Run the production replacement path against the moved file with its CURRENT state (no stale
        // pre-read): it mints the version chain and lands BOTH the demoted base and the successor in the
        // target, since the demote CAS and the successor's document both resolve from the file's new home.
        String g = createFileTx(userId, docB, f);

        assertChainInOneDocument(f, docB, 2);
        Assertions.assertNull(inTx(() -> firstFileIdInDocument(docA)), "the source has no active rows");

        List<File> rows = activeChainRows(f); // ordered by version: [0] = base f, [1] = successor g
        Assertions.assertEquals(2, rows.size());
        Assertions.assertEquals(f, rows.get(0).getId());
        Assertions.assertEquals(g, rows.get(1).getId(), "the successor is the newly minted version");
        Assertions.assertNotNull(rows.get(0).getVersionId(), "the version-chain id was minted");
        Assertions.assertEquals(rows.get(0).getVersionId(), rows.get(1).getVersionId(),
                "both rows share the minted version-chain id");
        Assertions.assertEquals(docB, rows.get(0).getDocumentId(), "the demoted base stays in the target");
        Assertions.assertEquals(docB, rows.get(1).getDocumentId(), "the successor is created in the target");
        Assertions.assertFalse(rows.get(0).isLatestVersion(), "the base is demoted");
        Assertions.assertTrue(rows.get(1).isLatestVersion(), "the successor is the latest");
    }

    @Test
    public void firstReplacement_after_move_staleSourceReplacementRejected() throws Exception {
        String userId = createUser();
        String docA = createDocument(userId, "A");
        String docB = createDocument(userId, "B");
        String f = createFileTx(userId, docA, null);

        Assertions.assertEquals("ok", move(f, docB, userId));
        // A replacement that still names the OLD document must be rejected as a cross-document mismatch, so
        // no successor is inserted into the source — the file stays whole in the target.
        Assertions.assertThrows(PreviousVersionMismatchException.class,
                () -> createFileTx(userId, docA, f),
                "a replacement targeting the file's old document must be rejected");
        assertChainInOneDocument(f, docB, 1);
        Assertions.assertNull(inTx(() -> firstFileIdInDocument(docA)), "the source must have no active rows");
    }

    /**
     * Move WINS the row-lock race; the concurrent first-replacement then finds its base re-parented and must
     * fail closed on the document-constrained demote CAS (never silently succeed in the new document).
     * NEUTER TARGET: the demote guard's {@code documentId = :expectedDocumentId} constraint.
     */
    @Test
    public void firstReplacement_during_move_replacementLosesOnDemoteGuard() throws Exception {
        String userId = createUser();
        String docA = createDocument(userId, "A");
        String docB = createDocument(userId, "B");
        String f = createFileTx(userId, docA, null); // null version id

        CountDownLatch moveHoldsLock = new CountDownLatch(1);
        CountDownLatch moveMayCommit = new CountDownLatch(1);
        AtomicReference<Throwable> moveError = new AtomicReference<>();
        AtomicReference<Object> replOutcome = new AtomicReference<>();

        // MOVE re-parents f to B and HOLDS f's row lock (uncommitted) until released.
        Thread mover = new Thread(() -> runInHeldTx(moveError, em -> {
            FileMoveService.moveFile(f, docB, userId);
            moveHoldsLock.countDown();
            await(moveMayCommit);
        }));

        // REPLACEMENT of f: its unlocked read still sees f in A (move uncommitted), so it passes validation,
        // then its demote CAS blocks on f's row lock; once the move commits, the CAS sees f in B -> 0 rows.
        Path replSrc = writeSource();
        Thread repl = new Thread(() -> {
            try {
                await(moveHoldsLock);
                inTx(() -> {
                    raiseLockTimeout();
                    return FileUtil.createFile("g.txt", f, replSrc, Files.size(replSrc), "eng", userId, docA);
                });
                replOutcome.set("created");
            } catch (VersionConcurrencyException expected) {
                replOutcome.set(expected);
            } catch (Throwable t) {
                replOutcome.set(t);
            }
        });

        mover.start();
        await(moveHoldsLock);
        repl.start();
        awaitBlockedSession(); // the replacement's demote CAS is genuinely blocked on the move's row lock
        moveMayCommit.countDown();
        joinAll(mover, repl);

        Assertions.assertNull(moveError.get(), "the move failed: " + moveError.get());
        Assertions.assertTrue(replOutcome.get() instanceof VersionConcurrencyException,
                "a replacement whose base was moved must fail closed, got: " + replOutcome.get());
        assertChainInOneDocument(f, docB, 1);
        Assertions.assertNull(inTx(() -> firstFileIdInDocument(docA)), "no successor may be inserted into the source");
    }

    /**
     * Move LOSES to a concurrent first-replacement committed during the move: the replacement minted a version
     * id and inserted a successor at the source after the move enumerated the (then single) row, so the
     * post-update re-verification finds a residual active source row on the new version chain and rolls the
     * move back. NEUTER TARGET: the chain-growth-aware re-verification.
     */
    @Test
    public void firstReplacement_during_move_moveLosesOnReverification() throws Exception {
        String userId = createUser();
        String docA = createDocument(userId, "A");
        String docB = createDocument(userId, "B");
        String f = createFileTx(userId, docA, null); // null version id

        CountDownLatch replHoldsLock = new CountDownLatch(1);
        CountDownLatch replMayCommit = new CountDownLatch(1);
        AtomicReference<Throwable> replError = new AtomicReference<>();
        AtomicReference<Object> moveOutcome = new AtomicReference<>();

        // REPLACEMENT demotes f (minting version id) and inserts g at A, then HOLDS f's row lock uncommitted.
        Path replSrc = writeSource();
        Thread repl = new Thread(() -> runInHeldTx(replError, em -> {
            FileUtil.createFile("g.txt", f, replSrc, Files.size(replSrc), "eng", userId, docA);
            replHoldsLock.countDown();
            await(replMayCommit);
        }));

        // MOVE enumerates f as a single null-version row, then its bulk relink blocks on f's row lock; once the
        // replacement commits, f now carries the new version id and g remains active at A -> residual -> abort.
        Thread mover = new Thread(() -> moveOutcome.set(move(f, docB, userId)));

        repl.start();
        await(replHoldsLock);
        mover.start();
        awaitBlockedSession();
        replMayCommit.countDown();
        joinAll(repl, mover);

        Assertions.assertNull(replError.get(), "the replacement failed: " + replError.get());
        Assertions.assertTrue(moveOutcome.get() instanceof FileMoveConflictException,
                "the move must lose closed when a replacement split-grows the chain, got: " + moveOutcome.get());
        assertChainInOneDocument(f, docA, 2);
        Assertions.assertNull(inTx(() -> firstFileIdInDocument(docB)), "the target must have gained no rows");
    }

    // === delete-promote ===============================================================================

    /**
     * A pre-existing soft-deleted chain row keeps its historical document and does NOT trip the move: the move
     * enumerates only the active rows, moves them, and the re-verification excludes the soft-deleted residual.
     */
    @Test
    public void delete_before_move_softDeletedRowStaysAtSource() throws Exception {
        String userId = createUser();
        String docA = createDocument(userId, "A");
        String docB = createDocument(userId, "B");
        String v0 = createFileTx(userId, docA, null);
        String v1 = createFileTx(userId, docA, v0); // {v0, v1(latest)} in A
        inTx(() -> {
            new FileDao().delete(v1, userId); // v1 soft-deleted, v0 promoted to latest
            return null;
        });

        Assertions.assertEquals("ok", move(v0, docB, userId));
        assertChainInOneDocument(v0, docB, 1);
        Assertions.assertTrue(isSoftDeleted(v1), "the deleted version stays soft-deleted");
        Assertions.assertEquals(docA, documentOf(v1), "a soft-deleted historical row keeps its original document");
    }

    @Test
    public void delete_after_move_promotesWithinTarget() throws Exception {
        String userId = createUser();
        String docA = createDocument(userId, "A");
        String docB = createDocument(userId, "B");
        String v0 = createFileTx(userId, docA, null);
        String v1 = createFileTx(userId, docA, v0); // {v0, v1(latest)} in A

        Assertions.assertEquals("ok", move(v1, docB, userId));
        assertChainInOneDocument(v0, docB, 2);
        // Deleting the latest at the target promotes the prior version WITHIN the target.
        inTx(() -> {
            new FileDao().delete(v1, userId);
            return null;
        });
        assertChainInOneDocument(v0, docB, 1);
        Assertions.assertEquals(v0, activeChainRows(v0).get(0).getId(), "the prior version is promoted at the target");
        Assertions.assertEquals(docB, documentOf(v0));
    }

    /**
     * Move loses to a concurrent delete-promote committed during the move: deleting the latest shrinks the
     * active set the move enumerated, so the guarded relink's affected count no longer matches and the move
     * rolls back. The delete's own promotion still leaves exactly one latest at the source.
     */
    @Test
    public void delete_during_move_moveLosesOnCountMismatch() throws Exception {
        String userId = createUser();
        String docA = createDocument(userId, "A");
        String docB = createDocument(userId, "B");
        String v0 = createFileTx(userId, docA, null);
        String v1 = createFileTx(userId, docA, v0); // {v0, v1(latest)} in A

        CountDownLatch delHoldsLock = new CountDownLatch(1);
        CountDownLatch delMayCommit = new CountDownLatch(1);
        AtomicReference<Throwable> delError = new AtomicReference<>();
        AtomicReference<Object> moveOutcome = new AtomicReference<>();

        Thread deleter = new Thread(() -> runInHeldTx(delError, em -> {
            new FileDao().delete(v1, userId); // soft-delete v1 + promote v0, holds v1's row lock
            delHoldsLock.countDown();
            await(delMayCommit);
        }));

        Thread mover = new Thread(() -> moveOutcome.set(move(v1, docB, userId)));

        deleter.start();
        await(delHoldsLock);
        mover.start();
        awaitBlockedSession();
        delMayCommit.countDown();
        joinAll(deleter, mover);

        Assertions.assertNull(delError.get(), "the delete failed: " + delError.get());
        Assertions.assertTrue(moveOutcome.get() instanceof FileMoveConflictException,
                "the move must lose closed when the active set shrinks during the move, got: " + moveOutcome.get());
        assertChainInOneDocument(v0, docA, 1);
        Assertions.assertTrue(isSoftDeleted(v1), "the concurrently-deleted version stays deleted at the source");
        Assertions.assertNull(inTx(() -> firstFileIdInDocument(docB)), "the target must have gained no rows");
    }

    /** First active latest file id in a document, or null when it has none. */
    private String firstFileIdInDocument(String documentId) {
        List<File> files = new FileDao().getByDocumentId(null, documentId);
        return files.isEmpty() ? null : files.get(0).getId();
    }

    // --- concurrency harness (engine lock introspection, no sleeps as synchronization) -----------------

    private interface TxBody {
        void run(EntityManager em) throws Exception;
    }

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
            Assertions.assertTrue(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "the coordinating latch must release in time");
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
