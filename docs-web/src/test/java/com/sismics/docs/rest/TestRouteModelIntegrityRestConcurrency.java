package com.sismics.docs.rest;

import com.sismics.docs.core.constant.AclTargetType;
import com.sismics.docs.core.dao.GroupDao;
import com.sismics.docs.core.dao.RouteModelDao;
import com.sismics.docs.core.model.jpa.Group;
import com.sismics.docs.core.model.jpa.RouteModel;
import com.sismics.docs.core.util.RouteModelStepUtil;
import com.sismics.docs.core.util.SecurityUtil;
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

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * END-TO-END concurrency tests for the group-first route-model integrity protocol: unlike
 * {@link TestRouteModelIntegrityConcurrency} (which drives the protocol helpers directly), these
 * race REAL REST requests through the actual resource-layer writers, so removing the lock calls
 * from RouteModelResource's shared {@code validateAndLockForWrite} gate or from GroupResource makes
 * them fail (verified by removing the resource-layer lock calls and watching both fail). Both route-model
 * writers (create and update) route through the same shared gate; the two scenarios here pin that
 * gate from both call sites.
 *
 * <p>Coordination: the TEST thread holds a pessimistic lock (the "gate") on a strategically chosen
 * row in its own transaction while the requests are fired, and observes the requests actually
 * parking on locks via the database's lock-wait view (H2: {@code SESSIONS.BLOCKER_ID}; PostgreSQL:
 * {@code pg_blocking_pids} rooted at the gate's own backend PID) — a deterministic signal,
 * not a timing heuristic). Releasing the gate lets H2 serialize the requests on the REAL locks — or
 * lets them race, if the locks were removed, which the authoritative stored-blob assertion catches.
 */
public class TestRouteModelIntegrityRestConcurrency extends BaseJerseyTest {

    private static final long JOIN_TIMEOUT_MS = 30_000;
    private static final long AWAIT_BLOCKED_TIMEOUT_MS = 15_000;

    private static String stepsForGroup(String groupName) {
        return stepsForGroup(groupName, "Review");
    }

    /**
     * A racing update's payload MUST differ from the stored blob (different step name): Hibernate
     * dirty-checking silently skips the UPDATE for an identical value, which would make the racing
     * write a SQL-level no-op and the race unobservable.
     */
    private static String stepsForGroup(String groupName, String stepName) {
        return "[{\"type\":\"VALIDATE\",\"transitions\":[{\"name\":\"VALIDATED\",\"actions\":[]}]," +
                "\"target\":{\"name\":\"" + groupName + "\",\"type\":\"GROUP\"},\"name\":\"" + stepName + "\"}]";
    }

