package com.sismics.docs.rest;

import com.sismics.docs.core.dao.UserDao;
import com.sismics.docs.core.model.jpa.User;
import com.sismics.docs.core.util.TransactionUtil;
import com.sismics.util.context.ThreadLocalContext;
import com.sismics.util.filter.TokenBasedSecurityFilter;
import com.sismics.util.jpa.EMF;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityTransaction;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Form;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * Race/regression tests for the admin disable/enable transition. The account's enabled/disabled state is
 * owned SOLELY by the admin transition, which decides and applies it under a FOR UPDATE lock on the target
 * row held to commit; {@code UserDao.update} no longer carries {@code disableDate} in its generic copy list.
 * Together these close two races: (a) a paused self-profile-update writing its stale enabled state back over
 * a just-committed admin disable (re-enabling the account), and (b) a stale duplicate disable flipping the
 * state without a credential-epoch bump.
 *
 * <p>The stale-object tests model the schedulable gap between the endpoint's load and its write with a
 * detached object captured across committed transactions — deterministic, no sleeps. The concurrency test
 * drives two REAL admin-disable requests and forces them to overlap with a test-held row lock (the "gate"),
 * observing them park on the lock through the engine's own lock-wait view (H2:
 * {@code INFORMATION_SCHEMA.SESSIONS.BLOCKER_ID}; PostgreSQL: {@code pg_blocking_pids} rooted at the gate's
 * backend PID) — a deterministic signal, not a timing heuristic. Runs on H2 and on real PostgreSQL.</p>
 */
public class TestAdminDisableTransition extends BaseJerseyTest {

    private static final long JOIN_TIMEOUT_MS = 30_000;
    private static final long AWAIT_BLOCKED_TIMEOUT_MS = 15_000;

    /**
     * A test-held gate: its own EntityManager + transaction holding a pessimistic row lock on the target
     * user row, plus a deterministic, dialect-aware observer for sessions parked on that lock. Always
     * release() in a finally block. Mirrors {@code TestDocumentRestoreOwnershipRace}'s harness.
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
         * Number of DB sessions currently parked in THIS test's lock queue: waiters blocked by the gate
         * directly, plus waiters blocked by those waiters (the second request queues behind the FIRST once
         * it inherits the row lock, not the gate's). H2 serves only this fork, so any waiter is ours.
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

    private String userIdOf(String username) {
        String[] out = new String[1];
        TransactionUtil.handle(() -> out[0] = new UserDao().getActiveByUsername(username).getId());
        return out[0];
    }

    /** True when the account is present, active, and currently disabled (disableDate set). */
    private boolean isDisabled(String username) {
        boolean[] out = new boolean[1];
        TransactionUtil.handle(() -> {
            User user = new UserDao().getActiveByUsername(username);
            out[0] = user != null && user.getDisableDate() != null;
        });
        return out[0];
    }

    private String emailOf(String username) {
        String[] out = new String[1];
        TransactionUtil.handle(() -> out[0] = new UserDao().getActiveByUsername(username).getEmail());
        return out[0];
    }

    private long committedEpoch(String username) {
        long[] out = new long[1];
        TransactionUtil.handle(() -> {
            String id = new UserDao().getActiveByUsername(username).getId();
            Number n = (Number) ThreadLocalContext.get().getEntityManager()
                    .createNativeQuery("select USE_CREDENTIALEPOCH_N from T_USER where USE_ID_C = :id")
                    .setParameter("id", id).getSingleResult();
            out[0] = n.longValue();
        });
        return out[0];
    }

    /** Loads a user and returns it DETACHED (its committed transaction closes), a stale in-memory carrier. */
    private User loadDetached(String username) {
        User[] out = new User[1];
        TransactionUtil.handle(() -> out[0] = new UserDao().getActiveByUsername(username));
        return out[0];
    }

    /** Applies a committed admin disable/enable transition via the DAO primitives (the concurrent-admin dependency). */
    private void applyTransition(String userId, boolean disabled, boolean bump) {
        TransactionUtil.handle(() -> {
            UserDao userDao = new UserDao();
            userDao.getActiveByIdForUpdate(userId).setDisableDate(disabled ? new Date() : null);
            if (bump) {
                userDao.bumpCredentialEpoch(userId);
            }
        });
    }

