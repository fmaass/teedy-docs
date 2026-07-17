package com.sismics.docs.rest;

import com.sismics.docs.core.dao.DocumentDao;
import com.sismics.docs.core.dao.UserDao;
import com.sismics.docs.core.model.jpa.User;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * END-TO-END #111 concurrency tests for the document RESTORE / self-delete pair. The trash guard
 * ({@code hasActiveDocumentsSharedToOthers}) scans only ACTIVE documents, so a trashed-but-shared document
 * is invisible to a concurrent self-delete; without a shared serialization point, restore could resurrect
 * such a document — leaving it ACTIVE and directly shared under a now-deleted owner (the orphaned shared
 * document #111 forbids). Restore therefore takes the owner's (self) user row FOR UPDATE held to commit, so
 * it serializes on the REAL lock against the self-delete, which takes the same row.
 *
 * <p>Both orderings are exercised, but WITHOUT racing two waiters over a released lock (DB lock acquisition
 * among waiters is not FIFO on either engine, so a "who wins the released lock" assertion is inherently
 * non-deterministic). Instead each test parks EXACTLY ONE operation — the restore — on the owner row and
 * then makes the other operation's outcome deterministic:
 * <ul>
 *   <li>Ordering (i), self-delete wins: the restore parks on the owner-row lock (already past its own
 *       authentication and trashed-document fetch); the gate then commits the owner's soft-deletion under
 *       the held lock and releases, so the sole waiting restore resumes, re-reads the now soft-deleted owner,
 *       and aborts (404) instead of resurrecting the shared document.</li>
 *   <li>Ordering (ii), restore wins: the restore parks on the owner-row lock; the gate releases WITHOUT
 *       deleting the owner, so the sole waiting restore acquires the lock, resurrects the document, and
 *       commits (200). Only AFTER that commit is the self-delete issued, and it now sees the newly-active
 *       shared document under the #111 guard and is refused (400).</li>
 * </ul>
 * There is never more than one waiter on the owner-row lock, so there is no lock-acquisition race to resolve
 * and each outcome is deterministic.
 *
 * <p>Coordination mirrors {@link TestRouteModelIntegrityRestConcurrency}: the TEST thread holds a pessimistic
 * lock (the "gate") on the owner user row in its own transaction while the restore is fired, and observes the
 * restore actually parking on the lock via the database's lock-wait view (H2:
 * {@code INFORMATION_SCHEMA.SESSIONS.BLOCKER_ID}; PostgreSQL: {@code pg_blocking_pids} rooted at the gate's
 * own backend PID) — a deterministic signal, not a timing heuristic. Removing the restore endpoint's
 * owner-row lock makes both tests fail: the restore no longer parks on the gate, so the block barrier times
 * out — the base-red signal that the serialization is gone. Runs on H2 and on real PostgreSQL.</p>
 */
public class TestDocumentRestoreOwnershipRace extends BaseJerseyTest {

    private static final long JOIN_TIMEOUT_MS = 30_000;
    private static final long AWAIT_BLOCKED_TIMEOUT_MS = 15_000;

    /**
     * The test-held gate: its own EntityManager + transaction holding a pessimistic row lock on the owner
     * user row, plus a deterministic, dialect-aware observer for sessions parked on locks. Always release()
     * in a finally block. Copied in spirit from the route-model REST concurrency harness.
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
         * Number of DB sessions currently parked in THIS test's lock queue. H2 exposes parked sessions via
         * {@code INFORMATION_SCHEMA.SESSIONS.BLOCKER_ID} (the in-memory DB serves only this fork, so any
         * waiter is ours); PostgreSQL via {@code pg_blocking_pids} rooted at the gate connection's own
         * backend PID — counting waiters blocked by the gate directly, plus waiters blocked by those waiters
         * (the second request queues behind the FIRST request once it inherits the row lock, not the gate's).
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

        /**
         * Commit the gate transaction — persisting any mutation staged on the locked owner row (e.g. its
         * soft-deletion) — and release the row lock, so a parked waiter resumes and observes the committed
         * state. Idempotent with {@link #release()}.
         */
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

    /** Owner id of the named account, read on the test thread in its own committed transaction. */
    private String userIdOf(String username) {
        return TestUserResource.inTx(() -> new UserDao().getActiveByUsername(username).getId());
    }

    /** True when the account is active (not soft-deleted). */
    private boolean userActive(String username) {
        return TestUserResource.inTx(() -> new UserDao().getActiveByUsername(username) != null);
    }

