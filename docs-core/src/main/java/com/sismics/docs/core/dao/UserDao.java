package com.sismics.docs.core.dao;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.sismics.docs.core.constant.AclType;
import com.sismics.docs.core.constant.AuditLogType;
import com.sismics.docs.core.constant.Constants;
import com.sismics.docs.core.constant.PermType;
import com.sismics.docs.core.dao.criteria.UserCriteria;
import com.sismics.docs.core.dao.dto.UserDto;
import com.sismics.docs.core.model.jpa.Acl;
import com.sismics.docs.core.model.jpa.Document;
import com.sismics.docs.core.model.jpa.File;
import com.sismics.docs.core.model.jpa.User;
import com.sismics.docs.core.util.AuditLogUtil;
import com.sismics.docs.core.util.EncryptionUtil;
import com.sismics.docs.core.util.FileUtil;
import com.sismics.docs.core.util.jpa.QueryParam;
import com.sismics.docs.core.util.jpa.QueryUtil;
import com.sismics.docs.core.util.jpa.SortCriteria;
import com.sismics.util.context.ThreadLocalContext;

import at.favre.lib.crypto.bcrypt.BCrypt;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.Query;

/**
 * User DAO.
 * 
 * @author jtremeaux
 */
public class UserDao {
    /**
     * Logger.
     */
    private static final Logger log = LoggerFactory.getLogger(UserDao.class);

    /**
     * A precomputed bcrypt hash of a value no submitted password can equal, used to spend an equivalent
     * amount of hashing work when the username does not exist. Without it, a missing user returns before any
     * bcrypt verify runs, and the response-time difference against a wrong-password (which does verify) leaks
     * whether an account exists. It MUST be generated at the same work factor as real password hashing
     * ({@link #resolveBcryptWork()}, default {@link Constants#DEFAULT_BCRYPT_WORK}) — a mismatched cost makes
     * the unknown-account verify faster or slower than a real one and reopens the very timing oracle it
     * exists to close.
     */
    private static final String DUMMY_PASSWORD_HASH =
            BCrypt.withDefaults().hashToString(resolveBcryptWork(), "docs-nonexistent-account-timing-equalizer".toCharArray());

    /**
     * A non-empty probe verified against the dummy hash when the submitted password is missing/blank, so that
     * an omitted password spends the same bcrypt work regardless of whether the account exists.
     */
    private static final char[] EMPTY_PASSWORD_PROBE = "docs-omitted-password-timing-equalizer".toCharArray();

    /**
     * Authenticates an user.
     *
     * @param username User login
     * @param password User password
     * @return The authenticated user or null
     */
    public User authenticate(String username, String password) {
        // A missing or blank password can never authenticate. Handle it uniformly BEFORE the user lookup, so
        // an existing and a nonexistent account are indistinguishable for an omitted password: both spend one
        // bcrypt verify against the dummy hash and return null. Previously a real username dereferenced the
        // null password and 500'd while an unknown username 403'd — an enumeration oracle by response shape.
        if (Strings.isNullOrEmpty(password)) {
            BCrypt.verifyer().verify(EMPTY_PASSWORD_PROBE, DUMMY_PASSWORD_HASH);
            return null;
        }

        EntityManager em = ThreadLocalContext.get().getEntityManager();
        Query q = em.createQuery("select u from User u where u.username = :username and u.deleteDate is null");
        q.setParameter("username", username);
        try {
            User user = (User) q.getSingleResult();
            BCrypt.Result result = BCrypt.verifyer().verify(password.toCharArray(), user.getPassword());
            if (!result.verified || user.getDisableDate() != null) {
                return null;
            }
            return user;
        } catch (NoResultException e) {
            // Spend comparable hashing work for a nonexistent account so timing does not reveal whether the
            // username exists (constant-time-ish authentication). Password is guaranteed non-blank here.
            BCrypt.verifyer().verify(password.toCharArray(), DUMMY_PASSWORD_HASH);
            return null;
        }
    }

    /**
     * Creates a new user.
     * 
     * @param user User to create
     * @param userId User ID
     * @return User ID
     * @throws Exception e
     */
    public String create(User user, String userId) throws Exception {
        // Create the user UUID
        user.setId(UUID.randomUUID().toString());
        
        // Checks for user unicity, case-insensitively (BL-020). An exact = match is
        // case-sensitive on PostgreSQL, which would let a case-variant of an existing
        // username (e.g. an LDAP "ADMIN" alongside a local "admin") create a shadow
        // account. Folding case here makes the guard hold on every backend.
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        Query q = em.createQuery("select u from User u where lower(u.username) = lower(:username) and u.deleteDate is null");
        q.setParameter("username", user.getUsername());
        List<?> l = q.getResultList();
        if (l.size() > 0) {
            throw new Exception("AlreadyExistingUsername");
        }
        
        // Create the user
        user.setCreateDate(new Date());
        user.setPassword(hashPassword(user.getPassword()));
        user.setPrivateKey(EncryptionUtil.generatePrivateKey());
        user.setStorageCurrent(0L);
        em.persist(user);

        // Force the INSERT now so the active-username unique index (IDX_USER_USERNAME_ACTIVE,
        // dbupdate-050) is checked HERE — inside create(), synchronously — rather than surfacing
        // later as an unhandled PersistenceException at request-transaction commit (a 500). The
        // case-insensitive precheck above already rejects the common case; this flush is the race
        // backstop that closes the window between the precheck SELECT and the commit. A violation
        // of THIS index is translated to the same "AlreadyExistingUsername" the precheck throws, so
        // every creation path (local, LDAP, OIDC) gets one clean signal. Any OTHER constraint
        // violation (e.g. IDX_USER_OIDC on the OIDC path) is left to propagate unchanged so its own
        // handler still sees it.
        try {
            em.flush();
        } catch (jakarta.persistence.PersistenceException e) {
            if (isActiveUsernameConflict(e)) {
                throw new Exception("AlreadyExistingUsername", e);
            }
            throw e;
        }

        // Create audit log
        AuditLogUtil.create(user, AuditLogType.CREATE, userId);

        return user.getId();
    }

