package com.sismics.docs.core.dao;

import com.sismics.BaseTest;
import com.sismics.docs.core.exception.InactiveGroupException;
import com.sismics.docs.core.model.jpa.Group;
import com.sismics.docs.core.model.jpa.User;
import com.sismics.docs.core.model.jpa.UserGroup;
import com.sismics.docs.core.util.TransactionUtil;
import com.sismics.util.context.ThreadLocalContext;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Deterministic cross-transaction race tests for the #190 group-membership protocol in
 * {@link GroupDao#addMember(UserGroup)}.
 *
 * <p>The membership contract is "already a member = OK", so the endpoint must succeed for BOTH callers
 * of a concurrent double-add while the database ends up with exactly ONE active row. The old
 * implementation was a blind insert behind an UNLOCKED read-then-check, so both callers saw "not a
 * member" and both inserted — two active rows for one pair, which then made {@code removeMember}'s
 * single-result read ambiguous and left the user a member after a successful removal. Insert-and-translate
 * would not fix it either: on PostgreSQL a unique-constraint violation poisons the whole transaction, so
 * the "already a member" success would become a 500 at commit.</p>
 *
 * <p>Each caller runs in its OWN committed {@link TransactionUtil} transaction on its own thread. Ordering
 * is enforced by the GROUP row lock itself and the competitor's blocked state is confirmed through ENGINE
 * LOCK INTROSPECTION ({@code pg_locks} / H2 {@code INFORMATION_SCHEMA.SESSIONS.BLOCKER_ID}), never a
 * sleep — so the interleaving is deterministic and the contended arm fails if the lock is ever removed
 * (nothing would block). Exactly one waiter is parked. Unique UUID names keep committed rows from
 * colliding on the shared (non-reset) docs-core PostgreSQL schema. Runs on both H2 and PostgreSQL.</p>
 */
public class TestGroupMembershipRace extends BaseTest {

    /** Returns the id of a freshly committed active user. */
    private String createUser(String prefix) {
        String[] out = new String[1];
        TransactionUtil.handle(() -> {
            User user = new User();
            user.setUsername(prefix + UUID.randomUUID());
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

    /** Returns the id of a freshly committed active group. */
    private String createGroup() {
        String[] out = new String[1];
        TransactionUtil.handle(() -> {
            Group group = new Group();
            group.setName("g" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
            out[0] = new GroupDao().create(group, "admin");
        });
        return out[0];
    }

    /** Soft-deletes a group in its own committed transaction. */
    private void deleteGroup(String groupId) {
        TransactionUtil.handle(() -> new GroupDao().delete(groupId, "admin"));
    }

    /** Adds a membership in its own committed transaction and returns the membership id. */
    private String addMember(String groupId, String userId) {
        String[] out = new String[1];
        TransactionUtil.handle(() -> {
            UserGroup userGroup = new UserGroup();
            userGroup.setGroupId(groupId);
            userGroup.setUserId(userId);
            out[0] = new GroupDao().addMember(userGroup);
        });
        return out[0];
    }

    /** Number of ACTIVE membership rows for a pair, read in its own committed transaction. */
    private long activeMembershipCount(String groupId, String userId) {
        long[] out = new long[1];
        TransactionUtil.handle(() -> {
            Number n = (Number) ThreadLocalContext.get().getEntityManager()
                    .createNativeQuery("select count(*) from T_USER_GROUP where UGP_IDGROUP_C = :g and UGP_IDUSER_C = :u and UGP_DELETEDATE_D is null")
                    .setParameter("g", groupId).setParameter("u", userId).getSingleResult();
            out[0] = n.longValue();
        });
        return out[0];
    }

    /** Total membership rows (active + soft-deleted) for a pair. */
    private long totalMembershipCount(String groupId, String userId) {
        long[] out = new long[1];
        TransactionUtil.handle(() -> {
            Number n = (Number) ThreadLocalContext.get().getEntityManager()
                    .createNativeQuery("select count(*) from T_USER_GROUP where UGP_IDGROUP_C = :g and UGP_IDUSER_C = :u")
                    .setParameter("g", groupId).setParameter("u", userId).getSingleResult();
            out[0] = n.longValue();
        });
        return out[0];
    }

    private static void await(CountDownLatch latch) {
        try {
            Assertions.assertTrue(latch.await(30, TimeUnit.SECONDS), "the coordinating thread must signal in time");
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * True when SOME database session is currently blocked waiting on a lock, read from the engine's own
     * lock/waiting introspection (never a sleep): {@code pg_locks.granted = false} on PostgreSQL, a
     * non-null {@code INFORMATION_SCHEMA.SESSIONS.BLOCKER_ID} on H2. Runs on its own connection.
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
     * Bounded poll of {@link #someSessionIsBlocked()} until the competitor has genuinely blocked on the
     * group row lock. Returns only on a real observed block; times out (fails) otherwise — which is
     * exactly what would happen if the pessimistic lock were removed. The 15ms interval is a poll cadence,
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
        throw new AssertionError("no database session blocked on a row lock within 20s — the group lock is missing");
    }

    // --- the #190 race: two concurrent adds of the SAME pair, each in its own transaction -------------

    /**
     * The defect's own interleaving. Caller A adds the membership and holds its transaction open (so it
     * still holds the group row lock); caller B then adds the SAME pair and is proven — via engine lock
     * introspection — to be blocked on that lock. A commits; B's recheck under the lock now sees A's row.
     *
     * <p>Asserts the full contract: BOTH transactions commit successfully (nobody gets an error, nobody
     * gets a poisoned transaction), exactly ONE active row exists, and B returns A's membership id — the
     * idempotent path's return contract, which callers rely on.</p>
     */
    @Test
    public void concurrentDoubleAddCommitsBothAndLeavesExactlyOneActiveRow() throws Exception {
        String userId = createUser("grp_race_");
        String groupId = createGroup();

        CountDownLatch firstHasLock = new CountDownLatch(1);
        CountDownLatch firstMayCommit = new CountDownLatch(1);
        AtomicReference<String> firstId = new AtomicReference<>();
        AtomicReference<String> secondId = new AtomicReference<>();
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        AtomicReference<Throwable> secondFailure = new AtomicReference<>();

        Thread first = new Thread(() -> {
            try {
                TransactionUtil.handle(() -> {
                    UserGroup userGroup = new UserGroup();
                    userGroup.setGroupId(groupId);
                    userGroup.setUserId(userId);
                    firstId.set(new GroupDao().addMember(userGroup));
                    // Still inside the transaction: the group row lock is held until it commits.
                    firstHasLock.countDown();
                    await(firstMayCommit);
                });
            } catch (Throwable t) {
                firstFailure.set(t);
                firstHasLock.countDown();
                firstMayCommit.countDown();
            }
        });

        Thread second = new Thread(() -> {
            await(firstHasLock);
            try {
                TransactionUtil.handle(() -> {
                    UserGroup userGroup = new UserGroup();
                    userGroup.setGroupId(groupId);
                    userGroup.setUserId(userId);
                    secondId.set(new GroupDao().addMember(userGroup));
                });
            } catch (Throwable t) {
                secondFailure.set(t);
            }
        });

        first.start();
        second.start();
        await(firstHasLock);
        Assertions.assertNull(firstFailure.get(), "the first add must not fail: " + firstFailure.get());
        // Deterministic interleaving: proceed only once the competitor has really blocked on the lock.
        awaitBlockedSession();
        firstMayCommit.countDown();
        first.join(60_000);
        second.join(60_000);
        Assertions.assertFalse(first.isAlive(), "the first worker must finish");
        Assertions.assertFalse(second.isAlive(), "the second worker must finish");

        Assertions.assertNull(firstFailure.get(), "the first concurrent add must commit successfully");
        Assertions.assertNull(secondFailure.get(),
                "the second concurrent add must ALSO commit successfully (already a member = OK), but failed with: "
                        + secondFailure.get());
        Assertions.assertEquals(1, activeMembershipCount(groupId, userId),
                "a concurrent double-add must leave EXACTLY ONE active membership row");
        Assertions.assertEquals(1, totalMembershipCount(groupId, userId),
                "the losing add must not have inserted a row at all (not even a soft-deleted one)");
        Assertions.assertNotNull(firstId.get(), "the first add must return a membership id");
        Assertions.assertEquals(firstId.get(), secondId.get(),
                "the idempotent path must return the EXISTING membership id");
    }

    /**
     * The no-contention realisation: under READ COMMITTED the first add may simply have committed before
     * the second transaction begins, in which case nothing ever blocks (so this arm must NOT call
     * {@link #awaitBlockedSession()}, which fails when nothing blocks). The contract is identical.
     */
    @Test
    public void sequentialDoubleAddIsIdempotentAndReturnsTheExistingId() {
        String userId = createUser("grp_seq_");
        String groupId = createGroup();

        String firstId = addMember(groupId, userId);
        String secondId = addMember(groupId, userId);

        Assertions.assertEquals(firstId, secondId, "re-adding an existing member must return its membership id");
        Assertions.assertEquals(1, activeMembershipCount(groupId, userId),
                "re-adding an existing member must not create a second active row");
        Assertions.assertEquals(1, totalMembershipCount(groupId, userId),
                "re-adding an existing member must not insert anything");
    }

    /**
     * Fail closed: when the group has been soft-deleted the add must NOT insert an unserialized row (a
     * membership of a dead group), it must raise. Removal-then-re-add on a LIVE group still works, so the
     * active-only unique index does not turn a legitimate re-add into an error.
     */
    @Test
    public void addToADeletedGroupFailsClosedAndReAddAfterRemovalWorks() {
        String userId = createUser("grp_closed_");
        String groupId = createGroup();

        addMember(groupId, userId);
        TransactionUtil.handle(() -> new GroupDao().removeMember(groupId, userId));
        Assertions.assertEquals(0, activeMembershipCount(groupId, userId), "the member must have been removed");

        // Re-add after removal: accepted, and a NEW row (the old one stays as soft-deleted history).
        String reAddedId = addMember(groupId, userId);
        Assertions.assertNotNull(reAddedId, "re-adding a removed member must succeed");
        Assertions.assertEquals(1, activeMembershipCount(groupId, userId), "the re-add must leave one active row");
        Assertions.assertEquals(2, totalMembershipCount(groupId, userId),
                "the removed row must survive as soft-deleted history alongside the re-added one");

        // Now delete the group and prove the add fails closed instead of inserting into a dead group.
        deleteGroup(groupId);
        String deadUserId = createUser("grp_dead_");
        Assertions.assertThrows(InactiveGroupException.class, () -> addMember(groupId, deadUserId),
                "adding a member to a soft-deleted group must fail closed");
        Assertions.assertEquals(0, totalMembershipCount(groupId, deadUserId),
                "a failed-closed add must leave no membership row behind");
    }
}
