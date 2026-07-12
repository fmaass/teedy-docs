package com.sismics.docs.core.dao;

import com.sismics.docs.BaseTransactionalTest;
import com.sismics.docs.core.model.jpa.Document;
import com.sismics.docs.core.model.jpa.Favorite;
import com.sismics.docs.core.model.jpa.User;
import com.sismics.util.context.ThreadLocalContext;
import com.sismics.util.jpa.EMF;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityTransaction;
import java.util.Date;
import java.util.List;

/**
 * Unit tests for {@link FavoriteDao}.
 *
 * <p>The load-bearing case is {@link #concurrentDuplicateStarRecoversToCommittableTransaction()}:
 * a favorite committed by one transaction must NOT make a colliding create in a second,
 * independent transaction throw or leave the transaction rollback-only — the idempotent create
 * must recover and return the winning row's id, so a repeat star can never surface a 500. A
 * separate case, {@link #createForMissingDocumentSignalsNotFound()}, pins that a document-FK
 * violation (document purged, not a duplicate star) is distinguished by re-querying committed
 * state and signalled as not-found rather than retried into a 500.
 */
public class TestFavoriteDao extends BaseTransactionalTest {
    private String createDocument(User user, String title) {
        DocumentDao documentDao = new DocumentDao();
        Document document = new Document();
        document.setUserId(user.getId());
        document.setLanguage("eng");
        document.setTitle(title);
        document.setCreateDate(new Date());
        return documentDao.create(document, user.getId());
    }

    @Test
    public void crudCycle() throws Exception {
        User user = createUser("fav_crud");
        String documentId = createDocument(user, "Fav crud doc");
        FavoriteDao dao = new FavoriteDao();

        Assertions.assertNull(dao.getByUserAndDocument(user.getId(), documentId));
        Assertions.assertTrue(dao.getDocumentIdsByUser(user.getId()).isEmpty());

        String id = dao.create(user.getId(), documentId);
        Assertions.assertNotNull(id);

        var fetched = dao.getByUserAndDocument(user.getId(), documentId);
        Assertions.assertNotNull(fetched);
        Assertions.assertEquals(documentId, fetched.getDocumentId());
        Assertions.assertEquals(user.getId(), fetched.getUserId());
        Assertions.assertNotNull(fetched.getCreateDate());

        Assertions.assertEquals(List.of(documentId), dao.getDocumentIdsByUser(user.getId()));

        // Hard delete: gone, and scoped to the owner.
        Assertions.assertFalse(dao.delete("someone-else", documentId), "foreign delete must not affect the row");
        Assertions.assertTrue(dao.delete(user.getId(), documentId));
        Assertions.assertNull(dao.getByUserAndDocument(user.getId(), documentId));
        Assertions.assertTrue(dao.getDocumentIdsByUser(user.getId()).isEmpty());
        // A second delete of an already-removed star reports no row.
        Assertions.assertFalse(dao.delete(user.getId(), documentId));
    }

    @Test
    public void repeatCreateInSameTransactionIsIdempotent() throws Exception {
        User user = createUser("fav_idem");
        String documentId = createDocument(user, "Fav idem doc");
        FavoriteDao dao = new FavoriteDao();

        String id1 = dao.create(user.getId(), documentId);
        // Precheck path: the second create returns the SAME id and inserts no second row.
        String id2 = dao.create(user.getId(), documentId);
        Assertions.assertEquals(id1, id2, "repeat star returns the existing id");
        Assertions.assertEquals(1, dao.getDocumentIdsByUser(user.getId()).size(), "no duplicate row");
    }

    @Test
    public void favoritesAreScopedPerUser() throws Exception {
        User alice = createUser("fav_alice");
        User bob = createUser("fav_bob");
        String documentId = createDocument(alice, "Shared doc");
        FavoriteDao dao = new FavoriteDao();

        dao.create(alice.getId(), documentId);
        // Bob has not favorited it: his list is empty and the pair-lookup is null.
        Assertions.assertTrue(dao.getDocumentIdsByUser(bob.getId()).isEmpty());
        Assertions.assertNull(dao.getByUserAndDocument(bob.getId(), documentId));
        // Alice sees exactly her one favorite.
        Assertions.assertEquals(List.of(documentId), dao.getDocumentIdsByUser(alice.getId()));
    }

