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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * #202 group-deletion lock-ORDER and race tests, the GROUP counterpart of
 * {@link TestUserDeleteRouteLockOrdering}.
 *
 * <p>Two invariants are pinned here:</p>
 * <ul>
 *   <li><b>Order.</b> GROUP rows are acquired in ascending group ID order by every multi-group lock
 *       site — {@code RouteResource.start}'s step targets, {@code RouteModelStepUtil.lockGroupsByName}
 *       and {@code GroupResource.delete}'s target-plus-children union (#217) alike — and groups sit
 *       between users and documents in the global order
 *       USER -&gt; GROUP -&gt; DOCUMENT -&gt; ROUTE. Ordering by NAME (what lockGroupsByName did before
 *       #202) while the start ordered by id would let the two sites walk the same pair of group rows in
 *       opposite directions and deadlock, so the ordering tests deliberately use a fixture whose name
 *       order is the REVERSE of its id order. The order also covers IMPLICIT acquisitions: the route
 *       INSERT takes FOR KEY SHARE on the initiator's user row through {@code T_ROUTE.FK_RTE_IDUSER_C},
 *       so that row is locked in the USER phase — see
 *       {@link #groupTargetedStartLocksItsInitiatorBeforeAnyGroupRow()}.</li>
 *   <li><b>Serialization, both directions.</b> A route start locks the ACTIVE row of every GROUP-typed
 *       step target FOR UPDATE before it creates the steps, and {@code GroupResource.delete} takes that
 *       same row before its route-cancel scan. So a start racing the deletion of one of its group targets
 *       either fails closed (the deletion won the row) or has its steps already persisted when the
 *       deletion's cancel scan runs (the start won it). Neither interleaving can leave an ACTIVE route
 *       with an OPEN step targeting a deleted group — the #202 defect signature, asserted directly.</li>
 * </ul>
 *
 * <p>Every test that asserts row-lock semantics is skipped on H2 (whose FOR UPDATE and
 * uncommitted-write conflict rules differ and do not model these invariants); CI exercises them in the
 * docs-web PostgreSQL job. {@link #routeStartAfterGroupDeleteFailsClosedAndCreatesNoRoute()} needs no
 * lock semantics and runs on both. Assertions are invariants (acquisition order / no stranded step /
 * fail-closed), never which racer wins.</p>
 */
public class TestGroupDeleteRouteLockOrdering extends BaseJerseyTest {

    private static final long JOIN_TIMEOUT_MS = 60_000;
    private static final long AWAIT_TIMEOUT_MS = 20_000;

    /**
     * A test-held transaction on its own EntityManager, plus a dialect-aware observer for sessions parked
     * on locks it (transitively) blocks. Mirrors {@code TestUserDeleteRouteLockOrdering.Gate}. Always
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

    /**
     * A pair of groups whose NAME order is the reverse of their ID order: {@link #nameFirst} sorts first
     * alphabetically but carries the HIGHER id, so a name-ordered acquisition and an id-ordered one walk
     * the pair in opposite directions. That inversion is what makes the ordering tests discriminating.
     */
    private static final class InvertedGroupPair {
        private final String nameFirst;
        private final String nameFirstId;
        private final String nameSecond;
        private final String nameSecondId;

        private InvertedGroupPair(String nameFirst, String nameFirstId, String nameSecond, String nameSecondId) {
            this.nameFirst = nameFirst;
            this.nameFirstId = nameFirstId;
            this.nameSecond = nameSecond;
            this.nameSecondId = nameSecondId;
        }

        /** The row an ID-ordered acquisition takes FIRST (the lower id — the name-SECOND group). */
        String lowerId() {
            return nameSecondId;
        }

        /** The row an ID-ordered acquisition takes LAST (the higher id — the name-FIRST group). */
        String higherId() {
            return nameFirstId;
        }
    }

    /**
     * Two groups that are each other's PARENT (#217), keyed by the id order an ascending acquisition
     * must follow — NOT by name, which is irrelevant to this fixture.
     */
    private static final class MutualParentPair {
        private final String lowerIdName;
        private final String lowerId;
        private final String higherIdName;
        private final String higherId;

        private MutualParentPair(String lowerIdName, String lowerId, String higherIdName, String higherId) {
            this.lowerIdName = lowerIdName;
            this.lowerId = lowerId;
            this.higherIdName = higherIdName;
            this.higherId = higherId;
        }
    }

    // ----------------------------------------------------------------------------------------------------
    // (i) ascending-group-id acquisition order, at both multi-group lock sites
    // ----------------------------------------------------------------------------------------------------

    /**
     * {@code POST /route/start} locks its GROUP-typed step targets in ascending group ID order — not in
     * step order and not in name order. Deterministic probe: gate the HIGHER-id group row, run a start
     * whose model names the higher-id group in its FIRST step, and observe that while the start is parked
     * on the gated row it ALREADY holds the lower-id one.
     *
     * <p>Against the pre-#202 code the start takes no group lock at all, so nothing ever parks on the
     * gated row and the await fails; against a step-ordered or name-ordered variant the lower-id row is
     * still free at that point and the lockability assertion fails.</p>
     */
    @Test
    public void routeStartLocksGroupTargetsInAscendingIdOrderNotNameOrder() throws Exception {
        Assumptions.assumeTrue(EMF.isDriverPostgresql(),
                "asserts PostgreSQL row-lock ordering semantics — H2 cannot model them");
        String adminToken = adminToken();
        InvertedGroupPair pair = createInvertedGroupPair("gdrlstart");

        // The model names the higher-id group FIRST, so step order disagrees with id order too.
        String modelId = createModel(adminToken, "GdrlStartOrderModel",
                step("First", pair.nameFirst, "GROUP") + "," + step("Second", pair.nameSecond, "GROUP"));
        String documentId = clientUtil.createDocument(adminToken);

        AtomicReference<Integer> startStatus = new AtomicReference<>();
        Thread startThread = new Thread(() -> startStatus.set(target().path("/route/start").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form()
                        .param("documentId", documentId)
                        .param("routeModelId", modelId))).getStatus()));

        Gate gate = new Gate();
        try {
            gate.lockForUpdate("T_GROUP", "GRP_ID_C", pair.higherId());

            startThread.start();
            gate.awaitCondition(() -> gate.blockedSessions() >= 1,
                    "the route start to park on the gated (higher-id) group row");

            // INVARIANT (#202): parked on the higher-id group, the start ALREADY holds the lower-id one.
            Assertions.assertFalse(rowIsLockable("T_GROUP", "GRP_ID_C", pair.lowerId()),
                    "the lower-id group must already be locked while the start is parked on the higher-id"
                            + " one (group locks are acquired in ascending group id order, #202)");
        } finally {
            gate.release();
        }

        startThread.join(JOIN_TIMEOUT_MS);
        Assertions.assertFalse(startThread.isAlive(), "the route start must complete");
        Assertions.assertEquals(Status.OK.getStatusCode(), startStatus.get().intValue(),
                "the route start must succeed once the gate releases (both group targets are active)");
    }

    /**
     * A route-model write ({@code PUT /routemodel}, the {@code lockGroupsByName} site) locks the groups it
     * references in ascending group ID order — the SAME order the start above takes. Same deterministic
     * probe. Against the pre-#202 name-ordered implementation the writer reaches for the name-first
     * (higher-id) row immediately, holding nothing, so the lower-id row is still lockable and the
     * assertion fails — which is exactly the opposite-direction acquisition that would deadlock against a
     * concurrent start.
     */
    @Test
    public void routeModelWriteLocksGroupsInAscendingIdOrderNotNameOrder() throws Exception {
        Assumptions.assumeTrue(EMF.isDriverPostgresql(),
                "asserts PostgreSQL row-lock ordering semantics — H2 cannot model them");
        String adminToken = adminToken();
        InvertedGroupPair pair = createInvertedGroupPair("gdrlmodel");
        String steps = "[" + step("First", pair.nameFirst, "GROUP") + ","
                + step("Second", pair.nameSecond, "GROUP") + "]";

        AtomicReference<Integer> createStatus = new AtomicReference<>();
        Thread createThread = new Thread(() -> createStatus.set(target().path("/routemodel").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form()
                        .param("name", "GdrlModelOrderModel")
                        .param("steps", steps))).getStatus()));

        Gate gate = new Gate();
        try {
            gate.lockForUpdate("T_GROUP", "GRP_ID_C", pair.higherId());

            createThread.start();
            gate.awaitCondition(() -> gate.blockedSessions() >= 1,
                    "the route-model write to park on the gated (higher-id) group row");

            // INVARIANT (#202): the route-model writer walks the pair in the SAME direction as the start.
            Assertions.assertFalse(rowIsLockable("T_GROUP", "GRP_ID_C", pair.lowerId()),
                    "the lower-id group must already be locked while the route-model write is parked on"
                            + " the higher-id one (lockGroupsByName orders by group id, not by name, #202)");
        } finally {
            gate.release();
        }

        createThread.join(JOIN_TIMEOUT_MS);
        Assertions.assertFalse(createThread.isAlive(), "the route-model write must complete");
        Assertions.assertEquals(Status.OK.getStatusCode(), createStatus.get().intValue(),
                "the route-model write must succeed once the gate releases");
    }

    /**
     * USER before GROUP in the start transaction. Deterministic probe: gate the GROUP row of a model that
     * targets a user AND a group; while the start is parked there, its USER target's row must already be
     * held. Pre-#202 the start never parks on the group row at all; with the two acquisitions swapped the
     * user row would still be free.
     */
    @Test
    public void routeStartLocksItsUserTargetBeforeItsGroupTarget() throws Exception {
        Assumptions.assumeTrue(EMF.isDriverPostgresql(),
                "asserts PostgreSQL row-lock ordering semantics — H2 cannot model them");
        String adminToken = adminToken();
        clientUtil.createUser("gdrl_ug_target");
        clientUtil.createGroup("gdrlugtarget");
        String groupId = groupId("gdrlugtarget");
        String targetUserId = userId("gdrl_ug_target");

        String modelId = createModel(adminToken, "GdrlUserGroupModel",
                step("User step", "gdrl_ug_target", "USER") + "," + step("Group step", "gdrlugtarget", "GROUP"));
        String documentId = clientUtil.createDocument(adminToken);

        AtomicReference<Integer> startStatus = new AtomicReference<>();
        Thread startThread = new Thread(() -> startStatus.set(target().path("/route/start").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form()
                        .param("documentId", documentId)
                        .param("routeModelId", modelId))).getStatus()));

        Gate gate = new Gate();
        try {
            gate.lockForUpdate("T_GROUP", "GRP_ID_C", groupId);

            startThread.start();
            gate.awaitCondition(() -> gate.blockedSessions() >= 1,
                    "the route start to park on the gated group row");

            // INVARIANT (#202): USER -> GROUP. Parked on the group, the start already holds the user row.
            Assertions.assertFalse(rowIsLockable("T_USER", "USE_ID_C", targetUserId),
                    "the USER step target's row must already be locked while the start is parked on its"
                            + " GROUP target (global order USER -> GROUP -> DOCUMENT)");
        } finally {
            gate.release();
        }

        startThread.join(JOIN_TIMEOUT_MS);
        Assertions.assertFalse(startThread.isAlive(), "the route start must complete");
        Assertions.assertEquals(Status.OK.getStatusCode(), startStatus.get().intValue(),
                "the route start must succeed once the gate releases");
    }

    /**
     * The route INITIATOR's row is acquired in the USER phase, BEFORE any group row — because
     * {@code T_ROUTE.FK_RTE_IDUSER_C} makes the route INSERT acquire it implicitly (FOR KEY SHARE)
     * otherwise, i.e. AFTER the GROUP phase, which is the one place a start reached for a USER row out of
     * order. The realizable cycle is against a KEY-strength holder of that row — the batch purge
     * ({@code AppResource.batchCleanStorage}) hard-deletes soft-deleted users and then soft-deleted groups
     * in one transaction, so it holds a user row and waits for a group row while a pre-fix start holds the
     * group row and waits for that user's key share.
     *
     * <p>What this test pins is the ORDER, which is what makes such a cycle impossible, rather than one
     * scenario's deadlock: gate the initiator's row, let a second start (B, whose step targets are USER X
     * and the same group) park on it, then run A (group-targeted, initiated by X) and assert that while A
     * is parked the GROUP row is still FREE — A has not taken a group lock ahead of the user row it will
     * need. Against the pre-fix build this assertion fails (A holds the group and is parked at its INSERT
     * on the user row's key share).</p>
     *
     * <p>Deliberately NOT asserted here: a deadlock between A and B. Hibernate's {@code PESSIMISTIC_WRITE}
     * emits {@code FOR NO KEY UPDATE} on PostgreSQL, which is compatible with the foreign key's
     * {@code FOR KEY SHARE}, so two starts never conflict on that row — measured on the pre-fix build
     * (both 200, zero engine deadlocks). The deadlock-counter assertion below is a regression guard only.</p>
     */
    @Test
    public void groupTargetedStartLocksItsInitiatorBeforeAnyGroupRow() throws Exception {
        Assumptions.assumeTrue(EMF.isDriverPostgresql(),
                "asserts PostgreSQL row-lock ordering and FK key-share semantics — H2 cannot model them");
        String adminToken = adminToken();
        clientUtil.createUser("gdrl_init_x");
        String initiatorToken = clientUtil.login("gdrl_init_x");
        String initiatorId = userId("gdrl_init_x");
        clientUtil.createGroup("gdrlinitgrp");
        String groupId = groupId("gdrlinitgrp");

        // A: group-only model, started BY X (so X is the route's initiator). X needs READ on the model.
        String modelA = createModel(adminToken, "GdrlInitModelA", step("Only step", "gdrlinitgrp", "GROUP"));
        grantRead(adminToken, modelA, "gdrl_init_x");
        String docA = clientUtil.createDocument(initiatorToken);

        // B: targets USER X and the SAME group, started by admin — it holds X FOR UPDATE, then wants the group.
        String modelB = createModel(adminToken, "GdrlInitModelB",
                step("User step", "gdrl_init_x", "USER") + "," + step("Group step", "gdrlinitgrp", "GROUP"));
        String docB = clientUtil.createDocument(adminToken);

        long deadlocksBefore = databaseDeadlockCount();

        AtomicReference<Integer> statusB = new AtomicReference<>();
        Thread startB = new Thread(() -> statusB.set(target().path("/route/start").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form().param("documentId", docB).param("routeModelId", modelB)))
                .getStatus()));

        AtomicReference<Integer> statusA = new AtomicReference<>();
        Thread startA = new Thread(() -> statusA.set(target().path("/route/start").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, initiatorToken)
                .post(Entity.form(new Form().param("documentId", docA).param("routeModelId", modelA)))
                .getStatus()));

        Gate gate = new Gate();
        try {
            gate.lockForUpdate("T_USER", "USE_ID_C", initiatorId);

            // B first, so it is AHEAD of A in the queue on X: post-fix that is harmless, pre-fix it is the
            // arrangement that closes the cycle (B gets X, wants the group A holds; A wants X's key share).
            startB.start();
            gate.awaitCondition(() -> gate.blockedSessions() >= 1,
                    "start B to park on the gated user row (its USER step target)");

            startA.start();
            gate.awaitCondition(() -> gate.blockedSessions() >= 2,
                    "start A to park on the gated user row (its INITIATOR row)");

            // INVARIANT (#202): A parks on the USER row holding NO group lock. Pre-fix it reaches the group
            // first and only meets the user row at its route INSERT (FK key share) — the cycle.
            Assertions.assertTrue(rowIsLockable("T_GROUP", "GRP_ID_C", groupId),
                    "a group-targeted start must acquire its INITIATOR's row before any group row"
                            + " (T_ROUTE's FK to the initiator is otherwise taken after the group, #202)");
        } finally {
            gate.release();
        }

        startB.join(JOIN_TIMEOUT_MS);
        startA.join(JOIN_TIMEOUT_MS);
        Assertions.assertFalse(startB.isAlive(), "start B must complete");
        Assertions.assertFalse(startA.isAlive(), "start A must complete");

        Assertions.assertEquals(deadlocksBefore, databaseDeadlockCount(),
                "the two starts must not deadlock (initiator key-share vs group lock)");
        Assertions.assertEquals(Status.OK.getStatusCode(), statusB.get().intValue(), "start B must succeed");
        Assertions.assertEquals(Status.OK.getStatusCode(), statusA.get().intValue(), "start A must succeed");
        Assertions.assertEquals(1L, count("select count(*) from T_ROUTE where RTE_IDDOCUMENT_C = :v",
                Map.of("v", docA)), "start A created its route");
        Assertions.assertEquals(1L, count("select count(*) from T_ROUTE where RTE_IDDOCUMENT_C = :v",
                Map.of("v", docB)), "start B created its route");
    }

    // ----------------------------------------------------------------------------------------------------
    // (ii) create-during-delete: a start racing the deletion of one of its GROUP targets
    // ----------------------------------------------------------------------------------------------------

    /**
     * A {@code POST /route/start} whose model targets a group being deleted PARKS on that group's row lock
     * (which the deletion holds to commit) and, once the deletion commits, FAILS CLOSED: the target no
     * longer resolves to an active group, so no route and no step are created.
     *
     * <p>The deletion is held mid-transaction by gating the document of a PRE-EXISTING active route it
     * must cancel — so it is parked with the group row already held, exactly the window in which the
     * defect fired: pre-#202 the start took no group lock, sailed past the deletion's already-executed
     * cancel scan, and committed an ACTIVE route whose open step targeted a group that no longer existed.
     * Against the pre-fix code the "start parks on the group row" await is what fails first; the stranded
     * step assertion at the end fails too.</p>
     */
    @Test
    public void routeStartRacingGroupDeleteParksOnGroupRowAndFailsClosed() throws Exception {
        Assumptions.assumeTrue(EMF.isDriverPostgresql(),
                "asserts PostgreSQL row-lock wait semantics — H2 cannot model them");
        String adminToken = adminToken();
        clientUtil.createGroup("gdrlracedel");
        String groupId = groupId("gdrlracedel");

        String modelId = createModel(adminToken, "GdrlRaceDelModel", step("Only step", "gdrlracedel", "GROUP"));

        // A pre-existing ACTIVE route targeting the group: its document is the row the deletion parks on.
        String gatedDoc = clientUtil.createDocument(adminToken);
        startRoute(adminToken, modelId, gatedDoc);

        // The document the racing start targets.
        String racingDoc = clientUtil.createDocument(adminToken);

        AtomicReference<Integer> deleteStatus = new AtomicReference<>();
        Thread deleteThread = new Thread(() -> deleteStatus.set(target().path("/group/gdrlracedel").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete().getStatus()));

        AtomicReference<Integer> startStatus = new AtomicReference<>();
        AtomicReference<String> startBody = new AtomicReference<>();
        Thread startThread = new Thread(() -> {
            Response r = target().path("/route/start").request()
                    .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                    .post(Entity.form(new Form()
                            .param("documentId", racingDoc)
                            .param("routeModelId", modelId)));
            startStatus.set(r.getStatus());
            startBody.set(r.readEntity(String.class));
        });

        Gate gate = new Gate();
        try {
            // Park the deletion AFTER it has taken the group row lock: it holds that row while it waits
            // for the document of the route it is cancelling.
            gate.lockForUpdate("T_DOCUMENT", "DOC_ID_C", gatedDoc);

            deleteThread.start();
            gate.awaitCondition(() -> gate.blockedSessions() >= 1,
                    "the group deletion to park on the gated document of the route it cancels");

            startThread.start();
            // INVARIANT: the start does not race past the deletion — it parks, transitively behind this
            // gate, on the group row lock the deletion holds.
            gate.awaitCondition(() -> gate.blockedSessions() >= 2,
                    "the route start to park on the group row lock held by the deletion");
        } finally {
            gate.release();
        }

        deleteThread.join(JOIN_TIMEOUT_MS);
        startThread.join(JOIN_TIMEOUT_MS);
        Assertions.assertFalse(deleteThread.isAlive(), "the group deletion must complete");
        Assertions.assertFalse(startThread.isAlive(), "the route start must complete");

        Assertions.assertEquals(Status.OK.getStatusCode(), deleteStatus.get().intValue(),
                "the group deletion must succeed");
        assertFailsClosedWithInvalidTarget(startStatus.get(), startBody.get(),
                "a route start whose GROUP target was deleted under it must fail closed");

        Assertions.assertEquals(1L, count(
                "select count(*) from T_GROUP where GRP_ID_C = :v and GRP_DELETEDATE_D is not null",
                Map.of("v", groupId)), "the group is soft-deleted");
        Assertions.assertEquals(0L, count("select count(*) from T_ROUTE where RTE_IDDOCUMENT_C = :v",
                Map.of("v", racingDoc)), "no route may be created for a start that failed closed");
        Assertions.assertEquals(0L, openStepsTargetingActiveRoutes(groupId),
                "no ACTIVE route may keep an OPEN step targeting the deleted group (#202)");
    }

    // ----------------------------------------------------------------------------------------------------
    // (iii) delete-during-create: a group deletion racing an in-flight start that targets it
    // ----------------------------------------------------------------------------------------------------

    /**
     * The mirror direction: the START wins the group row. {@code DELETE /group/:name} then PARKS on that
     * row instead of scanning, so its route-cancel scan runs strictly AFTER the start's steps are
     * committed — and the deletion cancels the just-created route instead of stranding it.
     *
     * <p>Deterministic interleaving: gate the document the start locks AFTER its group targets, so the
     * start is parked with the group row held; the deletion then joins the queue behind it. Against the
     * pre-#202 code the deletion reads the group unlocked, never parks (the await fails first), commits
     * the soft-delete while the start is still parked, and the start then commits an ACTIVE route whose
     * open step targets the deleted group — the final assertion here.</p>
     */
    @Test
    public void groupDeleteRacingRouteStartParksOnGroupRowAndCancelsTheNewRoute() throws Exception {
        Assumptions.assumeTrue(EMF.isDriverPostgresql(),
                "asserts PostgreSQL row-lock wait semantics — H2 cannot model them");
        String adminToken = adminToken();
        clientUtil.createGroup("gdrlracestart");
        String groupId = groupId("gdrlracestart");

        String modelId = createModel(adminToken, "GdrlRaceStartModel", step("Only step", "gdrlracestart", "GROUP"));
        String racingDoc = clientUtil.createDocument(adminToken);

        AtomicReference<Integer> startStatus = new AtomicReference<>();
        Thread startThread = new Thread(() -> startStatus.set(target().path("/route/start").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form()
                        .param("documentId", racingDoc)
                        .param("routeModelId", modelId))).getStatus()));

        AtomicReference<Integer> deleteStatus = new AtomicReference<>();
        Thread deleteThread = new Thread(() -> deleteStatus.set(target().path("/group/gdrlracestart").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete().getStatus()));

        Gate gate = new Gate();
        try {
            // Park the start AFTER its group lock: the document lock is the acquisition that follows it.
            gate.lockForUpdate("T_DOCUMENT", "DOC_ID_C", racingDoc);

            startThread.start();
            gate.awaitCondition(() -> gate.blockedSessions() >= 1,
                    "the route start to park on the gated document (its group target already locked)");

            deleteThread.start();
            // INVARIANT: the deletion cannot run its cancel scan while a start holding the group row is
            // in flight — it parks, transitively behind this gate, on that row.
            gate.awaitCondition(() -> gate.blockedSessions() >= 2,
                    "the group deletion to park on the group row lock held by the in-flight start");
        } finally {
            gate.release();
        }

        startThread.join(JOIN_TIMEOUT_MS);
        deleteThread.join(JOIN_TIMEOUT_MS);
        Assertions.assertFalse(startThread.isAlive(), "the route start must complete");
        Assertions.assertFalse(deleteThread.isAlive(), "the group deletion must complete");

        Assertions.assertEquals(Status.OK.getStatusCode(), startStatus.get().intValue(),
                "the route start must succeed: it won the group row while the group was still active");
        Assertions.assertEquals(Status.OK.getStatusCode(), deleteStatus.get().intValue(),
                "the group deletion must succeed");

        Assertions.assertEquals(1L, count(
                "select count(*) from T_GROUP where GRP_ID_C = :v and GRP_DELETEDATE_D is not null",
                Map.of("v", groupId)), "the group is soft-deleted");
        Assertions.assertEquals(1L, count(
                "select count(*) from T_ROUTE where RTE_IDDOCUMENT_C = :v and RTE_STATUS_C = 'CANCELLED'",
                Map.of("v", racingDoc)),
                "the deletion's cancel scan ran after the start committed, so it cancelled the new route");
        Assertions.assertEquals(0L, openStepsTargetingActiveRoutes(groupId),
                "no ACTIVE route may keep an OPEN step targeting the deleted group (#202)");
        Assertions.assertEquals(0L, count(
                "select count(*) from T_ACL where ACL_SOURCEID_C = :v and ACL_TYPE_C = 'ROUTING'"
                        + " and ACL_DELETEDATE_D is null", Map.of("v", racingDoc)),
                "the cancelled route leaves no ROUTING grant on its document");
    }

    // ----------------------------------------------------------------------------------------------------
    // (iv) no-contention arm (dialect-independent)
    // ----------------------------------------------------------------------------------------------------

    /**
     * No-contention arm, asserting no lock semantics and therefore running on H2 as well: once the
     * deletion has COMMITTED, a start of the same model fails closed at target resolution and creates
     * nothing.
     */
    @Test
    public void routeStartAfterGroupDeleteFailsClosedAndCreatesNoRoute() {
        String adminToken = adminToken();
        clientUtil.createGroup("gdrlafterdel");
        String modelId = createModel(adminToken, "GdrlAfterDelModel", step("Only step", "gdrlafterdel", "GROUP"));
        String documentId = clientUtil.createDocument(adminToken);

        Response deleteResponse = target().path("/group/gdrlafterdel").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete();
        Assertions.assertEquals(Status.OK.getStatusCode(), deleteResponse.getStatus(),
                "the group deletion must succeed");

        Response r = target().path("/route/start").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form()
                        .param("documentId", documentId)
                        .param("routeModelId", modelId)));
        assertFailsClosedWithInvalidTarget(r.getStatus(), r.readEntity(String.class),
                "a route start whose GROUP target is already deleted must fail closed");
        Assertions.assertEquals(0L, count("select count(*) from T_ROUTE where RTE_IDDOCUMENT_C = :v",
                Map.of("v", documentId)), "no route may be created for a start that failed closed");
    }

    // ----------------------------------------------------------------------------------------------------
    // (v) delete-vs-delete: groups that are each other's parent (#217)
    // ----------------------------------------------------------------------------------------------------

    /**
     * {@code DELETE /group/:name} acquires every GROUP row it writes — the target AND its active children,
     * whose rows the child-detach UPDATE sets {@code parentId = null} on — in ascending group id order,
     * before it writes any of them (#217).
     *
     * <p>Deterministic probe, the same shape as the start/route-model ordering tests above: build a
     * mutual-parent pair, gate the HIGHER-id row, and delete the HIGHER-id group. While the deletion is
     * parked on the gated target row its child — the LOWER-id group — must ALREADY be held, because
     * ascending order puts the child first.</p>
     *
     * <p>Against the pre-#217 code the deletion locks its target by NAME first and reaches the child only
     * later, inside {@code GroupDao.delete}: it parks on the gated row holding no other group row at all,
     * so the lower-id row is still free and this assertion fails. That is precisely the descending
     * acquisition that deadlocks against the mirror-image deletion of the other group.</p>
     */
    @Test
    public void groupDeleteLocksItsChildrenAndTargetInAscendingIdOrder() throws Exception {
        Assumptions.assumeTrue(EMF.isDriverPostgresql(),
                "asserts PostgreSQL row-lock ordering semantics — H2 cannot model them");
        String adminToken = adminToken();
        MutualParentPair pair = createMutualParentPair("gdrlmutorder");

        AtomicReference<Integer> deleteStatus = new AtomicReference<>();
        Thread deleteThread = new Thread(() -> deleteStatus.set(target().path("/group/" + pair.higherIdName)
                .request().cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete().getStatus()));

        Gate gate = new Gate();
        try {
            gate.lockForUpdate("T_GROUP", "GRP_ID_C", pair.higherId);

            deleteThread.start();
            gate.awaitCondition(() -> gate.blockedSessions() >= 1,
                    "the group deletion to park on the gated (higher-id) target row");

            // INVARIANT (#217): parked on the higher-id target, the deletion ALREADY holds its lower-id
            // child — the whole set of group rows it writes is taken in ascending id order, up front.
            Assertions.assertFalse(rowIsLockable("T_GROUP", "GRP_ID_C", pair.lowerId),
                    "the lower-id child group must already be locked while the deletion is parked on the"
                            + " higher-id target row (the deletion acquires target + children in ascending"
                            + " group id order, #217)");
        } finally {
            gate.release();
        }

        deleteThread.join(JOIN_TIMEOUT_MS);
        Assertions.assertFalse(deleteThread.isAlive(), "the group deletion must complete");
        Assertions.assertEquals(Status.OK.getStatusCode(), deleteStatus.get().intValue(),
                "the group deletion must succeed once the gate releases");
        Assertions.assertEquals(1L, count(
                "select count(*) from T_GROUP where GRP_ID_C = :v and GRP_DELETEDATE_D is not null",
                Map.of("v", pair.higherId)), "the target group is soft-deleted");
        Assertions.assertEquals(1L, count(
                "select count(*) from T_GROUP where GRP_ID_C = :v and GRP_IDPARENT_C is null"
                        + " and GRP_DELETEDATE_D is null", Map.of("v", pair.lowerId)),
                "the surviving child is detached from the deleted parent");
    }

    /**
     * The #217 defect signature itself: two groups that are each other's parent, deleted CONCURRENTLY,
     * must serialize rather than deadlock. Each deletion writes both rows, so before the fix one held its
     * own row and reached for the other's while its counterpart did the mirror image — a cycle the engine
     * can only break by aborting a transaction, surfacing as a failed request.
     *
     * <p>Deterministic interleaving instead of a repro loop: gate the LOWER-id row, then start the
     * deletion of the LOWER-id group first and the HIGHER-id one second, so both are parked and queued on
     * that one row before either can proceed. Releasing the gate then runs them back-to-back with maximal
     * overlap. Post-fix both deletions discover the SAME two ids and take the lower one first, so the
     * second simply waits for the first; pre-fix the higher-id deletion is already holding its own target
     * row when it queues here, and the cycle closes the moment the lower-id deletion is granted the row
     * and reaches for the child it must detach.</p>
     *
     * <p>Asserted as invariants, not as a winner: no engine-level deadlock, both requests succeed, both
     * groups end up soft-deleted.</p>
     */
    @Test
    public void concurrentDeletesOfMutualParentGroupsSerializeWithoutDeadlock() throws Exception {
        Assumptions.assumeTrue(EMF.isDriverPostgresql(),
                "asserts PostgreSQL row-lock wait semantics — H2 cannot model them");
        String adminToken = adminToken();
        MutualParentPair pair = createMutualParentPair("gdrlmutrace");

        long deadlocksBefore = databaseDeadlockCount();

        AtomicReference<Integer> lowerStatus = new AtomicReference<>();
        Thread deleteLower = new Thread(() -> lowerStatus.set(target().path("/group/" + pair.lowerIdName)
                .request().cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete().getStatus()));

        AtomicReference<Integer> higherStatus = new AtomicReference<>();
        Thread deleteHigher = new Thread(() -> higherStatus.set(target().path("/group/" + pair.higherIdName)
                .request().cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete().getStatus()));

        Gate gate = new Gate();
        try {
            gate.lockForUpdate("T_GROUP", "GRP_ID_C", pair.lowerId);

            deleteLower.start();
            gate.awaitCondition(() -> gate.blockedSessions() >= 1,
                    "the lower-id group's deletion to park on the gated row");

            deleteHigher.start();
            gate.awaitCondition(() -> gate.blockedSessions() >= 2,
                    "the higher-id group's deletion to park on the gated row as well");
        } finally {
            gate.release();
        }

        deleteLower.join(JOIN_TIMEOUT_MS);
        deleteHigher.join(JOIN_TIMEOUT_MS);
        Assertions.assertFalse(deleteLower.isAlive(), "the lower-id group's deletion must complete");
        Assertions.assertFalse(deleteHigher.isAlive(), "the higher-id group's deletion must complete");

        // INVARIANT (#217): mutually-parented groups deleted concurrently do not deadlock.
        Assertions.assertEquals(deadlocksBefore, databaseDeadlockCount(),
                "concurrent deletions of two groups that are each other's parent must not deadlock (#217)");
        Assertions.assertEquals(Status.OK.getStatusCode(), lowerStatus.get().intValue(),
                "the lower-id group's deletion must succeed");
        Assertions.assertEquals(Status.OK.getStatusCode(), higherStatus.get().intValue(),
                "the higher-id group's deletion must succeed");
        Assertions.assertEquals(2L, count(
                "select count(*) from T_GROUP where GRP_ID_C in (:a, :b) and GRP_DELETEDATE_D is not null",
                Map.of("a", pair.lowerId, "b", pair.higherId)), "both groups are soft-deleted");
    }

    // ----------------------------------------------------------------------------------------------------
    // helpers
    // ----------------------------------------------------------------------------------------------------

    /** One VALIDATE step of a route-model blob, targeting a principal by name. */
    private static String step(String stepName, String targetName, String targetType) {
        return "{\"type\":\"VALIDATE\",\"transitions\":[{\"name\":\"VALIDATED\",\"actions\":[]}],"
                + "\"target\":{\"name\":\"" + targetName + "\",\"type\":\"" + targetType + "\"},"
                + "\"name\":\"" + stepName + "\"}";
    }

    /** Create a route model from one or more {@link #step} fragments, as admin. */
    private String createModel(String adminToken, String name, String stepFragments) {
        return target().path("/routemodel").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form()
                        .param("name", name)
                        .param("steps", "[" + stepFragments + "]")), JsonObject.class)
                .getString("id");
    }

    /** Grant a user READ on an ACL source (here: a route model), as admin. */
    private void grantRead(String adminToken, String sourceId, String username) {
        target().path("/acl").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form()
                        .param("source", sourceId)
                        .param("perm", "READ")
                        .param("target", username)
                        .param("type", "USER")), JsonObject.class);
    }

    /** The database's cumulative deadlock counter — an engine-level, winner-agnostic deadlock probe. */
    private long databaseDeadlockCount() {
        return count("select deadlocks from pg_stat_database where datname = current_database()", Map.of());
    }

    /** Start a route and require it to succeed. */
    private void startRoute(String adminToken, String modelId, String documentId) {
        Response response = target().path("/route/start").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form()
                        .param("documentId", documentId)
                        .param("routeModelId", modelId)));
        Assertions.assertEquals(Status.OK.getStatusCode(), response.getStatus(),
                "the route start must succeed while its GROUP target is active");
    }

    /**
     * Builds the name-order/id-order inversion by CONSTRUCTION rather than by search (#221): create two
     * groups under throwaway names, read back the ids the database assigned, then assign the final names
     * INVERSELY to that observed order — the alphabetically first name goes to the higher id. The
     * inversion is therefore a property of the fixture, not of the draw.
     *
     * <p>The previous version created six groups and hunted for a pair whose random UUIDs happened to
     * sort against their names, which left a real (if small) chance of finding none — an observed flake.
     * Two groups and two renames replace it with a certainty.</p>
     *
     * @param prefix Group-name prefix (alphanumeric; the final names are {@code prefix + "a"} and
     *               {@code prefix + "b"}, which sort in that order)
     * @return the inverted pair
     */
    private InvertedGroupPair createInvertedGroupPair(String prefix) {
        String adminToken = adminToken();
        String seedA = prefix + "seeda";
        String seedB = prefix + "seedb";
        clientUtil.createGroup(seedA);
        clientUtil.createGroup(seedB);
        String seedAId = groupId(seedA);
        String seedBId = groupId(seedB);

        boolean seedAIsHigher = seedAId.compareTo(seedBId) > 0;
        String higherIdSeed = seedAIsHigher ? seedA : seedB;
        String higherId = seedAIsHigher ? seedAId : seedBId;
        String lowerIdSeed = seedAIsHigher ? seedB : seedA;
        String lowerId = seedAIsHigher ? seedBId : seedAId;

        // The name that sorts FIRST goes to the group with the HIGHER id — that is the inversion.
        String nameFirst = prefix + "a";
        String nameSecond = prefix + "b";
        updateGroup(adminToken, higherIdSeed, nameFirst, null);
        updateGroup(adminToken, lowerIdSeed, nameSecond, null);

        return new InvertedGroupPair(nameFirst, higherId, nameSecond, lowerId);
    }

    /**
     * Builds the #217 fixture: two groups that are each other's PARENT. Deleting either one writes BOTH
     * rows — its own (the soft-delete) and its child's (the parent detach) — so a deletion of each,
     * concurrently, is the pair of transactions that must not be able to take those two rows in opposite
     * directions.
     *
     * @param prefix Group-name prefix (alphanumeric)
     * @return the pair, keyed by observed id order
     */
    private MutualParentPair createMutualParentPair(String prefix) {
        String adminToken = adminToken();
        String first = prefix + "a";
        String second = prefix + "b";
        clientUtil.createGroup(first);
        clientUtil.createGroup(second, first);       // second.parent = first
        updateGroup(adminToken, first, first, second); // first.parent = second — the cycle is now closed

        String firstId = groupId(first);
        String secondId = groupId(second);
        return firstId.compareTo(secondId) < 0
                ? new MutualParentPair(first, firstId, second, secondId)
                : new MutualParentPair(second, secondId, first, firstId);
    }

    /**
     * {@code POST /group/:name} as admin, requiring success. A null {@code parentName} clears the parent
     * (the endpoint treats an absent parent as "no parent").
     *
     * @param adminToken Admin auth token
     * @param currentName The group's current name (the path segment)
     * @param newName The name to set (unchanged for a pure re-parent)
     * @param parentName The parent group's name, or null for none
     */
    private void updateGroup(String adminToken, String currentName, String newName, String parentName) {
        Form form = new Form().param("name", newName);
        if (parentName != null) {
            form.param("parent", parentName);
        }
        Response response = target().path("/group/" + currentName).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(form));
        Assertions.assertEquals(Status.OK.getStatusCode(), response.getStatus(),
                "the group update must succeed (" + currentName + " -> " + newName + ")");
    }

    /** Assert a start was rejected with the typed invalid-target error. */
    private void assertFailsClosedWithInvalidTarget(int status, String body, String message) {
        Assertions.assertEquals(Status.BAD_REQUEST.getStatusCode(), status,
                message + " (status was " + status + ", body " + body + ")");
        try (JsonReader reader = Json.createReader(new StringReader(body))) {
            Assertions.assertEquals("InvalidRouteModel", reader.readObject().getString("type"), message);
        }
    }

    /** OPEN steps of ACTIVE routes targeting the principal — the #202 stranded-step signature. */
    private long openStepsTargetingActiveRoutes(String principalId) {
        return count("select count(*) from T_ROUTE r"
                + " join T_ROUTE_STEP rs on rs.RTP_IDROUTE_C = r.RTE_ID_C"
                + " where rs.RTP_IDTARGET_C = :v and rs.RTP_ENDDATE_D is null and rs.RTP_DELETEDATE_D is null"
                + " and r.RTE_STATUS_C = 'ACTIVE' and r.RTE_DELETEDATE_D is null", Map.of("v", principalId));
    }

    /**
     * True when the row is currently lockable (FOR UPDATE acquired within a short timeout) — i.e. NOT held
     * by another transaction.
     */
    private boolean rowIsLockable(String table, String pkColumn, String id) {
        EntityManager em = EMF.get().createEntityManager();
        EntityTransaction tx = em.getTransaction();
        try {
            tx.begin();
            em.createNativeQuery("SET LOCAL lock_timeout = 750").executeUpdate();
            em.createNativeQuery("select " + pkColumn + " from " + table + " where " + pkColumn + " = :id for update")
                    .setParameter("id", id).getSingleResult();
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

    private String groupId(String groupName) {
        return single("select GRP_ID_C from T_GROUP where GRP_NAME_C = :v and GRP_DELETEDATE_D is null",
                Map.of("v", groupName));
    }

    private String userId(String username) {
        return single("select USE_ID_C from T_USER where USE_USERNAME_C = :v and USE_DELETEDATE_D is null",
                Map.of("v", username));
    }

    private String single(String sql, Map<String, Object> params) {
        return (String) runOnOwnEm(sql, params, jakarta.persistence.Query::getSingleResult);
    }

    private long count(String sql, Map<String, Object> params) {
        return ((Number) runOnOwnEm(sql, params, jakarta.persistence.Query::getSingleResult)).longValue();
    }

    /**
     * Runs a read on a dedicated EntityManager + transaction, restoring the thread-local one afterwards
     * (the Jersey test client and the app share this thread).
     */
    private Object runOnOwnEm(String sql, Map<String, Object> params,
                              java.util.function.Function<jakarta.persistence.Query, Object> action) {
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
            Object result = action.apply(q);
            tx.commit();
            return result;
        } finally {
            if (tx.isActive()) {
                tx.rollback();
            }
            em.close();
            ThreadLocalContext.get().setEntityManager(prev);
        }
    }
}
