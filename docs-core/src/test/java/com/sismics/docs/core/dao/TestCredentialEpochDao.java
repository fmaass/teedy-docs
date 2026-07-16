package com.sismics.docs.core.dao;

import com.sismics.BaseTest;
import com.sismics.docs.core.model.jpa.ApiKey;
import com.sismics.docs.core.model.jpa.AuthenticationToken;
import com.sismics.docs.core.model.jpa.User;
import com.sismics.docs.core.util.TransactionUtil;
import com.sismics.util.context.ThreadLocalContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.UUID;

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
    public void concurrentBumpsComposeToPlusTwo() throws Exception {
        String userId = createUser();

        Runnable bump = () -> TransactionUtil.handle(() -> new UserDao().bumpCredentialEpoch(userId));
        Thread t1 = new Thread(bump);
        Thread t2 = new Thread(bump);
        t1.start();
        t2.start();
        t1.join(30_000);
        t2.join(30_000);
        Assertions.assertFalse(t1.isAlive() || t2.isAlive(), "both bump threads must finish");

        // The atomic in-place UPDATE takes a row lock, so the two commits serialize: no lost update
        // (a read-modify-write would have left this at +1).
        Assertions.assertEquals(2L, readEpoch(userId));
    }

    @Test
    public void unrelatedUpdateDoesNotLowerTheEpoch() {
        String userId = createUser();
        TransactionUtil.handle(() -> new UserDao().bumpCredentialEpoch(userId));
        TransactionUtil.handle(() -> new UserDao().bumpCredentialEpoch(userId));
        Assertions.assertEquals(2L, readEpoch(userId));

        // A generic profile update loads the row (epoch 2 in the DB) and writes back email/quota/etc. The
        // epoch is absent from UserDao.update's copy list and @DynamicUpdate keeps the untouched column out
        // of the UPDATE, so a stale-epoch write cannot overwrite the bumped value.
        TransactionUtil.handle(() -> {
            UserDao userDao = new UserDao();
            User user = userDao.getById(userId);
            user.setEmail("changed@docs.com");
            userDao.update(user, userId);
        });
        Assertions.assertEquals(2L, readEpoch(userId), "a generic update must not lower the epoch");
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
