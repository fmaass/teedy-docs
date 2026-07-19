package com.sismics.docs.core.dao;

import com.sismics.BaseTest;
import com.sismics.docs.core.model.jpa.ApiKey;
import com.sismics.docs.core.model.jpa.AuthenticationToken;
import com.sismics.docs.core.model.jpa.User;
import com.sismics.docs.core.util.TransactionUtil;
import com.sismics.util.context.ThreadLocalContext;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Tests for the credential-epoch single-writer bump, its concurrency/rollback semantics, and the
 * fail-closed NOT-NULL stamp guard on credential mints. Runs on H2 (the {@code test} job) and on real
 * PostgreSQL (the {@code test-postgres} CI job) — the atomic in-place increment and the row-lock
 * composition it relies on are dialect-sensitive, so both engines gate it.
 *
 * <p>Unlike {@code BaseTransactionalTest} (one rolled-back transaction per test), these tests need
 * COMMITTED transactions to observe cross-transaction composition and rollback, so each unit of work
 * runs in its own {@link TransactionUtil#handle(Runnable)} frame. Users carry unique ids/usernames so
 * committed rows cannot collide across tests on the shared (non-reset) docs-core Postgres schema.</p>
 */
public class TestCredentialEpochDao extends BaseTest {

    /** Creates a committed active user and returns its generated id. New rows start at epoch 0 (Java primitive default). */
    private String createUser() {
        // A unique username; UserDao.create generates the id (a UUID) and returns it.
        String username = "e" + UUID.randomUUID();
        String[] id = new String[1];
        TransactionUtil.handle(() -> {
            User user = new User();
            user.setUsername(username);
            user.setPassword("12345678");
            user.setEmail("e@docs.com");
            user.setRoleId("admin");
            user.setStorageQuota(100_000L);
            try {
                id[0] = new UserDao().create(user, "admin");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        return id[0];
    }

    private static void await(CountDownLatch latch) {
        try {
            Assertions.assertTrue(latch.await(30, TimeUnit.SECONDS), "the coordinating thread must signal in time");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    private static void joinAll(Thread... threads) throws InterruptedException {
        for (Thread t : threads) {
            t.join(30_000);
        }
        for (Thread t : threads) {
            Assertions.assertFalse(t.isAlive(), "every worker thread must finish");
        }
    }

    /**
     * True when SOME database session is currently blocked waiting on a lock, read from the engine's own
     * lock/waiting introspection (never a sleep): {@code pg_locks.granted = false} on PostgreSQL, a non-null
     * {@code INFORMATION_SCHEMA.SESSIONS.BLOCKER_ID} on H2. Runs on its own connection.
     */
    private boolean someSessionIsBlocked() {
        boolean[] blocked = new boolean[1];
        TransactionUtil.handle(() -> {
            EntityManager em = ThreadLocalContext.get().getEntityManager();
            String product = em.unwrap(org.hibernate.Session.class)
                    .doReturningWork(conn -> conn.getMetaData().getDatabaseProductName());
            String sql = product.toLowerCase().contains("postgres")
                    ? "select count(*) from pg_locks where not granted"
                    : "select count(*) from information_schema.sessions where blocker_id is not null";
            Number n = (Number) em.createNativeQuery(sql).getSingleResult();
            blocked[0] = n.longValue() > 0;
        });
        return blocked[0];
    }

    /**
     * Bounded poll of {@link #someSessionIsBlocked()} until the competitor has genuinely blocked on the row
     * lock. Returns only on a real observed block; times out (fails) otherwise. The 15ms interval is a poll
     * cadence, not a race sleep: progress is gated on the observed lock state, never on elapsed time.
     */
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
        throw new AssertionError("no database session blocked on a row lock within 20s — the forced overlap did not occur");
    }

    /** Reads the persisted epoch directly from the row (bypasses any managed-entity cache). */
    private long readEpoch(String userId) {
        long[] holder = new long[1];
        TransactionUtil.handle(() -> {
            Number n = (Number) ThreadLocalContext.get().getEntityManager()
                    .createNativeQuery("select USE_CREDENTIALEPOCH_N from T_USER where USE_ID_C = :id")
                    .setParameter("id", userId)
                    .getSingleResult();
            holder[0] = n.longValue();
        });
        return holder[0];
    }

    @Test
    public void freshUserStartsAtEpochZero() {
        String userId = createUser();
        Assertions.assertEquals(0L, readEpoch(userId));
    }

    @Test
    public void bumpIncrementsByOne() {
        String userId = createUser();
        int[] rows = new int[1];
        TransactionUtil.handle(() -> rows[0] = new UserDao().bumpCredentialEpoch(userId));
        Assertions.assertEquals(1, rows[0], "the active user's row is updated");
        Assertions.assertEquals(1L, readEpoch(userId));
    }

    @Test
    public void multipleCommittedIncrementsReachTheLatestEpoch() {
        String userId = createUser();
        TransactionUtil.handle(() -> new UserDao().bumpCredentialEpoch(userId));
        TransactionUtil.handle(() -> new UserDao().bumpCredentialEpoch(userId));
        TransactionUtil.handle(() -> new UserDao().bumpCredentialEpoch(userId));
        Assertions.assertEquals(3L, readEpoch(userId), "three committed increments compose to +3");
    }

    @Test
    public void concurrentBumpsComposeToPlusTwoUnderForcedOverlap() throws Exception {
        String userId = createUser();

        CountDownLatch firstBumped = new CountDownLatch(1);
        CountDownLatch firstMayCommit = new CountDownLatch(1);

        // Worker A executes its bump (the in-place UPDATE takes and HOLDS the row lock), then keeps the
        // transaction open — lock held, uncommitted — until released, so worker B is forced to contend for the
        // SAME row concurrently rather than running after A has finished (which a plain join would allow).
        Thread a = new Thread(() -> TransactionUtil.handle(() -> {
            new UserDao().bumpCredentialEpoch(userId);
            firstBumped.countDown();
            await(firstMayCommit);
        }));
        // Worker B starts its bump only once A's bump is in-flight, so the two are provably concurrent; its
        // UPDATE then blocks on A's row lock until A commits.
        Thread b = new Thread(() -> {
            await(firstBumped);
            TransactionUtil.handle(() -> new UserDao().bumpCredentialEpoch(userId));
        });

        a.start();
        b.start();
        await(firstBumped);       // A's bump UPDATE has executed and holds the row lock (uncommitted)
        awaitBlockedSession();    // prove B is genuinely blocked contending for the same row (real overlap, not a sleep)
        firstMayCommit.countDown();
        joinAll(a, b);

        // A read-modify-write bump would have B compute its target from the pre-A value and write an absolute
        // E+1, losing A's increment (final +1). The atomic in-place UPDATE re-evaluates against the latest
        // committed row when it unblocks, so the two forced-overlap increments compose to +2.
        Assertions.assertEquals(2L, readEpoch(userId), "two forced-overlap bumps compose to +2 (no lost update)");
    }

    @Test
    public void staleProfileUpdateCannotLowerTheEpoch() {
        String userId = createUser();

        // Load the profile object while the epoch is still 0 — BEFORE any bump. This is the stale carrier a
        // real request holds when it reads the row, then a credential-invalidating bump lands before it writes
        // back. Loading AFTER the bump (caller and DB both at the bumped value) would let a regressed copy list
        // write the same value over itself and pass — so the pre-bump load is what makes this discriminating.
        User[] holder = new User[1];
        TransactionUtil.handle(() -> holder[0] = new UserDao().getById(userId));
        User staleUser = holder[0];
        Assertions.assertEquals(0L, staleUser.getCredentialEpoch(), "the stale carrier holds the pre-bump epoch 0");

        // A credential-invalidating event bumps the epoch to 1 in its own committed transaction.
        TransactionUtil.handle(() -> new UserDao().bumpCredentialEpoch(userId));
        Assertions.assertEquals(1L, readEpoch(userId));

        // Replay the stale (epoch-0) object through a generic profile update. The epoch is absent from
        // UserDao.update's copy list, so the stale 0 is never written over the bumped 1. A regression that
        // ADDED the epoch to the copy list would write 0 here, lowering the persisted epoch — this assertion
        // catches it.
        TransactionUtil.handle(() -> {
            UserDao userDao = new UserDao();
            staleUser.setEmail("changed@docs.com");
            userDao.update(staleUser, userId);
        });
        Assertions.assertEquals(1L, readEpoch(userId), "a stale-object update must not lower the bumped epoch");
    }

    @Test
    public void rollbackAfterIncrementLeavesEpochUnadvanced() {
        String userId = createUser();
        TransactionUtil.handle(() -> new UserDao().bumpCredentialEpoch(userId));
        Assertions.assertEquals(1L, readEpoch(userId));

        // A transaction that increments then fails rolls back — the increment is discarded.
        Assertions.assertThrows(RuntimeException.class, () -> TransactionUtil.handle(() -> {
            new UserDao().bumpCredentialEpoch(userId);
            throw new RuntimeException("force rollback after the increment");
        }));
        Assertions.assertEquals(1L, readEpoch(userId), "a rolled-back increment must not advance the epoch");
    }

    @Test
    public void bumpOnSoftDeletedUserIsANoOp() {
        String userId = createUser();
        TransactionUtil.handle(() -> {
            ThreadLocalContext.get().getEntityManager()
                    .createNativeQuery("update T_USER set USE_DELETEDATE_D = :now where USE_ID_C = :id")
                    .setParameter("now", new Date())
                    .setParameter("id", userId)
                    .executeUpdate();
        });
        int[] rows = new int[1];
        TransactionUtil.handle(() -> rows[0] = new UserDao().bumpCredentialEpoch(userId));
        Assertions.assertEquals(0, rows[0], "a soft-deleted user matches no rows (already credential-dead)");
    }

    @Test
    public void unstampedTokenMintFailsClosed() {
        String userId = createUser();
        // A session-token mint that forgets to stamp the epoch leaves the field null; the NOT NULL column
        // rejects it (Hibernate binds the null explicitly rather than relying on the SQL default), so no
        // un-stamped token can ever be persisted — the structural guard behind the five mint sites.
        Assertions.assertThrows(Exception.class, () -> TransactionUtil.handle(() -> {
            AuthenticationTokenDao dao = new AuthenticationTokenDao();
            dao.create(new AuthenticationToken().setUserId(userId).setLongLasted(false));
            ThreadLocalContext.get().getEntityManager().flush();
        }));
    }

    @Test
    public void unstampedApiKeyMintFailsClosed() {
        String userId = createUser();
        Assertions.assertThrows(Exception.class, () -> TransactionUtil.handle(() -> {
            ApiKey apiKey = new ApiKey();
            apiKey.setUserId(userId);
            apiKey.setName("no-stamp");
            apiKey.setKeyHash("hash-" + UUID.randomUUID());
            apiKey.setPrefix("tdapi_nope");
            new ApiKeyDao().create(apiKey);
            ThreadLocalContext.get().getEntityManager().flush();
        }));
    }

    @Test
    public void stampedTokenMintSucceeds() {
        String userId = createUser();
        String[] tokenId = new String[1];
        TransactionUtil.handle(() -> {
            AuthenticationTokenDao dao = new AuthenticationTokenDao();
            tokenId[0] = dao.create(new AuthenticationToken().setUserId(userId).setLongLasted(false)
                    .setCredentialEpoch(0L));
        });
        Assertions.assertNotNull(tokenId[0], "a properly stamped token mint persists");
    }
}
