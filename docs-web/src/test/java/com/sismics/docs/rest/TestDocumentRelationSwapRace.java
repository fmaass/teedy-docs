package com.sismics.docs.rest;

import com.sismics.docs.core.dao.DocumentDao;
import com.sismics.docs.core.dao.RelationDao;
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
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * END-TO-END concurrency tests for {@code POST /document/relation/swap} (#191). Both run on H2 and on real
 * PostgreSQL.
 *
 * <p>The relation table has no unique constraint on {@code (REL_IDDOCFROM_C, REL_IDDOCTO_C)}, so a pair of
 * documents can legitimately carry BOTH directions at once. That makes the swap's read-then-write
 * genuinely unsafe without serialization: two opposite-direction swaps of the same pair would each observe
 * "both directions active", each drop the direction the other intended to keep, and leave the two
 * documents unrelated — a swap that deletes the very link it was asked to reverse. The endpoint therefore
 * locks BOTH document rows FOR UPDATE in deterministic id order before reading any relation row, and the
 * relation-list reconcile takes its source document row for the same reason.</p>
 *
 * <p>Coordination mirrors {@link TestDocumentRestoreOwnershipRace}: the TEST thread holds the document
 * row locks in its own transaction (the "gate"), fires exactly ONE request so there is never more than one
 * waiter and no lock-acquisition race to resolve, observes that request genuinely parking on the lock
 * through the database's own lock-wait view AND that it has not completed, then applies the competing
 * operation UNDER the held lock and releases. The assertions are INVARIANTS over the final state, never
 * "who won".</p>
 *
 * <p><b>Base-red.</b> Replacing the endpoint's {@code getActiveByIdForUpdate} pair with a non-locking
 * read makes the first two tests fail: the request no longer parks, so it completes against a snapshot
 * the gate is about to invalidate — the opposite-swap test then ends with the pair pointing the way
 * NEITHER caller asked for, and the reset test answers 200 for a relation that no longer exists. The
 * third test's competing operation parks on the document row through its own write regardless of this
 * endpoint's locks, so it is an invariant test rather than a lock-existence probe.</p>
 */
public class TestDocumentRelationSwapRace extends BaseJerseyTest {

    private static final String COOKIE = TokenBasedSecurityFilter.COOKIE_NAME;
    private static final long JOIN_TIMEOUT_MS = 30_000;
    private static final long AWAIT_BLOCKED_TIMEOUT_MS = 15_000;

    /**
     * The test-held gate: its own EntityManager + transaction holding pessimistic row locks on both
     * document rows, plus a deterministic, dialect-aware observer for sessions parked on locks. Always
     * release() in a finally block.
     */
    private static final class Gate {
        private final EntityManager em;
        private final EntityTransaction tx;
        /** PG only: the gate connection's backend PID — the root of OUR lock queue. -1 on H2. */
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

        /**
         * Lock both document rows in the same deterministic id order the endpoint uses, so the gate can
         * never deadlock against the request it is gating.
         */
        void lockPair(String documentId, String targetDocumentId) {
            String firstId = documentId.compareTo(targetDocumentId) <= 0 ? documentId : targetDocumentId;
            String secondId = firstId.equals(documentId) ? targetDocumentId : documentId;
            DocumentDao documentDao = new DocumentDao();
            Assertions.assertNotNull(documentDao.getActiveByIdForUpdate(firstId), "Gate must lock the first document row");
            Assertions.assertNotNull(documentDao.getActiveByIdForUpdate(secondId), "Gate must lock the second document row");
        }

        /**
         * Number of DB sessions currently parked in THIS test's lock queue. H2 exposes parked sessions via
         * {@code INFORMATION_SCHEMA.SESSIONS.BLOCKER_ID} (the in-memory DB serves only this fork, so any
         * waiter is ours); PostgreSQL via {@code pg_blocking_pids} rooted at the gate connection's own
         * backend PID.
         */
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
            long deadline = System.currentTimeMillis() + AWAIT_BLOCKED_TIMEOUT_MS;
            while (!condition.getAsBoolean()) {
                Assertions.assertTrue(System.currentTimeMillis() < deadline, "Timed out waiting for: " + what);
                Thread.sleep(25);
            }
        }

        /** Commit the work staged under the held locks and release them, so the parked waiter resumes. */
        void commitAndRelease() {
            if (tx.isActive()) {
                tx.commit();
            }
            if (em.isOpen()) {
                em.close();
            }
            ThreadLocalContext.cleanup();
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

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    /** Point {@code fromId} at {@code toId} through the real relation-writing endpoint. */
    private void link(String fromId, String toId, String token) throws Exception {
        target().path("/document/" + fromId).request()
                .cookie(COOKIE, token)
                .post(Entity.form(new Form()
                        .param("title", "Document Title")
                        .param("language", "eng")
                        .param("relations", toId)), JsonObject.class);
        awaitAsyncQuiescence("the relation write publishes a document-updated event");
    }

    /** Active row count for an ordered pair, read straight from the table in its own transaction. */
    private int activeRows(String fromId, String toId) {
        return TestUserResource.inTx(() -> new RelationDao().getActiveBetween(fromId, toId).size());
    }

    /** Start a swap request on its own thread, recording its status (or failure). */
    private Thread startSwap(String documentId, String targetDocumentId, String token,
                             AtomicReference<Integer> status, AtomicReference<Throwable> error) {
        Thread thread = new Thread(() -> {
            try {
                Response response = target().path("/document/relation/swap").request()
                        .cookie(COOKIE, token)
                        .post(Entity.form(new Form().param("id", documentId).param("target", targetDocumentId)));
                status.set(response.getStatus());
            } catch (Throwable t) {
                error.set(t);
            }
        });
        thread.start();
        return thread;
    }

    private void joinRequest(Thread thread, AtomicReference<Throwable> error) throws Exception {
        thread.join(JOIN_TIMEOUT_MS);
        Assertions.assertFalse(thread.isAlive(), "the request must complete");
        Assertions.assertNull(error.get(), "the request failed: " + error.get());
        awaitAsyncQuiescence("the swap publishes a document-updated event for both documents");
    }

    // ---------------------------------------------------------------------------------------------
    // Tests
    // ---------------------------------------------------------------------------------------------

    /**
     * Two opposite-direction swaps of the SAME pair, while both directions are active, must never leave
     * the documents unrelated.
     *
     * <p>Both {@code A -> B} and {@code B -> A} are active. Each swap's canonical outcome for that state
     * is "drop every row starting at the document I was given", so run against the same stale snapshot the
     * two would delete BOTH rows and destroy the relation entirely. Here the {@code (A, B)} swap parks on
     * the gate's document locks; the opposite {@code (B, A)} swap is applied to completion under those
     * locks and committed; the parked swap then resumes, re-reads under its own locks, and — now seeing
     * only the forward direction — flips it instead of deleting it. Exactly one active relation survives,
     * pointing the way the last swap asked for.</p>
     */
    @Test
    public void oppositeSwapsNeverLeaveTheDocumentsUnrelated() throws Exception {
        clientUtil.createUser("relswap_race_opp");
        String token = clientUtil.login("relswap_race_opp");
        String docA = clientUtil.createDocument(token);
        String docB = clientUtil.createDocument(token);
        link(docA, docB, token);
        link(docB, docA, token);
        Assertions.assertEquals(1, activeRows(docA, docB), "both directions are active before the race");
        Assertions.assertEquals(1, activeRows(docB, docA), "both directions are active before the race");

        AtomicReference<Integer> swapStatus = new AtomicReference<>();
        AtomicReference<Throwable> swapError = new AtomicReference<>();

        Gate gate = new Gate();
        Thread swapThread = null;
        try {
            gate.lockPair(docA, docB);
            swapThread = startSwap(docA, docB, token, swapStatus, swapError);
            // With the document locks the swap parks before reading any relation row. Without them it
            // never takes a lock, so this barrier times out — the base-red signal.
            gate.awaitCondition(() -> gate.blockedSessions() >= 1, "the swap parked on the document row locks");
            Assertions.assertNull(swapStatus.get(),
                    "the swap cannot complete while the gate holds the document row locks");

            // The opposite swap, applied to completion under the held locks: deterministically "the swap
            // that acquired the locks first", with no second waiter racing for them.
            Assertions.assertTrue(new RelationDao().swap(docB, docA), "the opposite swap finds the pair related");
            gate.commitAndRelease();
        } finally {
            gate.release();
        }

        joinRequest(swapThread, swapError);
        Assertions.assertEquals(Response.Status.OK.getStatusCode(), swapStatus.get().intValue(),
                "the second swap still finds a relation to reverse");

        int forward = activeRows(docA, docB);
        int reverse = activeRows(docB, docA);
        Assertions.assertEquals(1, forward + reverse,
                "the pair keeps exactly one active relation — two opposite swaps must never delete both rows");
        Assertions.assertEquals(1, reverse, "the surviving row points the way the last swap asked for");
    }

    /**
     * A swap racing a relation-list reconcile on the same document leaves ONE consistent direction, never
     * a torn pair.
     *
     * <p>Ordering (a) — the reconcile wins. {@code A -> B} is active; the swap parks on the gate's
     * document locks, the reconcile then removes A's outgoing links under those locks and commits. The
     * resuming swap re-reads, finds nothing left in either direction, and answers 404 rather than
     * resurrecting or flipping the row the reconcile just retired.</p>
     */
    @Test
    public void swapLosingToARelationResetFindsNothingToReverse() throws Exception {
        clientUtil.createUser("relswap_race_reset");
        String token = clientUtil.login("relswap_race_reset");
        String docA = clientUtil.createDocument(token);
        String docB = clientUtil.createDocument(token);
        link(docA, docB, token);

        AtomicReference<Integer> swapStatus = new AtomicReference<>();
        AtomicReference<Throwable> swapError = new AtomicReference<>();

        Gate gate = new Gate();
        Thread swapThread = null;
        try {
            gate.lockPair(docA, docB);
            swapThread = startSwap(docA, docB, token, swapStatus, swapError);
            gate.awaitCondition(() -> gate.blockedSessions() >= 1, "the swap parked on the document row locks");
            Assertions.assertNull(swapStatus.get(),
                    "the swap cannot complete while the gate holds the document row locks");

            // The reconcile that won the locks: drop every outgoing link of A. It re-takes A's row lock
            // itself, which the gate already holds in this same transaction.
            new RelationDao().updateRelationList(docA, Collections.emptySet());
            gate.commitAndRelease();
        } finally {
            gate.release();
        }

        joinRequest(swapThread, swapError);
        Assertions.assertEquals(Response.Status.NOT_FOUND.getStatusCode(), swapStatus.get().intValue(),
                "the relation was retired under the lock, so there is nothing left to reverse");
        Assertions.assertEquals(0, activeRows(docA, docB), "the reconcile's removal stands");
        Assertions.assertEquals(0, activeRows(docB, docA), "the swap did not resurrect the link in the other direction");
    }

    /**
     * Ordering (b) — the swap wins. {@code A -> B} is active; a relation-list reconcile on A (clearing its
     * outgoing links) parks on the gate's document row, the swap is then applied under the held lock and
     * committed. The resuming reconcile must re-read A's outgoing rows and find none — the single row now
     * starts at B, so it is no longer A's to retire. Run against the snapshot it would have taken before
     * the swap, it would instead soft-delete the row the swap had just flipped, leaving the documents
     * unrelated even though the swap reported success. The pair must end on exactly one active relation,
     * pointing the way the swap left it.
     */
    @Test
    public void relationReconcileLosingToASwapKeepsOneDirection() throws Exception {
        clientUtil.createUser("relswap_race_keep");
        String token = clientUtil.login("relswap_race_keep");
        String docA = clientUtil.createDocument(token);
        String docB = clientUtil.createDocument(token);
        link(docA, docB, token);

        AtomicReference<Integer> updateStatus = new AtomicReference<>();
        AtomicReference<Throwable> updateError = new AtomicReference<>();

        Gate gate = new Gate();
        Thread updateThread = null;
        try {
            gate.lockPair(docA, docB);
            updateThread = new Thread(() -> {
                try {
                    Response response = target().path("/document/" + docA).request()
                            .cookie(COOKIE, token)
                            .post(Entity.form(new Form()
                                    .param("title", "Document Title")
                                    .param("language", "eng")
                                    .param("relations_reset", "true")));
                    updateStatus.set(response.getStatus());
                } catch (Throwable t) {
                    updateError.set(t);
                }
            });
            updateThread.start();
            gate.awaitCondition(() -> gate.blockedSessions() >= 1, "the document update parked on A's row lock");
            Assertions.assertNull(updateStatus.get(),
                    "the document update cannot complete while the gate holds A's row lock");

            Assertions.assertTrue(new RelationDao().swap(docA, docB), "the swap that won the lock flips the link");
            gate.commitAndRelease();
        } finally {
            gate.release();
        }

        joinRequest(updateThread, updateError);
        Assertions.assertEquals(Response.Status.OK.getStatusCode(), updateStatus.get().intValue(),
                "the document update itself succeeds");

        int forward = activeRows(docA, docB);
        int reverse = activeRows(docB, docA);
        Assertions.assertEquals(1, reverse, "the swap's reversed row survives the reconcile");
        Assertions.assertEquals(0, forward, "the reconcile added nothing back");
        Assertions.assertEquals(1, forward + reverse,
                "exactly one active relation between the pair — the reconcile never retires a row that no longer starts at A");
    }
}
