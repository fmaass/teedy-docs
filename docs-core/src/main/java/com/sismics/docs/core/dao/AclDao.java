package com.sismics.docs.core.dao;

import com.sismics.docs.core.constant.AclTargetType;
import com.sismics.docs.core.constant.AclType;
import com.sismics.docs.core.constant.AuditLogType;
import com.sismics.docs.core.constant.PermType;
import com.sismics.docs.core.dao.dto.AclDto;
import com.sismics.docs.core.exception.TagWriteLockoutException;
import com.sismics.docs.core.model.jpa.Acl;
import com.sismics.docs.core.model.jpa.Tag;
import com.sismics.docs.core.util.AuditLogUtil;
import com.sismics.docs.core.util.SecurityUtil;
import com.sismics.util.context.ThreadLocalContext;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * ACL DAO.
 *
 * @author bgamard
 */
public class AclDao {
    /**
     * Creates a new ACL.
     *
     * @param acl ACL
     * @param userId User ID
     * @return New ID
     */
    public String create(Acl acl, String userId) {
        // Create the UUID
        acl.setId(UUID.randomUUID().toString());

        // Create the ACL
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        em.persist(acl);

        // Create audit log
        acl.setAuditMessage(acl.getPerm().name() + " granted to " + resolveTargetName(acl.getTargetId()));
        AuditLogUtil.create(acl, AuditLogType.CREATE, userId);

        return acl.getId();
    }

    /**
     * Search ACLs by target ID.
     *
     * @param targetId Target ID
     * @return ACL list
     */
    @SuppressWarnings("unchecked")
    public List<Acl> getByTargetId(String targetId) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        Query q = em.createQuery("select a from Acl a where a.targetId = :targetId and a.deleteDate is null");
        q.setParameter("targetId", targetId);

