package com.sismics.docs.core.dao;

import com.sismics.BaseTest;
import com.sismics.docs.core.model.jpa.User;
import com.sismics.util.context.ThreadLocalContext;
import com.sismics.util.jpa.EMF;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Callable;

/**
 * Tests {@link UserDao#authenticate(String, String)} handling of a missing/blank password.
 *
 * <p>A missing password must be handled identically for an existing and a nonexistent account. Previously a
 * real username dereferenced the null password inside {@code authenticate} and threw (surfacing at the REST
 * boundary as a 500), while an unknown username returned null (a 403) — a user-enumeration oracle by response
 * shape. Both must now return null without throwing.</p>
 */
public class TestUserAuthenticate extends BaseTest {

    @BeforeEach
    public void setUp() {
        ThreadLocalContext.cleanup();
    }

    @AfterEach
    public void tearDown() {
        ThreadLocalContext.cleanup();
    }

    private <T> T inTx(Callable<T> work) {
        EntityManager em = EMF.get().createEntityManager();
        ThreadLocalContext ctx = ThreadLocalContext.get();
        ctx.setEntityManager(em);
        em.getTransaction().begin();
        try {
            T result = work.call();
            em.getTransaction().commit();
            return result;
        } catch (Exception e) {
            if (em.getTransaction().isActive()) {
                em.getTransaction().rollback();
            }
            throw new RuntimeException(e);
        } finally {
            em.close();
            ThreadLocalContext.cleanup();
        }
    }

    /**
     * A null password returns null (never throws) for BOTH the seeded "admin" account and a nonexistent one:
     * an omitted password is indistinguishable between an existing and a nonexistent account.
     */
    @Test
    public void nullPasswordReturnsNullForExistingAndNonexistent() {
        User existing = inTx(() -> new UserDao().authenticate("admin", null));
        Assertions.assertNull(existing, "a null password must not authenticate the existing admin account");

        User nonexistent = inTx(() -> new UserDao().authenticate("no_such_account_xyz", null));
        Assertions.assertNull(nonexistent, "a null password must not authenticate a nonexistent account");
    }

    /**
     * A blank ("") password is likewise rejected uniformly for existing and nonexistent accounts.
     */
    @Test
    public void blankPasswordReturnsNullForExistingAndNonexistent() {
        User existing = inTx(() -> new UserDao().authenticate("admin", ""));
        Assertions.assertNull(existing, "a blank password must not authenticate the existing admin account");

        User nonexistent = inTx(() -> new UserDao().authenticate("no_such_account_xyz", ""));
        Assertions.assertNull(nonexistent, "a blank password must not authenticate a nonexistent account");
    }

    /**
     * Sanity: the seeded admin still authenticates with the correct password, and a wrong password is
     * rejected — so the null/blank assertions above are not vacuously passing on a broken lookup.
     */
    @Test
    public void correctPasswordAuthenticatesAndWrongPasswordDoesNot() {
        User ok = inTx(() -> new UserDao().authenticate("admin", "admin"));
        Assertions.assertNotNull(ok, "the seeded admin must authenticate with the correct password");
        Assertions.assertEquals("admin", ok.getUsername());

        User wrong = inTx(() -> new UserDao().authenticate("admin", "definitely-wrong"));
        Assertions.assertNull(wrong, "a wrong password must not authenticate");
    }
}
