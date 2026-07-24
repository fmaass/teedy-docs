package com.sismics.docs.core.dao;

import com.sismics.BaseTest;
import com.sismics.docs.core.model.jpa.Document;
import com.sismics.docs.core.model.jpa.File;
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
 * The #174 served-cover reconciliation ({@link DocumentDao#reconcileServingCover}) must be atomic per
 * document. Two guarantees are covered:
 *
 * <ul>
 *   <li><b>Synchronous:</b> reconciliation writes the served pointer (DOC_IDFILE_C) inside the calling
 *       transaction, with no async event — a caller (the cover endpoints) can read the new pointer back
 *       immediately. An explicit cover wins while attached; a dangling one is cleared and the pointer
 *       falls back to the first file by order.</li>
 *   <li><b>Serialized:</b> a pessimistic row lock makes the read-then-write atomic, so a slower/older
 *       pass cannot overwrite a newer cover chosen by a concurrent pass. The competitor's blocked state
 *       is confirmed through engine lock introspection (never a sleep), so the test fails if the lock is
 *       ever removed. Runs on both H2 and PostgreSQL.</li>
 * </ul>
 */
public class TestDocumentCoverReconcileRace extends BaseTest {

    private String createUser(String prefix) {
        String username = prefix + UUID.randomUUID().toString().substring(0, 12);
        String[] out = new String[1];
        TransactionUtil.handle(() -> {
            User user = new User();
            user.setUsername(username);
            user.setPassword("12345678");
            user.setEmail("e@docs.com");
            user.setRoleId("admin");
            user.setStorageQuota(1_000_000L);
            try {
                out[0] = new UserDao().create(user, "admin");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        return out[0];
    }

    private String createDocument(String ownerId) {
        String[] out = new String[1];
        TransactionUtil.handle(() -> {
            Document document = new Document();
            document.setUserId(ownerId);
            document.setTitle("cover-race");
            document.setLanguage("eng");
            document.setCreateDate(new Date());
            out[0] = new DocumentDao().create(document, ownerId);
        });
        return out[0];
    }

    private String createFile(String documentId, String ownerId, int order) {
        String[] out = new String[1];
        TransactionUtil.handle(() -> {
            File file = new File();
            file.setDocumentId(documentId);
            file.setUserId(ownerId);
            file.setMimeType("text/plain");
            file.setOrder(order);
            file.setVersion(0);
            file.setLatestVersion(true);
            file.setSize(10L);
            out[0] = new FileDao().create(file, ownerId);
        });
        return out[0];
    }

    private String servedFileId(String documentId) {
        String[] out = new String[1];
        TransactionUtil.handle(() -> out[0] = new DocumentDao().getById(documentId).getFileId());
        return out[0];
    }

    private String coverFileId(String documentId) {
        String[] out = new String[1];
        TransactionUtil.handle(() -> out[0] = new DocumentDao().getById(documentId).getIdFileCover());
        return out[0];
    }

    private void softDeleteFile(String fileId) {
        TransactionUtil.handle(() -> ThreadLocalContext.get().getEntityManager()
                .createQuery("update File f set f.deleteDate = :now where f.id = :id")
                .setParameter("now", new Date())
                .setParameter("id", fileId)
                .executeUpdate());
    }

    @Test
    public void reconcileWritesServedPointerSynchronously() {
        String owner = createUser("coverrec_sync_");
        String documentId = createDocument(owner);
        String file1 = createFile(documentId, owner, 0);
        String file2 = createFile(documentId, owner, 1);

        TransactionUtil.handle(() -> new DocumentDao().reconcileServingCover(documentId));
        Assertions.assertEquals(file1, servedFileId(documentId), "the default served pointer is the first file by order");
        Assertions.assertNull(coverFileId(documentId), "no explicit cover is set by default");

        TransactionUtil.handle(() -> {
            DocumentDao dao = new DocumentDao();
            dao.updateCoverFileId(documentId, file2);
            dao.reconcileServingCover(documentId);
        });
        Assertions.assertEquals(file2, coverFileId(documentId), "the explicit cover is recorded");
        Assertions.assertEquals(file2, servedFileId(documentId), "the explicit cover serves, synchronously in the same transaction");

        softDeleteFile(file2);
        TransactionUtil.handle(() -> new DocumentDao().reconcileServingCover(documentId));
        Assertions.assertNull(coverFileId(documentId), "a dangling explicit cover is cleared");
        Assertions.assertEquals(file1, servedFileId(documentId), "the served pointer falls back to the first remaining file");
    }

    @Test
    public void concurrentReconcileCannotWriteAStaleServedPointer() throws Exception {
        String owner = createUser("coverrec_race_");
        String documentId = createDocument(owner);
        String file1 = createFile(documentId, owner, 0);
        String file2 = createFile(documentId, owner, 1);
        TransactionUtil.handle(() -> {
            DocumentDao dao = new DocumentDao();
            dao.updateCoverFileId(documentId, file2);
            dao.reconcileServingCover(documentId);
        });
        Assertions.assertEquals(file2, servedFileId(documentId));

        CountDownLatch aHasReconciled = new CountDownLatch(1);
        CountDownLatch aMayCommit = new CountDownLatch(1);

        // A deletes the cover file AND reconciles in ONE transaction — the row lock is taken INSIDE
        // reconcileServingCover (the SUT), never by the test — then holds the transaction open.
        Thread threadA = new Thread(() -> TransactionUtil.handle(() -> {
            ThreadLocalContext.get().getEntityManager()
                    .createQuery("update File f set f.deleteDate = :now where f.id = :id")
                    .setParameter("now", new Date())
                    .setParameter("id", file2)
                    .executeUpdate();
            new DocumentDao().reconcileServingCover(documentId);
            aHasReconciled.countDown();
            await(aMayCommit);
        }));

        // B runs a second production reconcile concurrently. With the lock it blocks at the read and
        // re-reads A's committed state (file2 gone → serve file1). Without it, B reads the pre-commit
        // state (file2 still attached), computes file2, then overwrites the served pointer with the
        // now-deleted file.
        Thread threadB = new Thread(() -> {
            await(aHasReconciled);
            TransactionUtil.handle(() -> new DocumentDao().reconcileServingCover(documentId));
        });

        threadA.start();
        threadB.start();
        await(aHasReconciled);
        awaitBlockedSession();
        aMayCommit.countDown();
        joinAll(threadA, threadB);

        Assertions.assertEquals(file1, servedFileId(documentId),
                "a concurrent reconcile must not write the deleted cover file as the served pointer");
        Assertions.assertNull(coverFileId(documentId), "the dangling cover is cleared");
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
        throw new AssertionError("no database session blocked on a row lock within 20s — the reconcile row lock is missing");
    }
}
