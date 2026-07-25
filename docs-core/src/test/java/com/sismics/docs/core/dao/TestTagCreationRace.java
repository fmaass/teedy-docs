package com.sismics.docs.core.dao;

import com.sismics.BaseTest;
import com.sismics.docs.core.constant.PermType;
import com.sismics.docs.core.exception.InactiveOwnerException;
import com.sismics.docs.core.model.jpa.Tag;
import com.sismics.docs.core.model.jpa.User;
import com.sismics.docs.core.util.TagCreationUtil;
import com.sismics.docs.core.util.TransactionUtil;
import com.sismics.util.context.ThreadLocalContext;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Deterministic cross-transaction race tests for the #185 tag-creation owner lock in
 * {@link TagCreationUtil#createTag}, the tag counterpart of the #111 document guard. Without the
 * owner-row lock a tag created while its owner is being deleted survives ACTIVE under a soft-deleted
 * owner: the deletion's reassignment snapshot never sees it, it is still listed and linkable for an
 * admin caller, and the next {@code clean_storage} orphan-tag purge then strips it off whatever
 * surviving document it was applied to.
 *
 * <p>Three arms, covering the three realisations of the race under READ COMMITTED:</p>
 * <ol>
 *   <li><b>delete-first</b> — the deletion holds the owner row; the creation blocks on it, then
 *       re-reads the now soft-deleted owner and aborts, leaving no row behind.</li>
 *   <li><b>create-first</b> — the creation holds the owner row; the deletion blocks on it, so its
 *       reassignment snapshot is taken AFTER the tag committed and moves it to the surviving
 *       target.</li>
 *   <li><b>delete already committed</b> — no contention at all. Under READ COMMITTED the deletion
 *       may simply have committed before the creating transaction began, in which case nothing ever
 *       blocks; this arm therefore must NOT call {@link #awaitBlockedSession()}, which fails when
 *       nothing blocks.</li>
 * </ol>
 *
 * <p>Only invariants that hold for EVERY interleaving are asserted, and each arm ends on the
 * committed final state (tag row present/absent and its owner), never on the exception alone —
 * arm (i) would otherwise pass on an engine that makes the unguarded insert wait on the foreign key
 * and then lets it through. Ordering is enforced by the DB row lock itself; the competitor's blocked
 * state is confirmed through ENGINE LOCK INTROSPECTION ({@code pg_locks} / H2
 * {@code INFORMATION_SCHEMA.SESSIONS.BLOCKER_ID}), never a sleep, and exactly one waiter is parked
 * per contended arm. Unique UUID usernames keep committed rows from colliding on the shared
 * (non-reset) docs-core PostgreSQL schema. Runs on both H2 and PostgreSQL.</p>
 */
public class TestTagCreationRace extends BaseTest {

    /** Returns [userId, username] for a freshly committed active user. */
    private String[] createUser(String prefix) {
        String username = prefix + UUID.randomUUID();
        String[] out = new String[2];
        out[1] = username;
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
        return out;
    }

    private static Tag newTag(String ownerId) {
        Tag tag = new Tag();
        tag.setName("race" + UUID.randomUUID().toString().substring(0, 8));
        tag.setColor("#ff0000");
        tag.setUserId(ownerId);
        return tag;
    }

    /** Number of T_TAG rows owned by this user, in ANY delete state (0 proves nothing was inserted). */
    private long tagRowCount(String ownerId) {
        long[] out = new long[1];
        TransactionUtil.handle(() -> {
            Number n = (Number) ThreadLocalContext.get().getEntityManager()
                    .createNativeQuery("select count(*) from T_TAG where TAG_IDUSER_C = :id")
                    .setParameter("id", ownerId).getSingleResult();
            out[0] = n.longValue();
        });
        return out[0];
    }

    /** Owner of a tag that is still ACTIVE, or null when the row is absent or soft-deleted. */
    private String activeTagOwner(String tagId) {
        String[] out = new String[1];
        TransactionUtil.handle(() -> {
            Tag tag = new TagDao().getById(tagId);
            out[0] = tag == null || tag.getDeleteDate() != null ? null : tag.getUserId();
        });
        return out[0];
    }

    private boolean hasDirectAcl(String tagId, PermType perm, String targetId) {
        boolean[] out = new boolean[1];
        TransactionUtil.handle(() -> out[0] = new AclDao().hasDirectUserAcl(tagId, perm, targetId));
        return out[0];
    }

    private boolean userIsActive(String userId) {
        boolean[] out = new boolean[1];
        TransactionUtil.handle(() -> {
            User user = new UserDao().getById(userId);
            out[0] = user != null && user.getDeleteDate() == null;
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
     * Bounded poll of {@link #someSessionIsBlocked()} until the single parked competitor has genuinely
     * blocked on the owner-row lock. Returns only on a real observed block; times out (fails) otherwise.
     * The 15ms interval is a poll cadence, not a race sleep: progress is gated on the observed lock state,
     * never on elapsed time.
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

    // --- (i) delete acquires the owner lock first -> the waiting tag creation aborts, nothing is inserted ---

    @Test
    public void deleteFirstAbortsARacingTagCreation() throws Exception {
        String[] owner = createUser("tcr_df_");

        CountDownLatch deleteHasLock = new CountDownLatch(1);
        CountDownLatch deleteMayProceed = new CountDownLatch(1);
        AtomicBoolean createAborted = new AtomicBoolean(false);
        AtomicReference<String> createdTagId = new AtomicReference<>(null);

        Thread deleteThread = new Thread(() -> TransactionUtil.handle(() -> {
            UserDao userDao = new UserDao();
            userDao.getActiveByIdForUpdate(owner[0]); // lock the owner row
            deleteHasLock.countDown();
            await(deleteMayProceed);
            userDao.delete(owner[1], owner[0]); // soft-delete the owner
        }));

        Thread createThread = new Thread(() -> {
            await(deleteHasLock);
            try {
                TransactionUtil.handle(() ->
                        // the owner lock blocks here, then re-reads the now soft-deleted owner
                        createdTagId.set(TagCreationUtil.createTag(newTag(owner[0]), owner[0])));
            } catch (InactiveOwnerException e) {
                createAborted.set(true);
            }
        });

        deleteThread.start();
        createThread.start();
        await(deleteHasLock);
        awaitBlockedSession();       // the creation is genuinely blocked on the owner-row lock
        deleteMayProceed.countDown();
        joinAll(deleteThread, createThread);

        Assertions.assertTrue(createAborted.get(), "the racing creation is aborted once the owner is self-deleted");
        Assertions.assertNull(createdTagId.get(), "the creation returned no id");
        Assertions.assertFalse(userIsActive(owner[0]), "the owner was soft-deleted");
        Assertions.assertEquals(0L, tagRowCount(owner[0]),
                "no tag row of any delete state survives under the deleted owner — the whole creation rolled back");
    }

    // --- (ii) the tag creation wins the lock -> the deletion's reassignment snapshot sees the new tag ---

    @Test
    public void createFirstIsSeenByTheReassignmentSnapshot() throws Exception {
        String[] owner = createUser("tcr_cf_a_");
        String[] target = createUser("tcr_cf_b_"); // reassignment target (surviving user)

        CountDownLatch createHasLock = new CountDownLatch(1);
        CountDownLatch createMayCommit = new CountDownLatch(1);
        AtomicReference<String> createdTagId = new AtomicReference<>(null);

        Thread createThread = new Thread(() -> TransactionUtil.handle(() -> {
            // Takes the owner row FOR UPDATE, inserts the tag + its base ACLs, then holds both the lock
            // and the uncommitted row until the deletion has been proven blocked.
            createdTagId.set(TagCreationUtil.createTag(newTag(owner[0]), owner[0]));
            createHasLock.countDown();
            await(createMayCommit);
        }));

        Thread deleteThread = new Thread(() -> {
            await(createHasLock);
            TransactionUtil.handle(() -> {
                UserDao userDao = new UserDao();
                // Lock both owner rows in deterministic id order, mirroring the admin reassign-delete path.
                if (owner[0].compareTo(target[0]) <= 0) {
                    userDao.getActiveByIdForUpdate(owner[0]);
                    userDao.getActiveByIdForUpdate(target[0]);
                } else {
                    userDao.getActiveByIdForUpdate(target[0]);
                    userDao.getActiveByIdForUpdate(owner[0]);
                }
                userDao.reassignOwnedDocuments(owner[0], target[0]);
                userDao.delete(owner[1], owner[0]);
            });
        });

        createThread.start();
        deleteThread.start();
        await(createHasLock);
        awaitBlockedSession();       // the deletion is genuinely blocked on the owner-row lock
        createMayCommit.countDown();
        joinAll(createThread, deleteThread);

        String tagId = createdTagId.get();
        Assertions.assertNotNull(tagId, "the creation that held the lock committed");
        Assertions.assertFalse(userIsActive(owner[0]), "the departing owner was soft-deleted");
        Assertions.assertEquals(target[0], activeTagOwner(tagId),
                "the tag is still ACTIVE and was reassigned to the surviving target — never stranded under the deleted owner");
        Assertions.assertTrue(hasDirectAcl(tagId, PermType.READ, target[0]), "the target can read the moved tag");
        Assertions.assertTrue(hasDirectAcl(tagId, PermType.WRITE, target[0]), "the target can manage the moved tag");
    }

    // --- (iii) the delete already committed: no contention, nothing ever blocks, the creation still fails closed ---

    @Test
    public void createAfterACommittedDeleteIsRefusedWithoutAnyLockWait() {
        String[] owner = createUser("tcr_pc_");
        TransactionUtil.handle(() -> new UserDao().delete(owner[1], owner[0]));
        Assertions.assertFalse(userIsActive(owner[0]), "the owner is soft-deleted and committed before the creation starts");

        AtomicReference<String> createdTagId = new AtomicReference<>(null);
        // No awaitBlockedSession() here on purpose: this realisation involves no lock wait at all, so a
        // blocked-session assertion would fail for the wrong reason.
        Assertions.assertThrows(InactiveOwnerException.class, () -> TransactionUtil.handle(() ->
                createdTagId.set(TagCreationUtil.createTag(newTag(owner[0]), owner[0]))));

        Assertions.assertNull(createdTagId.get(), "the creation returned no id");
        Assertions.assertEquals(0L, tagRowCount(owner[0]),
                "no tag row of any delete state was created under the already-deleted owner");
    }
}
