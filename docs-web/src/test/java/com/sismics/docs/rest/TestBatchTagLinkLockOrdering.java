package com.sismics.docs.rest;

import com.sismics.docs.core.dao.TagDao;
import com.sismics.docs.core.dao.UserDao;
import com.sismics.util.context.ThreadLocalContext;
import com.sismics.util.filter.TokenBasedSecurityFilter;
import com.sismics.util.jpa.EMF;
import jakarta.json.JsonObject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityTransaction;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Form;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * #137 batch/hot lock-ORDER tests. The reassign and clean_storage batch paths must acquire their DOCUMENT and
 * TAG row locks DOCUMENT-before-TAG — the canonical USER -> DOCUMENT -> TAG order the FK-forced hot tag-link
 * path already imposes. Inserting a {@code T_DOCUMENT_TAG} row checks its DOCUMENT foreign key before its TAG
 * foreign key, so under PostgreSQL it takes a {@code FOR KEY SHARE} lock on the parent DOCUMENT row before the
 * parent TAG row (confirmed by {@link #reverseTagBeforeDocumentDeadlocksAgainstHotTagLink()} below: a batch
 * transaction that takes the locks TAG-before-DOCUMENT deadlocks against the insert). A batch path that locked
 * the tag first would invert that order and could deadlock a concurrent tag-link.
 *
 * <p>Every test here asserts PostgreSQL row-lock / FK semantics and is skipped on H2 (whose FOR UPDATE and
 * uncommitted-write conflict rules differ and do not model these invariants reliably). CI exercises them in the
 * docs-web PostgreSQL job. Two kinds of test:</p>
 * <ul>
 *   <li><b>Real-method order guards</b> ({@link #reassignAcquiresDocumentLockBeforeTagLock()},
 *       {@link #cleanStorageAcquiresDocumentLockBeforeTagLock()}): drive the REAL batch code concurrent with a
 *       test-held "gate" lock and observe — via the database's own lock-wait view — that the DOCUMENT row is
 *       already locked while the batch path is parked on the TAG row. Reverting either reorder makes the batch
 *       path park on the tag BEFORE touching the document, so the probe finds the document still free and the
 *       assertion fails (verified). Gate/observer harness mirrors {@link TestUserDeleteAndGrantLockOrdering}.</li>
 *   <li><b>Deterministic deadlock invariant</b> ({@link #canonicalDocumentBeforeTagDoesNotDeadlockAgainstHotTagLink()},
 *       {@link #reverseTagBeforeDocumentDeadlocksAgainstHotTagLink()}): two controlled transactions on separate
 *       connections — a batch lock sequence and the real hot {@code T_DOCUMENT_TAG} insert — interleaved
 *       deterministically via {@code pg_blocking_pids}. The canonical DOCUMENT-before-TAG order never deadlocks
 *       (asserted repeatedly); the reverse TAG-before-DOCUMENT order DOES (SQLState 40P01), which both proves
 *       the hazard is real and shows the no-deadlock assertion is not vacuous. Bounded {@code lock_timeout} with
 *       a smaller {@code deadlock_timeout} makes the detector fire first, so a failure surfaces as a deadlock
 *       rather than hanging CI.</li>
 * </ul>
 */
public class TestBatchTagLinkLockOrdering extends BaseJerseyTest {

    private static final long JOIN_TIMEOUT_MS = 30_000;
    private static final long AWAIT_TIMEOUT_MS = 15_000;

    /**
     * A test-held pessimistic row lock (its own EntityManager + transaction) plus a deterministic,
     * dialect-aware observer for sessions parked on locks. Copied in spirit from
     * {@link TestUserDeleteAndGrantLockOrdering}. Always {@link #release()} in a finally block.
     */
    private static final class Gate {
        private final EntityManager em;
        private final EntityTransaction tx;
        private final int gatePid;

        Gate() {
            em = EMF.get().createEntityManager();
            ThreadLocalContext.get().setEntityManager(em);
            tx = em.getTransaction();
            tx.begin();
            gatePid = EMF.isDriverPostgresql()
                    ? ((Number) em.createNativeQuery("select pg_backend_pid()").getSingleResult()).intValue()
                    : -1;
        }

        long blockedSessions() {
            String sql = EMF.isDriverPostgresql()
                    ? "with waiters as (" +
                      "  select pid, pg_blocking_pids(pid) as blockers from pg_stat_activity" +
                      "  where datname = current_database() and wait_event_type = 'Lock')" +
                      " select count(*) from waiters w" +
                      " where w.blockers && array[" + gatePid + "]" +
                      "    or w.blockers && (select coalesce(array_agg(w2.pid), array[]::integer[])" +
                      "                        from waiters w2 where w2.blockers && array[" + gatePid + "])"
                    : "select count(*) from information_schema.sessions where blocker_id is not null";
            return ((Number) em.createNativeQuery(sql).getSingleResult()).longValue();
        }

        void awaitCondition(BooleanSupplier condition, String what) throws InterruptedException {
            long deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MS;
            while (!condition.getAsBoolean()) {
                Assertions.assertTrue(System.currentTimeMillis() < deadline, "Timed out waiting for: " + what);
                Thread.sleep(25);
            }
        }

        void release() {
            if (tx.isActive()) {
                tx.rollback();
            }
            if (em.isOpen()) {
                em.close();
            }
            ThreadLocalContext.cleanup();
        }
    }

    // ----------------------------------------------------------------------------------------------------
    // Real-method order guards (H2 + PostgreSQL)
    // ----------------------------------------------------------------------------------------------------

    /**
     * The admin reassign path ({@link UserDao#reassignOwnedDocuments}) reassigns the DOCUMENT before it locks
     * and moves the TAG. Gate holds the departing user's tag row; the reassign reassigns the document first,
     * then parks on the tag row — so while it is parked the document is ALREADY locked. Reverting the reorder
     * (tag before document) parks it on the tag BEFORE touching the document, leaving the document lockable
     * and failing the assertion.
     */
    @Test
    public void reassignAcquiresDocumentLockBeforeTagLock() throws Exception {
        Assumptions.assumeTrue(EMF.isDriverPostgresql(),
                "asserts a PostgreSQL row-lock ordering property — H2's FOR UPDATE / uncommitted-write conflict"
                        + " semantics differ and do not model it reliably");
        clientUtil.createUser("bto_reassign_departing");
        clientUtil.createUser("bto_reassign_target");
        String departingToken = clientUtil.login("bto_reassign_departing");
        String departingId = userId("bto_reassign_departing");
        String targetId = userId("bto_reassign_target");

        // The departing user owns BOTH a document and a tag, so reassignOwnedDocuments exercises its document
        // block AND its tag lock/move block.
        String docId = clientUtil.createDocument(departingToken);
        String tagId = createTag(departingToken, "BtoReassign");

        AtomicReference<Throwable> reassignError = new AtomicReference<>();
        Thread reassignThread = new Thread(() -> {
            EntityManager em = EMF.get().createEntityManager();
            ThreadLocalContext.get().setEntityManager(em);
            EntityTransaction tx = em.getTransaction();
            try {
                tx.begin();
                new UserDao().reassignOwnedDocuments(departingId, targetId);
                tx.commit();
            } catch (Throwable t) {
                reassignError.set(t);
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

        Gate gate = new Gate();
        Assertions.assertNotNull(new TagDao().getByIdForUpdate(tagId), "gate must lock the tag row");
        try {
            reassignThread.start();
            gate.awaitCondition(() -> gate.blockedSessions() >= 1, "reassign parked on the tag row");

            // INVARIANT (#137): the reassigned document is ALREADY locked while reassign is parked on the tag —
            // proof the document lock was taken before the tag lock. Pre-fix the document would still be free.
            Assertions.assertFalse(documentIsLockable(docId),
                    "the reassigned document must already be locked while reassign is parked on the tag row"
                            + " (batch path must acquire DOCUMENT before TAG, #137)");
        } finally {
            gate.release();
        }

        reassignThread.join(JOIN_TIMEOUT_MS);
        Assertions.assertFalse(reassignThread.isAlive(), "the reassign must complete");
        Assertions.assertNull(reassignError.get(), "reassign failed: " + reassignError.get());

        Assertions.assertEquals(1L, count(
                "select count(*) from T_DOCUMENT where DOC_ID_C = :doc and DOC_IDUSER_C = :owner",
                Map.of("doc", docId, "owner", targetId)), "the document is now owned by the target");
        Assertions.assertEquals(1L, count(
                "select count(*) from T_TAG where TAG_ID_C = :tag and TAG_IDUSER_C = :owner",
                Map.of("tag", tagId, "owner", targetId)), "the tag is now owned by the target");
    }

    /**
     * The clean_storage sweep ({@code AppResource.batchCleanStorage}) soft-deletes orphan DOCUMENTS before
     * orphan TAGS. Gate holds an orphan tag row; the sweep soft-deletes the orphan document first, then parks
     * on the orphan-tag soft-delete — so while it is parked the orphan document is ALREADY locked. Reverting
     * the reorder (tag before document) parks it on the tag BEFORE touching the document, failing the assertion.
     */
    @Test
    public void cleanStorageAcquiresDocumentLockBeforeTagLock() throws Exception {
        Assumptions.assumeTrue(EMF.isDriverPostgresql(),
                "asserts a PostgreSQL row-lock ordering property — H2's FOR UPDATE / uncommitted-write conflict"
                        + " semantics differ and do not model it reliably");
        // Manufacture an orphan DOCUMENT and an orphan TAG: a user owns both, then is soft-deleted directly so
        // its document and tag are left with a soft-deleted owner — exactly what clean_storage sweeps.
        clientUtil.createUser("bto_clean_owner");
        String ownerToken = clientUtil.login("bto_clean_owner");
        String ownerId = userId("bto_clean_owner");
        String docId = clientUtil.createDocument(ownerToken);
        String tagId = createTag(ownerToken, "BtoClean");
        executeSql("update T_USER set USE_DELETEDATE_D = :now where USE_ID_C = :id",
                Map.of("now", new Date(), "id", ownerId));

        String adminToken = adminToken();
        AtomicReference<Integer> cleanStatus = new AtomicReference<>();
        AtomicReference<Throwable> cleanError = new AtomicReference<>();
        Thread cleanThread = new Thread(() -> {
            try {
                Response r = target().path("/app/batch/clean_storage").request()
                        .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                        .post(Entity.form(new Form()));
                cleanStatus.set(r.getStatus());
            } catch (Throwable t) {
                cleanError.set(t);
            }
        });

        Gate gate = new Gate();
        Assertions.assertNotNull(new TagDao().getByIdForUpdate(tagId), "gate must lock the orphan tag row");
        try {
            cleanThread.start();
            gate.awaitCondition(() -> gate.blockedSessions() >= 1, "clean_storage parked on the orphan-tag row");

            Assertions.assertFalse(documentIsLockable(docId),
                    "the orphan document must already be soft-deleted (locked) while clean_storage is parked on"
                            + " the orphan-tag row (batch path must acquire DOCUMENT before TAG, #137)");
        } finally {
            gate.release();
        }

        cleanThread.join(JOIN_TIMEOUT_MS);
        Assertions.assertFalse(cleanThread.isAlive(), "clean_storage must complete");
        Assertions.assertNull(cleanError.get(), "clean_storage failed: " + cleanError.get());
        Assertions.assertEquals(Response.Status.OK.getStatusCode(), cleanStatus.get().intValue(),
                "clean_storage returns OK once the gate releases the tag row");
    }

    // ----------------------------------------------------------------------------------------------------
    // Deterministic deadlock invariant (PostgreSQL only)
    // ----------------------------------------------------------------------------------------------------

    /**
     * With BOTH paths acquiring DOCUMENT before TAG, no lock cycle can form: repeatedly interleaving a batch
     * lock sequence (DOCUMENT then TAG) against the hot {@code T_DOCUMENT_TAG} insert never deadlocks.
     */
    @Test
    public void canonicalDocumentBeforeTagDoesNotDeadlockAgainstHotTagLink() throws Exception {
        Assumptions.assumeTrue(EMF.isDriverPostgresql(),
                "PostgreSQL row-lock/FK deadlock semantics — H2 cannot model them");
        clientUtil.createUser("bto_nodl_owner");
        String token = clientUtil.login("bto_nodl_owner");
        String docId = clientUtil.createDocument(token);
        String tagId = createTag(token, "BtoNoDeadlock");

        for (int i = 0; i < 25; i++) {
            Outcome out = runHotTagLinkInterleaving(docId, tagId, /* lockDocumentFirstInA */ true);
            Assertions.assertFalse(out.deadlock,
                    "canonical DOCUMENT-before-TAG order must not deadlock against the hot tag-link (iteration "
                            + i + ")");
            Assertions.assertNull(out.aError,
                    "batch transaction saw an unexpected error (iteration " + i + "): " + out.aError);
            Assertions.assertNull(out.bError,
                    "hot tag-link saw an unexpected error (iteration " + i + "): " + out.bError);
        }
    }

    /**
     * FAILING-FIRST / falsifiability evidence: the PRE-FIX order (batch path locks TAG before DOCUMENT) DOES
     * deadlock (SQLState 40P01) against the hot tag-link path — proving the canonical-order test above is not
     * vacuous and that DOCUMENT-before-TAG is precisely what removes the deadlock class #137 fixes.
     */
    @Test
    public void reverseTagBeforeDocumentDeadlocksAgainstHotTagLink() throws Exception {
        Assumptions.assumeTrue(EMF.isDriverPostgresql(),
                "PostgreSQL row-lock/FK deadlock semantics — H2 cannot model them");
        clientUtil.createUser("bto_dl_owner");
        String token = clientUtil.login("bto_dl_owner");
        String docId = clientUtil.createDocument(token);
        String tagId = createTag(token, "BtoDeadlock");

        for (int i = 0; i < 5; i++) {
            Outcome out = runHotTagLinkInterleaving(docId, tagId, /* lockDocumentFirstInA */ false);
            Assertions.assertTrue(out.deadlock,
                    "reverse TAG-before-DOCUMENT order must deadlock (SQLState 40P01) against the hot tag-link"
                            + " (iteration " + i + "); aError=" + out.aError + " bError=" + out.bError);
        }
    }

    /**
     * Result of one interleaving run.
     */
    private static final class Outcome {
        boolean deadlock;
        Throwable aError;
        Throwable bError;
    }

    /**
     * Run one deterministic interleaving of a batch lock sequence (transaction A) against the hot tag-link
     * insert (transaction B) on the same document + tag rows.
     *
     * <p>Barrier: A takes its first row lock, B starts and issues the {@code T_DOCUMENT_TAG} insert (which
     * takes {@code FOR KEY SHARE} on the parent DOCUMENT then TAG), and A waits — via {@code pg_blocking_pids}
     * on a third observer connection — until B is actually parked, blocked by A, before A takes its second
     * lock. With {@code lockDocumentFirstInA=true} A locks DOCUMENT then TAG (canonical, no cycle); with
     * {@code false} A locks TAG then DOCUMENT (reverse), closing the cycle so PostgreSQL's detector aborts one
     * transaction with SQLState 40P01. Bounded {@code lock_timeout} with a smaller {@code deadlock_timeout}
     * ensures the detector fires before any timeout and nothing hangs. B always rolls back, so no row persists.</p>
     */
    private Outcome runHotTagLinkInterleaving(String docId, String tagId, boolean lockDocumentFirstInA)
            throws Exception {
        Outcome out = new Outcome();
        EntityManager emA = EMF.get().createEntityManager();
        EntityManager emObs = EMF.get().createEntityManager();
        AtomicInteger bPid = new AtomicInteger(-1);
        AtomicReference<Throwable> bError = new AtomicReference<>();
        CountDownLatch bReady = new CountDownLatch(1);

        Thread bThread = new Thread(() -> {
            EntityManager emB = EMF.get().createEntityManager();
            EntityTransaction txB = emB.getTransaction();
            try {
                txB.begin();
                setTimeouts(emB);
                bPid.set(backendPid(emB));
                bReady.countDown();
                // The hot tag-link path: insert the child T_DOCUMENT_TAG row (as TagDao.updateTagList does via
                // em.persist). PostgreSQL locks the parent DOCUMENT row (FK checked first) then the parent TAG.
                emB.createNativeQuery("insert into T_DOCUMENT_TAG (DOT_ID_C, DOT_IDDOCUMENT_C, DOT_IDTAG_C)"
                                + " values (:id, :d, :t)")
                        .setParameter("id", UUID.randomUUID().toString())
                        .setParameter("d", docId)
                        .setParameter("t", tagId)
                        .executeUpdate();
            } catch (Throwable t) {
                bError.set(t);
            } finally {
                rollbackQuietly(txB);
                closeQuietly(emB);
            }
        });

        EntityTransaction txA = emA.getTransaction();
        try {
            txA.begin();
            setTimeouts(emA);
            int aPid = backendPid(emA);

            String firstTable = lockDocumentFirstInA ? "T_DOCUMENT" : "T_TAG";
            String firstPk = lockDocumentFirstInA ? "DOC_ID_C" : "TAG_ID_C";
            String firstId = lockDocumentFirstInA ? docId : tagId;
            String secondTable = lockDocumentFirstInA ? "T_TAG" : "T_DOCUMENT";
            String secondPk = lockDocumentFirstInA ? "TAG_ID_C" : "DOC_ID_C";
            String secondId = lockDocumentFirstInA ? tagId : docId;

            lockForUpdate(emA, firstTable, firstPk, firstId);

            bThread.start();
            bReady.await();
            awaitBlockedBy(emObs, aPid, bPid.get());

            try {
                lockForUpdate(emA, secondTable, secondPk, secondId);
            } catch (Throwable t) {
                out.aError = t;
            }
            rollbackQuietly(txA);

            bThread.join(JOIN_TIMEOUT_MS);
            out.bError = bError.get();
            out.deadlock = isDeadlock(out.aError) || isDeadlock(out.bError);
            return out;
        } finally {
            rollbackQuietly(txA);
            closeQuietly(emA);
            closeQuietly(emObs);
        }
    }

    // ----------------------------------------------------------------------------------------------------
    // helpers
    // ----------------------------------------------------------------------------------------------------

    private String createTag(String token, String name) {
        return target().path("/tag").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .put(Entity.form(new Form().param("name", name).param("color", "#ff0000")), JsonObject.class)
                .getString("id");
    }

    /**
     * Set a bounded {@code lock_timeout} and a smaller {@code deadlock_timeout} on the session so the deadlock
     * detector fires before any lock timeout. {@code deadlock_timeout} is superuser-settable, so it is guarded
     * by a savepoint: a non-superuser test role degrades to the server default (1s, still &lt; the 10s
     * lock_timeout) instead of poisoning the transaction.
     */
    private static void setTimeouts(EntityManager em) {
        em.createNativeQuery("set local lock_timeout = 10000").executeUpdate();
        try {
            em.createNativeQuery("savepoint dt_sp").executeUpdate();
            em.createNativeQuery("set local deadlock_timeout = 200").executeUpdate();
            em.createNativeQuery("release savepoint dt_sp").executeUpdate();
        } catch (RuntimeException e) {
            em.createNativeQuery("rollback to savepoint dt_sp").executeUpdate();
        }
    }

    private static int backendPid(EntityManager em) {
        return ((Number) em.createNativeQuery("select pg_backend_pid()").getSingleResult()).intValue();
    }

    private static void lockForUpdate(EntityManager em, String table, String pkCol, String id) {
        em.createNativeQuery("select " + pkCol + " from " + table + " where " + pkCol + " = :id for update")
                .setParameter("id", id).getSingleResult();
    }

    /** Poll (on a fresh snapshot each time) until {@code blockedPid} is parked, blocked by {@code blockerPid}. */
    private void awaitBlockedBy(EntityManager emObs, int blockerPid, int blockedPid) throws InterruptedException {
        long deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MS;
        while (true) {
            EntityTransaction tx = emObs.getTransaction();
            Boolean blocked;
            tx.begin();
            try {
                // blockerPid / blockedPid are backend PIDs read from pg_backend_pid — inlined (not user input).
                blocked = (Boolean) emObs.createNativeQuery(
                                "select " + blockerPid + " = any(pg_blocking_pids(" + blockedPid + "))")
                        .getSingleResult();
            } finally {
                tx.commit();
            }
            if (Boolean.TRUE.equals(blocked)) {
                return;
            }
            Assertions.assertTrue(System.currentTimeMillis() < deadline,
                    "timed out waiting for the hot tag-link transaction to park on the batch lock");
            Thread.sleep(25);
        }
    }

    private static boolean isDeadlock(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof java.sql.SQLException
                    && "40P01".equals(((java.sql.SQLException) c).getSQLState())) {
                return true;
            }
        }
        return false;
    }

    private static void rollbackQuietly(EntityTransaction tx) {
        try {
            if (tx != null && tx.isActive()) {
                tx.rollback();
            }
        } catch (RuntimeException ignore) {
            // best effort
        }
    }

    private static void closeQuietly(EntityManager em) {
        try {
            if (em != null && em.isOpen()) {
                em.close();
            }
        } catch (RuntimeException ignore) {
            // best effort
        }
    }

    /**
     * True when the document row is currently lockable (FOR UPDATE acquired within a short, dialect-aware
     * timeout) — i.e. NOT held by another transaction. Mirrors {@link TestUserDeleteAndGrantLockOrdering}.
     */
    private boolean documentIsLockable(String documentId) {
        EntityManager em = EMF.get().createEntityManager();
        EntityTransaction tx = em.getTransaction();
        try {
            tx.begin();
            em.createNativeQuery(EMF.isDriverPostgresql() ? "SET LOCAL lock_timeout = 750" : "SET LOCK_TIMEOUT 750")
                    .executeUpdate();
            em.createNativeQuery("select DOC_ID_C from T_DOCUMENT where DOC_ID_C = :id for update")
                    .setParameter("id", documentId).getSingleResult();
            return true;
        } catch (RuntimeException e) {
            return false;
        } finally {
            if (tx.isActive()) {
                tx.rollback();
            }
            em.close();
        }
    }

    private String userId(String username) {
        EntityManager em = EMF.get().createEntityManager();
        EntityTransaction tx = em.getTransaction();
        try {
            tx.begin();
            Object o = em.createNativeQuery(
                            "select USE_ID_C from T_USER where USE_USERNAME_C = :name and USE_DELETEDATE_D is null")
                    .setParameter("name", username).getSingleResult();
            tx.commit();
            return (String) o;
        } finally {
            if (tx.isActive()) {
                tx.rollback();
            }
            em.close();
        }
    }

    private long count(String sql, Map<String, Object> params) {
        EntityManager em = EMF.get().createEntityManager();
        EntityTransaction tx = em.getTransaction();
        try {
            tx.begin();
            jakarta.persistence.Query q = em.createNativeQuery(sql);
            for (Map.Entry<String, Object> e : params.entrySet()) {
                q.setParameter(e.getKey(), e.getValue());
            }
            Object n = q.getSingleResult();
            tx.commit();
            return ((Number) n).longValue();
        } finally {
            if (tx.isActive()) {
                tx.rollback();
            }
            em.close();
        }
    }

    private void executeSql(String sql, Map<String, Object> params) {
        EntityManager em = EMF.get().createEntityManager();
        EntityTransaction tx = em.getTransaction();
        try {
            tx.begin();
            jakarta.persistence.Query q = em.createNativeQuery(sql);
            for (Map.Entry<String, Object> e : params.entrySet()) {
                q.setParameter(e.getKey(), e.getValue());
            }
            q.executeUpdate();
            tx.commit();
        } finally {
            if (tx.isActive()) {
                tx.rollback();
            }
            em.close();
        }
    }
}
