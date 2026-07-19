package com.sismics.docs.core.dao;

import com.google.common.collect.Lists;
import com.sismics.BaseTest;
import com.sismics.docs.core.constant.AclType;
import com.sismics.docs.core.constant.PermType;
import com.sismics.docs.core.model.jpa.Acl;
import com.sismics.docs.core.model.jpa.RouteModel;
import com.sismics.docs.core.model.jpa.Tag;
import com.sismics.docs.core.model.jpa.User;
import com.sismics.docs.core.util.CredentialLifecycleUtil;
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
 * Deterministic cross-transaction race test for the #121 duplicate-grant fix: {@code AclResource.add()} is
 * check-then-act, so two concurrent identical grants on the same source both pass the existence check and
 * insert a duplicate {@code (sourceId, perm, targetId)} row. The fix serializes on the SOURCE row —
 * {@link CredentialLifecycleUtil#lockAclSourceForGrant(String)} locks the tag row (or the route-model row)
 * FOR UPDATE before the duplicate-check — so the second grant BLOCKS until the first commits, then re-reads
 * the now-present grant under READ_COMMITTED and skips the insert. Exactly ONE row results.
 *
 * <p>The interleave mirrors {@code AclResource.add()}'s real sequence (lock source, duplicate-check via
 * {@link AclDao#checkPermission}, insert) and is ordered by the DB row lock itself: the competitor's blocked
 * state is confirmed through ENGINE LOCK INTROSPECTION (never a sleep), so the test FAILS if the source lock
 * is ever removed (the competitor never blocks → {@code awaitBlockedSession} times out) OR if two rows are
 * written. Unique UUID names keep committed rows from colliding on the shared docs-core schema. Runs on both
 * H2 and PostgreSQL. Covers a TAG source and a workflow/ROUTE-MODEL source (the generic ACL editor edits
 * both), proving the same serialization holds for each.</p>
 */
public class TestAclGrantSerializationRace extends BaseTest {

    /** Returns the id of a freshly committed active user. */
    private String createUser(String prefix) {
        String username = prefix + UUID.randomUUID().toString().substring(0, 12);
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
            tag.setName("grantrace" + UUID.randomUUID().toString().substring(0, 8));
            tag.setColor("#ff0000");
            tag.setUserId(ownerId);
            out[0] = new TagDao().create(tag, ownerId);
        });
        return out[0];
    }

    /** Returns the id of a freshly committed route model owned by {@code ownerId}. */
    private String createRouteModel(String ownerId) {
        String[] out = new String[1];
        TransactionUtil.handle(() -> {
            RouteModel routeModel = new RouteModel();
            routeModel.setName("grantrace" + UUID.randomUUID().toString().substring(0, 8));
            routeModel.setSteps("[]");
            out[0] = new RouteModelDao().create(routeModel, ownerId);
        });
        return out[0];
    }

    /**
     * Counts the ACTUAL non-deleted USER WRITE ACL rows for a (source, target) — NOT distinct holders, so a
     * raced duplicate insert of the SAME grant is visible as two rows.
     */
    private long aclRows(String sourceId, String targetId) {
        long[] out = new long[1];
        TransactionUtil.handle(() -> {
            EntityManager em = ThreadLocalContext.get().getEntityManager();
            Number n = (Number) em.createQuery("select count(a.id) from Acl a where a.sourceId = :s"
                            + " and a.perm = :p and a.targetId = :t and a.type = :ty and a.deleteDate is null")
                    .setParameter("s", sourceId)
                    .setParameter("p", PermType.WRITE)
                    .setParameter("t", targetId)
                    .setParameter("ty", AclType.USER)
                    .getSingleResult();
            out[0] = n.longValue();
        });
        return out[0];
    }

    /** Mirrors AclResource.add(): lock the source row, duplicate-check, insert if absent. */
    private void grantOnce(String sourceId, String targetId) {
        CredentialLifecycleUtil.lockAclSourceForGrant(sourceId);
        AclDao aclDao = new AclDao();
        if (!aclDao.checkPermission(sourceId, PermType.WRITE, Lists.newArrayList(targetId))) {
            Acl acl = new Acl();
            acl.setSourceId(sourceId);
            acl.setPerm(PermType.WRITE);
            acl.setType(AclType.USER);
            acl.setTargetId(targetId);
            aclDao.create(acl, targetId);
        }
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

    /** Bounded poll until the competitor has genuinely blocked on the source-row lock; fails otherwise. */
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
        throw new AssertionError("no database session blocked on a row lock within 20s — the source lock is missing");
    }

    /**
     * Drives the deterministic two-grant interleave on the given committed source id and asserts exactly one
     * ACL row results (the second grant blocked, re-read the committed grant, and skipped its insert).
     */
    private void assertConcurrentIdenticalGrantsYieldOneRow(String sourceId, String targetId) throws Exception {
        Assertions.assertEquals(0L, aclRows(sourceId, targetId), "no grant exists at the start");

        CountDownLatch aHasActed = new CountDownLatch(1);
        CountDownLatch aMayCommit = new CountDownLatch(1);
        AtomicBoolean aInserted = new AtomicBoolean(false);

        // Grant A: locks the source, sees no grant, inserts, then holds the tx (and the source lock) open.
        Thread threadA = new Thread(() -> TransactionUtil.handle(() -> {
            CredentialLifecycleUtil.lockAclSourceForGrant(sourceId);
            AclDao aclDao = new AclDao();
            if (!aclDao.checkPermission(sourceId, PermType.WRITE, Lists.newArrayList(targetId))) {
                Acl acl = new Acl();
                acl.setSourceId(sourceId);
                acl.setPerm(PermType.WRITE);
                acl.setType(AclType.USER);
                acl.setTargetId(targetId);
                aclDao.create(acl, targetId);
                aInserted.set(true);
            }
            aHasActed.countDown();
            await(aMayCommit);
        }));

        // Grant B: its lockAclSourceForGrant BLOCKS on A's source lock until A commits, then the re-read sees
        // A's committed grant and B skips its insert.
        Thread threadB = new Thread(() -> {
            await(aHasActed);
            TransactionUtil.handle(() -> grantOnce(sourceId, targetId));
        });

        threadA.start();
        threadB.start();
        await(aHasActed);
        awaitBlockedSession();       // B is genuinely blocked on the source-row lock (fails if the lock is gone)
        aMayCommit.countDown();
        joinAll(threadA, threadB);

        Assertions.assertTrue(aInserted.get(), "the first grant saw no existing grant and inserted one");
        Assertions.assertEquals(1L, aclRows(sourceId, targetId),
                "exactly one (source, WRITE, target) row — the raced duplicate grant was serialized away");
    }

    @Test
    public void concurrentIdenticalGrantsOnATagYieldExactlyOneRow() throws Exception {
        String owner = createUser("grantrace_tagowner_");
        String target = createUser("grantrace_tagtarget_");
        String tagId = createTag(owner);
        assertConcurrentIdenticalGrantsYieldOneRow(tagId, target);
    }

    @Test
    public void concurrentIdenticalGrantsOnARouteModelYieldExactlyOneRow() throws Exception {
        String owner = createUser("grantrace_rmowner_");
        String target = createUser("grantrace_rmtarget_");
        String routeModelId = createRouteModel(owner);
        assertConcurrentIdenticalGrantsYieldOneRow(routeModelId, target);
    }

    /**
     * #121 FIX: a source that cannot be locked active must make lockAclSourceForGrant return false (so
     * AclResource.add aborts with NotFoundException) rather than proceed to an UNSERIALIZED insert. Covers a
     * soft-deleted route-model source (getActiveByIdForUpdate returns null) and a sourceId matching nothing.
     */
    @Test
    public void lockAclSourceForGrantRejectsSoftDeletedRouteModelAndUnknownSource() {
        String owner = createUser("grantrace_del_");
        String routeModelId = createRouteModel(owner);
        TransactionUtil.handle(() -> new RouteModelDao().delete(routeModelId, owner));

        boolean[] out = new boolean[2];
        String missing = "no-such-source-" + UUID.randomUUID();
        TransactionUtil.handle(() -> {
            out[0] = CredentialLifecycleUtil.lockAclSourceForGrant(routeModelId);
            out[1] = CredentialLifecycleUtil.lockAclSourceForGrant(missing);
        });
        Assertions.assertFalse(out[0],
                "a grant on a soft-deleted route-model source must be rejected (no unserialized insert)");
        Assertions.assertFalse(out[1], "a grant on a nonexistent source must be rejected");
    }
}
