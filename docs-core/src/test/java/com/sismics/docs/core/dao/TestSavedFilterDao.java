package com.sismics.docs.core.dao;

import com.sismics.docs.BaseTransactionalTest;
import com.sismics.docs.core.model.jpa.SavedFilter;
import com.sismics.docs.core.model.jpa.User;
import com.sismics.util.context.ThreadLocalContext;
import com.sismics.util.jpa.EMF;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityTransaction;
import java.util.List;

/**
 * Unit tests for {@link SavedFilterDao}.
 *
 * <p>The load-bearing case is {@link #duplicateNameHitsDbConstraintViaFlush()}: it proves
 * the DAO's in-request {@code flush()} translation actually REACHES the DB unique index
 * across TWO INDEPENDENT transactions — the first create is COMMITTED by transaction 1,
 * and the colliding create runs on a fresh EntityManager/transaction (the shape of two
 * concurrent requests, following the own-EM idiom of TestRouteModelIntegrityConcurrency).
 * There is no precheck in the DAO, so the only thing that can reject the second create is
 * the {@code IDX_SFL_USER_NAME} constraint the flush surfaces. Removing the flush (letting
 * the violation defer to commit) makes this test no longer throw {@code SavedFilterExists}
 * at create time, so it is a real assertion of the translation, not a self-check.
 */
public class TestSavedFilterDao extends BaseTransactionalTest {
    private SavedFilter filter(String userId, String name, String query) {
        SavedFilter f = new SavedFilter();
        f.setUserId(userId);
        f.setName(name);
        f.setQuery(query);
        return f;
    }

    @Test
    public void crudCycle() throws Exception {
        User user = createUser("sfl_crud");
        SavedFilterDao dao = new SavedFilterDao();

        String id = dao.create(filter(user.getId(), "Invoices", "tags=t1&search=acme"));
        Assertions.assertNotNull(id);

        SavedFilter fetched = dao.getByIdAndUser(id, user.getId());
        Assertions.assertNotNull(fetched);
        Assertions.assertEquals("Invoices", fetched.getName());
        Assertions.assertEquals("tags=t1&search=acme", fetched.getQuery());
        Assertions.assertNotNull(fetched.getCreateDate());

        // getByIdAndUser is owner-scoped: a foreign user gets null.
        Assertions.assertNull(dao.getByIdAndUser(id, "someone-else"));

        List<SavedFilter> list = dao.getByUserId(user.getId());
        Assertions.assertEquals(1, list.size());
        Assertions.assertEquals(id, list.get(0).getId());

        // Hard delete: gone, and scoped to the owner.
        Assertions.assertFalse(dao.delete(id, "someone-else"), "foreign delete must not affect the row");
        Assertions.assertTrue(dao.delete(id, user.getId()));
        Assertions.assertNull(dao.getByIdAndUser(id, user.getId()));
        Assertions.assertTrue(dao.getByUserId(user.getId()).isEmpty());
    }

    @Test
    public void getByUserIdIsOrderedByNameAndScopedToUser() throws Exception {
        User alice = createUser("sfl_alice");
        User bob = createUser("sfl_bob");
        SavedFilterDao dao = new SavedFilterDao();

        dao.create(filter(alice.getId(), "Zeta", "search=z"));
        dao.create(filter(alice.getId(), "Alpha", "search=a"));
        dao.create(filter(bob.getId(), "Beta", "search=b"));

        List<SavedFilter> aliceList = dao.getByUserId(alice.getId());
        Assertions.assertEquals(2, aliceList.size(), "only alice's filters");
        Assertions.assertEquals("Alpha", aliceList.get(0).getName(), "ordered by name");
        Assertions.assertEquals("Zeta", aliceList.get(1).getName());

        Assertions.assertEquals(1, dao.getByUserId(bob.getId()).size(), "bob sees only his own");
    }

    @Test
    public void duplicateNameHitsDbConstraintViaFlush() throws Exception {
        // TRANSACTION 1 (the setUp EM/tx): create the user + the first filter, then
        // COMMIT — the first row is durable, exactly as if request 1 completed. Capture
        // the EM reference WHILE the tx is active: ThreadLocalContext.getEntityManager()
        // flushes on every access and would throw outside an active transaction.
        EntityManager em1 = ThreadLocalContext.get().getEntityManager();
        User user = createUser("sfl_dup");
        SavedFilterDao dao = new SavedFilterDao();
        String firstId = dao.create(filter(user.getId(), "Invoices", "search=first"));
        em1.getTransaction().commit();

        // TRANSACTION 2: a genuinely INDEPENDENT EntityManager + transaction — what a
        // second concurrent request gets from the RequestContextFilter (own-EM idiom of
        // TestRouteModelIntegrityConcurrency). The DAO has NO precheck, so the ONLY
        // thing that can reject this create is transaction 1's COMMITTED row surfacing
        // through IDX_SFL_USER_NAME at the DAO's in-request flush(). This is the
        // cross-transaction race backstop proven to reach the real DB constraint.
        EntityManager em2 = EMF.get().createEntityManager();
        ThreadLocalContext.get().setEntityManager(em2);
        EntityTransaction tx2 = em2.getTransaction();
        tx2.begin();
        try {
            SavedFilterExistsException thrown = Assertions.assertThrows(
                    SavedFilterExistsException.class,
                    () -> dao.create(filter(user.getId(), "Invoices", "search=second")),
                    "the DB unique index must reject a duplicate (user, name) at flush time");
            Assertions.assertNotNull(thrown.getCause(), "the exception must wrap the underlying DB cause");

            // The unique-violation flush ABORTED tx2 at the DB level. On PostgreSQL any
            // further statement in this transaction fails with 25P02 ("current transaction
            // is aborted") — em.clear() reset only the JPA persistence context, NOT the DB
            // transaction state. So ROLL BACK tx2 and open a FRESH transaction for cleanup;
            // this is correct on BOTH H2 and PostgreSQL (H2 tolerated the reuse, PG does not).
            tx2.rollback();
            EntityTransaction cleanupTx = em2.getTransaction();
            cleanupTx.begin();
            // Cleanup of the COMMITTED filter row (tearDown's rollback cannot undo tx1):
            // hard-delete it and commit. The committed user row stays — usernames are
            // test-unique, so it cannot collide with other tests.
            Assertions.assertTrue(dao.delete(firstId, user.getId()), "cleanup of the committed row");
            cleanupTx.commit();
        } finally {
            if (tx2.isActive()) {
                tx2.rollback();
            }
            em2.close();
            // Restore the setUp EM with an active transaction so tearDown's rollback works.
            ThreadLocalContext.get().setEntityManager(em1);
            em1.getTransaction().begin();
        }
    }

    @Test
    public void sameNameDifferentUsersAllowed() throws Exception {
        User alice = createUser("sfl_shareA");
        User bob = createUser("sfl_shareB");
        SavedFilterDao dao = new SavedFilterDao();

        String aliceId = dao.create(filter(alice.getId(), "Shared name", "search=a"));
        // Same name, different user: the index is (user, name), so this must succeed.
        String bobId = dao.create(filter(bob.getId(), "Shared name", "search=b"));

        Assertions.assertNotNull(aliceId);
        Assertions.assertNotNull(bobId);
        Assertions.assertNotEquals(aliceId, bobId);
    }
}
