package com.sismics.util.context;

import com.sismics.docs.BaseTransactionalTest;
import com.sismics.util.jpa.EMF;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import jakarta.persistence.EntityManager;

import java.util.Date;
import java.util.UUID;

/**
 * Characterization of {@link ThreadLocalContext#getEntityManager()} — the MUTATING getter that,
 * when the stored entity manager is non-null and open, calls {@code flush()} then {@code clear()}
 * before returning it (disabling the L1 cache), and otherwise returns the field untouched.
 *
 * <p>Pins CURRENT behavior so that a later change to this side effect is a deliberate, reviewed
 * change. The main assertion is behavioral: an entity persisted but NOT explicitly flushed is
 * flushed to the database by the getter (a native count sees it) AND detached by the getter's
 * {@code clear()} (the manager no longer contains it). The expected value is not manufactured inside
 * the test — it is read back from the database and from JPA's own identity map.
 */
public class TestThreadLocalContextCharacterization extends BaseTransactionalTest {

    @Test
    public void getEntityManagerFlushesThenClearsOpenManager() {
        ThreadLocalContext context = ThreadLocalContext.get();
        // Grab the base-provided open EM directly. (This first call also flushes+clears, but nothing
        // is pending yet, so it is a no-op relative to the assertions below.)
        EntityManager em = context.getEntityManager();

        // Persist an entity WITHOUT calling flush() ourselves, staying on the raw EM so no DAO's own
        // getEntityManager() call flushes it behind our back.
        String username = "tlc_char_" + UUID.randomUUID();
        com.sismics.docs.core.model.jpa.User user = newRawUser(username);
        em.persist(user);
        Assertions.assertTrue(em.contains(user),
                "precondition: the entity is managed and unflushed before the mutating get");

        // The mutating get must flush (INSERT reaches the DB) then clear (entity detached).
        EntityManager sameEm = context.getEntityManager();
        Assertions.assertSame(em, sameEm, "the getter returns the same entity manager instance");

        Assertions.assertFalse(em.contains(user),
                "clear() must detach the entity (L1 cache emptied) as a side effect of the getter");

        // A native count bypasses the (now empty) L1 cache; seeing the row proves flush() ran BEFORE
        // clear(). Had clear() run without a preceding flush, the INSERT would have been discarded and
        // the count would be 0. Never committed — the base teardown rolls this back.
        Number count = (Number) em.createNativeQuery(
                        "select count(*) from T_USER where USE_USERNAME_C = :username")
                .setParameter("username", username)
                .getSingleResult();
        Assertions.assertEquals(1L, count.longValue(),
                "flush() must have sent the INSERT to the database before clear() detached the entity");
    }

    @Test
    public void getEntityManagerReturnsNullWithoutTouchingWhenUnset() {
        ThreadLocalContext context = ThreadLocalContext.get();
        EntityManager original = context.getEntityManager();
        context.setEntityManager(null);
        try {
            // The null guard short-circuits: no flush, no exception, just null back.
            Assertions.assertNull(context.getEntityManager(),
                    "the getter returns null (no flush/clear) when no entity manager is installed");
        } finally {
            // Restore for the base teardown, which rolls back this manager's transaction.
            context.setEntityManager(original);
        }
    }

    @Test
    public void getEntityManagerReturnsClosedManagerWithoutFlushing() {
        ThreadLocalContext context = ThreadLocalContext.get();
        EntityManager original = context.getEntityManager();

        EntityManager closed = EMF.get().createEntityManager();
        closed.getTransaction().begin();
        closed.getTransaction().rollback();
        closed.close();

        context.setEntityManager(closed);
        try {
            // The isOpen() guard short-circuits on a closed manager: it is returned as-is, un-flushed,
            // and no IllegalStateException from flushing a closed manager escapes.
            EntityManager returned = context.getEntityManager();
            Assertions.assertSame(closed, returned, "a closed entity manager is returned untouched");
            Assertions.assertFalse(returned.isOpen(), "the returned manager is still closed");
        } finally {
            context.setEntityManager(original);
        }
    }

    /**
     * Builds a fully-populated detached User with all NOT NULL columns set, so a bare
     * {@code em.persist} + flush succeeds without going through a DAO (whose own getEntityManager()
     * call would flush the entity before this test wants it to). {@code USE_IDROLE_C} references the
     * "admin" role seeded by the schema migrations.
     */
    private static com.sismics.docs.core.model.jpa.User newRawUser(String username) {
        com.sismics.docs.core.model.jpa.User user = new com.sismics.docs.core.model.jpa.User();
        user.setId(UUID.randomUUID().toString());
        user.setRoleId("admin");
        user.setUsername(username);
        user.setPassword("x");
        user.setPrivateKey("x");
        user.setEmail("tlc_char@docs.com");
        user.setStorageQuota(100_000L);
        user.setStorageCurrent(0L);
        user.setCreateDate(new Date());
        return user;
    }
}
