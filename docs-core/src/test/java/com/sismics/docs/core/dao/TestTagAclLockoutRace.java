package com.sismics.docs.core.dao;

import com.sismics.BaseTest;
import com.sismics.docs.core.constant.AclType;
import com.sismics.docs.core.constant.PermType;
import com.sismics.docs.core.exception.TagWriteLockoutException;
import com.sismics.docs.core.model.jpa.Acl;
import com.sismics.docs.core.model.jpa.Tag;
import com.sismics.docs.core.model.jpa.User;
import com.sismics.docs.core.util.TransactionUtil;
import com.sismics.util.context.ThreadLocalContext;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Deterministic cross-transaction race test for the #88 last-WRITE lockout guard, which lives inside
 * {@code AclDao.delete}. Two co-owner revokes on the SAME tag race: without serialization each
 * observes two owners, each removes one, and both commit → the tag is stranded at zero owners
 * (unmanageable). The guard loads the tag row FOR UPDATE (TagDao.getByIdForUpdate), so the second
 * revoke blocks on the row lock until the first commits, then re-reads one owner under
 * READ_COMMITTED and is refused — exactly one owner is removed and one survives.
 *
 * Ordering is enforced by the DB row lock itself; the competitor's blocked state is confirmed
 * through ENGINE LOCK INTROSPECTION (pg_locks / INFORMATION_SCHEMA.SESSIONS.BLOCKER_ID), never a
 * sleep — so the test FAILS if the pessimistic lock is ever removed (the competitor would never
 * block and awaitBlockedSession times out). Unique UUID names keep committed rows from colliding
 * on the shared (non-reset) docs-core PostgreSQL schema. Runs on both H2 and PostgreSQL.
 */
public class TestTagAclLockoutRace extends BaseTest {

    /** Returns the id of a freshly committed active user. */
    private String createUser(String prefix) {
        String username = prefix + UUID.randomUUID();
        String[] out = new String[1];
        TransactionUtil.handle(() -> {
            User user = new User();
            user.setUsername(username);
            user.setPassword("12345678");
            user.setEmail("e@docs.com");
            user.setRoleId("admin");
            user.setStorageQuota(100_000L);
            try {
                out[0] = new UserDao().create(user, "admin");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        return out[0];
    }

    /** Returns the id of a freshly committed tag owned by {@code ownerId}. */
    private String createTag(String ownerId) {
        String[] out = new String[1];
        TransactionUtil.handle(() -> {
            Tag tag = new Tag();
            tag.setName("race" + UUID.randomUUID().toString().substring(0, 8));
            tag.setColor("#ff0000");
            tag.setUserId(ownerId);
            out[0] = new TagDao().create(tag, ownerId);
        });
        return out[0];
    }

    private void grantWrite(String tagId, String targetId) {
        TransactionUtil.handle(() -> {
            Acl acl = new Acl();
            acl.setSourceId(tagId);
            acl.setPerm(PermType.WRITE);
            acl.setType(AclType.USER);
            acl.setTargetId(targetId);
            new AclDao().create(acl, targetId);
        });
    }

    private long writeHolders(String tagId) {
        long[] out = new long[1];
        TransactionUtil.handle(() -> out[0] = new AclDao().countAcls(tagId, PermType.WRITE, AclType.USER));
        return out[0];
    }

    private boolean hasWrite(String tagId, String targetId) {
        boolean[] out = new boolean[1];
        TransactionUtil.handle(() -> out[0] = new AclDao().hasDirectUserAcl(tagId, PermType.WRITE, targetId));
        return out[0];
    }

    private static void await(CountDownLatch latch) {
        try {
            Assertions.assertTrue(latch.await(30, TimeUnit.SECONDS), "the coordinating thread must signal in time");
        } catch (InterruptedException e) {
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
     * True when SOME database session is currently blocked waiting on a lock, read from the engine's
     * own lock/waiting introspection (never a sleep): {@code pg_locks.granted = false} on PostgreSQL,
     * a non-null {@code INFORMATION_SCHEMA.SESSIONS.BLOCKER_ID} on H2. Runs on its own connection.
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
     * Bounded poll of {@link #someSessionIsBlocked()} until the competitor has genuinely blocked on
     * the tag-row lock. Returns only on a real observed block; times out (fails) otherwise — which is
     * exactly what happens if the pessimistic lock is removed. The 15ms interval is a poll cadence,
     * not a race sleep: progress is gated on the observed lock state, never on elapsed time.
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
        throw new AssertionError("no database session blocked on a row lock within 20s — the pessimistic lock is missing");
    }

    @Test
    public void concurrentCoOwnerRevokesCannotStrandTheTagAtZeroOwners() throws Exception {
        String userA = createUser("tagrace_a_");
        String userB = createUser("tagrace_b_");
        String tagId = createTag(userA);
        grantWrite(tagId, userA);
        grantWrite(tagId, userB);
        Assertions.assertEquals(2L, writeHolders(tagId), "two distinct WRITE holders at the start");

        CountDownLatch aHasActed = new CountDownLatch(1);
        CountDownLatch aMayCommit = new CountDownLatch(1);
        AtomicBoolean aDeleted = new AtomicBoolean(false);
        AtomicBoolean bRefused = new AtomicBoolean(false);
        AtomicBoolean bDeleted = new AtomicBoolean(false);

        // Revoke A: the REAL guarded delete. AclDao.delete locks the tag row FOR UPDATE, counts two
        // holders, and removes A. Then A holds the transaction (and the lock) open on the latch.
        Thread threadA = new Thread(() -> TransactionUtil.handle(() -> {
            new AclDao().delete(tagId, PermType.WRITE, userA, userA, AclType.USER);
            aDeleted.set(true);
            aHasActed.countDown();
            await(aMayCommit);
        }));

        // Revoke B: its AclDao.delete BLOCKS on A's tag-row lock until A commits, then re-reads one
        // holder under the lock and the guard refuses it with TagWriteLockoutException.
        Thread threadB = new Thread(() -> {
            await(aHasActed);
            TransactionUtil.handle(() -> {
                try {
                    new AclDao().delete(tagId, PermType.WRITE, userB, userB, AclType.USER);
                    bDeleted.set(true);
                } catch (TagWriteLockoutException e) {
                    bRefused.set(true);
                }
            });
        });

        threadA.start();
        threadB.start();
        await(aHasActed);
        awaitBlockedSession();       // B is genuinely blocked on the tag-row lock (fails if the lock is gone)
        aMayCommit.countDown();
        joinAll(threadA, threadB);

        Assertions.assertTrue(aDeleted.get(), "the first revoke saw two owners and removed one");
        Assertions.assertTrue(bRefused.get(), "the second revoke re-read one owner under the lock and was refused");
        Assertions.assertFalse(bDeleted.get(), "the second revoke did not delete");
        Assertions.assertEquals(1L, writeHolders(tagId), "exactly one WRITE owner remains — the tag is never stranded at zero");
        Assertions.assertFalse(hasWrite(tagId, userA), "the first owner's WRITE was removed");
        Assertions.assertTrue(hasWrite(tagId, userB), "the second owner's WRITE survived");
    }
}
