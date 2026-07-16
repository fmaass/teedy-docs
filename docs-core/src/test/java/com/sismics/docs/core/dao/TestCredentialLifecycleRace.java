package com.sismics.docs.core.dao;

import com.sismics.BaseTest;
import com.sismics.docs.core.constant.AclType;
import com.sismics.docs.core.constant.PermType;
import com.sismics.docs.core.model.jpa.Acl;
import com.sismics.docs.core.model.jpa.AuthenticationToken;
import com.sismics.docs.core.model.jpa.Document;
import com.sismics.docs.core.model.jpa.User;
import com.sismics.docs.core.util.DocumentUtil;
import com.sismics.docs.core.util.TransactionUtil;
import com.sismics.util.context.ThreadLocalContext;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Deterministic cross-transaction race tests for the #111 owner-row lock protocol, the pessimistic
 * self-change lock, and the self-change rotation rollback. Each participant runs in its own committed
 * {@link TransactionUtil} frame on its own thread; ordering is enforced by the DB row lock itself and the
 * competitor's blocked state is confirmed through ENGINE LOCK INTROSPECTION (not a fixed sleep) before the
 * holder is released — the holder waits on a latch until the main thread has proven, via {@code pg_locks}
 * (PostgreSQL) or {@code INFORMATION_SCHEMA.SESSIONS.BLOCKER_ID} (H2), that the competitor has reached and
 * blocked on the lock. So the interleaving the assertions check is deterministic and the tests fail if the
 * pessimistic lock is ever removed (the competitor would never block). Unique UUID usernames keep committed
 * rows from colliding on the shared (non-reset) docs-core PostgreSQL schema. Runs on both H2 and PostgreSQL.
 */
public class TestCredentialLifecycleRace extends BaseTest {

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

    private String createDoc(String ownerId, String title) {
        String[] out = new String[1];
        TransactionUtil.handle(() -> {
            Document d = new Document();
            d.setUserId(ownerId);
            d.setLanguage("eng");
            d.setTitle(title);
            d.setCreateDate(new Date());
            out[0] = DocumentUtil.createDocument(d, ownerId).getId();
        });
        return out[0];
    }

    private void grantUserAcl(String documentId, String targetId, String userId) {
        Acl acl = new Acl();
        acl.setSourceId(documentId);
        acl.setPerm(PermType.READ);
        acl.setType(AclType.USER);
        acl.setTargetId(targetId);
        new AclDao().create(acl, userId);
    }

    private boolean docActive(String documentId) {
        boolean[] out = new boolean[1];
        TransactionUtil.handle(() -> out[0] = new DocumentDao().getOwnerIfActive(documentId) != null);
        return out[0];
    }

    private String ownerOf(String documentId) {
        String[] out = new String[1];
        TransactionUtil.handle(() -> out[0] = new DocumentDao().getOwnerIfActive(documentId));
        return out[0];
    }

    private boolean sharedToOthers(String ownerId) {
        boolean[] out = new boolean[1];
        TransactionUtil.handle(() -> out[0] = new DocumentDao().hasActiveDocumentsSharedToOthers(ownerId));
        return out[0];
    }

    private long committedEpoch(String userId) {
        long[] out = new long[1];
        TransactionUtil.handle(() -> {
            Number n = (Number) ThreadLocalContext.get().getEntityManager()
                    .createNativeQuery("select USE_CREDENTIALEPOCH_N from T_USER where USE_ID_C = :id")
                    .setParameter("id", userId).getSingleResult();
            out[0] = n.longValue();
        });
        return out[0];
    }

    private long tokenCount(String userId) {
        long[] out = new long[1];
        TransactionUtil.handle(() -> {
            Number n = (Number) ThreadLocalContext.get().getEntityManager()
                    .createNativeQuery("select count(*) from T_AUTHENTICATION_TOKEN where AUT_IDUSER_C = :id")
                    .setParameter("id", userId).getSingleResult();
            out[0] = n.longValue();
        });
        return out[0];
    }

