package com.sismics.docs.rest;

import com.sismics.util.context.ThreadLocalContext;
import com.sismics.util.filter.TokenBasedSecurityFilter;
import com.sismics.util.jpa.EMF;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityTransaction;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Form;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * #189 user-deletion lock-ORDER tests: every path that touches a USER row, DOCUMENT rows and TAG rows in
 * one transaction must acquire them USER -&gt; DOCUMENT -&gt; TAG, and every multi-document acquisition must
 * be one globally-sorted-by-id sequence.
 *
 * <p>Two halves share one serialization point — the departing user's own row lock:</p>
 * <ul>
 *   <li><b>Deletion</b> locks its ENTIRE document set (the documents of the routes it will cancel UNIONED
 *       with the account's own active documents) in ascending document id BEFORE the #133 tag lock. Before
 *       the fix the tag lock came first and the document locks followed, so a self-delete held tags while
 *       waiting on a document — the opposite direction to a document write that links a tag, which holds
 *       the document row and then waits on the tag row through the {@code T_DOCUMENT_TAG} foreign key.
 *       A real cycle (PostgreSQL 40P01, reproduced by
 *       {@link #selfDeleteDoesNotDeadlockAgainstHotTagLinkOnOwnedDocument()} against the pre-fix code).</li>
 *   <li><b>Route creation</b> ({@code POST /route/start}, the only path that creates route steps) resolves
 *       its step targets first and locks the USER-typed ones' active rows FOR UPDATE (deduplicated,
 *       ascending id) BEFORE its document lock, so it takes USER -&gt; DOCUMENT like the deletion does, and
 *       a start racing a deletion of one of its targets parks on that user row and fails closed.</li>
 * </ul>
 *
 * <p>GROUP-typed step targets are deliberately NOT locked (they are a different principal table and
 * locking them as users would reject every group-targeted model, including the seeded
 * administrators-group workflow that {@link TestRouteResource} exercises). This class therefore makes NO
 * claim about group-deletion races.</p>
 *
 * <p>Every test here asserts PostgreSQL row-lock semantics and is skipped on H2 (whose FOR UPDATE /
 * FOR KEY SHARE and uncommitted-write conflict rules differ and do not model these invariants). CI
 * exercises them in the docs-web PostgreSQL job. Assertions are invariants (no deadlock / acquisition
 * order / fail-closed), never which racer wins.</p>
 */
public class TestUserDeleteRouteLockOrdering extends BaseJerseyTest {

    private static final long JOIN_TIMEOUT_MS = 60_000;
    private static final long AWAIT_TIMEOUT_MS = 20_000;

    /**
     * A test-held transaction on its own EntityManager, plus a dialect-aware observer for sessions parked
     * on locks it (transitively) blocks. Mirrors {@code TestBatchTagLinkLockOrdering.Gate}. Always
     * {@link #release()} in a finally block.
     */
    private static final class Gate {
        private final EntityManager em;
        private final EntityTransaction tx;
        private final int gatePid;

        Gate() {
            em = EMF.get().createEntityManager();
            tx = em.getTransaction();
            tx.begin();
            gatePid = ((Number) em.createNativeQuery("select pg_backend_pid()").getSingleResult()).intValue();
        }

        /** Lock a row of {@code table} FOR UPDATE, holding it until {@link #release()}. */
        void lockForUpdate(String table, String pkColumn, String id) {
            em.createNativeQuery("select " + pkColumn + " from " + table + " where " + pkColumn + " = :id for update")
                    .setParameter("id", id).getSingleResult();
        }

        /**
         * Lock a row of {@code table} FOR NO KEY UPDATE — the mode Hibernate's
         * {@code LockModeType.PESSIMISTIC_WRITE} actually emits on PostgreSQL, and therefore the mode
         * {@code DocumentDao.getActiveByIdForUpdate} holds a document row in. It conflicts with another
         * FOR NO KEY UPDATE and with FOR UPDATE, but NOT with the FOR KEY SHARE an FK check takes.
         */
        void lockForNoKeyUpdate(String table, String pkColumn, String id) {
            em.createNativeQuery("select " + pkColumn + " from " + table + " where " + pkColumn + " = :id"
                            + " for no key update")
                    .setParameter("id", id).getSingleResult();
        }

        void execute(String sql, Map<String, Object> params) {
            jakarta.persistence.Query q = em.createNativeQuery(sql);
            for (Map.Entry<String, Object> e : params.entrySet()) {
                q.setParameter(e.getKey(), e.getValue());
            }
            q.executeUpdate();
        }

        /** Sessions parked on a lock held by this gate, directly or transitively through another waiter. */
        long blockedSessions() {
            String sql = "with waiters as (" +
                    "  select pid, pg_blocking_pids(pid) as blockers from pg_stat_activity" +
                    "  where datname = current_database() and wait_event_type = 'Lock')" +
                    " select count(*) from waiters w" +
                    " where w.blockers && array[" + gatePid + "]" +
                    "    or w.blockers && (select coalesce(array_agg(w2.pid), array[]::integer[])" +
                    "                        from waiters w2 where w2.blockers && array[" + gatePid + "])";
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
            try {
                if (tx.isActive()) {
                    tx.rollback();
                }
            } catch (RuntimeException ignore) {
                // best effort: a gate aborted by the deadlock detector cannot be rolled back cleanly
            }
            if (em.isOpen()) {
                em.close();
            }
        }
    }

    // ----------------------------------------------------------------------------------------------------
    // (i) self-delete vs the hot tag-link on a document the departing user OWNS
    // ----------------------------------------------------------------------------------------------------

    /**
     * DOCUMENT before TAG in the self-delete transaction. The racing arm models the real "write a document
     * and (re)link a tag to it" path: it holds the DOCUMENT row FOR NO KEY UPDATE (the mode
     * {@code DocumentDao.getActiveByIdForUpdate} takes — Hibernate's PESSIMISTIC_WRITE maps to
     * {@code for no key update} on PostgreSQL) and then inserts the {@code T_DOCUMENT_TAG} child row,
     * whose foreign-key check takes FOR KEY SHARE on the parent TAG. So its direction is DOCUMENT then TAG,
     * and the self-delete must take the same one.
     *
     * <p>Deterministic interleaving: the racing arm takes the document lock, the self-delete then parks on
     * that document, and only THEN does the racing arm reach for the tag.</p>
     *
     * <p>Post-fix the self-delete holds no tag while parked, so the tag insert goes straight through and
     * both transactions finish. Pre-fix the self-delete had already taken the tag FOR UPDATE (the #133
     * lock ran before any document lock, via raw {@code for update} — which DOES conflict with FOR KEY
     * SHARE) while waiting for the document, closing the cycle: PostgreSQL's detector aborts one of them
     * with SQLState 40P01, observed as {@code ERROR: deadlock detected} on the deletion's own
     * {@code select ... from T_DOCUMENT ... for no key update}.</p>
     */
    @Test
    public void selfDeleteDoesNotDeadlockAgainstHotTagLinkOnOwnedDocument() throws Exception {
        Assumptions.assumeTrue(EMF.isDriverPostgresql(),
                "asserts PostgreSQL row-lock/FK deadlock semantics — H2 cannot model them");
        clientUtil.createUser("udrl_dl_departing");
        clientUtil.createUser("udrl_dl_other");
        String departingToken = clientUtil.login("udrl_dl_departing");
        String otherToken = clientUtil.login("udrl_dl_other");

        // The departing user OWNS the document the tag-link races on.
        String ownedDoc = clientUtil.createDocument(departingToken);

        // A tag the departing user has WRITE on, linked to a SURVIVING foreign document: exactly the set
        // CredentialLifecycleUtil.lockForeignLinkedWriteTags locks. The tag is created by the other user
        // (so there are TWO live WRITE holders) — otherwise the #122 sole-write-holder guard would refuse
        // the self-delete before it reaches any lock we care about.
        String tagId = target().path("/tag").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, otherToken)
                .put(Entity.form(new Form().param("name", "UdrlDeadlock").param("color", "#ff0000")), JsonObject.class)
                .getString("id");
        target().path("/acl").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, otherToken)
                .put(Entity.form(new Form()
                        .param("source", tagId)
                        .param("perm", "WRITE")
                        .param("target", "udrl_dl_departing")
                        .param("type", "USER")), JsonObject.class);
        String foreignDoc = clientUtil.createDocument(otherToken);
        linkTag(foreignDoc, tagId);

        long deadlocksBefore = databaseDeadlockCount();

        AtomicReference<Integer> deleteStatus = new AtomicReference<>();
        AtomicReference<Throwable> deleteError = new AtomicReference<>();
        Thread deleteThread = new Thread(() -> {
            try {
                Response r = target().path("/user").request()
                        .cookie(TokenBasedSecurityFilter.COOKIE_NAME, departingToken)
                        .delete();
                deleteStatus.set(r.getStatus());
            } catch (Throwable t) {
                deleteError.set(t);
            }
        });

        Throwable tagLinkError = null;
        Gate tagLink = new Gate();
        try {
            // The hot path's FIRST acquisition: the DOCUMENT row FOR NO KEY UPDATE, exactly as
            // DocumentDao.getActiveByIdForUpdate holds it while a document write is in flight.
            tagLink.lockForNoKeyUpdate("T_DOCUMENT", "DOC_ID_C", ownedDoc);

            deleteThread.start();
            tagLink.awaitCondition(() -> tagLink.blockedSessions() >= 1,
                    "the self-delete to park on the departing user's own document");

            // The hot path's SECOND acquisition: FOR KEY SHARE on the parent TAG, taken by the real
            // T_DOCUMENT_TAG child insert's foreign-key check. Pre-fix the self-delete is already
            // holding that tag FOR UPDATE (TagDao.lockForeignLinkedWriteTagIds, raw `for update`, which
            // DOES conflict with FOR KEY SHARE) while waiting for this document -> cycle.
            try {
                tagLink.execute("insert into T_DOCUMENT_TAG (DOT_ID_C, DOT_IDDOCUMENT_C, DOT_IDTAG_C)"
                                + " values (:id, :doc, :tag)",
                        Map.of("id", UUID.randomUUID().toString(), "doc", ownedDoc, "tag", tagId));
            } catch (Throwable t) {
                tagLinkError = t;
            }
        } finally {
            tagLink.release();
        }

        deleteThread.join(JOIN_TIMEOUT_MS);
        Assertions.assertFalse(deleteThread.isAlive(), "the self-delete must complete");
        Assertions.assertNull(deleteError.get(), "the self-delete request failed: " + deleteError.get());

        Assertions.assertFalse(isDeadlock(tagLinkError),
                "the hot tag-link must not deadlock (40P01) against a concurrent self-delete: " + tagLinkError);
        Assertions.assertEquals(Status.OK.getStatusCode(), deleteStatus.get().intValue(),
                "the self-delete must succeed (a 40P01 victim would surface as a 500 here)");
        Assertions.assertEquals(deadlocksBefore, databaseDeadlockCount(),
                "PostgreSQL must have detected no deadlock during the interleaving");

        // The deletion really ran (so the no-deadlock assertion is not vacuous).
        Assertions.assertEquals(1L, count(
                "select count(*) from T_USER where USE_USERNAME_C = :v and USE_DELETEDATE_D is not null",
                Map.of("v", "udrl_dl_departing")), "the departing account is soft-deleted");
        Assertions.assertEquals(1L, count(
                "select count(*) from T_DOCUMENT where DOC_ID_C = :v and DOC_DELETEDATE_D is not null",
                Map.of("v", ownedDoc)), "the departing account's own document is trashed");
    }

    // ----------------------------------------------------------------------------------------------------
    // (ii)+(iii) route creation vs the deletion of one of its USER step targets
    // ----------------------------------------------------------------------------------------------------

    /**
     * A {@code POST /route/start} whose model targets a user being deleted PARKS on that user's row lock
     * (which the deletion holds to commit) and, once the deletion commits, FAILS CLOSED: the target no
     * longer resolves to an active user, so no route and no step are created. This is the USER-before-
     * DOCUMENT half of the fix — the start must reach the user lock before it locks the document.
     */
    @Test
    public void routeStartRacingSelfDeleteParksOnUserRowAndFailsClosed() throws Exception {
        Assumptions.assumeTrue(EMF.isDriverPostgresql(),
                "asserts PostgreSQL row-lock wait semantics — H2 cannot model them");
        String adminToken = adminToken();
        clientUtil.createUser("udrl_race_departing");
        String departingToken = clientUtil.login("udrl_race_departing");

        // The departing user owns a document, so the deletion takes a document lock we can gate on.
        String departingDoc = clientUtil.createDocument(departingToken);
        // A model whose only step targets the departing USER, started on an admin-owned document.
        String modelId = createUserValidateModel(adminToken, "UdrlRaceModel", "udrl_race_departing");
        String targetDoc = clientUtil.createDocument(adminToken);

        AtomicReference<Integer> deleteStatus = new AtomicReference<>();
        Thread deleteThread = new Thread(() -> deleteStatus.set(target().path("/user").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, departingToken)
                .delete().getStatus()));

        AtomicReference<Integer> startStatus = new AtomicReference<>();
        AtomicReference<String> startBody = new AtomicReference<>();
        Thread startThread = new Thread(() -> {
            Response r = target().path("/route/start").request()
                    .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                    .post(Entity.form(new Form()
                            .param("documentId", targetDoc)
                            .param("routeModelId", modelId)));
            startStatus.set(r.getStatus());
            startBody.set(r.readEntity(String.class));
        });

        Gate gate = new Gate();
        try {
            // Park the deletion AFTER it has taken the departing user's row lock: it holds that row while
            // it waits for this document.
            gate.lockForUpdate("T_DOCUMENT", "DOC_ID_C", departingDoc);

            deleteThread.start();
            gate.awaitCondition(() -> gate.blockedSessions() >= 1, "the self-delete to park on the gated document");

            startThread.start();
            // INVARIANT: the start does not race past the deletion — it parks, transitively behind this
            // gate, on the departing user's row lock the deletion holds.
            gate.awaitCondition(() -> gate.blockedSessions() >= 2,
                    "the route start to park on the departing user's row lock held by the self-delete");
        } finally {
            gate.release();
        }

        deleteThread.join(JOIN_TIMEOUT_MS);
        startThread.join(JOIN_TIMEOUT_MS);
        Assertions.assertFalse(deleteThread.isAlive(), "the self-delete must complete");
        Assertions.assertFalse(startThread.isAlive(), "the route start must complete");

        Assertions.assertEquals(Status.OK.getStatusCode(), deleteStatus.get().intValue(),
                "the self-delete must succeed");
        assertFailsClosedWithInvalidTarget(startStatus.get(), startBody.get(),
                "a route start whose USER target was deleted under it must fail closed");

        Assertions.assertEquals(0L, count("select count(*) from T_ROUTE where RTE_IDDOCUMENT_C = :v",
                Map.of("v", targetDoc)), "no route may be created for a start that failed closed");
        Assertions.assertEquals(0L, count(
                "select count(*) from T_ROUTE_STEP where RTP_IDTARGET_C = :v",
                Map.of("v", userId("udrl_race_departing", false))),
                "no step may be assigned to the deleted user");
    }

    /**
     * No-contention arm: once the deletion has COMMITTED, the same start fails closed at target resolution
     * — before any row lock. Proven by holding the (now soft-deleted) user's row FOR UPDATE for the whole
     * request and observing that nothing ever parks on it.
     */
    @Test
    public void routeStartAfterSelfDeleteFailsClosedWithoutLockWait() throws Exception {
        Assumptions.assumeTrue(EMF.isDriverPostgresql(),
                "asserts PostgreSQL row-lock wait semantics — H2 cannot model them");
        String adminToken = adminToken();
        clientUtil.createUser("udrl_after_departing");
        String departingToken = clientUtil.login("udrl_after_departing");
        String departingId = userId("udrl_after_departing", true);

        String modelId = createUserValidateModel(adminToken, "UdrlAfterModel", "udrl_after_departing");
        String targetDoc = clientUtil.createDocument(adminToken);

        Response deleteResponse = target().path("/user").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, departingToken)
                .delete();
        Assertions.assertEquals(Status.OK.getStatusCode(), deleteResponse.getStatus(),
                "the self-delete must succeed");

        Gate gate = new Gate();
        int startStatus;
        String startBody;
        try {
            gate.lockForUpdate("T_USER", "USE_ID_C", departingId);
            Response r = target().path("/route/start").request()
                    .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                    .post(Entity.form(new Form()
                            .param("documentId", targetDoc)
                            .param("routeModelId", modelId)));
            startStatus = r.getStatus();
            startBody = r.readEntity(String.class);
            Assertions.assertEquals(0L, gate.blockedSessions(),
                    "with the target already deleted the start must fail at resolution, never wait on a row lock");
        } finally {
            gate.release();
        }

        assertFailsClosedWithInvalidTarget(startStatus, startBody,
                "a route start whose USER target is already deleted must fail closed");
        Assertions.assertEquals(0L, count("select count(*) from T_ROUTE where RTE_IDDOCUMENT_C = :v",
                Map.of("v", targetDoc)), "no route may be created for a start that failed closed");
    }

    // ----------------------------------------------------------------------------------------------------
    // (iv) two concurrent self-deletes whose document sets overlap
    // ----------------------------------------------------------------------------------------------------

    /**
     * Two users, each OWNING a document that carries an active route targeting the OTHER user: each
     * deletion's document set is {own document, the other's document}, and the two sets are identical.
     * The deletion must acquire that whole union in ascending document id, NOT "route documents first,
     * then owned documents" — otherwise the user who owns the LOWER-id document walks the pair in the
     * opposite direction to the other and the two deletions can deadlock.
     *
     * <p>Deterministic order guard: gate the HIGHER-id document, then run the lower-id owner's deletion.
     * Sorted, it takes the lower-id document first and is parked on the higher one while already holding
     * it. Pre-fix it reached for the route document (the higher-id one) first and was parked holding
     * NOTHING, leaving the lower-id document lockable — which is what makes this assertion fail against
     * the pre-fix code.</p>
     */
    @Test
    public void concurrentSelfDeletesWithOverlappingDocumentSetsAcquireDocumentsInGlobalOrder() throws Exception {
        Assumptions.assumeTrue(EMF.isDriverPostgresql(),
                "asserts PostgreSQL row-lock ordering semantics — H2 cannot model them");
        String adminToken = adminToken();
        clientUtil.createUser("udrl_pair_a");
        clientUtil.createUser("udrl_pair_b");
        String tokenA = clientUtil.login("udrl_pair_a");
        String tokenB = clientUtil.login("udrl_pair_b");
        String docA = clientUtil.createDocument(tokenA);
        String docB = clientUtil.createDocument(tokenB);

        // Label by document id, not by creation order: "lo" owns the lower-id document.
        boolean aIsLo = docA.compareTo(docB) < 0;
        String loDoc = aIsLo ? docA : docB;
        String hiDoc = aIsLo ? docB : docA;
        String loUser = aIsLo ? "udrl_pair_a" : "udrl_pair_b";
        String hiUser = aIsLo ? "udrl_pair_b" : "udrl_pair_a";
        String loToken = aIsLo ? tokenA : tokenB;
        String hiToken = aIsLo ? tokenB : tokenA;

        // Cross the sets: the lo-owned document carries a route targeting the hi user and vice versa, so
        // each deletion's route-document is the OTHER user's owned document.
        startUserRoute(adminToken, "UdrlPairToHi", hiUser, loDoc);
        startUserRoute(adminToken, "UdrlPairToLo", loUser, hiDoc);

        long deadlocksBefore = databaseDeadlockCount();

        AtomicReference<Integer> loStatus = new AtomicReference<>();
        AtomicReference<Integer> hiStatus = new AtomicReference<>();
        Thread loDelete = new Thread(() -> loStatus.set(target().path("/user").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, loToken).delete().getStatus()));
        Thread hiDelete = new Thread(() -> hiStatus.set(target().path("/user").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, hiToken).delete().getStatus()));

        Gate gate = new Gate();
        try {
            gate.lockForUpdate("T_DOCUMENT", "DOC_ID_C", hiDoc);

            loDelete.start();
            gate.awaitCondition(() -> gate.blockedSessions() >= 1,
                    "the lower-id owner's deletion to park on the gated higher-id document");

            // INVARIANT (#189): while parked on the higher-id document, the deletion ALREADY holds the
            // lower-id one — proof the union was walked in ascending document id rather than
            // route-documents-then-owned-documents. Pre-fix the lower-id document is still free here.
            Assertions.assertFalse(documentIsLockable(loDoc),
                    "the lower-id document must already be locked while the deletion is parked on the"
                            + " higher-id one (the deletion's document set must be one globally sorted"
                            + " sequence, #189)");

            // The second deletion, with the SAME (overlapping) document set, joins the queue.
            hiDelete.start();
            gate.awaitCondition(() -> gate.blockedSessions() >= 2,
                    "the second deletion to park behind the first");
        } finally {
            gate.release();
        }

        loDelete.join(JOIN_TIMEOUT_MS);
        hiDelete.join(JOIN_TIMEOUT_MS);
        Assertions.assertFalse(loDelete.isAlive(), "the lower-id owner's deletion must complete");
        Assertions.assertFalse(hiDelete.isAlive(), "the higher-id owner's deletion must complete");

        Assertions.assertEquals(deadlocksBefore, databaseDeadlockCount(),
                "two concurrent deletions with overlapping document sets must not deadlock");
        Assertions.assertEquals(Status.OK.getStatusCode(), loStatus.get().intValue(),
                "the lower-id owner's self-delete must succeed");
        Assertions.assertEquals(Status.OK.getStatusCode(), hiStatus.get().intValue(),
                "the higher-id owner's self-delete must succeed");
        Assertions.assertEquals(2L, count(
                "select count(*) from T_USER where USE_USERNAME_C in (:a, :b) and USE_DELETEDATE_D is not null",
                Map.of("a", "udrl_pair_a", "b", "udrl_pair_b")), "both accounts are soft-deleted");
        Assertions.assertEquals(0L, count(
                "select count(*) from T_ROUTE where RTE_IDDOCUMENT_C in (:a, :b) and RTE_STATUS_C = 'ACTIVE'",
                Map.of("a", loDoc, "b", hiDoc)), "both routes were cancelled by the deletions");
    }

    // ----------------------------------------------------------------------------------------------------
    // helpers
    // ----------------------------------------------------------------------------------------------------

    /** A single-VALIDATE-step model whose only step targets a USER, created as admin. */
    private String createUserValidateModel(String adminToken, String name, String targetUsername) {
        return target().path("/routemodel").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form()
                        .param("name", name)
                        .param("steps", "[{\"type\":\"VALIDATE\",\"transitions\":[{\"name\":\"VALIDATED\","
                                + "\"actions\":[]}],\"target\":{\"name\":\"" + targetUsername
                                + "\",\"type\":\"USER\"},\"name\":\"Only step\"}]")), JsonObject.class)
                .getString("id");
    }

    /** Create a USER-targeted model and start it on a document, as admin. */
    private void startUserRoute(String adminToken, String modelName, String targetUsername, String documentId) {
        String modelId = createUserValidateModel(adminToken, modelName, targetUsername);
        Response response = target().path("/route/start").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form()
                        .param("documentId", documentId)
                        .param("routeModelId", modelId)));
        Assertions.assertEquals(Status.OK.getStatusCode(), response.getStatus(),
                "the route start must succeed while its USER target is active");
    }

    /** Assert a start was rejected with the typed invalid-target error. */
    private void assertFailsClosedWithInvalidTarget(int status, String body, String message) {
        Assertions.assertEquals(Status.BAD_REQUEST.getStatusCode(), status,
                message + " (status was " + status + ", body " + body + ")");
        try (JsonReader reader = Json.createReader(new StringReader(body))) {
            Assertions.assertEquals("InvalidRouteModel", reader.readObject().getString("type"), message);
        }
    }

    /** Links a tag to a document by inserting a T_DOCUMENT_TAG row directly (committed). */
    private void linkTag(String documentId, String tagId) {
        executeSql("insert into T_DOCUMENT_TAG (DOT_ID_C, DOT_IDDOCUMENT_C, DOT_IDTAG_C) values (:id, :doc, :tag)",
                Map.of("id", UUID.randomUUID().toString(), "doc", documentId, "tag", tagId));
    }

    /** The database's cumulative deadlock counter — an engine-level, winner-agnostic deadlock probe. */
    private long databaseDeadlockCount() {
        return count("select deadlocks from pg_stat_database where datname = current_database()", Map.of());
    }

    /**
     * True when the document row is currently lockable (FOR UPDATE acquired within a short timeout) —
     * i.e. NOT held by another transaction.
     */
    private boolean documentIsLockable(String documentId) {
        EntityManager em = EMF.get().createEntityManager();
        EntityTransaction tx = em.getTransaction();
        try {
            tx.begin();
            em.createNativeQuery("SET LOCAL lock_timeout = 750").executeUpdate();
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

    private static boolean isDeadlock(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof java.sql.SQLException && "40P01".equals(((java.sql.SQLException) c).getSQLState())) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param username Username
     * @param activeOnly Whether to require the account to still be active
     * @return the user id
     */
    private String userId(String username, boolean activeOnly) {
        EntityManager prev = ThreadLocalContext.get().getEntityManager();
        EntityManager em = EMF.get().createEntityManager();
        EntityTransaction tx = em.getTransaction();
        try {
            ThreadLocalContext.get().setEntityManager(em);
            tx.begin();
            Object o = em.createNativeQuery("select USE_ID_C from T_USER where USE_USERNAME_C = :name"
                            + (activeOnly ? " and USE_DELETEDATE_D is null" : ""))
                    .setParameter("name", username).getSingleResult();
            tx.commit();
            return (String) o;
        } finally {
            if (tx.isActive()) {
                tx.rollback();
            }
            em.close();
            ThreadLocalContext.get().setEntityManager(prev);
        }
    }

    private long count(String sql, Map<String, Object> params) {
        EntityManager prev = ThreadLocalContext.get().getEntityManager();
        EntityManager em = EMF.get().createEntityManager();
        EntityTransaction tx = em.getTransaction();
        try {
            ThreadLocalContext.get().setEntityManager(em);
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
            ThreadLocalContext.get().setEntityManager(prev);
        }
    }

    private void executeSql(String sql, Map<String, Object> params) {
        EntityManager prev = ThreadLocalContext.get().getEntityManager();
        EntityManager em = EMF.get().createEntityManager();
        EntityTransaction tx = em.getTransaction();
        try {
            ThreadLocalContext.get().setEntityManager(em);
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
            ThreadLocalContext.get().setEntityManager(prev);
        }
    }
}
