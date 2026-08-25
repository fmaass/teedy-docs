package com.sismics.docs.core.dao;

import com.sismics.docs.core.constant.AccessTargetType;
import com.sismics.docs.core.dao.dto.AccessUserCountDto;
import com.sismics.docs.core.dao.dto.DocumentAccessStatsDto;
import com.sismics.docs.core.model.jpa.AccessEvent;
import com.sismics.docs.core.util.SecurityUtil;
import com.sismics.util.context.ThreadLocalContext;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Access events (#300): the append-only record of who read which document or file and when, plus the
 * aggregations the counters are computed from.
 *
 * <p>There is no maintained counter column anywhere. Every count on this class is a {@code group by}
 * over {@code T_ACCESS_EVENT}, so a count can always be re-derived, scoped to one user, or later
 * scoped to a time window, and the same rows are what an access-history view will read.</p>
 *
 * <p>Every read here is bounded by an index created in dbupdate-066: personal counts hit
 * {@code IDX_ACC_TARGET_USER}, the administrator aggregations hit {@code IDX_ACC_TYPE_TARGET}.</p>
 */
public class AccessEventDao {
    /**
     * Persists one access event into the CALLER's transaction.
     *
     * <p>This is the raw write and is not best-effort by itself: a failure propagates, and the row is
     * flushed with whatever transaction is installed. Production callers therefore go through
     * {@link com.sismics.docs.core.util.AccessRecordingUtil}, which runs it in a short transaction of
     * its own after the serving request has already committed, so a failing insert can never reach the
     * response. Calling this directly from a request path would put the read at risk.</p>
     *
     * @param accessEvent Access event (id and date are assigned here when absent)
     * @return The event ID
     */
    public String create(AccessEvent accessEvent) {
        if (accessEvent.getId() == null) {
            accessEvent.setId(UUID.randomUUID().toString());
        }
        if (accessEvent.getCreateDate() == null) {
            accessEvent.setCreateDate(new Date());
        }

        EntityManager em = ThreadLocalContext.get().getEntityManager();
        em.persist(accessEvent);

        return accessEvent.getId();
    }

    /**
     * Counts how many times ONE user accessed ONE target. This is the personal counter: it can never
     * report another user's reads because the user id is part of the predicate, not of the projection.
     *
     * @param type Target kind
     * @param targetId Target ID
     * @param userId User ID whose own accesses are counted
     * @return Number of recorded accesses
     */
    public long countByTargetAndUser(AccessTargetType type, String targetId, String userId) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        Query q = em.createNativeQuery("select count(a.ACC_ID_C) from T_ACCESS_EVENT a"
                + " where a.ACC_IDTARGET_C = :targetId and a.ACC_TYPE_C = :type and a.ACC_IDUSER_C = :userId");
        q.setParameter("targetId", targetId);
        q.setParameter("type", type.name());
        q.setParameter("userId", userId);
        return ((Number) q.getSingleResult()).longValue();
    }

    /**
     * Counts, in ONE grouped query, how many times one user accessed each of several targets. The file
     * panel lists every file of a document at once, so a per-row query would be an N+1 on a view that
     * already renders N rows.
     *
     * @param type Target kind
     * @param targetIds Target IDs to count
     * @param userId User ID whose own accesses are counted
     * @return Count per target ID; a target with no recorded access is ABSENT from the map
     */
    public Map<String, Long> countByTargetsAndUser(AccessTargetType type, Collection<String> targetIds, String userId) {
        if (targetIds == null || targetIds.isEmpty()) {
            return Collections.emptyMap();
        }

        EntityManager em = ThreadLocalContext.get().getEntityManager();
        Query q = em.createNativeQuery("select a.ACC_IDTARGET_C, count(a.ACC_ID_C) from T_ACCESS_EVENT a"
                + " where a.ACC_IDTARGET_C in (:targetIds) and a.ACC_TYPE_C = :type and a.ACC_IDUSER_C = :userId"
                + " group by a.ACC_IDTARGET_C");
        q.setParameter("targetIds", targetIds);
        q.setParameter("type", type.name());
        q.setParameter("userId", userId);

        Map<String, Long> countByTarget = new HashMap<>();
        @SuppressWarnings("unchecked")
        List<Object[]> resultList = q.getResultList();
        for (Object[] o : resultList) {
            countByTarget.put((String) o[0], ((Number) o[1]).longValue());
        }
        return countByTarget;
    }

    /**
     * Counts every recorded access of one kind, across all users and targets. An aggregate with no
     * per-user and no per-document detail, so it discloses nothing about who read what.
     *
     * @param type Target kind
     * @return Total number of recorded accesses
     */
    public long countByType(AccessTargetType type) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        Query q = em.createNativeQuery("select count(a.ACC_ID_C) from T_ACCESS_EVENT a where a.ACC_TYPE_C = :type");
        q.setParameter("type", type.name());
        return ((Number) q.getSingleResult()).longValue();
    }

    /**
     * The most-accessed documents, with their per-user breakdown — the administrator view.
     *
     * <p><b>Visibility.</b> The ranking is restricted to documents the CALLER may read, using the same
     * two-branch ACL rule the rest of the application applies (a direct READ ACL on the document, or a
     * READ ACL on one of its tags), with {@link SecurityUtil#skipAclCheck} short-circuiting it for the
     * administrators group exactly as everywhere else. Ranking by raw popularity without that predicate
     * would turn this screen into a side channel that names documents — titles included — the caller
     * cannot open.</p>
     *
     * <p>Deleted documents are excluded; their access events stay in the table (the history is
     * append-only) but a trashed document is not a "most used document".</p>
     *
     * <p>Two queries, not N+1: one for the ranked page, one grouped breakdown for exactly those ids.</p>
     *
     * @param targetIdList Caller's ACL target list
     * @param limit Maximum number of documents to rank
     * @return Ranked documents, most-accessed first; ties broken by title then id so the order is stable
     */
    public List<DocumentAccessStatsDto> findMostAccessedDocuments(List<String> targetIdList, int limit) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();

        boolean scoped = !SecurityUtil.skipAclCheck(targetIdList);
        if (scoped && targetIdList.isEmpty()) {
            return Collections.emptyList();
        }

        StringBuilder sb = new StringBuilder("select a.ACC_IDTARGET_C, d.DOC_TITLE_C, count(a.ACC_ID_C) c2"
                + " from T_ACCESS_EVENT a"
                + " join T_DOCUMENT d on d.DOC_ID_C = a.ACC_IDTARGET_C and d.DOC_DELETEDATE_D is null"
                + " where a.ACC_TYPE_C = :type ");
        if (scoped) {
            sb.append(aclReadPredicate("d.DOC_ID_C"));
        }
        sb.append(" group by a.ACC_IDTARGET_C, d.DOC_TITLE_C order by c2 desc, d.DOC_TITLE_C asc, a.ACC_IDTARGET_C asc");

        Query q = em.createNativeQuery(sb.toString());
        q.setParameter("type", AccessTargetType.DOCUMENT.name());
        if (scoped) {
            q.setParameter("targetIdList", targetIdList);
        }
        q.setMaxResults(limit);

        @SuppressWarnings("unchecked")
        List<Object[]> rankedList = q.getResultList();
        if (rankedList.isEmpty()) {
            return Collections.emptyList();
        }

        Map<String, DocumentAccessStatsDto> byId = new LinkedHashMap<>();
        List<String> documentIds = new ArrayList<>();
        Map<String, List<AccessUserCountDto>> userCountsById = new HashMap<>();
        for (Object[] o : rankedList) {
            String documentId = (String) o[0];
            documentIds.add(documentId);
            List<AccessUserCountDto> userCounts = new ArrayList<>();
            userCountsById.put(documentId, userCounts);
            byId.put(documentId, new DocumentAccessStatsDto(
                    documentId, (String) o[1], ((Number) o[2]).longValue(), userCounts));
        }

        // Per-user breakdown for exactly the ranked ids. Rows whose user no longer exists are dropped by
        // the join: the event stays (the totals above still count it), but there is no name to show.
        Query breakdown = em.createNativeQuery("select a.ACC_IDTARGET_C, u.USE_USERNAME_C, count(a.ACC_ID_C) c2"
                + " from T_ACCESS_EVENT a"
                + " join T_USER u on u.USE_ID_C = a.ACC_IDUSER_C"
                + " where a.ACC_TYPE_C = :type and a.ACC_IDTARGET_C in (:documentIds)"
                + " group by a.ACC_IDTARGET_C, u.USE_USERNAME_C order by c2 desc, u.USE_USERNAME_C asc");
        breakdown.setParameter("type", AccessTargetType.DOCUMENT.name());
        breakdown.setParameter("documentIds", documentIds);
        @SuppressWarnings("unchecked")
        List<Object[]> breakdownList = breakdown.getResultList();
        for (Object[] o : breakdownList) {
            List<AccessUserCountDto> userCounts = userCountsById.get((String) o[0]);
            if (userCounts != null) {
                userCounts.add(new AccessUserCountDto((String) o[1], ((Number) o[2]).longValue()));
            }
        }

        return new ArrayList<>(byId.values());
    }

    /**
     * The READ-permission predicate, AND-ed into an aggregation over documents. Mirrors
     * {@link AclDao#checkPermission} exactly — a direct READ ACL on the document, or a READ ACL on a tag
     * the document carries — expressed as an {@code exists} so it can narrow a grouped query.
     *
     * @param documentIdColumn The column holding the document id to test
     * @return SQL fragment beginning with {@code and}, binding {@code :targetIdList}
     */
    private static String aclReadPredicate(String documentIdColumn) {
        return " and (exists (select 1 from T_ACL acl where acl.ACL_SOURCEID_C = " + documentIdColumn
                + " and acl.ACL_TARGETID_C in (:targetIdList) and acl.ACL_PERM_C = 'READ' and acl.ACL_DELETEDATE_D is null)"
                + " or exists (select 1 from T_ACL acl, T_DOCUMENT_TAG dt where acl.ACL_SOURCEID_C = dt.DOT_IDTAG_C"
                + " and dt.DOT_IDDOCUMENT_C = " + documentIdColumn + " and dt.DOT_DELETEDATE_D is null"
                + " and acl.ACL_TARGETID_C in (:targetIdList) and acl.ACL_PERM_C = 'READ' and acl.ACL_DELETEDATE_D is null)) ";
    }
}