    private boolean authenticates(String username, String password) {
        boolean[] out = new boolean[1];
        TransactionUtil.handle(() -> out[0] = new UserDao().authenticate(username, password) != null);
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
     * Bounded poll of {@link #someSessionIsBlocked()} until the competitor has genuinely blocked on the row
     * lock. Returns only on a real observed block; times out (fails) otherwise — which is exactly what would
     * happen if the pessimistic lock were removed. The 15ms interval is a poll cadence, not a race sleep:
     * progress is gated on the observed lock state, never on elapsed time.
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

    // --- #111 D2.4 ordering A: delete acquires the lock first -> the waiting share aborts ---

    @Test
    public void deleteFirstThenWaitingShareAborts() throws Exception {
        String[] owner = createUser("race_df_a_");
        String[] shareTo = createUser("race_df_c_");
        String docId = createDoc(owner[0], "doc");

        CountDownLatch deleteHasLock = new CountDownLatch(1);
        CountDownLatch deleteMayProceed = new CountDownLatch(1);
        AtomicReference<String> shareLockResult = new AtomicReference<>("unset");

        Thread deleteThread = new Thread(() -> TransactionUtil.handle(() -> {
            UserDao userDao = new UserDao();
            userDao.getActiveByIdForUpdate(owner[0]); // lock the owner row
            Assertions.assertFalse(new DocumentDao().hasActiveDocumentsSharedToOthers(owner[0]));
            deleteHasLock.countDown();
            await(deleteMayProceed);
            userDao.delete(owner[1], owner[0]); // owner-scoped trash + soft-delete of the owner
        }));

        Thread shareThread = new Thread(() -> {
            await(deleteHasLock);
            TransactionUtil.handle(() -> {
                String lockedOwner = new DocumentDao().lockOwnerForGrant(docId); // blocks, then re-reads the deleted owner
                shareLockResult.set(lockedOwner);
                if (lockedOwner != null) {
                    grantUserAcl(docId, shareTo[0], owner[0]);
                }
            });
        });

        deleteThread.start();
        shareThread.start();
        await(deleteHasLock);
        awaitBlockedSession();       // the share is genuinely blocked on the owner-row lock
        deleteMayProceed.countDown();
        joinAll(deleteThread, shareThread);

        Assertions.assertNull(shareLockResult.get(), "the waiting share re-reads the deleted document and aborts");
        Assertions.assertFalse(docActive(docId), "the document was trashed by the self-delete");
        Assertions.assertFalse(sharedToOthers(owner[0]), "no grant was attached to the deleted document");
    }

    // --- #111 D2.4 ordering B: share acquires the lock first -> the delete is refused ---

    @Test
    public void shareFirstThenDeleteRefused() throws Exception {
        String[] owner = createUser("race_sf_a_");
        String[] shareTo = createUser("race_sf_c_");
        String docId = createDoc(owner[0], "doc");

        CountDownLatch shareHasLock = new CountDownLatch(1);
        CountDownLatch shareMayProceed = new CountDownLatch(1);
        AtomicBoolean deleteRefused = new AtomicBoolean(false);
        AtomicBoolean shareCreated = new AtomicBoolean(false);

        Thread shareThread = new Thread(() -> TransactionUtil.handle(() -> {
            String lockedOwner = new DocumentDao().lockOwnerForGrant(docId); // lock the owner row
            Assertions.assertEquals(owner[0], lockedOwner);
            shareHasLock.countDown();
            await(shareMayProceed);
            grantUserAcl(docId, shareTo[0], owner[0]);
            shareCreated.set(true);
        }));

        Thread deleteThread = new Thread(() -> {
            await(shareHasLock);
            TransactionUtil.handle(() -> {
                UserDao userDao = new UserDao();
                userDao.getActiveByIdForUpdate(owner[0]); // blocks until the share commits
                if (new DocumentDao().hasActiveDocumentsSharedToOthers(owner[0])) {
                    deleteRefused.set(true); // guard now sees the newly-shared document
                    return;
                }
                userDao.delete(owner[1], owner[0]);
            });
        });

        shareThread.start();
        deleteThread.start();
        await(shareHasLock);
        awaitBlockedSession();       // the delete is genuinely blocked on the owner-row lock
        shareMayProceed.countDown();
        joinAll(shareThread, deleteThread);

        Assertions.assertTrue(shareCreated.get(), "the share committed first");
        Assertions.assertTrue(deleteRefused.get(), "the self-delete sees the newly-shared document and is refused");
        Assertions.assertTrue(docActive(docId), "the document is NOT trashed (the delete was refused)");
    }

    // --- #111 phantom arm: a document created concurrently with a delete-first is aborted, not orphaned ---

    @Test
    public void deleteFirstAbortsARacingDocumentCreation() throws Exception {
        String[] owner = createUser("race_ph_");

        CountDownLatch deleteHasLock = new CountDownLatch(1);
        CountDownLatch deleteMayProceed = new CountDownLatch(1);
        AtomicBoolean createAborted = new AtomicBoolean(false);

        Thread deleteThread = new Thread(() -> TransactionUtil.handle(() -> {
            UserDao userDao = new UserDao();
            userDao.getActiveByIdForUpdate(owner[0]);
            deleteHasLock.countDown();
            await(deleteMayProceed);
            userDao.delete(owner[1], owner[0]);
        }));

        Thread createThread = new Thread(() -> {
            await(deleteHasLock);
            try {
                TransactionUtil.handle(() -> {
                    Document d = new Document();
                    d.setUserId(owner[0]);
                    d.setLanguage("eng");
                    d.setTitle("phantom");
                    d.setCreateDate(new Date());
                    DocumentUtil.createDocument(d, owner[0]); // owner lock blocks, then the owner is gone
                });
            } catch (RuntimeException e) {
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
        long[] activeDocs = new long[1];
        TransactionUtil.handle(() -> activeDocs[0] = new DocumentDao().countByUserId(owner[0]));
        Assertions.assertEquals(0L, activeDocs[0], "no phantom document survives active under the deleted owner");
    }

    // --- #111 reassignment race (i2): a share cannot ride a stale owner past a transfer ---

    @Test
    public void shareRacingReassignmentRetriesOntoTheNewOwner() throws Exception {
        String[] owner = createUser("race_rs_a_");
        String[] target = createUser("race_rs_b_"); // reassign target (new owner)
        String[] shareTo = createUser("race_rs_c_"); // grant target principal
        String docId = createDoc(owner[0], "doc");

        CountDownLatch reassignHasLock = new CountDownLatch(1);
        CountDownLatch reassignMayProceed = new CountDownLatch(1);
        AtomicReference<String> shareLockedOwner = new AtomicReference<>("unset");

        Thread reassignThread = new Thread(() -> TransactionUtil.handle(() -> {
            UserDao userDao = new UserDao();
            // Lock both owner rows in deterministic id order, mirroring the admin reassign-delete path.
            if (owner[0].compareTo(target[0]) <= 0) {
                userDao.getActiveByIdForUpdate(owner[0]);
                userDao.getActiveByIdForUpdate(target[0]);
            } else {
                userDao.getActiveByIdForUpdate(target[0]);
                userDao.getActiveByIdForUpdate(owner[0]);
            }
            reassignHasLock.countDown();
            await(reassignMayProceed);
            userDao.reassignOwnedDocuments(owner[0], target[0]); // move the document owner[0] -> target[0]
            userDao.delete(owner[1], owner[0]); // soft-delete the departing owner
        }));

        Thread shareThread = new Thread(() -> {
            await(reassignHasLock);
            TransactionUtil.handle(() -> {
                String lockedOwner = new DocumentDao().lockOwnerForGrant(docId); // must retry onto the new owner
                shareLockedOwner.set(lockedOwner);
                if (lockedOwner != null) {
                    grantUserAcl(docId, shareTo[0], lockedOwner);
                }
            });
        });

        reassignThread.start();
        shareThread.start();
        await(reassignHasLock);
        awaitBlockedSession();       // the share is genuinely blocked on the departing owner's row lock
        reassignMayProceed.countDown();
        joinAll(reassignThread, shareThread);

        Assertions.assertEquals(target[0], shareLockedOwner.get(),
                "the grant retries onto the CURRENT owner after the reassignment, never the stale owner");
        Assertions.assertEquals(target[0], ownerOf(docId), "the document is owned by the new owner");
        Assertions.assertTrue(sharedToOthers(target[0]), "the grant landed under the new owner");
    }

    // --- (1c) the conditional self-change genuinely BLOCKS on the row lock, then aborts on the bump ---

    @Test
    public void changeOwnPasswordBlocksOnTheRowLockThenAbortsOnAConcurrentBump() throws Exception {
        String[] u = createUser("race_lock_");
        TransactionUtil.handle(() -> new UserDao().changeOwnPassword(u[0], "OldPass123", 0L, u[0])); // epoch 0 -> 1
        long verifiedEpoch = 1L; // the epoch the self-change verified before it was released

        CountDownLatch holderHasLock = new CountDownLatch(1);
        CountDownLatch holderMayProceed = new CountDownLatch(1);
        AtomicLong changeResult = new AtomicLong(Long.MIN_VALUE);

        // Holder: hold SELECT ... FOR UPDATE on the user row, then bump + commit once released.
        Thread holder = new Thread(() -> TransactionUtil.handle(() -> {
            new UserDao().getActiveByIdForUpdate(u[0]);
            holderHasLock.countDown();
            await(holderMayProceed);
            new UserDao().bumpCredentialEpoch(u[0]); // 1 -> 2, commits at end of frame
        }));
        // Competitor: changeOwnPassword must BLOCK on its own SELECT ... FOR UPDATE of the same row.
        Thread competitor = new Thread(() -> {
            await(holderHasLock);
            TransactionUtil.handle(() -> changeResult.set(
                    new UserDao().changeOwnPassword(u[0], "SelfPass123", verifiedEpoch, u[0])));
        });

        holder.start();
        competitor.start();
        await(holderHasLock);
        awaitBlockedSession();       // proves changeOwnPassword is genuinely blocked on the row lock (not a sleep)
        holderMayProceed.countDown();
        joinAll(holder, competitor);

        Assertions.assertEquals(-1L, changeResult.get(),
                "once unblocked, the self-change re-reads the bumped epoch under the lock and aborts");
        Assertions.assertEquals(2L, committedEpoch(u[0]), "only the holder's bump applied; the aborted self-change added none");
        Assertions.assertTrue(authenticates(u[1], "OldPass123"), "the aborted self-change did not change the password");
        Assertions.assertFalse(authenticates(u[1], "SelfPass123"), "the abandoned self-change password never took effect");
    }

    // --- (j) a REAL rotation-insert failure after the bump rolls the whole transaction back ---

    @Test
    public void selfChangeRealRotationInsertFailureRollsBackEntirely() throws Exception {
        String[] u = createUser("race_j_");
        TransactionUtil.handle(() -> new UserDao().changeOwnPassword(u[0], "OldPass123", 0L, u[0])); // epoch 0 -> 1
        Assertions.assertEquals(1L, committedEpoch(u[0]));

        AtomicLong inTxEpoch = new AtomicLong(-1);
        Assertions.assertThrows(RuntimeException.class, () -> TransactionUtil.handle(() -> {
            // The REAL conditional self password-change: bumps the epoch 1 -> 2 in this transaction.
            long newEpoch = new UserDao().changeOwnPassword(u[0], "SelfPass123", 1L, u[0]);
            // In-tx probe: the bump already executed (G7). The final rolled-back state is identical pre/post-P2,
            // so this in-tx observation is what proves the bump ran before the failure.
            Number n = (Number) ThreadLocalContext.get().getEntityManager()
                    .createNativeQuery("select USE_CREDENTIALEPOCH_N from T_USER where USE_ID_C = :id")
                    .setParameter("id", u[0]).getSingleResult();
            inTxEpoch.set(n.longValue());
            // The REAL rotateSession mint insert, driven straight through the DAO and made to fail with a
            // genuine database error: AUT_IP_C is length 45, so an over-length IP is rejected by the column
            // constraint at INSERT time (rotateSession abbreviates before create; here we bypass that to
            // reproduce a failing insert in the SAME transaction as the bump).
            AuthenticationToken token = new AuthenticationToken()
                    .setUserId(u[0]).setLongLasted(false).setCredentialEpoch(newEpoch)
                    .setIp("x".repeat(200));
            new AuthenticationTokenDao().create(token);
            ThreadLocalContext.get().getEntityManager().flush(); // the over-length INSERT fails here
        }));

        Assertions.assertEquals(2L, inTxEpoch.get(), "the bump executed BEFORE the induced insert failure (G7)");
        Assertions.assertEquals(1L, committedEpoch(u[0]), "the whole transaction rolled back; the epoch is not advanced");
        Assertions.assertEquals(0L, tokenCount(u[0]), "no token row survived the rolled-back rotation");
        Assertions.assertTrue(authenticates(u[1], "OldPass123"), "the old password still authenticates — no half-revoked state");
    }
}