    /** GET /apikey requires a non-anonymous principal — a clean auth probe (200 = authenticated, 403 = dead). */
    private int probeWithCookie(String token) {
        return target().path("/apikey").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token).get().getStatus();
    }

    /** Drives the REAL admin update endpoint with a disabled flag. */
    private int adminSetDisabled(String adminToken, String username, boolean disabled) {
        return target().path("/user/" + username).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form().param("disabled", String.valueOf(disabled)))).getStatus();
    }

    private Thread disableThread(String adminToken, String username,
                                 AtomicReference<Integer> status, AtomicReference<Throwable> error) {
        return new Thread(() -> {
            try {
                status.set(adminSetDisabled(adminToken, username, true));
            } catch (Throwable t) {
                error.set(t);
            }
        });
    }

    /**
     * (a) A self-profile-update that loaded the account while it was still ENABLED cannot re-enable it after
     * an admin disable commits in between. The pre-fix copy list wrote the stale null {@code disableDate} back
     * over the disabled row (re-enabling the account); with disableDate off the copy list it cannot.
     */
    @Test
    public void staleSelfUpdateCannotReEnableADisabledAccount() {
        clientUtil.createUser("disable_stale_self");
        String userId = userIdOf("disable_stale_self");

        // The self-update's user object, captured while the account was still enabled (disableDate == null).
        User staleSelf = loadDetached("disable_stale_self");

        // Meanwhile an admin disables the account: sets disableDate under the row lock and bumps the epoch.
        applyTransition(userId, true, true); // epoch 0 -> 1
        Assertions.assertTrue(isDisabled("disable_stale_self"), "precondition: the admin disable committed");
        Assertions.assertEquals(1L, committedEpoch("disable_stale_self"), "precondition: the disable bumped the epoch");

        // The paused self-update resumes and writes its (now stale) fields back through the REAL UserDao.update.
        TransactionUtil.handle(() -> {
            staleSelf.setEmail("changed@docs.com");
            new UserDao().update(staleSelf, userId);
        });

        Assertions.assertTrue(isDisabled("disable_stale_self"),
                "the admin disable sticks: a racing self-update cannot re-enable the account");
        Assertions.assertEquals(1L, committedEpoch("disable_stale_self"),
                "the epoch stays bumped: pre-disable credentials remain dead");
        Assertions.assertEquals("changed@docs.com", emailOf("disable_stale_self"),
                "the legitimate profile field (email) is still applied — update is not a no-op");
    }

    /**
     * (b) A stale duplicate "disable=true" request that loaded the ALREADY-disabled state (so it would NOT
     * bump) cannot re-disable the account after a concurrent re-enable clears it. The pre-fix copy list wrote
     * the stale non-null disableDate back — a disable transition with NO bump, leaving re-enabled-window
     * credentials alive. Off the copy list the duplicate write cannot flip the state.
     */
    @Test
    public void staleDuplicateDisableCannotReDisableWithoutABump() {
        clientUtil.createUser("disable_stale_dup");
        String userId = userIdOf("disable_stale_dup");

        // Admin #1 disables the account.
        applyTransition(userId, true, true); // epoch 0 -> 1
        Assertions.assertTrue(isDisabled("disable_stale_dup"));
        Assertions.assertEquals(1L, committedEpoch("disable_stale_dup"));

        // The duplicate request loads the still-disabled state: it would compute "already disabled -> no bump".
        User staleDup = loadDetached("disable_stale_dup");

        // Meanwhile another admin RE-ENABLES the account (clears disableDate, no bump per #110).
        applyTransition(userId, false, false);
        Assertions.assertFalse(isDisabled("disable_stale_dup"), "precondition: the account was re-enabled");
        Assertions.assertEquals(1L, committedEpoch("disable_stale_dup"), "precondition: re-enable does not bump");

        // The duplicate resumes and writes its stale (disabled) fields back through UserDao.update.
        TransactionUtil.handle(() -> {
            staleDup.setEmail("dup@docs.com");
            new UserDao().update(staleDup, userId);
        });

        Assertions.assertFalse(isDisabled("disable_stale_dup"),
                "a stale duplicate disable cannot re-disable the account off the copy list");
        Assertions.assertEquals(1L, committedEpoch("disable_stale_dup"),
                "no phantom disable transition occurred, so the epoch is unchanged");
    }

    /**
     * Two concurrent admin-disable requests, forced to overlap on the target row lock, disable the account
     * and bump the epoch EXACTLY ONCE: the first request wins the lock and transitions, the second re-reads
     * the already-disabled state under the lock and does not bump. Off the lock (pre-fix) both requests read
     * the enabled state before either commits and BOTH bump, composing to +2.
     */
    @Test
    public void concurrentAdminDisablesSerializeToASingleBump() throws Exception {
        clientUtil.createUser("disable_concurrent");
        String userId = userIdOf("disable_concurrent");
        String adminToken = adminToken();
        Assertions.assertEquals(0L, committedEpoch("disable_concurrent"), "precondition: fresh user at epoch 0");

        AtomicReference<Integer> s1 = new AtomicReference<>();
        AtomicReference<Integer> s2 = new AtomicReference<>();
        AtomicReference<Throwable> e1 = new AtomicReference<>();
        AtomicReference<Throwable> e2 = new AtomicReference<>();
        Thread d1 = disableThread(adminToken, "disable_concurrent", s1, e1);
        Thread d2 = disableThread(adminToken, "disable_concurrent", s2, e2);

        Gate gate = new Gate();
        User gateLocked = new UserDao().getActiveByIdForUpdate(userId);
        Assertions.assertNotNull(gateLocked, "Gate must lock the target user row");
        try {
            d1.start();
            gate.awaitCondition(() -> gate.blockedSessions() >= 1, "first admin disable parked on the target row lock");
            d2.start();
            gate.awaitCondition(() -> gate.blockedSessions() >= 2, "second admin disable parked on the target row lock");
        } finally {
            gate.release();
        }

        d1.join(JOIN_TIMEOUT_MS);
        d2.join(JOIN_TIMEOUT_MS);
        Assertions.assertFalse(d1.isAlive() || d2.isAlive(), "both disable requests must complete");
        Assertions.assertNull(e1.get(), "first disable failed: " + e1.get());
        Assertions.assertNull(e2.get(), "second disable failed: " + e2.get());
        Assertions.assertEquals(200, s1.get().intValue());
        Assertions.assertEquals(200, s2.get().intValue());

        Assertions.assertTrue(isDisabled("disable_concurrent"), "the account is disabled");
        Assertions.assertEquals(1L, committedEpoch("disable_concurrent"),
                "the locked transition bumps exactly once for two concurrent disables (no double revocation)");
    }

    /**
     * #110 regression guard: enable -> disable -> re-enable bumps once on disable and leaves pre-disable
     * credentials dead across the re-enable. A pre-disable session (epoch 0) is revoked by the disable bump
     * and STAYS revoked after re-enable (its epoch is still below the user's), while a fresh login works.
     */
    @Test
    public void enableDisableReEnableBumpsOnceAndKeepsPreDisableSessionsDead() {
        clientUtil.createUser("disable_lifecycle");
        String preDisableSession = clientUtil.login("disable_lifecycle"); // stamped at epoch 0
        Assertions.assertEquals(200, probeWithCookie(preDisableSession), "the pre-disable session works");
        Assertions.assertEquals(0L, committedEpoch("disable_lifecycle"));

        String adminToken = adminToken();

        // Disable -> exactly one bump; the pre-disable session dies.
        Assertions.assertEquals(200, adminSetDisabled(adminToken, "disable_lifecycle", true));
        Assertions.assertTrue(isDisabled("disable_lifecycle"));
        Assertions.assertEquals(1L, committedEpoch("disable_lifecycle"), "a transition to disabled bumps once");
        Assertions.assertEquals(403, probeWithCookie(preDisableSession), "the disable revoked the pre-disable session");

        // Re-enable -> NO bump; the pre-disable session STAYS dead (its epoch 0 is still below the user's 1).
        Assertions.assertEquals(200, adminSetDisabled(adminToken, "disable_lifecycle", false));
        Assertions.assertFalse(isDisabled("disable_lifecycle"));
        Assertions.assertEquals(1L, committedEpoch("disable_lifecycle"), "a re-enable does not bump the epoch");
        Assertions.assertEquals(403, probeWithCookie(preDisableSession),
                "the pre-disable session remains dead across the re-enable (#110)");

        // A fresh login after re-enable works — the account is genuinely usable again.
        String postEnableSession = clientUtil.login("disable_lifecycle");
        Assertions.assertEquals(200, probeWithCookie(postEnableSession), "a fresh post-re-enable session works");
    }
}