    /**
     * True when the throwable chain is a violation of the active-username unique index
     * {@code IDX_USER_USERNAME_ACTIVE} (dbupdate-050). Detected by index name so it is dialect
     * agnostic (H2 names it in the message; PostgreSQL reports the lowercase constraint name).
     */
    private static boolean isActiveUsernameConflict(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            String msg = c.getMessage();
            if (msg != null && msg.toUpperCase().contains("IDX_USER_USERNAME_ACTIVE")) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Updates a user.
     * 
     * @param user User to update
     * @param userId User ID
     * @return Updated user
     */
    public User update(User user, String userId) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        
        // Get the user
        Query q = em.createQuery("select u from User u where u.id = :id and u.deleteDate is null");
        q.setParameter("id", user.getId());
        User userDb = (User) q.getSingleResult();

        // Update the user (except password). storageCurrent is DELIBERATELY not written here: it is
        // running quota accounting owned solely by the locked reserve/reclaim paths
        // (FileUtil.reserveStorage / reclaimUserQuota). Binding a stale caller-supplied storageCurrent
        // from a generic profile/admin/TOTP/OIDC update would clobber a concurrent upload's reservation
        // (combined with @DynamicUpdate on User, which keeps the untouched column out of the UPDATE).
        userDb.setEmail(user.getEmail());
        userDb.setStorageQuota(user.getStorageQuota());
        // BOTH TOTP columns (totpKey and totpKeyPending) are DELIBERATELY not written here. They carry a
        // security state that must never be resurrected by a stale in-memory copy: a generic profile/OIDC
        // update loads the row, and a totpKey a compare-and-swap disable cleared concurrently would be
        // bound back from the caller's pre-read entity (an @DynamicUpdate full-column rebind). They are
        // writable ONLY through the dedicated CAS writers below (setPendingTotpKey / activateTotpKey /
        // clearTotpKeys), each of which carries its own affected-row-count guard and audit event.
        // disableDate is DELIBERATELY not written here (same exclusion rationale as password/storageCurrent
        // above): the account's enabled/disabled state is owned solely by the admin disable/enable transition,
        // which decides and applies it under a FOR UPDATE row lock on the target
        // (UserResource.update -> CredentialLifecycleUtil.lockActiveUser). A generic profile/self/TOTP/OIDC
        // update carries a pre-read, possibly stale disableDate; writing it back here would let a racing
        // self-update re-enable an account an admin just disabled, so this copy is removed and disableDate is
        // never touched off the locked transition.
        // #82 preferred UI locale. Every caller loads the User from the DB before mutating it, so a
        // caller that does not touch the locale passes through the stored value unchanged; only the
        // self-service POST /user sets a new one. Included in this explicit copy list because
        // @DynamicUpdate keeps an unmodified column out of the UPDATE otherwise (same reason the
        // other fields are copied here rather than relying on the managed entity).
        userDb.setLocale(user.getLocale());
        // #147 preferred dark-mode flag. Same rationale as the locale copy above: every caller loads the
        // User from the DB before mutating it, so a caller that does not touch dark mode passes the stored
        // value through unchanged; only the self-service POST /user sets a new one. MANDATORY under
        // @DynamicUpdate — an unmodified column is kept out of the UPDATE, so omitting this copy makes the
        // server-side write silently no-op.
        userDb.setDarkMode(user.getDarkMode());

        // Create audit log
        AuditLogUtil.create(userDb, AuditLogType.UPDATE, userId);
        
        return user;
    }
    
    /**
     * Locks a user's row FOR UPDATE regardless of its delete state (dialect-portable
     * {@code PESSIMISTIC_WRITE}), returning the freshly re-read managed instance so a caller can mutate
     * running storage accounting on a value that reflects every committed reservation/reclaim — never a
     * stale pre-lock copy. Unlike {@link #getActiveByIdForUpdate(String)} this does NOT filter on
     * {@code deleteDate is null}, so it also locks a RETAINED soft-deleted uploader (the #55 ghost
     * key-holder, kept because a document was reassigned away from it). Returns {@code null} if the row
     * does not exist, so a reclaim after destructive work never throws — it credits the still-present
     * row or skips cleanly. The canonical quota lock order is GLOBAL sentinel first, then this row.
     *
     * @param id User ID (any delete state)
     * @return the locked, freshly re-read user, or {@code null} if the row is absent
     */
    public User getByIdForUpdate(String id) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        Query q = em.createQuery("select u from User u where u.id = :id");
        q.setParameter("id", id);
        q.setLockMode(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        try {
            return (User) q.getSingleResult();
        } catch (NoResultException e) {
            return null;
        }
    }

    /**
     * Update the user password.
     * 
     * @param user User to update
     * @param userId User ID
     * @return Updated user
     */
    public User updatePassword(User user, String userId) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        
        // Get the user
        Query q = em.createQuery("select u from User u where u.id = :id and u.deleteDate is null");
        q.setParameter("id", user.getId());
        User userDb = (User) q.getSingleResult();

        // Update the user
        userDb.setPassword(hashPassword(user.getPassword()));
        
        // Create audit log
        AuditLogUtil.create(userDb, AuditLogType.UPDATE, userId);
        