    /**
     * The test-held gate: its own EntityManager + transaction holding a pessimistic row lock, plus a
     * deterministic, dialect-aware observer for sessions parked on locks. Always release() in a
     * finally block.
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
         * Number of DB sessions currently parked in THIS test's lock queue. Dialect-aware (dialect
         * from {@link EMF#isDriverPostgresql()}, the hibernate.properties-driven detector production
         * DialectUtil uses): H2 exposes parked sessions via
         * {@code INFORMATION_SCHEMA.SESSIONS.BLOCKER_ID} (that in-memory DB serves only this fork,
         * so any waiter is ours); PostgreSQL via {@code pg_blocking_pids} rooted at the gate
         * connection's own backend PID — counting waiters blocked by the gate directly, plus
         * waiters blocked by those waiters (the create race's second request queues on the FIRST
         * request's lock, not the gate's). Pinning the signal to our queue keeps an unrelated
         * lock-waiting session elsewhere in the database (e.g. a background app-pool session) from
         * satisfying the barrier spuriously; {@code pg_blocking_pids} also absorbs the
         * transactionid-vs-tuple representation of row-lock waits in pg_locks.
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

        /**
         * Deterministically wait until the given condition holds (e.g. "N sessions are parked on
         * locks", or "the request already responded") — replaces fixed sleeps, so slow schedulers
         * cannot skew the interleave.
         */
        void awaitCondition(BooleanSupplier condition, String what) throws InterruptedException {
            long deadline = System.currentTimeMillis() + AWAIT_BLOCKED_TIMEOUT_MS;
            while (!condition.getAsBoolean()) {
                Assertions.assertTrue(System.currentTimeMillis() < deadline,
                        "Timed out waiting for: " + what);
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

    /**
     * UPDATE-vs-RENAME through the REST layer. Gate: the GROUP row — with the resource-layer locks
     * in place, BOTH requests park on it at their very first serialization point (the rename at
     * {@code getActiveByNameForUpdate}, the update at the shared gate's {@code lockGroupsByName})
     * BEFORE validating or writing anything; either release order keeps the committed blob naming a
     * live group. WITHOUT the locks, the update validates against the pre-rename group and its blob
     * write queues behind the rename repair's model-row lock — landing AFTER the repair and
     * committing a blob that names the vanished old group.
     */
    @Test
    public void restUpdateVsRestRename() throws Exception {
        String adminToken = adminToken();
        clientUtil.createGroup("restconvgrp");

        // A committed route model referencing the group.
        String modelId = TestUserResource.inTx(() ->
                new RouteModelDao().create(new RouteModel().setName("Rest conv model")
                        .setSteps(stepsForGroup("restconvgrp")), "admin"));

        Gate gate = new Gate();
        Group gateLocked = new GroupDao().getActiveByNameForUpdate("restconvgrp");
        Assertions.assertNotNull(gateLocked, "Gate must lock the fixture group row");

        AtomicReference<Integer> renameStatus = new AtomicReference<>();
        AtomicReference<Integer> updateStatus = new AtomicReference<>();
        AtomicReference<JsonObject> updateBody = new AtomicReference<>();
        AtomicReference<Throwable> renameError = new AtomicReference<>();
        AtomicReference<Throwable> updateError = new AtomicReference<>();

        Thread renameThread = new Thread(() -> {
            try {
                Response r = target().path("/group/restconvgrp").request()
                        .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                        .post(Entity.form(new Form().param("name", "restconvgrp2")));
                renameStatus.set(r.getStatus());
            } catch (Throwable t) {
                renameError.set(t);
            }
        });

        Thread updateThread = new Thread(() -> {
            try {
                Response r = target().path("/routemodel/" + modelId).request()
                        .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                        .post(Entity.form(new Form()
                                .param("name", "Rest conv model")
                                .param("steps", stepsForGroup("restconvgrp", "Review v2"))));
                updateStatus.set(r.getStatus());
                updateBody.set(r.readEntity(JsonObject.class));
            } catch (Throwable t) {
                updateError.set(t);
            }
        });

        try {
            renameThread.start();
            // Deterministic: the rename request is parked on a lock (the gate — or, lock-less, on
            // the gate at its rename write).
            gate.awaitCondition(() -> gate.blockedSessions() >= 1, "rename request parked on a lock");
            updateThread.start();
            // Deterministic: the update request is parked too (locked: on the gate at the shared
            // lock call; lock-less: on the rename repair's model-row lock at its write).
            gate.awaitCondition(() -> gate.blockedSessions() >= 2, "update request parked on a lock");
        } finally {
            gate.release();
        }

        renameThread.join(JOIN_TIMEOUT_MS);
        updateThread.join(JOIN_TIMEOUT_MS);
        Assertions.assertFalse(renameThread.isAlive(), "rename request must complete within the timeout");
        Assertions.assertFalse(updateThread.isAlive(), "update request must complete within the timeout");
        Assertions.assertNull(renameError.get(), "rename request failed: " + renameError.get());
        Assertions.assertNull(updateError.get(), "update request failed: " + updateError.get());

        // The rename always succeeds.
        Assertions.assertEquals(Response.Status.OK.getStatusCode(), renameStatus.get().intValue(),
                "Group rename must succeed");

        // The update either won the serialization (200, wrote pre-repair and was repaired) or lost it
        // (400 ValidationError: the group name vanished before its re-validation under the lock).
        int updSt = updateStatus.get();
        Assertions.assertTrue(updSt == Response.Status.OK.getStatusCode()
                        || updSt == Response.Status.BAD_REQUEST.getStatusCode(),
                "Update must either succeed or be rejected with a ValidationError, got " + updSt);
        if (updSt == Response.Status.BAD_REQUEST.getStatusCode()) {
            Assertions.assertEquals("ValidationError", updateBody.get().getString("type"));
        }

        assertBlobNamesLiveGroup(modelId, "restconvgrp2");
        cleanupGroup(adminToken, "restconvgrp2");
    }

    /**
     * CREATE-vs-RENAME through the REST layer — pins the shared gate from the ADD call site. Gate:
     * the fixture MODEL row — the rename acquires the group lock immediately, then its repair parks
     * on the gated model row, i.e. it is caught MID-REPAIR holding the group lock. With the shared
     * gate's locks in place, the create parks on that group lock BEFORE validating and, once the
     * rename commits, re-validates and fails closed (400) — no model is created with the dead name.
     * WITHOUT the locks, the create validates against the pre-rename group and commits a model the
     * in-flight repair (whose affected-set snapshot predates it) never sees — an orphaned blob.
     */
    @Test
    public void restCreateVsRestRename() throws Exception {
        String adminToken = adminToken();
        clientUtil.createGroup("restcvrgrp");

        // A committed route model referencing the group: gives the rename repair work to park on.
        String fixtureModelId = TestUserResource.inTx(() ->
                new RouteModelDao().create(new RouteModel().setName("Rest cvr fixture")
                        .setSteps(stepsForGroup("restcvrgrp")), "admin"));

        // GATE on the fixture MODEL row (not the group): the rename must be caught mid-repair.
        Gate gate = new Gate();
        RouteModel gateLocked = new RouteModelDao().getActiveByIdForUpdate(fixtureModelId);
        Assertions.assertNotNull(gateLocked, "Gate must lock the fixture route-model row");

        AtomicReference<Integer> renameStatus = new AtomicReference<>();
        AtomicReference<Integer> createStatus = new AtomicReference<>();
        AtomicReference<JsonObject> createBody = new AtomicReference<>();
        AtomicReference<Throwable> renameError = new AtomicReference<>();
        AtomicReference<Throwable> createError = new AtomicReference<>();

        Thread renameThread = new Thread(() -> {
            try {
                Response r = target().path("/group/restcvrgrp").request()
                        .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                        .post(Entity.form(new Form().param("name", "restcvrgrp2")));
                renameStatus.set(r.getStatus());
            } catch (Throwable t) {
                renameError.set(t);
            }
        });

        Thread createThread = new Thread(() -> {
            try {
                Response r = target().path("/routemodel").request()
                        .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                        .put(Entity.form(new Form()
                                .param("name", "Rest cvr created")
                                .param("steps", stepsForGroup("restcvrgrp"))));
                createStatus.set(r.getStatus());
                createBody.set(r.readEntity(JsonObject.class));
            } catch (Throwable t) {
                createError.set(t);
            }
        });

        try {
            renameThread.start();
            // Deterministic: the rename holds the group lock and is parked mid-repair on the gated
            // model row.
            gate.awaitCondition(() -> gate.blockedSessions() >= 1, "rename request parked mid-repair");
            createThread.start();
            // Deterministic, valid in BOTH worlds: locked, the create parks on the rename's group
            // lock (2 blocked sessions); lock-less, it sails through and responds instead.
            gate.awaitCondition(() -> gate.blockedSessions() >= 2 || createStatus.get() != null,
                    "create request parked on the group lock or completed");
        } finally {
            gate.release();
        }

        renameThread.join(JOIN_TIMEOUT_MS);
        createThread.join(JOIN_TIMEOUT_MS);
        Assertions.assertFalse(renameThread.isAlive(), "rename request must complete within the timeout");
        Assertions.assertFalse(createThread.isAlive(), "create request must complete within the timeout");
        Assertions.assertNull(renameError.get(), "rename request failed: " + renameError.get());
        Assertions.assertNull(createError.get(), "create request failed: " + createError.get());

        Assertions.assertEquals(Response.Status.OK.getStatusCode(), renameStatus.get().intValue(),
                "Group rename must succeed");

        // The create either lost the serialization (400: the group vanished before its re-validation
        // under the lock — correct protocol behavior in this interleave) or, if it somehow won, its
        // committed blob must name a live group.
        int crtSt = createStatus.get();
        Assertions.assertTrue(crtSt == Response.Status.OK.getStatusCode()
                        || crtSt == Response.Status.BAD_REQUEST.getStatusCode(),
                "Create must either succeed or be rejected with a ValidationError, got " + crtSt);
        if (crtSt == Response.Status.BAD_REQUEST.getStatusCode()) {
            Assertions.assertEquals("ValidationError", createBody.get().getString("type"));
        } else {
            assertBlobNamesLiveGroup(createBody.get().getString("id"), null);
        }

        // The fixture model was repaired to the new name regardless.
        assertBlobNamesLiveGroup(fixtureModelId, "restcvrgrp2");
        cleanupGroup(adminToken, "restcvrgrp2");
    }

    /**
     * AUTHORITATIVE integrity assertion: the committed RTM_STEPS_C blob's single GROUP target must
     * resolve to a live group; optionally also assert the exact expected name.
     */
    private void assertBlobNamesLiveGroup(String modelId, String expectedGroupName) {
        String storedBlob = TestUserResource.inTx(() -> new RouteModelDao().getActiveById(modelId).getSteps());
        List<String> groupTargets = RouteModelStepUtil.parseGroupTargetNames(storedBlob);
        Assertions.assertEquals(1, groupTargets.size(), "Blob must carry exactly one GROUP target");
        String finalGroup = groupTargets.get(0);
        String resolved = TestUserResource.inTx(() ->
                SecurityUtil.getTargetIdFromName(finalGroup, AclTargetType.GROUP));
        Assertions.assertNotNull(resolved,
                "Committed blob names group '" + finalGroup + "' which must resolve to a live group");
        if (expectedGroupName != null) {
            Assertions.assertEquals(expectedGroupName, finalGroup,
                    "Correct serialization always leaves the blob naming the renamed group");
        }
    }

    /**
     * Delete a group so its lingering presence does not pollute the shared-DB group count asserted
     * by testGroupResource (the H2 mem DB persists across tests in the JVM).
     */
    private void cleanupGroup(String adminToken, String groupName) {
        target().path("/group/" + groupName).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete();
    }
}
