package com.sismics.docs.core.dao;

import com.sismics.docs.core.constant.AclType;
import com.sismics.docs.core.constant.AuditLogType;
import com.sismics.docs.core.constant.PermType;
import com.sismics.docs.core.dao.dto.DocumentDto;
import com.sismics.docs.core.model.jpa.Document;
import com.sismics.docs.core.model.jpa.User;
import com.sismics.docs.core.util.AuditLogUtil;
import com.sismics.docs.core.util.DescriptionSanitizer;
import com.sismics.docs.core.util.PrincipalDeletionUtil;
import com.sismics.util.context.ThreadLocalContext;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.NoResultException;
import jakarta.persistence.Query;
import jakarta.persistence.TypedQuery;
import java.sql.Timestamp;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Document DAO.
 * 
 * @author bgamard
 */
public class DocumentDao {
    /**
     * Creates a new document.
     * 
     * @param document Document
     * @param userId User ID
     * @return New ID
     */
    public String create(Document document, String userId) {
        // Create the UUID
        document.setId(UUID.randomUUID().toString());
        document.setUpdateDate(new Date());
        
        // Create the document
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        em.persist(document);
        
        // Create audit log
        AuditLogUtil.create(document, AuditLogType.CREATE, userId);
        
        return document.getId();
    }
    