        return user;
    }

    /**
     * Changes a user's OWN password under a pessimistic row lock, conditional on the credential epoch
     * observed when the current password was verified. The password hash and the epoch are read from the
     * SAME {@code SELECT ... FOR UPDATE} row version, so verification and update cannot straddle a
     * concurrent bump: if the locked epoch no longer equals {@code verifiedEpoch} a credential-invalidating
     * event (e.g. a completed recovery reset) landed in between and MUST win — the change is abandoned and
     * this returns {@code -1} without touching the password. On success it hashes and stores the new
     * password, advances the epoch (the single atomic in-place increment, which kills every existing
     * session AND API key of this user), and returns the authoritative post-bump epoch read back from the
     * row with a SCALAR NATIVE query — never the managed entity, whose cached epoch the native bump does
     * not refresh — so the caller can stamp the rotated replacement session with the just-bumped value.
     *
     * @param userId User ID
     * @param clearPassword New clear-text password to hash and store
     * @param verifiedEpoch The credential epoch observed when the current password was verified
     * @param actingUserId Acting user ID (for the update audit log)
     * @return the new (post-bump) credential epoch on success, or {@code -1} when a concurrent credential
     *         change moved the epoch off the verified value (the update is abandoned, the other change wins)
     */
    public long changeOwnPassword(String userId, String clearPassword, long verifiedEpoch, String actingUserId) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        Query q = em.createQuery("select u from User u where u.id = :id and u.deleteDate is null");
        q.setParameter("id", userId);
        q.setLockMode(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        User userDb;
        try {
            userDb = (User) q.getSingleResult();
        } catch (NoResultException e) {
            return -1L;
        }
        // Fail closed on exact inequality: a locked epoch below OR above the verified value both mean the
        // verification-time snapshot is stale, so the change is abandoned rather than allowed to overwrite
        // the newer credential state.
        if (userDb.getCredentialEpoch() != verifiedEpoch) {
            return -1L;
        }
        userDb.setPassword(hashPassword(clearPassword));
        // Flush the password change before the native bump/re-read so the row already carries it and the
        // scalar re-read below reflects the whole in-transaction state.
        em.flush();
        int updated = bumpCredentialEpoch(userId);
        if (updated != 1) {
            return -1L;
        }
        Query reread = em.createNativeQuery(
                "select USE_CREDENTIALEPOCH_N from T_USER where USE_ID_C = :id and USE_DELETEDATE_D is null");
        reread.setParameter("id", userId);
        long newEpoch = ((Number) reread.getSingleResult()).longValue();

        AuditLogUtil.create(userDb, AuditLogType.UPDATE, actingUserId);

        return newEpoch;
    }

    /**
     * Update the hashed password silently.
     *
     * @param user User to update
     * @return Updated user
     */
    public User updateHashedPassword(User user) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();

        // Get the user
        Query q = em.createQuery("select u from User u where u.id = :id and u.deleteDate is null");
        q.setParameter("id", user.getId());
        User userDb = (User) q.getSingleResult();

        // Update the user
        userDb.setPassword(user.getPassword());

        // The startup admin-password-init path mutates an EXISTING account's credential, so it bumps the
        // epoch under the uniform rule: any session or API key stamped at the pre-init epoch is invalidated
        // once the configured password takes effect. Flush the password first so it and the native bump
        // both land before commit.
        em.flush();
        bumpCredentialEpoch(userDb.getId());

        return user;
    }

    /**
     * Update the onboarding status.
     *
     * @param user User to update
     * @return Updated user
     */
    public User updateOnboarding(User user) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();

        // Get the user
        Query q = em.createQuery("select u from User u where u.id = :id and u.deleteDate is null");
        q.setParameter("id", user.getId());
        User userDb = (User) q.getSingleResult();

        // Update the user
        userDb.setOnboarding(user.isOnboarding());

        return user;
    }

    /**
     * Gets a user by its ID.
     * 
     * @param id User ID
     * @return User
     */
    public User getById(String id) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        try {
            return em.find(User.class, id);
        } catch (NoResultException e) {
            return null;
        }
    }

    /**
     * Resolves usernames for a set of user IDs in a single query. Deliberately unfiltered on delete
     * date so a since-deleted user still resolves, mirroring the document-creator join in
     * {@link DocumentDao#getDocument} (a file's creator stays displayable after its uploader is
     * removed). Callers batch the distinct IDs of a file list here to avoid a per-file lookup.
     *
     * @param ids User IDs to resolve (may be empty)
     * @return Map of user ID to username; an ID with no matching user row is absent from the map
     */
    public Map<String, String> getUsernamesByIds(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyMap();
        }
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        Query q = em.createQuery("select u.id, u.username from User u where u.id in :ids");
        q.setParameter("ids", ids);
        Map<String, String> usernamesById = new HashMap<>();
        for (Object result : q.getResultList()) {
            Object[] row = (Object[]) result;
            usernamesById.put((String) row[0], (String) row[1]);
        }
        return usernamesById;
    }
    
    /**
     * Gets an active (non-deleted) user by ID, locking its row FOR UPDATE for the remainder of the
     * caller's transaction (dialect-portable {@code PESSIMISTIC_WRITE} — H2 and Postgres both emit
     * SELECT ... FOR UPDATE). This exists to close the reassignment-target TOCTOU: the delete-with-
     * reassignment flow validates the target active, then reassigns documents to it. Without a lock a
     * concurrent deletion of the target could commit in between, leaving documents owned by a
     * soft-deleted user (which clean_storage would later purge). Locking + re-checking active here
     * means a concurrent target-deletion either has not committed (this blocks until it does) or has
     * committed (the row is now soft-deleted and this returns null), so the caller fails cleanly rather
     * than stranding documents on a dead owner.
     *
     * @param id User ID
     * @return The locked active user, or null if it does not exist or is soft-deleted
     */
    public User getActiveByIdForUpdate(String id) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        Query q = em.createQuery("select u from User u where u.id = :id and u.deleteDate is null");
        q.setParameter("id", id);
        q.setLockMode(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        try {
            return (User) q.getSingleResult();
        } catch (NoResultException e) {
            return null;
        }
    }

    /**
     * Gets an active user by its username.
     *
     * @param username User's username
     * @return User
     */
    public User getActiveByUsername(String username) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        try {
            Query q = em.createQuery("select u from User u where u.username = :username and u.deleteDate is null");
            q.setParameter("username", username);
            return (User) q.getSingleResult();
        } catch (NoResultException e) {
            return null;
        }
    }

    /**
     * Gets an active user by its username, matching case-insensitively.
     *
     * <p>Unlike {@link #getActiveByUsername(String)} (an exact {@code =} match, which is
     * case-sensitive on PostgreSQL), this folds case on both sides. It exists for the
     * LDAP-provisioning hijack guard (BL-020): before creating a shadow account for an
     * LDAP uid, the handler must detect ANY existing user under a case-variant username
     * — otherwise on PostgreSQL a local {@code admin} and an LDAP {@code ADMIN} coexist.
     * If multiple case-variants somehow exist, the first row is returned.
     *
     * @param username User's username (matched case-insensitively)
     * @return User, or null if none exists under any case-variant
     */
    public User getActiveByUsernameIgnoreCase(String username) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        Query q = em.createQuery("select u from User u where lower(u.username) = lower(:username) and u.deleteDate is null");
        q.setParameter("username", username);
        @SuppressWarnings("unchecked")
        List<User> users = q.getResultList();
        if (users.isEmpty()) {
            return null;
        }
        return users.get(0);
    }

    /**
     * Gets an active user by its email.
     *
     * @param email User's email
     * @return The user when exactly one active user matches; null when no active user matches or the email is ambiguous
     */
    public User getByEmail(String email) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        Query q = em.createQuery("select u from User u where u.email = :email and u.deleteDate is null");
        q.setParameter("email", email);
        @SuppressWarnings("unchecked")
        List<User> users = q.getResultList();
        return users.size() == 1 ? users.get(0) : null;
    }

    /**
     * Returns whether an email belongs to multiple active users.
     *
     * @param email User's email
     * @return True if multiple active users match
     */
    public boolean hasMultipleActiveUsersByEmail(String email) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        Query q = em.createQuery("select count(u) from User u where u.email = :email and u.deleteDate is null");
        q.setParameter("email", email);
        return (Long) q.getSingleResult() > 1;
    }

    /**
     * Gets an active user by OIDC issuer and subject.
     *
     * @param issuer OIDC issuer URL
     * @param subject OIDC subject identifier
     * @return User
     */
    public User getByOidcSubject(String issuer, String subject) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        try {
            Query q = em.createQuery("select u from User u where u.oidcIssuer = :issuer and u.oidcSubject = :subject and u.deleteDate is null");
            q.setParameter("issuer", issuer);
            q.setParameter("subject", subject);
            return (User) q.getSingleResult();
        } catch (NoResultException e) {
            return null;
        }
    }

    /**
     * Atomically advances a user's credential epoch by one, in a single in-place SQL statement
     * ({@code set USE_CREDENTIALEPOCH_N = USE_CREDENTIALEPOCH_N + 1}) — never a read-modify-write. This is
     * the SOLE writer of the epoch after insert: doing the increment in the database means two concurrent
     * bumps compose to +2 (the row lock the UPDATE takes serializes them) and an unrelated
     * {@link #update(User, String)} — which does not carry the epoch in its copy list — cannot overwrite or
     * lower a just-bumped value. Scoped to an active (non-deleted) user; a deleted user already dead-ends
     * every credential via the eligibility predicate, so bumping it would be a no-op with no rows matched.
     * Portable across H2 and PostgreSQL (a plain arithmetic UPDATE).
     *
     * @param userId User ID
     * @return the number of rows updated (1 for an active user, 0 if absent or soft-deleted)
     */
    public int bumpCredentialEpoch(String userId) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        Query q = em.createNativeQuery(
                "update T_USER set USE_CREDENTIALEPOCH_N = USE_CREDENTIALEPOCH_N + 1"
                        + " where USE_ID_C = :userId and USE_DELETEDATE_D is null");
        q.setParameter("userId", userId);
        return q.executeUpdate();
    }

    /**
     * Stores a freshly generated TOTP secret as the account's PENDING enrollment key, as a compare-and-swap
     * conditioned on the account still having NO active key. A returned 1 means the pending key was written
     * (a first enrollment, or a fresh secret replacing an earlier un-activated pending one); a returned 0
     * means an active key already exists (or the user is gone), so the caller rejects the enable as
     * already-enabled rather than letting a second enrollment coexist with an active key. The single
     * bulk UPDATE takes the row write lock, so this cannot straddle a concurrent activation. On success the
     * user-update audit event is preserved (the same event {@link #update} recorded for a TOTP change).
     *
     * @param userId User ID
     * @param pendingKey New pending TOTP secret to store
     * @param actingUserId Acting user ID (for the update audit log)
     * @return the number of rows updated: 1 when the pending key was stored, 0 when an active key already exists
     */
    public int setPendingTotpKey(String userId, String pendingKey, String actingUserId) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        Query q = em.createQuery("update User u set u.totpKeyPending = :pendingKey"
                + " where u.id = :id and u.totpKey is null and u.deleteDate is null");
        q.setParameter("pendingKey", pendingKey);
        q.setParameter("id", userId);
        int updated = q.executeUpdate();
        if (updated == 1) {
            AuditLogUtil.create(getById(userId), AuditLogType.UPDATE, actingUserId);
        }
        return updated;
    }

    /**
     * Promotes a pending TOTP enrollment to the active key as a compare-and-swap: it sets the active key to
     * {@code expectedPendingKey} and clears the pending column ONLY while the pending column still equals
     * {@code expectedPendingKey} AND no active key exists. A returned 0 is the fail-closed signal — a
     * concurrent admin recovery ({@link #clearTotpKeys}) cleared the pending key, or the enrollment was
     * already activated, in between the caller verifying the code and this write — so the caller aborts
     * activation and never resurrects a cleared key. The single bulk UPDATE takes the row write lock, so
     * exactly one of two concurrent activations of the same pending key observes 1. On success the
     * user-update audit event is preserved.
     *
     * @param userId User ID
     * @param expectedPendingKey The pending secret the caller verified the submitted code against
     * @param actingUserId Acting user ID (for the update audit log)
     * @return the number of rows updated: 1 when the pending key was promoted, 0 when it was stale/cleared
     */
    public int activateTotpKey(String userId, String expectedPendingKey, String actingUserId) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        Query q = em.createQuery("update User u set u.totpKey = :expected, u.totpKeyPending = null"
                + " where u.id = :id and u.totpKeyPending = :expected and u.totpKey is null and u.deleteDate is null");
        q.setParameter("expected", expectedPendingKey);
        q.setParameter("id", userId);
        int updated = q.executeUpdate();
        if (updated == 1) {
            AuditLogUtil.create(getById(userId), AuditLogType.UPDATE, actingUserId);
        }
        return updated;
    }

    /**
     * Clears BOTH TOTP columns (active and pending) atomically in one bulk UPDATE — the sole disable path,
     * shared by the self-service disable and the admin recovery disable. Clearing both in a single statement
     * means an in-flight activation racing this disable resolves deterministically: the activation's own
     * compare-and-swap ({@link #activateTotpKey}) will observe 0 rows once this has cleared the pending key,
     * so a disabled account can never be silently re-armed by a straggling activation. Idempotent: a
     * returned 0 (nothing to clear, or user gone) is not an error. On a real clear the user-update audit
     * event is preserved.
     *
     * @param userId User ID
     * @param actingUserId Acting user ID (for the update audit log)
     * @return the number of rows updated (1 when a row was cleared, 0 when absent/soft-deleted)
     */
    public int clearTotpKeys(String userId, String actingUserId) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        Query q = em.createQuery("update User u set u.totpKey = null, u.totpKeyPending = null"
                + " where u.id = :id and u.deleteDate is null");
        q.setParameter("id", userId);
        int updated = q.executeUpdate();
        if (updated == 1) {
            AuditLogUtil.create(getById(userId), AuditLogType.UPDATE, actingUserId);
        }
        return updated;
    }

    /**
     * Reassigns the ownership of a departing user's ACTIVE documents to a target user, and moves ALL of the
     * departing user's active tags to the target so no tag is left with a soft-deleted owner and no surviving
     * document is left carrying a tag that would be orphaned by the deletion (#122, broadened by #134).
     *
     * <p>Only {@code DOC_IDUSER_C} (document ownership) is reassigned — NEVER a file's
     * {@code FIL_IDUSER_C}. Each file is encrypted with its uploader's key and is never re-encrypted,
     * so decryption must keep resolving through the original (soft-deleted, retained) uploader
     * (see {@code FileResource} decryption via {@code file.getUserId()}). Reassigning the file's
     * uploader column would break decryption of already-stored bytes.</p>
     *
     * <p>Ownership does NOT by itself grant the target access (access is ACL-driven): the caller must
     * create READ+WRITE ACLs for the target on each returned document.</p>
     *
     * @param departingUserId The user being deleted, whose documents are reassigned away
     * @param targetUserId The surviving user who becomes the new owner
     * @return The IDs of the documents that were reassigned (may be empty, never null)
     */
    @SuppressWarnings("unchecked")
    public List<String> reassignOwnedDocuments(String departingUserId, String targetUserId) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();

        // Snapshot the departing user's ACTIVE documents up front. Every subsequent mutation is scoped
        // to EXACTLY this snapshot (never a broad `where userId = departing`), so the set returned to the
        // caller — used to spare files and exclude deletion events — cannot diverge from the set actually
        // reassigned. A document created concurrently after this snapshot is not in it, is not reassigned,
        // and follows the normal owner-scoped trash path (its file soft-deleted with the user): consistent,
        // no orphan, no file destroyed out from under a reassigned document.
        Query q = em.createQuery("select d.id from Document d where d.userId = :departingUserId and d.deleteDate is null");
        q.setParameter("departingUserId", departingUserId);
        List<String> reassignedDocumentIds = q.getResultList();
        // NOTE: no early return on an empty document set — the #122 tag handling below must still run, because
        // a tag the departing user solely owns can be applied to ANOTHER user's document even when the
        // departing user owns no documents of their own (that tag would otherwise be orphaned by the delete).

        // #122/#134: capture EVERY active tag OWNED by the departing user BEFORE moving ownership (once moved
        // they are indistinguishable from the target's own tags). These are moved to the target so
        // clean_storage's orphan-tag purge — keyed on the tag owner being soft-deleted — does not soft- then
        // hard-delete them and cascade the loss of a surviving document's tag links (the #54-class tag-loss
        // failure). #134 broadens the snapshot from "owned tags currently linked to a surviving document" to
        // ALL active owned tags: reassignment no longer depends on link state, so a tag the departing user
        // owns but that is NOT currently linked to any surviving document (unlinked, or linked only to a
        // foreign document) is no longer missed and left with a soft-deleted owner and no editor — the admin
        // reassign path now preserves it exactly as the self-delete refusal guard would have required. Only
        // tags OWNED by the departing user are moved; a shared tag owned by someone else is untouched.
        //
        // RESIDUAL (not closed here): a tag CREATED by the departing user AFTER this snapshot but before the
        // delete commits is still missed — same root cause as the deferred #133 concurrent-link case. Closing
        // it would require serializing against the hot tag-create/tag-link path (a tag-creation lock), which is
        // out of scope for this change.
        Query tagSel = em.createQuery("select t.id from Tag t where t.userId = :departingUserId and t.deleteDate is null");
        tagSel.setParameter("departingUserId", departingUserId);
        List<String> reassignedTagIds = tagSel.getResultList();

        // Reassign document ownership (DOC_IDUSER_C) FIRST — before the tag rows are locked and mutated
        // below — so this path takes the DOCUMENT row lock before the TAG row lock (#137). That matches the
        // canonical USER -> DOCUMENT -> TAG order the FK-forced hot tag-link path already imposes: inserting
        // a T_DOCUMENT_TAG row checks its DOCUMENT foreign key before its TAG foreign key and so locks the
        // parent document row before the parent tag row. A batch path that locked the tag first would invert
        // that order and could deadlock against a concurrent tag-link. Scoped to the exact snapshot and
        // guarded on a non-empty set: an `in :docIds` bind with an empty list is both pointless and unsafe on
        // some dialects.
        if (!reassignedDocumentIds.isEmpty()) {
            Query docUpd = em.createQuery("update Document d set d.userId = :targetUserId where d.id in :docIds and d.deleteDate is null");
            docUpd.setParameter("targetUserId", targetUserId);
            docUpd.setParameter("docIds", reassignedDocumentIds);
            docUpd.executeUpdate();
        }

        // Move those tags' ownership to the target.
        if (!reassignedTagIds.isEmpty()) {
            // #121 canonical lock order: lock each affected tag row FOR UPDATE in a stable ascending id
            // order BEFORE mutating, so this reassignment serializes against any concurrent grant/revoke on
            // the same tag (which take the same tag-row lock) and two multi-tag lockers cannot deadlock. The
            // departing+target user rows are already locked by the caller (users sort before tags in the
            // canonical type order) and the documents were reassigned just above, so the full acquisition
            // order across this delete is user -> document -> tag — the same order the FK-forced hot
            // tag-link path imposes (#137), so the two paths cannot deadlock.
            List<String> tagLockOrder = new ArrayList<>(reassignedTagIds);
            Collections.sort(tagLockOrder);
            TagDao tagDao = new TagDao();
            for (String tagId : tagLockOrder) {
                tagDao.getByIdForUpdate(tagId);
            }

            Query tagUpd = em.createQuery("update Tag t set t.userId = :targetUserId where t.id in :tagIds and t.deleteDate is null");
            tagUpd.setParameter("targetUserId", targetUserId);
            tagUpd.setParameter("tagIds", reassignedTagIds);
            tagUpd.executeUpdate();

            // A tag's access is ACL-driven (its owner gets direct READ+WRITE USER ACLs at creation). The
            // departing user's tag ACLs are soft-deleted with the user (delete() clears all ACLs targeting
            // the departing user), so without this the target would OWN the moved tag but be unable to
            // read/use/delete it. Grant the target the same direct READ+WRITE USER ACLs on each moved tag,
            // idempotently (skip a permission the target already has a direct USER ACL row for).
            AclDao aclDao = new AclDao();
            for (String tagId : reassignedTagIds) {
                for (PermType perm : new PermType[] { PermType.READ, PermType.WRITE }) {
                    if (!aclDao.hasDirectUserAcl(tagId, perm, targetUserId)) {
                        Acl acl = new Acl();
                        acl.setPerm(perm);
                        acl.setType(AclType.USER);
                        acl.setSourceId(tagId);
                        acl.setTargetId(targetUserId);
                        aclDao.create(acl, targetUserId);
                    }
                }
            }
        }

        return reassignedDocumentIds;
    }

    /**
     * Deletes a user.
     *
     * @param username User's username
     * @param userId User ID
     */
    public void delete(String username, String userId) {
        delete(username, userId, java.util.Collections.emptySet());
    }

    /**
     * Deletes a user, optionally sparing the files that back documents reassigned to another user.
     *
     * <p>When a departing user's documents were reassigned to a surviving target (see
     * {@link #reassignOwnedDocuments(String, String)}), the files backing those documents must NOT be
     * soft-deleted here: they keep {@code FIL_IDUSER_C = departing} (the encryption key holder) and
     * stay live so the target can still open/decrypt them. Their document ids are supplied in
     * {@code reassignedDocumentIds} and excluded from the owner-scoped file soft-delete below.</p>
     *
     * @param username User's username
     * @param userId User ID
     * @param reassignedDocumentIds IDs of documents reassigned to a surviving user; their files are
     *        spared from the owner-scoped soft-delete (may be empty, never null)
     */
    public void delete(String username, String userId, java.util.Set<String> reassignedDocumentIds) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();

        // Get the user
        Query q = em.createQuery("select u from User u where u.username = :username and u.deleteDate is null");
        q.setParameter("username", username);
        User userDb = (User) q.getSingleResult();
        
        // Delete the user
        Date dateNow = new Date();
        userDb.setDeleteDate(dateNow);

        // Delete linked data
        q = em.createQuery("delete from AuthenticationToken at where at.userId = :userId");
        q.setParameter("userId", userDb.getId());
        q.executeUpdate();

        // Favorites are private, hard-deletable membership rows: the deleted user's stars are
        // removed outright (there is no soft-delete column, and they must not resurface).
        q = em.createQuery("delete from Favorite f where f.userId = :userId");
        q.setParameter("userId", userDb.getId());
        q.executeUpdate();

        // For each of the user's OWNED documents, cancel any ACTIVE route and clear its ROUTING ACLs
        // BEFORE the documents are trashed, as ONE coherent per-document operation sharing the SAME
        // dateNow used for the trash below. cancelActiveRoutesForDocument ends the route CANCELLED,
        // system-ends its open steps, and soft-deletes ALL the document's ROUTING ACLs with the
        // deterministic (dateNow - 1ms) timestamp — guaranteed distinct from the document's own trash
        // timestamp (dateNow) even when the deleted user is BOTH the owner and a step target (there is
        // no independent clock sample to collide), so restore-from-trash cannot resurrect the grant.
        //
        // Each owned document is LOCKED FOR UPDATE (getActiveByIdForUpdate) first; the lock is held to
        // transaction commit, so it spans both this cancel and the bulk document-trash below. A
        // concurrent route-start on the same document — which takes the same row lock — therefore
        // blocks until this deletion commits, then re-reads the row as trashed and is rejected
        // NotFound. That closes the TOCTOU window a bare scan-then-trash would leave open (a start
        // slipping in between and creating a fresh ACTIVE route on a document about to be trashed).
        //
        // NOTE: file trashing deliberately stays with the OWNER-scoped bulk File update below (only
        // the deleted user's OWN files), NOT the per-document file trash. A collaborator's file
        // uploaded into the owner's document is intentionally left stranded (deleteDate == null) for
        // the trash-purge quota reclaim to charge back to its own uploader (BL-021). Routing owned
        // docs through DocumentDao.delete (which trashes files by documentId) would over-trash those
        // collaborator files and break that reclaim invariant, so the route/ACL cancellation is done
        // directly here rather than via the full document-delete path.
        DocumentDao documentDao = new DocumentDao();
        // Lock the owned documents in a STABLE ascending order by document ID. Two concurrent
        // multi-document lockers (e.g. two user-deletions, or a user-delete vs a batch trash) that
        // acquired overlapping document locks in different orders could deadlock on doc-vs-doc; a
        // single global ordering removes that cycle.
        List<Document> ownedDocuments = new ArrayList<>(documentDao.findByUserId(userDb.getId()));
        ownedDocuments.sort(java.util.Comparator.comparing(Document::getId));
        for (Document ownedDocument : ownedDocuments) {
            documentDao.getActiveByIdForUpdate(ownedDocument.getId());
            com.sismics.docs.core.util.PrincipalDeletionUtil.cancelActiveRoutesForDocument(
                    ownedDocument.getId(), userId, dateNow);
        }

        q = em.createQuery("update Document d set d.deleteDate = :dateNow where d.userId = :userId and d.deleteDate is null");
        q.setParameter("userId", userDb.getId());
        q.setParameter("dateNow", dateNow);
        q.executeUpdate();

        // Soft-delete the departing user's OWN files, EXCEPT those backing a document reassigned to a
        // surviving user: those files stay live (with FIL_IDUSER_C = departing, the retained key
        // holder) so the target can still open/decrypt the reassigned document's content. A file with
        // a null documentId (never attached to a document) is always eligible for soft-delete.
        if (reassignedDocumentIds == null || reassignedDocumentIds.isEmpty()) {
            q = em.createQuery("update File f set f.deleteDate = :dateNow where f.userId = :userId and f.deleteDate is null");
            q.setParameter("userId", userDb.getId());
            q.setParameter("dateNow", dateNow);
            q.executeUpdate();
        } else {
            q = em.createQuery("update File f set f.deleteDate = :dateNow where f.userId = :userId and f.deleteDate is null"
                    + " and (f.documentId is null or f.documentId not in :reassignedDocumentIds)");
            q.setParameter("userId", userDb.getId());
            q.setParameter("dateNow", dateNow);
            q.setParameter("reassignedDocumentIds", reassignedDocumentIds);
            q.executeUpdate();
        }
        
        q = em.createQuery("update Acl a set a.deleteDate = :dateNow where a.targetId = :userId and a.deleteDate is null");
        q.setParameter("userId", userDb.getId());
        q.setParameter("dateNow", dateNow);
        q.executeUpdate();
        
        q = em.createQuery("update Comment c set c.deleteDate = :dateNow where c.userId = :userId and c.deleteDate is null");
        q.setParameter("userId", userDb.getId());
        q.setParameter("dateNow", dateNow);
        q.executeUpdate();
        
        // Create audit log
        AuditLogUtil.create(userDb, AuditLogType.DELETE, userId);
    }

    /**
     * Hash the user's password.
     * 
     * @param password Clear password
     * @return Hashed password
     */
    private String hashPassword(String password) {
        return BCrypt.withDefaults().hashToString(resolveBcryptWork(), password.toCharArray());
    }

    /**
     * Resolves the bcrypt work factor to use for password hashing: {@link Constants#DEFAULT_BCRYPT_WORK}
     * unless overridden by the {@link Constants#BCRYPT_WORK_ENV} environment variable (validated to the
     * bcrypt-legal 4..31 range). Shared by real password hashing and the dummy timing-equalizer hash so both
     * spend the same amount of work.
     *
     * @return the bcrypt work factor
     */
    private static int resolveBcryptWork() {
        int bcryptWork = Constants.DEFAULT_BCRYPT_WORK;
        String envBcryptWork = System.getenv(Constants.BCRYPT_WORK_ENV);
        if (!Strings.isNullOrEmpty(envBcryptWork)) {
            try {
                int envBcryptWorkInt = Integer.parseInt(envBcryptWork);
                if (envBcryptWorkInt >= 4 && envBcryptWorkInt <= 31) {
                    bcryptWork = envBcryptWorkInt;
                } else {
                    log.warn(Constants.BCRYPT_WORK_ENV + " needs to be in range 4...31. Falling back to " + Constants.DEFAULT_BCRYPT_WORK + ".");
                }
            } catch (NumberFormatException e) {
                log.warn(Constants.BCRYPT_WORK_ENV + " needs to be a number in range 4...31. Falling back to " + Constants.DEFAULT_BCRYPT_WORK + ".");
            }
        }
        return bcryptWork;
    }
    
    /**
     * Returns the list of all users.
     * 
     * @param criteria Search criteria
     * @param sortCriteria Sort criteria
     * @return List of users
     */
    public List<UserDto> findByCriteria(UserCriteria criteria, SortCriteria sortCriteria) {
        Map<String, Object> parameterMap = new HashMap<>();
        List<String> criteriaList = new ArrayList<>();
        
        StringBuilder sb = new StringBuilder("select u.USE_ID_C as c0, u.USE_USERNAME_C as c1, u.USE_EMAIL_C as c2, u.USE_CREATEDATE_D as c3, u.USE_STORAGECURRENT_N as c4, u.USE_STORAGEQUOTA_N as c5, u.USE_TOTPKEY_C as c6, u.USE_DISABLEDATE_D as c7, u.USE_IDROLE_C as c8");
        sb.append(" from T_USER u ");
        
        // Add search criterias
        if (criteria.getSearch() != null) {
            criteriaList.add("lower(u.USE_USERNAME_C) like lower(:search)");
            parameterMap.put("search", "%" + criteria.getSearch() + "%");
        }
        if (criteria.getUserId() != null) {
            criteriaList.add("u.USE_ID_C = :userId");
            parameterMap.put("userId", criteria.getUserId());
        }
        if (criteria.getUserName() != null) {
            criteriaList.add("u.USE_USERNAME_C = :userName");
            parameterMap.put("userName", criteria.getUserName());
        }
        if (criteria.getGroupId() != null) {
            sb.append(" join T_USER_GROUP ug on ug.UGP_IDUSER_C = u.USE_ID_C and ug.UGP_IDGROUP_C = :groupId and ug.UGP_DELETEDATE_D is null ");
            parameterMap.put("groupId", criteria.getGroupId());
        }
        
        criteriaList.add("u.USE_DELETEDATE_D is null");
        
        if (!criteriaList.isEmpty()) {
            sb.append(" where ");
            sb.append(Joiner.on(" and ").join(criteriaList));
        }
        
        // Perform the search
        QueryParam queryParam = QueryUtil.getSortedQueryParam(new QueryParam(sb.toString(), parameterMap), sortCriteria);
        @SuppressWarnings("unchecked")
        List<Object[]> l = QueryUtil.getNativeQuery(queryParam).getResultList();
        
        // Assemble results
        List<UserDto> userDtoList = new ArrayList<>();
        for (Object[] o : l) {
            int i = 0;
            UserDto userDto = new UserDto();
            userDto.setId((String) o[i++]);
            userDto.setUsername((String) o[i++]);
            userDto.setEmail((String) o[i++]);
            userDto.setCreateTimestamp(((Timestamp) o[i++]).getTime());
            userDto.setStorageCurrent(((Number) o[i++]).longValue());
            userDto.setStorageQuota(((Number) o[i++]).longValue());
            userDto.setTotpKey((String) o[i++]);
            if (o[i] != null) {
                userDto.setDisableTimestamp(((Timestamp) o[i]).getTime());
            }
            i++;
            userDto.setRoleId((String) o[i]);
            userDtoList.add(userDto);
        }
        return userDtoList;
    }

    /**
     * Returns the global storage physically occupied across the whole installation, in bytes — the value
     * the {@code DOCS_GLOBAL_QUOTA} cross-user cap is compared against.
     *
     * <p>This is NOT simply the sum of the active users' {@code USE_STORAGECURRENT_N} counters. A
     * reassign-delete (#55) retains the departing user's files that back documents reassigned to a
     * surviving user: those files stay live (with {@code FIL_IDUSER_C} = the departing, now soft-deleted
     * "ghost" key holder) so the target can still decrypt them. The ghost's counter is deliberately NOT
     * kept in step by the delete ({@code FileDeletedAsyncListener} never touches quota; the stale counter
     * is only corrected by a later {@code clean_storage} purge), and the ghost is soft-deleted, so its
     * counter is both stale AND excluded from an active-user sum — the retained bytes would go uncounted
     * (#99) and the global cap would silently under-count. So ghost usage is taken from the FILES that
     * survive, not from the ghost's counter: global storage = the active-user counter sum PLUS the
     * non-negative {@code FIL_SIZE_N} of every live file owned by a soft-deleted user.</p>
     *
     * <p>All the database work is ONE statement, so the counter sum and the ghost-retained file rows come
     * from a single statement-level snapshot. This is load-bearing: a reassign-delete
     * ({@code UserResource}) runs OUTSIDE the {@code GLOBAL_QUOTA_LOCK}, so if the counter sum and the
     * ghost-file scan were separate reads a commit landing between them could exclude the ghost's counter
     * AND miss its now-retained file — a quota-bypass window. The three UNION-ALL arms carry a discriminator
     * ({@code rowkind}): arm 0 is the active-user counter sum, arm 1 is the known-size ghost-retained bytes,
     * and arm 2 emits one row per ghost-retained file whose stored size is {@link File#UNKNOWN_SIZE}
     * ({@code FIL_SIZE_N = -1}, a legacy value from dbupdate-029) so Java can resolve those — and only
     * those — from the still-present on-disk encrypted content via the same {@link FileUtil#getFileSize}
     * pattern the quota-reclaim paths use (an unresolvable file contributes 0, never a negative).</p>
     *
     * @return Current global storage in bytes
     */
    public long getGlobalStorageCurrent() {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        Query query = em.createNativeQuery(
                "select 0 as rowkind, cast(coalesce(sum(u.USE_STORAGECURRENT_N), 0) as bigint) as amount,"
                        + " cast(null as varchar(36)) as file_id, cast(null as varchar(36)) as owner_id"
                        + " from T_USER u where u.USE_DELETEDATE_D is null"
                        + " union all"
                        + " select 1 as rowkind, cast(coalesce(sum(f.FIL_SIZE_N), 0) as bigint) as amount,"
                        + " cast(null as varchar(36)) as file_id, cast(null as varchar(36)) as owner_id"
                        + " from T_FILE f join T_USER fu on fu.USE_ID_C = f.FIL_IDUSER_C"
                        + " where f.FIL_DELETEDATE_D is null and fu.USE_DELETEDATE_D is not null and f.FIL_SIZE_N >= 0"
                        + " union all"
                        + " select 2 as rowkind, cast(null as bigint) as amount,"
                        + " f.FIL_ID_C as file_id, f.FIL_IDUSER_C as owner_id"
                        + " from T_FILE f join T_USER fu on fu.USE_ID_C = f.FIL_IDUSER_C"
                        + " where f.FIL_DELETEDATE_D is null and fu.USE_DELETEDATE_D is not null and f.FIL_SIZE_N = -1");

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();

        long total = 0L;
        // (fileId, ownerId) of each ghost-retained file whose stored size is unknown and must be sized on disk.
        List<String[]> unknownSizeFiles = new ArrayList<>();
        for (Object[] row : rows) {
            int rowKind = ((Number) row[0]).intValue();
            if (rowKind == 2) {
                unknownSizeFiles.add(new String[] { (String) row[2], (String) row[3] });
            } else if (row[1] != null) {
                total += ((Number) row[1]).longValue();
            }
        }

        // Resolve the unknown-size ghost files from disk (owner private key needed to decrypt-and-count).
        // Cache owners: several retained files can share one ghost key holder.
        Map<String, User> ownerCache = new HashMap<>();
        for (String[] unknown : unknownSizeFiles) {
            String fileId = unknown[0];
            String ownerId = unknown[1];
            User owner = ownerCache.computeIfAbsent(ownerId, this::getById);
            total += FileUtil.resolveReclaimableSize(fileId, File.UNKNOWN_SIZE, owner);
        }
        return total;
    }

    /**
     * Returns the number of active users.
     *
     * @return Number of active users
     */
    public long getActiveUserCount() {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        Query query = em.createNativeQuery("select count(u.USE_ID_C) from T_USER u where u.USE_DELETEDATE_D is null and (u.USE_DISABLEDATE_D is null or u.USE_DISABLEDATE_D >= :fromDate and u.USE_DISABLEDATE_D < :toDate)");
        LocalDate fromDate = LocalDate.now().minusMonths(1).withDayOfMonth(1);
        LocalDate toDate = fromDate.plusMonths(1);
        query.setParameter("fromDate", Date.from(fromDate.atStartOfDay(ZoneId.systemDefault()).toInstant()));
        query.setParameter("toDate", Date.from(toDate.atStartOfDay(ZoneId.systemDefault()).toInstant()));
        return ((Number) query.getSingleResult()).longValue();
    }
}