        return q.getResultList();
    }

    /**
     * Search ACLs by source ID.
     *
     * @param sourceId Source ID
     * @return ACL DTO list
     */
    @SuppressWarnings("unchecked")
    public List<AclDto> getBySourceId(String sourceId, AclType type) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        StringBuilder sb = new StringBuilder("select a.ACL_ID_C, a.ACL_PERM_C, a.ACL_TARGETID_C, ")
                .append(" u.USE_USERNAME_C, s.SHA_ID_C, s.SHA_NAME_C, g.GRP_NAME_C ")
                .append(" from T_ACL a ")
                .append(" left join T_USER u on u.USE_ID_C = a.ACL_TARGETID_C ")
                .append(" left join T_SHARE s on s.SHA_ID_C = a.ACL_TARGETID_C ")
                .append(" left join T_GROUP g on g.GRP_ID_C = a.ACL_TARGETID_C ")
                .append(" where a.ACL_DELETEDATE_D is null and a.ACL_SOURCEID_C = :sourceId ");
        if (type != null) {
            sb.append(" and a.ACL_TYPE_C = :type");
        }

        // Perform the query
        Query q = em.createNativeQuery(sb.toString());
        q.setParameter("sourceId", sourceId);
        if (type != null) {
            q.setParameter("type", type.name());
        }
        List<Object[]> l = q.getResultList();

        // Assemble results
        List<AclDto> aclDtoList = new ArrayList<>();
        for (Object[] o : l) {
            int i = 0;
            AclDto aclDto = new AclDto();
            aclDto.setId((String) o[i++]);
            aclDto.setPerm(PermType.valueOf((String) o[i++]));
            aclDto.setTargetId((String) o[i++]);
            String userName = (String) o[i++];
            String shareId = (String) o[i++];
            String shareName = (String) o[i++];
            String groupName = (String) o[i];
            if (userName != null) {
                aclDto.setTargetName(userName);
                aclDto.setTargetType(AclTargetType.USER.name());
            }
            if (shareId != null) { // Use ID because share name is nullable
                aclDto.setTargetName(shareName);
                aclDto.setTargetType(AclTargetType.SHARE.name());
            }
            if (groupName != null) {
                aclDto.setTargetName(groupName);
                aclDto.setTargetType(AclTargetType.GROUP.name());
            }
            aclDtoList.add(aclDto);
        }
        return aclDtoList;
    }

    /**
     * Check whether a DIRECT, non-deleted USER-type ACL row grants {@code perm} on {@code sourceId} to
     * {@code targetId}. Unlike {@link #checkPermission(String, PermType, List)} — which answers the
     * broader "is this accessible?" question and returns true via admin-bypass, tag inheritance, or a
     * transient ROUTING ACL — this checks only for the literal direct grant row. It is the correct
     * idempotency probe when the intent is to guarantee a durable direct grant exists (e.g. handing a
     * reassigned document's ownership to a new owner): a transient/inherited access ending must not
     * leave the new owner locked out.
     *
     * @param sourceId ACL source entity ID
     * @param perm Permission
     * @param targetId Target ID
     * @return True if a direct, non-deleted USER ACL row already grants that permission
     */
    public boolean hasDirectUserAcl(String sourceId, PermType perm, String targetId) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        Query q = em.createQuery("select count(a.id) from Acl a where a.sourceId = :sourceId and a.perm = :perm"
                + " and a.targetId = :targetId and a.type = :type and a.deleteDate is null");
        q.setParameter("sourceId", sourceId);
        q.setParameter("perm", perm);
        q.setParameter("targetId", targetId);
        q.setParameter("type", AclType.USER);
        return ((Number) q.getSingleResult()).longValue() > 0;
    }

    /**
     * Counts the DISTINCT holders (target ids) of a given {@link AclType} that hold {@code perm}
     * on {@code sourceId}. Used to enforce the last-owner invariant (a tag must always retain at
     * least one WRITE holder) — the count includes both user and group grants, since a group with
     * WRITE is as much an owner as a user, and counts each holder once even if duplicate rows exist.
     *
     * <p>#135: a holder whose target is a SOFT-DELETED user account is an INERT orphan ACL (a grant that
     * raced a concurrent user soft-delete). It is excluded here so it cannot inflate the holder count and
     * mask a sole-active-owner condition. The exclusion keys on the ACTUAL orphan condition — the target
     * being a soft-deleted {@code T_USER} row — NOT on the ACL type: a grant to a group is stored as a
     * {@code USER}-type ACL whose target id is a {@code T_GROUP} id (see {@code AclResource}), so keying
     * off {@code ACL_TYPE_C} would wrongly zero group WRITE holders. A group/share target has no
     * {@code T_USER} row, and an active user's row is not soft-deleted, so both are counted unchanged;
     * only a soft-deleted-user target is dropped.</p>
     *
     * @param sourceId ACL source entity ID
     * @param perm Permission
     * @param type ACL type
     * @return Number of distinct non-deleted holders
     */
    public long countAcls(String sourceId, PermType perm, AclType type) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        // Count DISTINCT holders, not rows: AclDao.delete removes ALL rows matching a
        // (sourceId, perm, targetId), so a sole holder represented by duplicate rows (a raced
        // double-grant) is still exactly one owner — counting rows would let it defeat the guard.
        Query q = em.createQuery("select count(distinct a.targetId) from Acl a where a.sourceId = :sourceId"
                + " and a.perm = :perm and a.type = :type and a.deleteDate is null"
                + " and not exists ("
                + "   select u.id from User u where u.id = a.targetId and u.deleteDate is not null)");
        q.setParameter("sourceId", sourceId);
        q.setParameter("perm", perm);
        q.setParameter("type", type);
        return ((Number) q.getSingleResult()).longValue();
    }

    /**
     * #122: true when {@code userId} is the SOLE active USER WRITE holder of at least one active tag that
     * is linked (via an active document-tag) to an active document owned by a DIFFERENT user. Used by the
     * self-delete guard (there is no reassignment target on that path): soft-deleting the user would drop
     * such a tag to zero WRITE holders while another owner's surviving document still uses it, and the
     * orphan-tag purge (keyed on the tag owner being soft-deleted) would then strip the tag from that
     * document — a #54-class data loss the guard refuses rather than allows. Distinct holders are counted so
     * duplicate rows of the one holder (a raced double-grant) cannot pose as a second owner.
     *
     * <p>#135: every WRITE-ACL reference additionally excludes a holder whose target is a SOFT-DELETED user
     * (a {@code T_USER} row with a non-null delete date). A grant can race a concurrent user soft-delete and
     * leave an inert orphan ACL targeting a now-deleted account; without this filter that orphan would
     * inflate the distinct-holder count and let the guard wrongly conclude a co-holder survives, so it would
     * NOT refuse a deletion that in fact strands the tag. The exclusion keys on the target being a
     * soft-deleted {@code T_USER} row, NOT on {@code ACL_TYPE_C}: a group grant is a {@code USER}-type ACL
     * whose target is a {@code T_GROUP} id, so a live group WRITE holder (not a {@code T_USER} row) is
     * correctly still counted and keeps the tag from being seen as stranded. Strictly more protective and a
     * no-op in normal operation.</p>
     *
     * @param userId Departing user ID
     * @return true if the user solely holds WRITE on a tag a surviving foreign document uses
     */
    public boolean hasSoleWriteTagLinkedToForeignDocument(String userId) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        Query q = em.createNativeQuery(
                "select count(*) from T_TAG t"
                        + " where t.TAG_DELETEDATE_D is null"
                        + " and exists (select 1 from T_ACL a where a.ACL_SOURCEID_C = t.TAG_ID_C"
                        + "   and a.ACL_PERM_C = 'WRITE' and a.ACL_TYPE_C = 'USER'"
                        + "   and a.ACL_TARGETID_C = :userId and a.ACL_DELETEDATE_D is null"
                        + "   and not exists (select 1 from T_USER u where u.USE_ID_C = a.ACL_TARGETID_C"
                        + "     and u.USE_DELETEDATE_D is not null))"
                        + " and (select count(distinct a2.ACL_TARGETID_C) from T_ACL a2"
                        + "   where a2.ACL_SOURCEID_C = t.TAG_ID_C and a2.ACL_PERM_C = 'WRITE'"
                        + "   and a2.ACL_TYPE_C = 'USER' and a2.ACL_DELETEDATE_D is null"
                        + "   and not exists (select 1 from T_USER u2 where u2.USE_ID_C = a2.ACL_TARGETID_C"
                        + "     and u2.USE_DELETEDATE_D is not null)) <= 1"
                        + " and exists (select 1 from T_DOCUMENT_TAG dt"
                        + "   join T_DOCUMENT d on d.DOC_ID_C = dt.DOT_IDDOCUMENT_C"
                        + "   where dt.DOT_IDTAG_C = t.TAG_ID_C and dt.DOT_DELETEDATE_D is null"
                        + "   and d.DOC_DELETEDATE_D is null and d.DOC_IDUSER_C <> :userId)");
        q.setParameter("userId", userId);
        return ((Number) q.getSingleResult()).longValue() > 0;
    }

    /**
     * Check if a source is accessible to a target.
     *
     * @param sourceId ACL source entity ID
     * @param perm Necessary permission
     * @param targetIdList List of targets
     * @return True if the document is accessible
     */
    public boolean checkPermission(String sourceId, PermType perm, List<String> targetIdList) {
        if (SecurityUtil.skipAclCheck(targetIdList)) {
            return true;
        }
        if (targetIdList.isEmpty()) {
            return false;
        }

        EntityManager em = ThreadLocalContext.get().getEntityManager();
        StringBuilder sb = new StringBuilder("select a.ACL_ID_C from T_ACL a ");
        sb.append(" where a.ACL_TARGETID_C in (:targetIdList) and a.ACL_SOURCEID_C = :sourceId and a.ACL_PERM_C = :perm and a.ACL_DELETEDATE_D is null ");
        sb.append(" union all ");
        sb.append(" select a.ACL_ID_C from T_ACL a, T_DOCUMENT_TAG dt, T_DOCUMENT d ");
        sb.append(" where a.ACL_SOURCEID_C = dt.DOT_IDTAG_C and dt.DOT_IDDOCUMENT_C = d.DOC_ID_C and dt.DOT_DELETEDATE_D is null ");
        sb.append(" and d.DOC_ID_C = :sourceId and d.DOC_DELETEDATE_D is null ");
        sb.append(" and a.ACL_TARGETID_C in (:targetIdList) and a.ACL_PERM_C = :perm and a.ACL_DELETEDATE_D is null ");
        Query q = em.createNativeQuery(sb.toString());
        q.setParameter("sourceId", sourceId);
        q.setParameter("perm", perm.name());
        q.setParameter("targetIdList", targetIdList);

        // We have a matching permission
        return q.getResultList().size() > 0;
    }

    /**
     * Delete an ACL.
     *
     * @param sourceId Source ID
     * @param perm Permission
     * @param targetId Target ID
     * @param userId User ID
     * @param type Type
     * @throws TagWriteLockoutException if this would remove the final WRITE holder of a live tag (#88)
     */
    @SuppressWarnings("unchecked")
    public void delete(String sourceId, PermType perm, String targetId, String userId, AclType type) {
        // #88: the last-WRITE lockout lives HERE so every caller upholds it, not just the REST path.
        // When the source is a live tag and this revokes a USER WRITE grant, lock the tag row FOR
        // UPDATE and refuse if the target holds the sole remaining WRITE — a concurrent revoke on the
        // same tag blocks on the lock, then re-reads one holder under READ_COMMITTED and is refused,
        // so two revokes can never both delete and strand the tag at zero. DISTINCT holders are
        // counted, so duplicate rows of one holder (a raced double-grant) cannot pose as a second
        // owner. (Only the revocation path is guarded; a creator's ACLs removed by user deletion are
        // collected by the orphan-tag purge lifecycle.)
        if (perm == PermType.WRITE && type == AclType.USER) {
            Tag tag = new TagDao().getByIdForUpdate(sourceId);
            if (tag != null && tag.getDeleteDate() == null
                    && hasDirectUserAcl(sourceId, PermType.WRITE, targetId)
                    && countAcls(sourceId, PermType.WRITE, AclType.USER) <= 1) {
                throw new TagWriteLockoutException();
            }
        }

        EntityManager em = ThreadLocalContext.get().getEntityManager();
        String auditMessage = perm.name() + " revoked from " + resolveTargetName(targetId);

        // Create audit log
        Query q = em.createQuery("from Acl a where a.sourceId = :sourceId and a.perm = :perm and a.targetId = :targetId and a.type = :type and a.deleteDate is null");
        q.setParameter("sourceId", sourceId);
        q.setParameter("perm", perm);
        q.setParameter("targetId", targetId);
        q.setParameter("type", type);
        List<Acl> aclList = q.getResultList();
        for (Acl acl : aclList) {
            acl.setAuditMessage(auditMessage);
            AuditLogUtil.create(acl, AuditLogType.DELETE, userId);
        }

        // Soft delete the ACLs
        q = em.createQuery("update Acl a set a.deleteDate = :dateNow where a.sourceId = :sourceId and a.perm = :perm and a.targetId = :targetId and a.type = :type and a.deleteDate is null");
        q.setParameter("sourceId", sourceId);
        q.setParameter("perm", perm);
        q.setParameter("targetId", targetId);
        q.setParameter("type", type);
        q.setParameter("dateNow", new Date());
        q.executeUpdate();
    }

    /**
     * Resolves an ACL target to the label visible to users.
     *
     * @param targetId Target ID
     * @return Target label, falling back to the target ID
     */
    private String resolveTargetName(String targetId) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        Query q = em.createNativeQuery("select coalesce("
                + "(select u.USE_USERNAME_C from T_USER u where u.USE_ID_C = :targetId), "
                + "(select g.GRP_NAME_C from T_GROUP g where g.GRP_ID_C = :targetId), "
                + "(select coalesce(s.SHA_NAME_C, s.SHA_ID_C) from T_SHARE s where s.SHA_ID_C = :targetId), "
                + ":targetId)");
        q.setParameter("targetId", targetId);
        Object targetName = q.getSingleResult();
        return targetName != null ? targetName.toString() : targetId;
    }
}