    @Test
    public void createForMissingDocumentSignalsNotFound() throws Exception {
        // A document purged between the resource's READ precheck and the insert raises a
        // document foreign-key violation, NOT the unique-star collision. The recovery must NOT
        // retry the same invalid insert (which would 500): it re-queries committed state, finds
        // no favorite row AND no document row, and signals not-found so the resource returns 404.
        User user = createUser("fav_missingdoc");
        FavoriteDao dao = new FavoriteDao();

        Assertions.assertThrows(jakarta.persistence.EntityNotFoundException.class,
                () -> dao.create(user.getId(), "no-such-document-id"),
                "starring a non-existent document signals not-found rather than a duplicate-star retry");
    }

    @Test
    public void concurrentDuplicateStarRecoversToCommittableTransaction() throws Exception {
        // GENUINE RACE: two threads whose transactions BOTH pass create()'s precheck (the row
        // does not exist yet), then BOTH try to insert the same (user, document). One wins at
        // the unique index; the loser hits the constraint at flush and MUST recover — roll the
        // poisoned transaction back, begin a fresh one, return the winner's id — so its
        // end-of-request commit (simulated here) succeeds. A poisoned or rollback-only
        // transaction is exactly the 500-trap this asserts against.
        //
        // The recovery path is only exercised if BOTH prechecks see null BEFORE either inserts.
        // A plain latch that releases both threads at once does not guarantee that: one thread
        // can insert+commit before the other's precheck runs, so the loser takes the precheck
        // path and never touches recovery. To force the ordering WITHOUT a production seam, the
        // worker uses a FavoriteDao subclass whose getByUserAndDocument awaits a shared
        // CyclicBarrier(2) on its FIRST call (the precheck): both threads block there having each
        // read null, then proceed together — guaranteeing exactly one unique violation and a real
        // traversal of the recovery path. The barrier fires once per thread so the recovery's own
        // re-query does not deadlock.
        //
        // Seed the user + document in the setUp transaction and COMMIT so both worker threads,
        // on their own EntityManagers, can read them. Then release the setUp EM (each thread
        // owns its own) and restore it in the finally so tearDown's rollback still works.
        EntityManager em1 = ThreadLocalContext.get().getEntityManager();
        final User user = createUser("fav_race");
        final String documentId = createDocument(user, "Race doc");
        em1.getTransaction().commit();
        em1.close();
        ThreadLocalContext.cleanup();

        final java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(2);
        final java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CyclicBarrier precheckBarrier = new java.util.concurrent.CyclicBarrier(2);
        final java.util.List<Throwable> failures = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        final java.util.List<String> ids = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

        Runnable worker = () -> {
            EntityManager em = EMF.get().createEntityManager();
            ThreadLocalContext.get().setEntityManager(em);
            EntityTransaction tx = em.getTransaction();
            tx.begin();
            try {
                ready.countDown();
                go.await();
                String id = new BarrierPrecheckFavoriteDao(precheckBarrier)
                        .create(user.getId(), documentId);
                // The simulated end-of-request commit: must NOT throw (transaction committable).
                em.getTransaction().commit();
                ids.add(id);
            } catch (Throwable t) {
                failures.add(t);
            } finally {
                try {
                    if (em.getTransaction().isActive()) {
                        em.getTransaction().rollback();
                    }
                } catch (Throwable ignored) {
                    // best-effort cleanup
                }
                em.close();
                ThreadLocalContext.cleanup();
            }
        };

        Thread t1 = new Thread(worker);
        Thread t2 = new Thread(worker);
        t1.start();
        t2.start();
        ready.await();
        go.countDown();
        t1.join(30_000);
        t2.join(30_000);

        // Restore a setUp-shaped EM/transaction for tearDown + the assertions/cleanup below.
        EntityManager emAssert = EMF.get().createEntityManager();
        ThreadLocalContext.get().setEntityManager(emAssert);
        emAssert.getTransaction().begin();

        Assertions.assertTrue(failures.isEmpty(),
                "neither racing star may throw or leave a rollback-only transaction: " + failures);
        Assertions.assertEquals(2, ids.size(), "both creates completed and committed");
        Assertions.assertEquals(ids.get(0), ids.get(1), "both must resolve to the SAME favorite id");

        FavoriteDao dao = new FavoriteDao();
        Assertions.assertEquals(1, dao.getDocumentIdsByUser(user.getId()).size(),
                "exactly one favorite row exists after the race");

        // Cleanup the committed rows (tearDown's rollback cannot undo them).
        dao.delete(user.getId(), documentId);
        new DocumentDao().permanentDelete(documentId);
        emAssert.getTransaction().commit();
        emAssert.close();
        // Leave a fresh EM/tx for tearDown's rollback.
        EntityManager emTeardown = EMF.get().createEntityManager();
        ThreadLocalContext.get().setEntityManager(emTeardown);
        emTeardown.getTransaction().begin();
    }

