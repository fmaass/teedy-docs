package com.sismics.docs.core.util;

import com.sismics.docs.core.dao.AclDao;
import com.sismics.docs.core.dao.DocumentDao;
import com.sismics.docs.core.dao.RouteModelDao;
import com.sismics.docs.core.dao.TagDao;
import com.sismics.docs.core.dao.UserDao;
import com.sismics.docs.core.model.jpa.User;
import com.sismics.util.context.ThreadLocalContext;

import jakarta.persistence.EntityManager;

/**
 * Application-facing facade over the credential-lifecycle DAO primitives: the uniform epoch bump, the
 * conditional self password-change, and the #111 owner-row lock / self-delete guard. REST resources call
 * THIS util rather than the DAOs directly, so the Phase 2 lifecycle wiring stays OFF the frozen legacy
 * {@code rest.resource -> core.dao} edge (the slice-migration ratchet) while the operations themselves
 * remain in the DAO layer where the concurrency semantics live.
 */
public final class CredentialLifecycleUtil {
    private CredentialLifecycleUtil() {
        // Utility class
    }

    /**
     * Advances a user's credential epoch by one — the uniform revocation for a credential-invalidating
     * event, killing every existing session AND API key of that user.
     *
     * @param userId User ID
     */
    public static void bumpEpoch(String userId) {
        new UserDao().bumpCredentialEpoch(userId);
    }

    /**
     * Conditional self password-change under a pessimistic row lock; see
     * {@link UserDao#changeOwnPassword(String, String, long, String)}.
     *
     * @return the post-bump epoch, or {@code -1} when a concurrent credential change won and the update
     *         was abandoned
     */
    public static long changeOwnPassword(String userId, String clearPassword, long verifiedEpoch, String actingUserId) {
        return new UserDao().changeOwnPassword(userId, clearPassword, verifiedEpoch, actingUserId);
    }

    /**
     * The user's CURRENT credential epoch, read fresh from the row with a scalar native query — never the
     * managed entity, whose cached epoch a native bump in the same transaction does not refresh. Used to
     * stamp a session rotated right after a same-transaction self-change bump.
     *
     * @param userId User ID
     * @return the current persisted credential epoch
     */
    public static long currentEpoch(String userId) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        Number epoch = (Number) em.createNativeQuery(
                        "select USE_CREDENTIALEPOCH_N from T_USER where USE_ID_C = :id")
                .setParameter("id", userId).getSingleResult();
        return epoch.longValue();
    }

    /**
     * Locks an active user's row FOR UPDATE for the rest of the caller's transaction (the #111 owner-row
     * lock), or returns null if the user does not exist or is soft-deleted.
     *
     * @param userId User ID
     * @return the locked active user, or null
     */
    public static User lockActiveUser(String userId) {
        return new UserDao().getActiveByIdForUpdate(userId);
    }

    /**
     * #111 self-delete guard: true when the owner still has an active document carrying an active DIRECT
     * grant to another principal.
     *
     * @param ownerId Owner user ID
     * @return true if the owner is sharing documents with other principals
     */
    public static boolean hasSharedDocuments(String ownerId) {
        return new DocumentDao().hasActiveDocumentsSharedToOthers(ownerId);
    }

    /**
     * True when a document row (any state) exists for the id — tells a document ACL source (which
     * participates in the #111 owner-row lock) from a tag / route-model source (which does not).
     *
     * @param documentId Source ID
     * @return true if a document row exists
     */
    public static boolean documentExists(String documentId) {
        return new DocumentDao().existsById(documentId);
    }

    /**
     * #111 grant-path owner-row lock; see {@link DocumentDao#lockOwnerForGrant(String)}.
     *
     * @param documentId Document ID
     * @return the locked current owner id, or null to abort the grant
     */
    public static String lockDocumentOwnerForGrant(String documentId) {
        return new DocumentDao().lockOwnerForGrant(documentId);
    }

    /**
     * #121 grant-path SOURCE serialization: locks the ACL source row FOR UPDATE (held to the caller's
     * transaction commit) BEFORE the add duplicate-check, so two concurrent identical grants on the same
     * source serialize — the second blocks on the row, then re-reads the now-present grant under
     * READ_COMMITTED and skips the insert, leaving exactly one {@code (source, perm, target)} row.
     *
     * <p>The source is polymorphic. A DOCUMENT source keeps the #111 owner-row lock
     * ({@link DocumentDao#lockOwnerForGrant}); this returns {@code false} — the caller must ABORT — when
     * that owner cannot be locked active (the document was deleted / its owner reassigned away). A TAG
     * source locks the tag row ({@link TagDao#getByIdForUpdate}, any state). A ROUTE-MODEL source locks the
     * ACTIVE route-model row ({@link RouteModelDao#getActiveByIdForUpdate}) and returns {@code false} when no
     * such row could be locked — a soft-deleted route model OR a {@code sourceId} matching no
     * document/tag/route-model — so the caller aborts (NotFoundException) rather than inserting an
     * UNSERIALIZED (and dangling) grant on an absent source.</p>
     *
     * <p>Only one source row is locked per grant, so within a single grant there is no lock-ordering
     * concern; across paths the canonical acquisition order is user rows, then document rows, then tag rows
     * (#137 — the order the FK-forced hot tag-link path imposes, since inserting a {@code T_DOCUMENT_TAG}
     * row locks its parent document row before its parent tag row). The {@code T_ACL} table carries no
     * foreign keys, so an insert takes no implicit lock on the target user's row — the grant path is
     * deadlock-free by construction.</p>
     *
     * @param sourceId ACL source entity ID
     * @return {@code false} only when a DOCUMENT source's owner cannot be locked active (abort the grant);
     *         {@code true} otherwise
     */
    public static boolean lockAclSourceForGrant(String sourceId) {
        DocumentDao documentDao = new DocumentDao();
        if (documentDao.existsById(sourceId)) {
            return documentDao.lockOwnerForGrant(sourceId) != null;
        }
        if (new TagDao().getByIdForUpdate(sourceId) != null) {
            return true;
        }
        // Route-model source: lock the ACTIVE route-model row and return whether it was actually lockable.
        // A soft-deleted route model (null) or a sourceId matching no document/tag/route-model returns false,
        // so AclResource.add aborts (NotFoundException) instead of inserting an unserialized/dangling grant.
        return new RouteModelDao().getActiveByIdForUpdate(sourceId) != null;
    }

    /**
     * #122 self-delete guard; see {@link AclDao#hasSoleWriteTagLinkedToForeignDocument(String)}. True when
     * soft-deleting {@code userId} would strand a tag with zero WRITE holders that a SURVIVING document
     * (owned by another user) still uses — the self-delete has no reassignment target, so it must be
     * refused rather than silently orphan the tag (which clean_storage would later purge, stripping the tag
     * from the other owner's document).
     *
     * @param userId Departing user ID
     * @return true if the departing user is the sole WRITE holder of a tag a surviving foreign document uses
     */
    public static boolean hasSoleWriteTagLinkedToSurvivingDocument(String userId) {
        return new AclDao().hasSoleWriteTagLinkedToForeignDocument(userId);
    }
}
