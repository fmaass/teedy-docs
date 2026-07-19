package com.sismics.docs.rest;

import com.sismics.docs.core.constant.AclType;
import com.sismics.docs.core.constant.PermType;
import com.sismics.docs.core.dao.AclDao;
import com.sismics.docs.core.dao.TagDao;
import com.sismics.docs.core.dao.UserDao;
import com.sismics.docs.core.model.jpa.Acl;
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

import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * END-TO-END lock-ordering tests driving the REAL REST endpoints, with the test thread holding a pessimistic
 * "gate" lock and observing the request park on it via the database's own lock-wait view (deterministic, not
 * a timing heuristic). Mirrors {@link TestDocumentRestoreOwnershipRace}'s harness.
 *
 * <ul>
 *   <li><b>FIX 2 (USER before DOCUMENT):</b> the admin reassign-delete must acquire the departing+target
 *       USER row locks BEFORE it cancels routes (which lock each route's DOCUMENT row). With the gate holding
 *       the target user row, the request parks on that user row and the route DOCUMENT is still free — proven
 *       by a short-timeout probe that acquires it. If the route cancellation ran first (the pre-fix
 *       DOCUMENT->USER order that deadlocks against self-delete's USER->DOCUMENT), the probe would find the
 *       document already locked and the assertion fails.</li>
 *   <li><b>#121 (grant serialization at the real endpoint):</b> with the gate holding the tag row, a real
 *       {@code PUT /acl} grant parks on the tag row (proving {@code AclResource.add} takes the source lock);
 *       the gate then commits an identical grant and releases, and the parked request re-reads it and skips
 *       its insert — exactly one row. Removing the source lock from the endpoint makes the request never
 *       park, so the barrier times out.</li>
 * </ul>
 * Runs on H2 and on real PostgreSQL.
 */
public class TestUserDeleteAndGrantLockOrdering extends BaseJerseyTest {

    private static final long JOIN_TIMEOUT_MS = 30_000;
    private static final long AWAIT_BLOCKED_TIMEOUT_MS = 15_000;

    /**
     * The test-held gate: its own EntityManager + transaction holding a pessimistic row lock, plus a
     * dialect-aware observer for sessions parked on locks. Copied in spirit from
     * {@link TestDocumentRestoreOwnershipRace}.
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
            long deadline = System.currentTimeMillis() + AWAIT_BLOCKED_TIMEOUT_MS;
            while (!condition.getAsBoolean()) {
                Assertions.assertTrue(System.currentTimeMillis() < deadline, "Timed out waiting for: " + what);
                Thread.sleep(25);
            }
        }

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

    /**
     * FIX 2: the admin reassign-delete acquires the USER row locks before it locks the route DOCUMENT.
     */
    @Test
    public void adminReassignDeleteAcquiresUserLocksBeforeLockingRouteDocument() throws Exception {
        clientUtil.createUser("lockord_departing");
        clientUtil.createUser("lockord_target");
        clientUtil.createUser("lockord_docowner");
        String ownerToken = clientUtil.login("lockord_docowner");
        String departingId = userId("lockord_departing");
        String targetId = userId("lockord_target");

        // A document owned by a surviving third user, carrying an active route whose OPEN step targets the
        // departing user — so the admin-delete's cancelRoutesTargetingPrincipal(departing) locks THIS document.
        String docId = clientUtil.createDocument(ownerToken);
        String routeId = UUID.randomUUID().toString();
        String ownerId = userId("lockord_docowner");
        executeSql("insert into T_ROUTE (RTE_ID_C, RTE_IDDOCUMENT_C, RTE_NAME_C, RTE_STATUS_C, RTE_IDUSER_C, RTE_CREATEDATE_D)"
                + " values (:id, :doc, :name, 'ACTIVE', :user, :now)",
                Map.of("id", routeId, "doc", docId, "name", "R", "user", ownerId, "now", new Date()));
        executeSql("insert into T_ROUTE_STEP (RTP_ID_C, RTP_IDROUTE_C, RTP_NAME_C, RTP_TYPE_C, RTP_IDTARGET_C, RTP_IDVALIDATORUSER_C, RTP_ORDER_N, RTP_CREATEDATE_D)"
                + " values (:id, :route, :name, 'VALIDATE', :target, :validator, 0, :now)",
                Map.of("id", UUID.randomUUID().toString(), "route", routeId, "name", "S",
                        "target", departingId, "validator", departingId, "now", new Date()));

        AtomicReference<Integer> deleteStatus = new AtomicReference<>();
        AtomicReference<Throwable> deleteError = new AtomicReference<>();
        Thread deleteThread = new Thread(() -> {
            try {
                Response r = target().path("/user/lockord_departing")
                        .queryParam("reassign_to_username", "lockord_target")
                        .request()
                        .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken())
                        .delete();
                deleteStatus.set(r.getStatus());
            } catch (Throwable t) {
                deleteError.set(t);
            }
        });

        Gate gate = new Gate();
        // Gate holds the reassignment TARGET user row locked. With FIX 2 the admin-delete locks the user rows
        // first, so it parks HERE — before it would touch the route document.
        Assertions.assertNotNull(new UserDao().getActiveByIdForUpdate(targetId), "gate must lock the target user row");
        try {
            deleteThread.start();
            gate.awaitCondition(() -> gate.blockedSessions() >= 1, "admin-delete parked on the target user row");

            // While the admin-delete is parked on the user row, the route document must still be FREE — proof
            // that USER was acquired before DOCUMENT. Pre-fix (route cancellation first) the document would
            // already be locked and this probe would time out (returning false) → assertion fails.
            Assertions.assertTrue(documentIsLockable(docId),
                    "the route document must NOT be locked while the admin-delete is parked on the user row"
                    + " (admin path must acquire USER before DOCUMENT)");
        } finally {
            gate.release();
        }

        deleteThread.join(JOIN_TIMEOUT_MS);
        Assertions.assertFalse(deleteThread.isAlive(), "the admin-delete request must complete");
        Assertions.assertNull(deleteError.get(), "the admin-delete request failed: " + deleteError.get());
        Assertions.assertEquals(Response.Status.OK.getStatusCode(), deleteStatus.get().intValue(),
                "the admin reassign-delete must succeed once the gate releases the user row");

        // Corroboration: the departing user is soft-deleted and its targeting route was cancelled.
        Assertions.assertEquals(1L, readCount(
                "select count(*) from T_USER where USE_ID_C = :v and USE_DELETEDATE_D is not null", departingId),
                "the departing user is soft-deleted");
        Assertions.assertEquals(1L, readCount(
                "select count(*) from T_ROUTE where RTE_ID_C = :v and RTE_STATUS_C = 'CANCELLED'", routeId),
                "the route targeting the departing user was cancelled");
    }

    /**
     * #121 at the real endpoint: a duplicate grant parks on the source (tag) row, then skips its insert once
     * the committed grant becomes visible — exactly one row.
     */
    @Test
    public void concurrentGrantOnATagEndpointSerializesToOneRow() throws Exception {
        clientUtil.createUser("grantord_owner");
        clientUtil.createUser("grantord_target");
        String ownerToken = clientUtil.login("grantord_owner");
        String targetId = userId("grantord_target");

        // Owner creates a tag (creator gets READ+WRITE), then will grant WRITE to the target.
        String tagId = target().path("/tag").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, ownerToken)
                .put(Entity.form(new Form().param("name", "GrantOrd").param("color", "#ff0000")), JsonObject.class)
                .getString("id");

        AtomicReference<Integer> grantStatus = new AtomicReference<>();
        AtomicReference<Throwable> grantError = new AtomicReference<>();
        Thread grantThread = new Thread(() -> {
            try {
                Response r = target().path("/acl").request()
                        .cookie(TokenBasedSecurityFilter.COOKIE_NAME, ownerToken)
                        .put(Entity.form(new Form()
                                .param("source", tagId)
                                .param("perm", "WRITE")
                                .param("target", "grantord_target")
                                .param("type", "USER")));
                grantStatus.set(r.getStatus());
            } catch (Throwable t) {
                grantError.set(t);
            }
        });

        Gate gate = new Gate();
        // Gate holds the tag (ACL source) row locked; the real PUT /acl parks on it via lockAclSourceForGrant.
        Assertions.assertNotNull(new TagDao().getByIdForUpdate(tagId), "gate must lock the tag row");
        try {
            grantThread.start();
            gate.awaitCondition(() -> gate.blockedSessions() >= 1, "grant parked on the tag (source) row");

            // Under the held lock, commit an IDENTICAL grant (as if the racing request had committed first),
            // then release so the parked request re-reads it and skips its own insert.
            Acl acl = new Acl();
            acl.setSourceId(tagId);
            acl.setPerm(PermType.WRITE);
            acl.setType(AclType.USER);
            acl.setTargetId(targetId);
            new AclDao().create(acl, targetId);
            gate.commitAndRelease();
        } finally {
            gate.release();
        }

        grantThread.join(JOIN_TIMEOUT_MS);
        Assertions.assertFalse(grantThread.isAlive(), "the grant request must complete");
        Assertions.assertNull(grantError.get(), "the grant request failed: " + grantError.get());
        Assertions.assertEquals(Response.Status.OK.getStatusCode(), grantStatus.get().intValue(),
                "the grant request must return OK");

        Assertions.assertEquals(1L, readCount(
                "select count(*) from T_ACL where ACL_SOURCEID_C = '" + tagId + "' and ACL_TARGETID_C = :v"
                        + " and ACL_PERM_C = 'WRITE' and ACL_TYPE_C = 'USER' and ACL_DELETEDATE_D is null", targetId),
                "exactly one (tag, WRITE, target) row — the raced duplicate grant was serialized away");
    }

    // ---- helpers ----

    /**
     * True when the document row is currently lockable (FOR UPDATE acquired within a short timeout) — i.e. it
     * is NOT held by another transaction. Uses a bounded, dialect-aware lock timeout so a held row returns
     * false instead of hanging. Runs on its own EntityManager, off ThreadLocalContext.
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
            Object o = em.createNativeQuery("select USE_ID_C from T_USER where USE_USERNAME_C = :name and USE_DELETEDATE_D is null")
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

    private long readCount(String sql, String paramValue) {
        EntityManager em = EMF.get().createEntityManager();
        EntityTransaction tx = em.getTransaction();
        try {
            tx.begin();
            Object n = em.createNativeQuery(sql).setParameter("v", paramValue).getSingleResult();
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
