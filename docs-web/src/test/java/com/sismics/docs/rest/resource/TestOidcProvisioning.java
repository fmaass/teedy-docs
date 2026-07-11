package com.sismics.docs.rest.resource;

import com.sismics.docs.core.constant.Constants;
import com.sismics.docs.core.dao.UserDao;
import com.sismics.docs.core.model.jpa.User;
import com.sismics.util.context.ThreadLocalContext;
import com.sismics.util.jpa.EMF;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityTransaction;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Phase 6 (#21): OIDC provisioning failure-path hardening against REAL H2 rows.
 *
 * <ul>
 *   <li>fail closed on a missing {@code sub} (no user row created);</li>
 *   <li>idempotent re-provisioning — same {@code (issuer, subject)} converges on one user,
 *       email refreshed, username immutable;</li>
 *   <li>the synthetic {@code @oidc.local} fallback never overwrites a stored real address;</li>
 *   <li>unique-conflict recovery: a REAL {@code IDX_USER_OIDC} flush violation renders the
 *       provisioning transaction unusable, and the production recovery path re-reads the
 *       winner on a clean transaction and returns it.</li>
 * </ul>
 *
 * <p>Runs on the process H2 EMF with a request-scoped transaction in ThreadLocalContext,
 * mirroring {@code RequestContextFilter}; the provisioning helper opens its own fresh
 * transaction underneath (which really commits), so committed OIDC users are cleaned up
 * explicitly in tearDown.
 */
public class TestOidcProvisioning {

    private static final String ISSUER = "https://iss.example";
    private final List<String> committedUsernames = new ArrayList<>();

    @BeforeEach
    public void setUp() {
        EntityManager em = EMF.get().createEntityManager();
        ThreadLocalContext context = ThreadLocalContext.get();
        context.setEntityManager(em);
        em.getTransaction().begin();
    }

    @AfterEach
    public void tearDown() {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        if (em != null && em.getTransaction().isActive()) {
            em.getTransaction().rollback();
        }
        // Hard-delete committed OIDC users created by the fresh-tx provisioning path.
        for (String username : committedUsernames) {
            EntityManager cleanupEm = EMF.get().createEntityManager();
            EntityTransaction tx = cleanupEm.getTransaction();
            tx.begin();
            try {
                cleanupEm.createNativeQuery("delete from T_USER where USE_USERNAME_C = :u")
                        .setParameter("u", username).executeUpdate();
                tx.commit();
            } catch (Exception e) {
                if (tx.isActive()) tx.rollback();
            } finally {
                cleanupEm.close();
            }
        }
        committedUsernames.clear();
        ThreadLocalContext.get().setEntityManager(null);
    }

    // --- (i) fail closed on missing sub ----------------------------------------------------
    // NOTE: The blank-sub rejection lives in callback(); provisionOrRecover is only reached
    // with a real subject. This asserts the derivation/provisioning invariant that a
    // provisioned user is ALWAYS bound to a non-null subject (no unbound orphan).

    @Test
    public void provisionedUserIsAlwaysSubjectBound() throws Exception {
        String subject = "sub-" + UUID.randomUUID();
        User user = invokeProvision("alice", "alice@example.com", subject, ISSUER);
        track(user);
        Assertions.assertNotNull(user);
        Assertions.assertEquals(subject, user.getOidcSubject(), "provisioned user must carry the subject");
        Assertions.assertEquals(ISSUER, user.getOidcIssuer());
        Assertions.assertNotNull(freshLookup(ISSUER, subject), "user must be persisted and findable by sub");
    }

    // --- (iii) idempotent re-provisioning + email update + username immutability -----------

    @Test
    public void reProvisionConvergesOnOneUserUpdatesEmailKeepsUsername() throws Exception {
        String subject = "sub-" + UUID.randomUUID();
        User first = invokeProvision("bob", "bob@example.com", subject, ISSUER);
        track(first);
        Assertions.assertNotNull(first);
        String username = first.getUsername();

        // Simulate the callback's repeat-login path: existing user found by sub, email refreshed.
        User existing = freshLookup(ISSUER, subject);
        Assertions.assertNotNull(existing);
        invokeMaybeUpdateEmail(existing, "bob-new@example.com");

        User afterUpdate = freshLookup(ISSUER, subject);
        Assertions.assertEquals("bob-new@example.com", afterUpdate.getEmail(), "email refreshed on repeat login");
        Assertions.assertEquals(username, afterUpdate.getUsername(), "username is immutable across logins");

        // Only one row for this (issuer, subject).
        Assertions.assertEquals(1, countOidcUsers(ISSUER, subject), "exactly one user per issuer+subject");
    }

    // --- (iii) synthetic-email guard -------------------------------------------------------

    @Test
    public void syntheticFallbackNeverOverwritesStoredRealEmail() throws Exception {
        String subject = "sub-" + UUID.randomUUID();
        User user = invokeProvision("carol", "carol@example.com", subject, ISSUER);
        track(user);
        User existing = freshLookup(ISSUER, subject);

        // On a repeat login the email claim is temporarily absent (null). maybeUpdateEmail
        // must leave the stored real address untouched — the @oidc.local fallback is
        // provisioning-only.
        invokeMaybeUpdateEmail(existing, null);
        Assertions.assertEquals("carol@example.com", freshLookup(ISSUER, subject).getEmail(),
                "absent email claim must not clobber the stored address");

        // A blank/invalid claim likewise must not overwrite.
        invokeMaybeUpdateEmail(existing, "not-an-email");
        Assertions.assertEquals("carol@example.com", freshLookup(ISSUER, subject).getEmail(),
                "invalid email claim must not clobber the stored address");
    }

    /**
     * A persistence failure inside the email refresh must PROPAGATE (failing the callback),
     * never be swallowed into a successful login with a silently-stale profile. Vector: the
     * user row no longer exists at update time (NoResultException — does not poison the tx,
     * so a swallow here would let the login proceed unnoticed).
     */
    @Test
    public void emailUpdateFailurePropagates() throws Exception {
        User ghost = new User();
        ghost.setId(UUID.randomUUID().toString());
        ghost.setUsername("ghost_" + UUID.randomUUID().toString().substring(0, 8));
        ghost.setEmail("old@example.com");

        Assertions.assertThrows(Exception.class,
                () -> invokeMaybeUpdateEmailDirect(ghost, "new@example.com"),
                "a failed email update must propagate, not be swallowed");
    }

    @Test
    public void provisioningWithoutEmailUsesSyntheticFallback() throws Exception {
        String subject = "sub-" + UUID.randomUUID();
        User user = invokeProvision("dave", null, subject, ISSUER);
        track(user);
        Assertions.assertNotNull(user);
        Assertions.assertTrue(user.getEmail().endsWith("@oidc.local"),
                "no email claim at provisioning -> synthetic fallback: " + user.getEmail());
    }

    // --- (ii) unique-conflict recovery on a REAL flush violation ---------------------------

    /**
     * First, prove the raw contract the recovery is built on: a second insert of the same
     * {@code (issuer, subject)} triggers a REAL {@code IDX_USER_OIDC} violation at flush and
     * marks that transaction rollback-only (unusable for further reads/writes).
     */
    @Test
    public void duplicateOidcInsertViolatesUniqueIndexAtFlush() throws Exception {
        String subject = "sub-" + UUID.randomUUID();
        // Winner: committed via the production fresh-tx path.
        User winner = invokeProvision("eve", "eve@example.com", subject, ISSUER);
        track(winner);
        Assertions.assertNotNull(winner);

        // Now attempt a raw duplicate insert on a separate transaction and observe the
        // flush-time violation renders the tx rollback-only.
        EntityManager em = EMF.get().createEntityManager();
        EntityTransaction tx = em.getTransaction();
        tx.begin();
        try {
            User dup = new User();
            dup.setId(UUID.randomUUID().toString());
            dup.setRoleId(Constants.DEFAULT_USER_ROLE);
            dup.setUsername("eve-dup-" + UUID.randomUUID().toString().substring(0, 8));
            dup.setEmail("dup@example.com");
            dup.setOidcIssuer(ISSUER);
            dup.setOidcSubject(subject);
            dup.setStorageQuota(100_000L);
            dup.setPassword("x");
            dup.setCreateDate(new java.util.Date());
            dup.setPrivateKey("k");
            dup.setStorageCurrent(0L);
            em.persist(dup);
            Assertions.assertThrows(PersistenceException.class, em::flush,
                    "a duplicate (issuer, subject) must violate IDX_USER_OIDC at flush");
            Assertions.assertTrue(tx.getRollbackOnly(),
                    "the violation must mark the transaction rollback-only (unusable)");
        } finally {
            if (tx.isActive()) tx.rollback();
            em.close();
        }
    }

    /**
     * The production recovery path: with a winner already committed under {@code (issuer,
     * subject)}, provisionOrRecover hits the flush-time violation, recovers by re-reading on
     * a clean transaction, and returns the WINNER row (login succeeds) — never a second user.
     */
    @Test
    public void provisionOrRecoverReturnsWinnerOnConflict() throws Exception {
        String subject = "sub-" + UUID.randomUUID();
        User winner = invokeProvision("frank", "frank@example.com", subject, ISSUER);
        track(winner);
        Assertions.assertNotNull(winner);
        String winnerId = winner.getId();

        // A concurrent first login for the same identity: provisioning collides, recovers.
        User recovered = invokeProvision("frank", "frank@example.com", subject, ISSUER);
        Assertions.assertNotNull(recovered, "recovery must yield the winner, not fail the login");
        Assertions.assertEquals(winnerId, recovered.getId(),
                "conflict recovery must converge on the already-committed winner row");
        Assertions.assertEquals(1, countOidcUsers(ISSUER, subject),
                "no second user row may be created on conflict");
    }

    // --- residual username collision: hash-prefix extension --------------------------------

    /**
     * A residual collision (an existing account already owns the name this identity derives
     * at the default hash length) must NOT fail provisioning: the hash prefix is extended
     * deterministically and provisioning terminates with the longer name.
     */
    @Test
    public void residualUsernameCollisionExtendsHashPrefix() throws Exception {
        String subject = "sub-" + UUID.randomUUID();
        String takenAt12 = invokeDerive("gina", ISSUER, subject, 12);
        String extendedAt16 = invokeDerive("gina", ISSUER, subject, 16);
        createCommittedLocalUser(takenAt12);

        User user = invokeProvision("gina", "gina@example.com", subject, ISSUER);
        track(user);
        Assertions.assertNotNull(user, "collision must extend the hash, not fail provisioning");
        Assertions.assertEquals(extendedAt16, user.getUsername(),
                "the extension must be the deterministic next hash length");
        Assertions.assertNotNull(freshLookup(ISSUER, subject), "extended user must be persisted");
    }

    /**
     * UserDao.create's unicity precheck is case-INsensitive; a case-variant owner of the
     * derived name must equally trigger the extension path, never a shadow account.
     */
    @Test
    public void caseVariantUsernameCollisionExtendsHashPrefix() throws Exception {
        String subject = "sub-" + UUID.randomUUID();
        String takenAt12 = invokeDerive("hana", ISSUER, subject, 12);
        String extendedAt16 = invokeDerive("hana", ISSUER, subject, 16);
        // A case-variant of the exact derived name (e.g. 'HANA_AB12...' vs 'hana_ab12...').
        createCommittedLocalUser(takenAt12.toUpperCase());

        User user = invokeProvision("hana", "hana@example.com", subject, ISSUER);
        track(user);
        Assertions.assertNotNull(user, "case-variant collision must extend the hash, not fail");
        Assertions.assertEquals(extendedAt16, user.getUsername(),
                "case-variant collision must be detected by the case-insensitive precheck");
    }

    // --- helpers ---------------------------------------------------------------------------

    private static String invokeDerive(String stem, String issuer, String subject, int hashLen) throws Exception {
        Method m = OidcResource.class.getDeclaredMethod(
                "deriveUsername", String.class, String.class, String.class, int.class);
        m.setAccessible(true);
        return (String) m.invoke(null, stem, issuer, subject, hashLen);
    }

    /** Creates a plain local (non-OIDC) user on a committed transaction and tracks it for cleanup. */
    private void createCommittedLocalUser(String username) throws Exception {
        EntityManager prev = ThreadLocalContext.get().getEntityManager();
        EntityManager em = EMF.get().createEntityManager();
        EntityTransaction tx = em.getTransaction();
        try {
            ThreadLocalContext.get().setEntityManager(em);
            tx.begin();
            User user = new User();
            user.setUsername(username);
            user.setPassword(UUID.randomUUID().toString());
            user.setEmail("local@example.com");
            user.setRoleId(Constants.DEFAULT_USER_ROLE);
            user.setStorageQuota(100_000L);
            new UserDao().create(user, username);
            tx.commit();
            committedUsernames.add(username);
        } finally {
            if (tx.isActive()) tx.rollback();
            em.close();
            ThreadLocalContext.get().setEntityManager(prev);
        }
    }

    private void track(User u) {
        if (u != null && u.getUsername() != null) {
            committedUsernames.add(u.getUsername());
        }
    }

    private User invokeProvision(String usernameClaim, String email, String subject, String issuer) throws Exception {
        OidcResource resource = new OidcResource();
        Method m = OidcResource.class.getDeclaredMethod(
                "provisionOrRecover", UserDao.class, String.class, String.class, String.class, String.class);
        m.setAccessible(true);
        return (User) m.invoke(resource, new UserDao(), usernameClaim, email, subject, issuer);
    }

    /**
     * Invokes maybeUpdateEmail on its own committed transaction, mirroring how the callback's
     * request transaction commits the email refresh in production (RequestContextFilter).
     */
    private void invokeMaybeUpdateEmail(User user, String email) throws Exception {
        OidcResource resource = new OidcResource();
        Method m = OidcResource.class.getDeclaredMethod(
                "maybeUpdateEmail", UserDao.class, User.class, String.class);
        m.setAccessible(true);

        EntityManager prev = ThreadLocalContext.get().getEntityManager();
        EntityManager em = EMF.get().createEntityManager();
        EntityTransaction tx = em.getTransaction();
        try {
            ThreadLocalContext.get().setEntityManager(em);
            tx.begin();
            // Re-attach a fresh copy carrying the identity + current email for the update path.
            User fresh = new UserDao().getByOidcSubject(user.getOidcIssuer(), user.getOidcSubject());
            m.invoke(resource, new UserDao(), fresh, email);
            tx.commit();
        } finally {
            if (tx.isActive()) tx.rollback();
            em.close();
            ThreadLocalContext.get().setEntityManager(prev);
        }
    }

    /**
     * Invokes maybeUpdateEmail with the given User object AS-IS (no re-fetch) on its own
     * transaction — used to exercise failure vectors like a nonexistent user id. The
     * reflective InvocationTargetException (wrapping the propagated failure) is unwrapped.
     */
    private void invokeMaybeUpdateEmailDirect(User user, String email) throws Exception {
        OidcResource resource = new OidcResource();
        Method m = OidcResource.class.getDeclaredMethod(
                "maybeUpdateEmail", UserDao.class, User.class, String.class);
        m.setAccessible(true);

        EntityManager prev = ThreadLocalContext.get().getEntityManager();
        EntityManager em = EMF.get().createEntityManager();
        EntityTransaction tx = em.getTransaction();
        try {
            ThreadLocalContext.get().setEntityManager(em);
            tx.begin();
            try {
                m.invoke(resource, new UserDao(), user, email);
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw (e.getCause() instanceof Exception) ? (Exception) e.getCause() : e;
            }
            tx.commit();
        } finally {
            if (tx.isActive()) tx.rollback();
            em.close();
            ThreadLocalContext.get().setEntityManager(prev);
        }
    }

    private User freshLookup(String issuer, String subject) {
        EntityManager em = EMF.get().createEntityManager();
        EntityTransaction tx = em.getTransaction();
        EntityManager prev = ThreadLocalContext.get().getEntityManager();
        try {
            ThreadLocalContext.get().setEntityManager(em);
            tx.begin();
            return new UserDao().getByOidcSubject(issuer, subject);
        } finally {
            if (tx.isActive()) tx.rollback();
            em.close();
            ThreadLocalContext.get().setEntityManager(prev);
        }
    }

    private long countOidcUsers(String issuer, String subject) {
        EntityManager em = EMF.get().createEntityManager();
        EntityTransaction tx = em.getTransaction();
        try {
            tx.begin();
            Object n = em.createNativeQuery(
                            "select count(*) from T_USER where USE_OIDC_ISSUER_C = :i and USE_OIDC_SUBJECT_C = :s and USE_DELETEDATE_D is null")
                    .setParameter("i", issuer).setParameter("s", subject).getSingleResult();
            return ((Number) n).longValue();
        } finally {
            if (tx.isActive()) tx.rollback();
            em.close();
        }
    }
}
