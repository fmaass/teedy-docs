package com.sismics.docs.core.util.authentication;

import com.sismics.BaseTest;
import com.sismics.docs.core.constant.Constants;
import com.sismics.docs.core.dao.UserDao;
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
 * Tests the account-origin partition enforced by {@link InternalAuthenticationHandler}: internal auth returns
 * ONLY genuine internal accounts. An account provisioned by an external identity provider (OIDC or LDAP) must
 * never authenticate via a local password — even a password that is otherwise valid, such as one planted on an
 * OIDC account through the password-recovery flow. This closes the OIDC-recovery -> local-login bypass at the
 * handler, symmetric with the pre-existing LDAP refusal.
 */
public class TestInternalAuthenticationHandlerPartition extends BaseTest {

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

    private void createUser(String username, String password, String oidcIssuer, String oidcSubject, boolean ldap) {
        inTx(() -> {
            User user = new User();
            user.setRoleId(Constants.DEFAULT_USER_ROLE);
            user.setUsername(username);
            user.setPassword(password);
            user.setEmail(username + "@example.com");
            user.setStorageQuota(1_000_000L);
            user.setOidcIssuer(oidcIssuer);
            user.setOidcSubject(oidcSubject);
            user.setLdap(ldap);
            new UserDao().create(user, "admin");
            return null;
        });
    }

    /**
     * An OIDC-origin account with a KNOWN, valid local password (the recovery-bypass scenario) is refused by
     * internal auth, while the raw {@link UserDao#authenticate} still verifies that same password — proving it
     * is the handler's origin refusal, not a wrong password, that blocks the login.
     */
    @Test
    public void oidcOriginAccountRefusedByInternalAuthDespiteValidLocalPassword() {
        createUser("oidc_partition_user", "recovered-pass", "https://idp.example.com", "oidc-subject-1", false);

        User raw = inTx(() -> new UserDao().authenticate("oidc_partition_user", "recovered-pass"));
        Assertions.assertNotNull(raw, "the local password is valid at the DAO level (models a recovered password)");

        User viaHandler = inTx(() -> new InternalAuthenticationHandler().authenticate("oidc_partition_user", "recovered-pass"));
        Assertions.assertNull(viaHandler, "internal auth must refuse an OIDC-origin account even with a valid local password");
    }

    /**
     * An LDAP-origin account is likewise refused by internal auth (pre-existing behaviour, kept symmetric).
     */
    @Test
    public void ldapOriginAccountRefusedByInternalAuth() {
        createUser("ldap_partition_user", "some-pass", null, null, true);

        User viaHandler = inTx(() -> new InternalAuthenticationHandler().authenticate("ldap_partition_user", "some-pass"));
        Assertions.assertNull(viaHandler, "internal auth must refuse an LDAP-origin account");
    }

    /**
     * A genuine internal account still authenticates through the handler with its correct password, and a
     * wrong password is rejected — so the refusals above are not vacuously passing on a broken lookup.
     */
    @Test
    public void internalAccountAuthenticatesThroughHandler() {
        createUser("internal_partition_user", "good-pass", null, null, false);

        User ok = inTx(() -> new InternalAuthenticationHandler().authenticate("internal_partition_user", "good-pass"));
        Assertions.assertNotNull(ok, "a genuine internal account must authenticate through internal auth");
        Assertions.assertEquals("internal_partition_user", ok.getUsername());

        User wrong = inTx(() -> new InternalAuthenticationHandler().authenticate("internal_partition_user", "wrong-pass"));
        Assertions.assertNull(wrong, "a wrong password must not authenticate");
    }
}
