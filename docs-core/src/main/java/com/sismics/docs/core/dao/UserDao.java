package com.sismics.docs.core.dao;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
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
import com.sismics.docs.core.model.jpa.User;
import com.sismics.docs.core.util.AuditLogUtil;
import com.sismics.docs.core.util.EncryptionUtil;
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
     * Authenticates an user.
     * 
     * @param username User login
     * @param password User password
     * @return The authenticated user or null
     */
    public User authenticate(String username, String password) {
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

        // Update the user (except password)
        userDb.setEmail(user.getEmail());
        userDb.setStorageQuota(user.getStorageQuota());
        userDb.setStorageCurrent(user.getStorageCurrent());
        userDb.setTotpKey(user.getTotpKey());
        userDb.setDisableDate(user.getDisableDate());

        // Create audit log
        AuditLogUtil.create(userDb, AuditLogType.UPDATE, userId);
        
        return user;
    }
    
    /**
     * Updates a user's quota.
     * 
     * @param user User to update
     */
    public void updateQuota(User user) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();

        // Get the user
        Query q = em.createQuery("select u from User u where u.id = :id and u.deleteDate is null");
        q.setParameter("id", user.getId());
        User userDb = (User) q.getSingleResult();

        // Update the user
        userDb.setStorageCurrent(user.getStorageCurrent());
    }

    /**
     * Sets a user's current storage usage by ID, REGARDLESS of the user's active state, and is a no-op
     * if the user row does not exist. Unlike {@link #updateQuota(User)} — which filters on
     * {@code deleteDate is null} and throws {@code NoResultException} for a soft-deleted user — this is
     * safe to call for a RETAINED soft-deleted uploader (the #55 ghost key-holder, kept because a
     * document was reassigned away from it). A storage-quota reclaim on such a uploader must never throw
     * after destructive work; it credits the (still-present) row or skips cleanly if the row is gone.
     * Used by {@link com.sismics.docs.core.util.FileUtil#reclaimUserQuota} so BOTH clean_storage and the
     * retention purge are safe against a ghost uploader.
     *
     * @param userId User ID (any delete state)
     * @param storageCurrent New storage_current value
     */
    public void updateQuotaById(String userId, long storageCurrent) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        User userDb = em.find(User.class, userId);
        if (userDb == null) {
            return;
        }
        userDb.setStorageCurrent(storageCurrent);
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
     * @return User
     */
    public User getByEmail(String email) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        try {
            Query q = em.createQuery("select u from User u where u.email = :email and u.deleteDate is null");
            q.setParameter("email", email);
            return (User) q.getSingleResult();
        } catch (NoResultException e) {
            return null;
        }
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
     * Stores the OIDC issuer and subject on a user for stable identity binding.
     *
     * @param userId User ID
     * @param issuer OIDC issuer URL
     * @param subject OIDC subject identifier
     */
    public void updateOidcBinding(String userId, String issuer, String subject) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        Query q = em.createNativeQuery("update T_USER set USE_OIDC_ISSUER_C = :issuer, USE_OIDC_SUBJECT_C = :subject where USE_ID_C = :userId");
        q.setParameter("issuer", issuer);
        q.setParameter("subject", subject);
        q.setParameter("userId", userId);
        q.executeUpdate();
    }

    /**
     * Reassigns the ownership of a departing user's ACTIVE documents to a target user, and moves the
     * departing user's tags that are linked to those documents to the target so no tag link is lost.
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
        if (reassignedDocumentIds.isEmpty()) {
            return reassignedDocumentIds;
        }

        // Capture the departing user's tags that are linked (via a live document-tag row) to the
        // snapshotted documents, BEFORE moving ownership (once moved they are indistinguishable from the
        // target's own tags). These are moved to the target so clean_storage's orphan-tag purge — keyed
        // on the tag owner being soft-deleted — does not soft- then hard-delete them and cascade the loss
        // of the surviving reassigned document's tag links (the #54-class FK / tag-loss failure). Only
        // tags OWNED by the departing user are moved; a shared tag owned by someone else is untouched.
        Query tagSel = em.createQuery("select distinct t.id from Tag t where t.userId = :departingUserId and t.deleteDate is null and t.id in ("
                + " select dt.tagId from DocumentTag dt where dt.documentId in :docIds and dt.deleteDate is null)");
        tagSel.setParameter("departingUserId", departingUserId);
        tagSel.setParameter("docIds", reassignedDocumentIds);
        List<String> reassignedTagIds = tagSel.getResultList();

        // Move those tags' ownership to the target.
        if (!reassignedTagIds.isEmpty()) {
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

        // Reassign document ownership (DOC_IDUSER_C) only, scoped to the exact snapshot.
        Query docUpd = em.createQuery("update Document d set d.userId = :targetUserId where d.id in :docIds and d.deleteDate is null");
        docUpd.setParameter("targetUserId", targetUserId);
        docUpd.setParameter("docIds", reassignedDocumentIds);
        docUpd.executeUpdate();

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
        return BCrypt.withDefaults().hashToString(bcryptWork, password.toCharArray());
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
     * Returns the global storage used by all users.
     *
     * @return Current global storage
     */
    public long getGlobalStorageCurrent() {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        Query query = em.createNativeQuery("select sum(u.USE_STORAGECURRENT_N) from T_USER u where u.USE_DELETEDATE_D is null");
        return ((Number) query.getSingleResult()).longValue();
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