    /**
     * True when the given user still OWNS at least one ACTIVE document carrying an ACTIVE DIRECT grant to
     * a principal OTHER than themselves — the #111 self-delete guard. "Direct" means an ACL whose source is
     * the document itself; a tag-inherited grant (source = a tag) is NOT joined here and dies with the
     * documents, as today. ROUTING grants are excluded (a route is deliberately canceled on deletion and
     * must not block it), and the owner's own base grants (target == owner) are excluded. Any remaining
     * grant — to another user, a group, or a share link — means the account is sharing documents and must
     * not be silently deleted. The count is done in one native statement so it composes with the owner-row
     * lock the caller holds.
     *
     * @param ownerId Owner user ID
     * @return true if the owner has an active document directly shared to another principal
     */
    public boolean hasActiveDocumentsSharedToOthers(String ownerId) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        Query q = em.createNativeQuery("select count(a.ACL_ID_C) from T_ACL a"
                + " join T_DOCUMENT d on d.DOC_ID_C = a.ACL_SOURCEID_C"
                + " where d.DOC_IDUSER_C = :ownerId and d.DOC_DELETEDATE_D is null"
                + " and a.ACL_DELETEDATE_D is null and a.ACL_TYPE_C = :type"
                + " and a.ACL_TARGETID_C <> :ownerId");
        q.setParameter("ownerId", ownerId);
        q.setParameter("type", AclType.USER.name());
        return ((Number) q.getSingleResult()).longValue() > 0;
    }

    /**
     * The CURRENT owner id of an ACTIVE document, read fresh from the row with a scalar native query so it
     * reflects committed cross-transaction changes (an entity read could return an identity-map-cached
     * instance whose fields predate a concurrent reassignment). Returns null when the document is absent or
     * trashed.
     *
     * @param id Document ID
     * @return the active document's owner id, or null if absent/trashed
     */
    public String getOwnerIfActive(String id) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        Query q = em.createNativeQuery(
                "select d.DOC_IDUSER_C from T_DOCUMENT d where d.DOC_ID_C = :id and d.DOC_DELETEDATE_D is null");
        q.setParameter("id", id);
        try {
            return (String) q.getSingleResult();
        } catch (NoResultException e) {
            return null;
        }
    }

    /**
     * True when a document row exists for the id in ANY state (active or trashed). Lets the ACL-grant path
     * tell a document source (which participates in the #111 owner-row lock protocol) from a tag or
     * route-model source (which does not).
     *
     * @param id Source ID
     * @return true if a T_DOCUMENT row exists for the id
     */
    public boolean existsById(String id) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        Query q = em.createNativeQuery("select count(d.DOC_ID_C) from T_DOCUMENT d where d.DOC_ID_C = :id");
        q.setParameter("id", id);
        return ((Number) q.getSingleResult()).longValue() > 0;
    }

    /**
     * #111 grant-path serialization: acquires a {@code SELECT ... FOR UPDATE} lock (eligibility-scoped) on
     * the CURRENT owner's user row for an active document and returns that owner id — the same owner-row
     * lock a self-delete of that owner takes, so a direct share/ACL grant and a self-delete on the owner's
     * documents serialize. The lock is held to the caller's transaction commit.
     *
     * <p>Returns null (the grant must ABORT, fail closed) when the document is absent/trashed, or when the
     * owner's user row cannot be locked active within the bounded retry budget. The retry loop handles a
     * concurrent ownership reassignment: if the locked owner turns out no longer to own the document (an
     * admin reassign-delete moved it while this waited), it re-reads the current owner and re-locks it,
     * WITHOUT releasing the previously-acquired lock (it persists to transaction end — harmless extra
     * contention), so a grant can never ride a stale owner lock past a transfer into a new owner's
     * self-delete.</p>
     *
     * @param documentId Document ID whose owner is to be locked
     * @return the current active owner id with its user row locked FOR UPDATE, or null to abort
     */
    public String lockOwnerForGrant(String documentId) {
        UserDao userDao = new UserDao();
        final int maxAttempts = 5;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            String ownerId = getOwnerIfActive(documentId);
            if (ownerId == null) {
                return null;
            }
            User lockedOwner = userDao.getActiveByIdForUpdate(ownerId);
            if (lockedOwner == null) {
                // The owner was soft-deleted while we waited: the document was either trashed (the next
                // re-read returns null and aborts) or reassigned to a new active owner (the next re-read
                // returns that owner). Retry against the freshly-read owner.
                continue;
            }
            String ownerAfterLock = getOwnerIfActive(documentId);
            if (ownerAfterLock == null) {
                return null;
            }
            if (ownerId.equals(ownerAfterLock)) {
                return ownerId;
            }
            // Ownership changed under us (reassignment). Loop to lock the current owner; the prior lock is
            // intentionally kept.
        }
        return null;
    }

    /**
     * Returns the list of all active documents.
     *
     * @param offset Offset
     * @param limit Limit
     * @return List of documents
     */
    public List<Document> findAll(int offset, int limit) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        TypedQuery<Document> q = em.createQuery("select d from Document d where d.deleteDate is null", Document.class);
        q.setFirstResult(offset);
        q.setMaxResults(limit);
        return q.getResultList();
    }

    /**
     * Returns the list of all active documents from a user.
     * 
     * @param userId User ID
     * @return List of documents
     */
    public List<Document> findByUserId(String userId) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        TypedQuery<Document> q = em.createQuery("select d from Document d where d.userId = :userId and d.deleteDate is null", Document.class);
        q.setParameter("userId", userId);
        return q.getResultList();
    }

    /**
     * Counts the active (non-trashed) documents owned by a user without loading them.
     *
     * <p>Used as a preflight bound for the full-account export so an over-cap account
     * can be rejected before its documents are eagerly loaded into heap.</p>
     *
     * @param userId User ID
     * @return Number of active documents owned by the user
     */
    public long countByUserId(String userId) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        TypedQuery<Long> q = em.createQuery("select count(d) from Document d where d.userId = :userId and d.deleteDate is null", Long.class);
        q.setParameter("userId", userId);
        return q.getSingleResult();
    }

    /**
     * Returns the list of all trashed (soft-deleted) documents owned by a user.
     *
     * @param userId User ID
     * @return List of trashed documents
     */
    public List<Document> findDeletedByUserId(String userId) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        TypedQuery<Document> q = em.createQuery("select d from Document d where d.userId = :userId and d.deleteDate is not null", Document.class);
        q.setParameter("userId", userId);
        return q.getResultList();
    }

    /**
     * Returns an active document with permission checking.
     * 
     * @param id Document ID
     * @param perm Permission needed
     * @param targetIdList List of targets
     * @return Document
     */
    public DocumentDto getDocument(String id, PermType perm, List<String> targetIdList) {
        AclDao aclDao = new AclDao();
        if (!aclDao.checkPermission(id, perm, targetIdList)) {
            return null;
        }

        EntityManager em = ThreadLocalContext.get().getEntityManager();
        StringBuilder sb = new StringBuilder("select distinct d.DOC_ID_C, d.DOC_TITLE_C, d.DOC_DESCRIPTION_C, d.DOC_SUBJECT_C, d.DOC_IDENTIFIER_C, d.DOC_PUBLISHER_C, d.DOC_FORMAT_C, d.DOC_SOURCE_C, d.DOC_TYPE_C, d.DOC_COVERAGE_C, d.DOC_RIGHTS_C, d.DOC_CREATEDATE_D, d.DOC_UPDATEDATE_D, d.DOC_LANGUAGE_C, d.DOC_IDFILE_C,");
        sb.append(" (select count(s.SHA_ID_C) from T_SHARE s, T_ACL ac where ac.ACL_SOURCEID_C = d.DOC_ID_C and ac.ACL_TARGETID_C = s.SHA_ID_C and ac.ACL_DELETEDATE_D is null and s.SHA_DELETEDATE_D is null) shareCount, ");
        sb.append(" (select count(f.FIL_ID_C) from T_FILE f where f.FIL_DELETEDATE_D is null and f.FIL_IDDOC_C = d.DOC_ID_C) fileCount, ");
        sb.append(" u.USE_USERNAME_C ");
        sb.append(" from T_DOCUMENT d ");
        sb.append(" join T_USER u on d.DOC_IDUSER_C = u.USE_ID_C ");
        sb.append(" where d.DOC_ID_C = :id and d.DOC_DELETEDATE_D is null ");

        Query q = em.createNativeQuery(sb.toString());
        q.setParameter("id", id);

        Object[] o;
        try {
            o = (Object[]) q.getSingleResult();
        } catch (NoResultException e) {
            return null;
        }
        
        DocumentDto documentDto = new DocumentDto();
        int i = 0;
        documentDto.setId((String) o[i++]);
        documentDto.setTitle((String) o[i++]);
        documentDto.setDescription((String) o[i++]);
        documentDto.setSubject((String) o[i++]);
        documentDto.setIdentifier((String) o[i++]);
        documentDto.setPublisher((String) o[i++]);
        documentDto.setFormat((String) o[i++]);
        documentDto.setSource((String) o[i++]);
        documentDto.setType((String) o[i++]);
        documentDto.setCoverage((String) o[i++]);
        documentDto.setRights((String) o[i++]);
        documentDto.setCreateTimestamp(((Timestamp) o[i++]).getTime());
        documentDto.setUpdateTimestamp(((Timestamp) o[i++]).getTime());
        documentDto.setLanguage((String) o[i++]);
        documentDto.setFileId((String) o[i++]);
        documentDto.setShared(((Number) o[i++]).intValue() > 0);
        documentDto.setFileCount(((Number) o[i++]).intValue());
        documentDto.setCreator((String) o[i]);
        return documentDto;
    }
    
    /**
     * Deletes a document.
     * 
     * @param id Document ID
     * @param userId User ID
     */
    public void delete(String id, String userId) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();

        // Get the document and lock its row FOR UPDATE (dialect-portable PESSIMISTIC_WRITE). This
        // serializes the trash against a concurrent route-start on the same document (route-start
        // takes the same row lock via getActiveByIdForUpdate): a start cannot slip in between this
        // cancel-and-trash and create a fresh ACTIVE route on a document being trashed. The lock is
        // held for the rest of this transaction.
        TypedQuery<Document> dq = em.createQuery("select d from Document d where d.id = :id and d.deleteDate is null", Document.class);
        dq.setParameter("id", id);
        dq.setLockMode(LockModeType.PESSIMISTIC_WRITE);
        Document documentDb = dq.getSingleResult();

        // Delete the document
        Date dateNow = new Date();
        documentDb.setDeleteDate(dateNow);

        // Cancel any ACTIVE route on the document before the generic ACL soft-delete below: the
        // route is ended CANCELLED, its open steps are system-ended, and its transient ROUTING ACLs
        // are soft-deleted with a timestamp DETERMINISTICALLY derived from dateNow (dateNow - 1ms) so
        // it is guaranteed distinct from the document's own delete timestamp. This is what prevents
        // restore() — which un-deletes the document's ACLs by exact-equality match on dateNow — from
        // resurrecting the routing ACL of a cancelled route. Must run first so the ROUTING ACLs are
        // already deleted and skipped by the bulk ACL update (which only touches still-active ACLs).
        PrincipalDeletionUtil.cancelActiveRoutesForDocument(id, userId, dateNow);

        // Delete linked data
        Query q = em.createQuery("update File f set f.deleteDate = :dateNow where f.documentId = :documentId and f.deleteDate is null");
        q.setParameter("documentId", id);
        q.setParameter("dateNow", dateNow);
        q.executeUpdate();
        
        q = em.createQuery("update Acl a set a.deleteDate = :dateNow where a.sourceId = :documentId and a.deleteDate is null");
        q.setParameter("documentId", id);
        q.setParameter("dateNow", dateNow);
        q.executeUpdate();
        
        q = em.createQuery("update DocumentTag dt set dt.deleteDate = :dateNow where dt.documentId = :documentId and dt.deleteDate is null");
        q.setParameter("documentId", id);
        q.setParameter("dateNow", dateNow);
        q.executeUpdate();
        
        q = em.createQuery("update Relation r set r.deleteDate = :dateNow where (r.fromDocumentId = :documentId or r.toDocumentId = :documentId) and r.deleteDate is null");
        q.setParameter("documentId", id);
        q.setParameter("dateNow", dateNow);
        q.executeUpdate();
        
        // Create audit log
        AuditLogUtil.create(documentDb, AuditLogType.DELETE, userId);
    }
    
    /**
     * Gets an active document by its ID.
     * 
     * @param id Document ID
     * @return Document
     */
    public Document getById(String id) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        TypedQuery<Document> q = em.createQuery("select d from Document d where d.id = :id and d.deleteDate is null", Document.class);
        q.setParameter("id", id);
        try {
            return q.getSingleResult();
        } catch (NoResultException e) {
            return null;
        }
    }
    
    /**
     * Gets an active (non-trashed) document by its ID and acquires a pessimistic write lock on its
     * row (SELECT ... FOR UPDATE, dialect-portable via LockModeType.PESSIMISTIC_WRITE — H2 and
     * Postgres both emit FOR UPDATE). The lock serializes concurrent callers on the same document
     * for the remainder of the caller's transaction: it is used by route-start to make the
     * "no active route already exists" check-and-create atomic, so two concurrent starts on one
     * document cannot both create an ACTIVE route. A trashed/deleted document returns null (the
     * row's deleteDate is set) — callers must treat that as not-found REGARDLESS of any ACL check,
     * since admins bypass the ACL check and could otherwise start a route on a trashed document.
     *
     * @param id Document ID
     * @return The locked active document, or null if it does not exist or is trashed/deleted
     */
    public Document getActiveByIdForUpdate(String id) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        TypedQuery<Document> q = em.createQuery("select d from Document d where d.id = :id and d.deleteDate is null", Document.class);
        q.setParameter("id", id);
        q.setLockMode(LockModeType.PESSIMISTIC_WRITE);
        try {
            return q.getSingleResult();
        } catch (NoResultException e) {
            return null;
        }
    }

    /**
     * Acquires a pessimistic write lock (SELECT ... FOR UPDATE, dialect-portable via
     * LockModeType.PESSIMISTIC_WRITE) on a document row by ID REGARDLESS of its deleteDate — the row
     * is locked whether the document is active or already trashed. This exists to enforce the single
     * global lock order DOCUMENT-before-ROUTE from paths that must lock the document of a route whose
     * document may already be trashed (e.g. the principal-deletion target-cancel path): getActiveByIdForUpdate
     * filters out trashed rows and would return null without locking, so it cannot be used there.
     * The lock is held for the remainder of the caller's transaction.
     *
     * @param id Document ID
     * @return true if a document row (active or trashed) was found and locked, false if none exists
     */
    public boolean lockByIdForUpdate(String id) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        TypedQuery<Document> q = em.createQuery("select d from Document d where d.id = :id", Document.class);
        q.setParameter("id", id);
        q.setLockMode(LockModeType.PESSIMISTIC_WRITE);
        try {
            q.getSingleResult();
            return true;
        } catch (NoResultException e) {
            return false;
        }
    }

    /**
     * Update a document and log the action.
     *
     * @param document Document to update
     * @param userId User ID
     * @return Updated document
     */
    public Document update(Document document, String userId) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();

        // Get the document
        TypedQuery<Document> q = em.createQuery("select d from Document d where d.id = :id and d.deleteDate is null", Document.class);
        q.setParameter("id", document.getId());
        Document documentDb = q.getSingleResult();

        // Update the document
        documentDb.setTitle(document.getTitle());
        // Intrinsic guard: re-sanitize at the entity write boundary so no caller can bypass
        // description sanitization. Idempotent over already-sanitized input.
        documentDb.setDescription(DescriptionSanitizer.sanitize(document.getDescription()));
        documentDb.setSubject(document.getSubject());
        documentDb.setIdentifier(document.getIdentifier());
        documentDb.setPublisher(document.getPublisher());
        documentDb.setFormat(document.getFormat());
        documentDb.setSource(document.getSource());
        documentDb.setType(document.getType());
        documentDb.setCoverage(document.getCoverage());
        documentDb.setRights(document.getRights());
        documentDb.setCreateDate(document.getCreateDate());
        documentDb.setLanguage(document.getLanguage());
        documentDb.setFileId(document.getFileId());
        documentDb.setUpdateDate(new Date());
        
        // Create audit log
        AuditLogUtil.create(documentDb, AuditLogType.UPDATE, userId);
        
        return documentDb;
    }

    /**
     * Update the file ID on a document.
     *
     * @param document Document
     */
    public void updateFileId(Document document) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        Query query = em.createNativeQuery("update T_DOCUMENT d set DOC_IDFILE_C = :fileId, DOC_UPDATEDATE_D = :updateDate where d.DOC_ID_C = :id");
        query.setParameter("updateDate", new Date());
        query.setParameter("fileId", document.getFileId());
        query.setParameter("id", document.getId());
        query.executeUpdate();
    }

    /**
     * Gets a soft-deleted document by its ID, scoped to its owner.
     *
     * <p>The owner filter is part of the signature so cross-user misuse is impossible
     * at the DAO level: a caller cannot fetch (and then restore/purge) a trashed
     * document owned by another user. For the non-user-scoped system purge, use
     * {@link #getDeletedByIdSystem(String)}.
     *
     * @param id Document ID
     * @param userId Owner user ID the document must belong to
     * @return Document or null (also null if it exists but is owned by another user)
     */
    public Document getDeletedById(String id, String userId) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        TypedQuery<Document> q = em.createQuery("select d from Document d where d.id = :id and d.userId = :userId and d.deleteDate is not null", Document.class);
        q.setParameter("id", id);
        q.setParameter("userId", userId);
        try {
            return q.getSingleResult();
        } catch (NoResultException e) {
            return null;
        }
    }

    /**
     * Gets a soft-deleted document by its ID without an owner filter.
     *
     * <p>Reserved for system-level, non-user-scoped operations (e.g. the scheduled
     * trash purge) that must act on every user's trash. User-facing endpoints must
     * use {@link #getDeletedById(String, String)} instead.
     *
     * @param id Document ID
     * @return Document or null
     */
    public Document getDeletedByIdSystem(String id) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        TypedQuery<Document> q = em.createQuery("select d from Document d where d.id = :id and d.deleteDate is not null", Document.class);
        q.setParameter("id", id);
        try {
            return q.getSingleResult();
        } catch (NoResultException e) {
            return null;
        }
    }

    /**
     * Restores a soft-deleted document and its linked data.
     *
     * @param id Document ID
     * @param userId User ID
     */
    public void restore(String id, String userId) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();

        TypedQuery<Document> dq = em.createQuery("select d from Document d where d.id = :id and d.deleteDate is not null", Document.class);
        dq.setParameter("id", id);
        Document documentDb = dq.getSingleResult();

        Date deletedAt = documentDb.getDeleteDate();
        documentDb.setDeleteDate(null);

        // Restore linked data that was soft-deleted at the same time
        Query q = em.createQuery("update File f set f.deleteDate = null where f.documentId = :documentId and f.deleteDate = :deletedAt");
        q.setParameter("documentId", id);
        q.setParameter("deletedAt", deletedAt);
        q.executeUpdate();

        q = em.createQuery("update Acl a set a.deleteDate = null where a.sourceId = :documentId and a.deleteDate = :deletedAt");
        q.setParameter("documentId", id);
        q.setParameter("deletedAt", deletedAt);
        q.executeUpdate();

        q = em.createQuery("update DocumentTag dt set dt.deleteDate = null where dt.documentId = :documentId and dt.deleteDate = :deletedAt");
        q.setParameter("documentId", id);
        q.setParameter("deletedAt", deletedAt);
        q.executeUpdate();

        q = em.createQuery("update Relation r set r.deleteDate = null where (r.fromDocumentId = :documentId or r.toDocumentId = :documentId) and r.deleteDate = :deletedAt");
        q.setParameter("documentId", id);
        q.setParameter("deletedAt", deletedAt);
        q.executeUpdate();

        AuditLogUtil.create(documentDb, AuditLogType.CREATE, userId);
    }

    /**
     * Permanently deletes a document and all its linked data from the database.
     *
     * @param id Document ID
     */
    public void permanentDelete(String id) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();

        em.createNativeQuery("delete from T_DOCUMENT_TAG where DOT_IDDOCUMENT_C = :id").setParameter("id", id).executeUpdate();
        em.createNativeQuery("delete from T_FAVORITE where FAV_IDDOCUMENT_C = :id").setParameter("id", id).executeUpdate();
        em.createNativeQuery("delete from T_DOCUMENT_METADATA where DME_IDDOCUMENT_C = :id").setParameter("id", id).executeUpdate();
        em.createNativeQuery("delete from T_CONTRIBUTOR where CTR_IDDOC_C = :id").setParameter("id", id).executeUpdate();
        em.createNativeQuery("delete from T_ACL where ACL_SOURCEID_C = :id").setParameter("id", id).executeUpdate();
        em.createNativeQuery("delete from T_RELATION where REL_IDDOCFROM_C = :id or REL_IDDOCTO_C = :id").setParameter("id", id).executeUpdate();
        em.createNativeQuery("delete from T_COMMENT where COM_IDDOC_C = :id").setParameter("id", id).executeUpdate();
        em.createNativeQuery("delete from T_AUDIT_LOG where LOG_IDENTITY_C = :id").setParameter("id", id).executeUpdate();
        // Route steps before routes before the document: both FKs are `on delete restrict`.
        em.createNativeQuery("delete from T_ROUTE_STEP where RTP_IDROUTE_C in (select RTE_ID_C from T_ROUTE where RTE_IDDOCUMENT_C = :id)").setParameter("id", id).executeUpdate();
        em.createNativeQuery("delete from T_ROUTE where RTE_IDDOCUMENT_C = :id").setParameter("id", id).executeUpdate();
        em.createNativeQuery("update T_DOCUMENT set DOC_IDFILE_C = null where DOC_ID_C = :id").setParameter("id", id).executeUpdate();
        em.createNativeQuery("delete from T_FILE where FIL_IDDOC_C = :id").setParameter("id", id).executeUpdate();
        em.createNativeQuery("delete from T_DOCUMENT where DOC_ID_C = :id").setParameter("id", id).executeUpdate();
    }

    /**
     * Finds documents in trash that have expired past the retention period.
     *
     * @param retentionDays Number of days to retain deleted documents
     * @return List of expired document IDs
     */
    public List<String> findExpiredTrash(int retentionDays) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        Query q = em.createNativeQuery("select DOC_ID_C from T_DOCUMENT where DOC_DELETEDATE_D is not null and DOC_DELETEDATE_D < :cutoff");
        q.setParameter("cutoff", new Date(System.currentTimeMillis() - (long) retentionDays * 24 * 60 * 60 * 1000));
        @SuppressWarnings("unchecked")
        List<String> ids = q.getResultList();
        return ids;
    }

    /**
     * Returns the number of documents.
     *
     * @return Number of documents
     */
    public long getDocumentCount() {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        Query query = em.createNativeQuery("select count(d.DOC_ID_C) from T_DOCUMENT d where d.DOC_DELETEDATE_D is null");
        return ((Number) query.getSingleResult()).longValue();
    }
}