    /** Owner id of the document if it is ACTIVE, else null (trashed or absent). */
    private String activeOwnerOf(String documentId) {
        return TestUserResource.inTx(() -> new DocumentDao().getOwnerIfActive(documentId));
    }

    /** True when the owner has an active document directly shared to another principal (#111 guard). */
    private boolean sharedToOthers(String ownerId) {
        return TestUserResource.inTx(() -> new DocumentDao().hasActiveDocumentsSharedToOthers(ownerId));
    }

    /** Owner shares a document to a target user (READ), via the real ACL endpoint. */
    private void shareToUser(String documentId, String targetUsername, String ownerToken) {
        Response r = target().path("/acl").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, ownerToken)
                .put(Entity.form(new Form()
                        .param("source", documentId)
                        .param("perm", "READ")
                        .param("target", targetUsername)
                        .param("type", "USER")));
        Assertions.assertEquals(Response.Status.OK.getStatusCode(), r.getStatus(), "share must succeed");
    }

    /** Owner trashes (soft-deletes) a document, via the real endpoint. */
    private void trash(String documentId, String ownerToken) {
        Response r = target().path("/document/" + documentId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, ownerToken)
                .delete();
        Assertions.assertEquals(Response.Status.OK.getStatusCode(), r.getStatus(), "trash must succeed");
    }

    /**
     * Ordering (i) — the self-delete acquires the owner-row lock first. The restore authenticates while the
     * owner is still active and fetches its trashed document, then parks on the owner-row lock. The gate
     * commits the owner's soft-deletion under the held lock (the self-delete that won the race, made
     * deterministic by staging it under the lock rather than racing a second request for it) and releases;
     * the sole waiting restore resumes, finds {@code lockActiveUser} returns null for the now soft-deleted
     * owner, and aborts (404) instead of resurrecting the shared document under a deleted owner.
     */
    @Test
    public void selfDeleteFirstAbortsTheWaitingRestore() throws Exception {
        clientUtil.createUser("restore_race_df_owner");
        clientUtil.createUser("restore_race_df_share");
        String ownerToken = clientUtil.login("restore_race_df_owner");

        String docId = clientUtil.createDocument(ownerToken);
        shareToUser(docId, "restore_race_df_share", ownerToken);
        trash(docId, ownerToken); // D + its ACL soft-deleted -> invisible to the self-delete guard
        String ownerId = userIdOf("restore_race_df_owner");

        AtomicReference<Integer> restoreStatus = new AtomicReference<>();
        AtomicReference<Throwable> restoreError = new AtomicReference<>();

        Thread restoreThread = new Thread(() -> {
            try {
                Response r = target().path("/document/" + docId + "/restore").request()
                        .cookie(TokenBasedSecurityFilter.COOKIE_NAME, ownerToken)
                        .post(Entity.form(new Form()));
                restoreStatus.set(r.getStatus());
            } catch (Throwable t) {
                restoreError.set(t);
            }
        });

        Gate gate = new Gate();
        User gateLocked = new UserDao().getActiveByIdForUpdate(ownerId);
        Assertions.assertNotNull(gateLocked, "Gate must lock the owner user row");
        try {
            restoreThread.start();
            // With the lock the restore parks on the owner-row lock. Without it the restore never takes the
            // lock, so this barrier times out — the failure signal when serialization is absent.
            gate.awaitCondition(() -> gate.blockedSessions() >= 1, "restore parked on the owner-row lock");
            // The self-delete that won the lock commits the owner's soft-deletion under the held lock, then
            // releases it. Staging the delete inside the gate transaction makes "the self-delete acquired
            // and committed first" deterministic — there is no second waiter racing the restore for the lock.
            gateLocked.setDeleteDate(new Date());
            gate.commitAndRelease();
        } finally {
            gate.release();
        }

        restoreThread.join(JOIN_TIMEOUT_MS);
        Assertions.assertFalse(restoreThread.isAlive(), "restore request must complete");
        Assertions.assertNull(restoreError.get(), "restore request failed: " + restoreError.get());

        Assertions.assertEquals(Response.Status.NOT_FOUND.getStatusCode(), restoreStatus.get().intValue(),
                "restore aborts non-disclosively once the owner was soft-deleted under the lock");

        Assertions.assertFalse(userActive("restore_race_df_owner"), "the owner is soft-deleted");
        Assertions.assertNull(activeOwnerOf(docId),
                "the document is NOT resurrected: no active shared document survives under the deleted owner");
    }

    /**
     * Ordering (ii) — the restore acquires the owner-row lock first. It parks on the owner-row lock; the gate
     * releases without deleting the owner, so the sole waiting restore acquires the lock, resurrects the
     * document (active + shared), and commits (200). Only AFTER that commit is the self-delete issued: it now
     * re-reads the now-active shared document under the same guard and is refused (400).
     */
    @Test
    public void restoreFirstRefusesTheWaitingSelfDelete() throws Exception {
        clientUtil.createUser("restore_race_sf_owner");
        clientUtil.createUser("restore_race_sf_share");
        String ownerToken = clientUtil.login("restore_race_sf_owner");

        String docId = clientUtil.createDocument(ownerToken);
        shareToUser(docId, "restore_race_sf_share", ownerToken);
        trash(docId, ownerToken);
        String ownerId = userIdOf("restore_race_sf_owner");

        AtomicReference<Integer> restoreStatus = new AtomicReference<>();
        AtomicReference<Throwable> restoreError = new AtomicReference<>();

        Thread restoreThread = new Thread(() -> {
            try {
                Response r = target().path("/document/" + docId + "/restore").request()
                        .cookie(TokenBasedSecurityFilter.COOKIE_NAME, ownerToken)
                        .post(Entity.form(new Form()));
                restoreStatus.set(r.getStatus());
            } catch (Throwable t) {
                restoreError.set(t);
            }
        });

        Gate gate = new Gate();
        User gateLocked = new UserDao().getActiveByIdForUpdate(ownerId);
        Assertions.assertNotNull(gateLocked, "Gate must lock the owner user row");
        try {
            restoreThread.start();
            // With the lock the restore parks on the owner-row lock. Without it the restore never takes the
            // lock, so this barrier times out — the failure signal when serialization is absent.
            gate.awaitCondition(() -> gate.blockedSessions() >= 1, "restore parked on the owner-row lock");
        } finally {
            // Release without deleting the owner: the restore is the sole waiter and deterministically wins.
            gate.release();
        }

        restoreThread.join(JOIN_TIMEOUT_MS);
        Assertions.assertFalse(restoreThread.isAlive(), "restore request must complete");
        Assertions.assertNull(restoreError.get(), "restore request failed: " + restoreError.get());
        Assertions.assertEquals(Response.Status.OK.getStatusCode(), restoreStatus.get().intValue(),
                "restore succeeds: the owner is still active when it holds the lock first");

        // The restore has committed the resurrection. A self-delete issued now (sequentially, with no race)
        // sees the newly-active shared document under the #111 guard and is refused.
        Response deleteResponse = target().path("/user").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, ownerToken).delete();
        int deleteStatus = deleteResponse.getStatus();
        JsonObject deleteBody = deleteResponse.readEntity(JsonObject.class);
        Assertions.assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), deleteStatus,
                "self-delete is refused once it sees the newly-active shared document under the guard");
        Assertions.assertEquals("SharedDocumentsError", deleteBody.getString("type"),
                "the refusal is the #111 shared-documents guard, not an unrelated error");

        Assertions.assertTrue(userActive("restore_race_sf_owner"), "the owner is NOT deleted (self-delete refused)");
        Assertions.assertEquals(ownerId, activeOwnerOf(docId), "the document is active again under its owner");
        Assertions.assertTrue(sharedToOthers(ownerId), "the resurrected document is still directly shared");
    }

    /**
     * Functional regression: the added owner-row lock does not break an ordinary restore. Owner trashes a
     * document it shares with another user, then restores it single-threaded — the restore succeeds and the
     * document comes back active and still shared.
     */
    @Test
    public void normalRestoreOfAnOwnedSharedDocumentStillWorks() {
        clientUtil.createUser("restore_race_fn_owner");
        clientUtil.createUser("restore_race_fn_share");
        String ownerToken = clientUtil.login("restore_race_fn_owner");

        String docId = clientUtil.createDocument(ownerToken);
        shareToUser(docId, "restore_race_fn_share", ownerToken);
        trash(docId, ownerToken);
        String ownerId = userIdOf("restore_race_fn_owner");
        Assertions.assertNull(activeOwnerOf(docId), "precondition: the document is trashed");

        Response r = target().path("/document/" + docId + "/restore").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, ownerToken)
                .post(Entity.form(new Form()));
        Assertions.assertEquals(Response.Status.OK.getStatusCode(), r.getStatus(), "normal restore must succeed");

        Assertions.assertEquals(ownerId, activeOwnerOf(docId), "the document is active again under its owner");
        Assertions.assertTrue(sharedToOthers(ownerId), "the restored document is still directly shared");
    }
}