    @Test
    public void userDeletionClearsThatUsersFavorites() throws Exception {
        User alice = createUser("fav_delA");
        User bob = createUser("fav_delB");
        // Both favorite bob's document.
        String documentId = createDocument(bob, "Deletion doc");
        FavoriteDao dao = new FavoriteDao();
        dao.create(alice.getId(), documentId);
        dao.create(bob.getId(), documentId);
        ThreadLocalContext.get().getEntityManager().flush();

        // Delete alice: her favorite row is hard-deleted; bob's survives (his document is intact,
        // so the "leaves other users' favorites untouched" contract holds).
        new UserDao().delete("fav_delA", bob.getId());
        Assertions.assertTrue(dao.getDocumentIdsByUser(alice.getId()).isEmpty(),
                "deleted user's favorites are removed");
        Assertions.assertEquals(1, dao.getDocumentIdsByUser(bob.getId()).size(),
                "other users' favorites are untouched by a user deletion");
    }

    @Test
    public void permanentDeleteOfDocumentClearsFavorites() throws Exception {
        User alice = createUser("fav_purgeA");
        User bob = createUser("fav_purgeB");
        String documentId = createDocument(alice, "Purge doc");
        FavoriteDao dao = new FavoriteDao();
        dao.create(alice.getId(), documentId);
        dao.create(bob.getId(), documentId);

        EntityManager em = ThreadLocalContext.get().getEntityManager();
        em.flush();
        Assertions.assertEquals(2, dao.getDocumentIdsByUser(alice.getId()).size()
                + dao.getDocumentIdsByUser(bob.getId()).size());

        // Purge the document: the FK is on delete restrict, so the purge must clear the
        // favorite rows first or fail. Both users' stars are gone afterward.
        new DocumentDao().permanentDelete(documentId);
        Assertions.assertTrue(dao.getDocumentIdsByUser(alice.getId()).isEmpty(), "alice's favorite purged");
        Assertions.assertTrue(dao.getDocumentIdsByUser(bob.getId()).isEmpty(), "bob's favorite purged");
    }

    /**
     * A {@link FavoriteDao} that makes the FIRST getByUserAndDocument call (create()'s precheck)
     * block on a shared barrier before returning, so two threads both complete their precheck —
     * each reading null — before either inserts, forcing a real unique violation and traversal of
     * the recovery path. The barrier fires only once per instance so the recovery's own re-query
     * (a later getByUserAndDocument on the same instance) does not deadlock.
     */
    private static final class BarrierPrecheckFavoriteDao extends FavoriteDao {
        private final java.util.concurrent.CyclicBarrier barrier;
        private boolean barrierArmed = true;

        BarrierPrecheckFavoriteDao(java.util.concurrent.CyclicBarrier barrier) {
            this.barrier = barrier;
        }

        @Override
        public Favorite getByUserAndDocument(String userId, String documentId) {
            Favorite result = super.getByUserAndDocument(userId, documentId);
            if (barrierArmed) {
                barrierArmed = false;
                try {
                    barrier.await(30, java.util.concurrent.TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new RuntimeException("precheck barrier failed", e);
                }
            }
            return result;
        }
    }
}
