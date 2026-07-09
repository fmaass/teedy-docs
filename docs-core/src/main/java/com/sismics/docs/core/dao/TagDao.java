package com.sismics.docs.core.dao;

import com.google.common.base.Joiner;
import com.sismics.docs.core.constant.AuditLogType;
import com.sismics.docs.core.dao.criteria.TagCriteria;
import com.sismics.docs.core.dao.dto.TagCoOccurrence;
import com.sismics.docs.core.dao.dto.TagDto;
import com.sismics.docs.core.model.jpa.DocumentTag;
import com.sismics.docs.core.model.jpa.Tag;
import com.sismics.docs.core.util.AuditLogUtil;
import com.sismics.docs.core.util.SecurityUtil;
import com.sismics.docs.core.util.jpa.QueryParam;
import com.sismics.docs.core.util.jpa.QueryUtil;
import com.sismics.docs.core.util.jpa.SortCriteria;
import com.sismics.util.context.ThreadLocalContext;

import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.Query;
import java.util.*;

/**
 * Tag DAO.
 * 
 * @author bgamard
 */
public class TagDao {
    /**
     * Left join restricting the aggregate tag alias {@code dt} to tags the caller has READ access to
     * (bind {@code :targetIdList}); pair with the WHERE condition {@code a.ACL_ID_C is not null}.
     */
    private static final String TAG_READ_ACL_JOIN =
            "left join T_ACL a on a.ACL_TARGETID_C in (:targetIdList) and a.ACL_SOURCEID_C = dt.DOT_IDTAG_C " +
            "and a.ACL_PERM_C = 'READ' and a.ACL_DELETEDATE_D is null ";

    /**
     * WHERE-clause predicate excluding any document that carries one of the excluded tags
     * (bind {@code :excludeTagIds}). Correlates on the given document-alias column so it can
     * be reused across the aggregate queries. Applied to the COUNTED documents only, leaving
     * the surrounding ACL join/filter path untouched.
     */
    private static String excludeDocPredicate(String docIdColumn) {
        return "and not exists (" +
                "  select 1 from T_DOCUMENT_TAG dtx " +
                "  where dtx.DOT_IDDOCUMENT_C = " + docIdColumn + " " +
                "  and dtx.DOT_IDTAG_C in (:excludeTagIds) " +
                "  and dtx.DOT_DELETEDATE_D is null" +
                ") ";
    }

    /**
     * Normalises a caller-supplied exclude list to the non-null, non-empty ids present, or
     * null when there is nothing to exclude. Mirrors how the selected-tag list is sanitised
     * upstream (blank ids dropped).
     */
    private static List<String> normalizeExcludeIds(List<String> excludeTagIds) {
        if (excludeTagIds == null || excludeTagIds.isEmpty()) {
            return null;
        }
        List<String> ids = new ArrayList<>();
        for (String id : excludeTagIds) {
            if (id != null && !id.isBlank()) {
                ids.add(id.trim());
            }
        }
        return ids.isEmpty() ? null : ids;
    }

    /**
     * Normalises AND ACL-scopes a caller-supplied exclude list: only tags the caller can
     * READ may act as exclusions (same visibility rule as selected tags, via
     * {@link #filterVisibleTagIds}). Excluding an unreadable tag must be a silent no-op —
     * otherwise the resulting count delta would disclose that an invisible tag is attached
     * to documents in the caller's result set. Returns null when nothing survives.
     */
    private List<String> visibleExcludeIds(List<String> excludeTagIds, List<String> targetIdList) {
        List<String> ids = normalizeExcludeIds(excludeTagIds);
        if (ids == null) {
            return null;
        }
        List<String> visible = filterVisibleTagIds(ids, targetIdList);
        return visible == null || visible.isEmpty() ? null : visible;
    }

    /**
     * Appends the FROM / alive-document join / optional ACL join / WHERE prefix shared by the
     * per-tag aggregate facet queries. The aggregate SELECT clause
     * ({@code dt.DOT_IDTAG_C, count(distinct dt.DOT_IDDOCUMENT_C)}) and any query-specific
     * suffix (co-occurrence subquery, GROUP BY) are the caller's responsibility.
     *
     * @param sb Query builder (the caller has already appended the SELECT clause)
     * @param acl Whether the caller's ACL scoping applies (binds {@code :targetIdList})
     * @param excludeIds Visible exclude IDs (binds {@code :excludeTagIds}), or null for none
     */
    private static void appendFacetBase(StringBuilder sb, boolean acl, List<String> excludeIds) {
        sb.append("from T_DOCUMENT_TAG dt " +
                "join T_DOCUMENT d on d.DOC_ID_C = dt.DOT_IDDOCUMENT_C and d.DOC_DELETEDATE_D is null ");
        if (acl) {
            sb.append(TAG_READ_ACL_JOIN);
        }
        sb.append("where dt.DOT_DELETEDATE_D is null ");
        if (acl) {
            sb.append("and a.ACL_ID_C is not null ");
        }
        if (excludeIds != null) {
            sb.append(excludeDocPredicate("dt.DOT_IDDOCUMENT_C"));
        }
    }

    /**
     * Maps aggregate rows of shape {@code [tagId, count]} to a tag-ID → count map.
     *
     * @param rows Native-query result rows
     * @return Map of tag ID to document count
     */
    private static Map<String, Long> rowsToCountMap(List<Object[]> rows) {
        Map<String, Long> result = new HashMap<>();
        for (Object[] row : rows) {
            result.put((String) row[0], ((Number) row[1]).longValue());
        }
        return result;
    }

    /**
     * Gets a tag by its ID.
     * 
     * @param id Tag ID
     * @return Tag
     */
    public Tag getById(String id) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        try {
            return em.find(Tag.class, id);
        } catch (NoResultException e) {
            return null;
        }
    }
    
    /**
     * Update tags on a document.
     * 
     * @param documentId Document ID
     * @param tagIdSet Set of tag ID
     */
    public void updateTagList(String documentId, Set<String> tagIdSet) {
        updateTagList(documentId, tagIdSet, null);
    }

    /**
     * Update tags on a document, restricting which existing links may be removed.
     *
     * @param documentId Document ID
     * @param tagIdSet Set of tag IDs the document should end up with
     * @param deletableTagIdSet Tags the caller is allowed to remove; existing links whose tag is NOT in
     *                          this set are preserved even if absent from tagIdSet. A null set means all
     *                          links are deletable (system callers with full visibility).
     */
    public void updateTagList(String documentId, Set<String> tagIdSet, Set<String> deletableTagIdSet) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();

        // Get current tag links
        Query q = em.createQuery("select dt from DocumentTag dt where dt.documentId = :documentId and dt.deleteDate is null");
        q.setParameter("documentId", documentId);
        @SuppressWarnings("unchecked")
        List<DocumentTag> documentTagList = q.getResultList();

        // Deleting tags no longer linked. Only remove links the caller is allowed to see/remove;
        // links to tags outside deletableTagIdSet (e.g. tags invisible to the acting user) are preserved.
        for (DocumentTag documentTag : documentTagList) {
            boolean deletable = deletableTagIdSet == null || deletableTagIdSet.contains(documentTag.getTagId());
            if (deletable && !tagIdSet.contains(documentTag.getTagId())) {
                documentTag.setDeleteDate(new Date());
            }
        }
        
        // Adding new tag links
        for (String tagId : tagIdSet) {
            boolean found = false;
            for (DocumentTag documentTag : documentTagList) {
                if (documentTag.getTagId().equals(tagId)) {
                    found = true;
                    break;
                }
            }
            
            if (!found) {
                DocumentTag documentTag = new DocumentTag();
                documentTag.setId(UUID.randomUUID().toString());
                documentTag.setDocumentId(documentId);
                documentTag.setTagId(tagId);
                em.persist(documentTag);
            }
        }
    }
    
    /**
     * Creates a new tag.
     * 
     * @param tag Tag
     * @param userId User ID
     * @return New ID
     */
    public String create(Tag tag, String userId) {
        // Create the UUID
        tag.setId(UUID.randomUUID().toString());
        
        // Create the tag
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        tag.setCreateDate(new Date());
        em.persist(tag);
        
        // Create audit log
        AuditLogUtil.create(tag, AuditLogType.CREATE, userId);
        
        return tag.getId();
    }
    
    /**
     * Deletes a tag.
     * 
     * @param tagId Tag ID
     * @param userId User ID
     */
    public void delete(String tagId, String userId) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
            
        // Get the tag
        Query q = em.createQuery("select t from Tag t where t.id = :id and t.deleteDate is null");
        q.setParameter("id", tagId);
        Tag tagDb = (Tag) q.getSingleResult();
        
        // Delete the tag
        Date dateNow = new Date();
        tagDb.setDeleteDate(dateNow);

        // Delete linked data
        q = em.createQuery("update DocumentTag dt set dt.deleteDate = :dateNow where dt.tagId = :tagId and dt.deleteDate is null");
        q.setParameter("dateNow", dateNow);
        q.setParameter("tagId", tagId);
        q.executeUpdate();

        q = em.createQuery("update Acl a set a.deleteDate = :dateNow where a.sourceId = :tagId and a.deleteDate is null");
        q.setParameter("tagId", tagId);
        q.setParameter("dateNow", dateNow);
        q.executeUpdate();

        q = em.createQuery("update Tag t set t.parentId = null where t.parentId = :tagId and t.deleteDate is null");
        q.setParameter("tagId", tagId);
        q.executeUpdate();
        
        // Create audit log
        AuditLogUtil.create(tagDb, AuditLogType.DELETE, userId);
    }
    
    /**
     * Update a tag.
     * 
     * @param tag Tag to update
     * @param userId User ID
     * @return Updated tag
     */
    public Tag update(Tag tag, String userId) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        
        // Get the tag
        Query q = em.createQuery("select t from Tag t where t.id = :id and t.deleteDate is null");
        q.setParameter("id", tag.getId());
        Tag tagDb = (Tag) q.getSingleResult();
        
        // Update the tag
        tagDb.setName(tag.getName());
        tagDb.setColor(tag.getColor());
        tagDb.setParentId(tag.getParentId());
        
        // Create audit log
        AuditLogUtil.create(tagDb, AuditLogType.UPDATE, userId);
        
        return tagDb;
    }

    /**
     * Returns the list of all tags.
     *
     * @param criteria Search criteria
     * @param sortCriteria Sort criteria
     * @return List of groups
     */
    public List<TagDto> findByCriteria(TagCriteria criteria, SortCriteria sortCriteria) {
        Map<String, Object> parameterMap = new HashMap<>();
        List<String> criteriaList = new ArrayList<>();

        StringBuilder sb = new StringBuilder("select distinct t.TAG_ID_C as c0, t.TAG_NAME_C as c1, t.TAG_COLOR_C as c2, t.TAG_IDPARENT_C as c3, u.USE_USERNAME_C as c4 ");
        sb.append(" from T_TAG t ");
        sb.append(" join T_USER u on t.TAG_IDUSER_C = u.USE_ID_C ");

        // Add search criterias
        if (criteria.getId() != null) {
            criteriaList.add("t.TAG_ID_C = :id");
            parameterMap.put("id", criteria.getId());
        }
        if (criteria.getTargetIdList() != null && !SecurityUtil.skipAclCheck(criteria.getTargetIdList())) {
            sb.append(" left join T_ACL a on a.ACL_TARGETID_C in (:targetIdList) and a.ACL_SOURCEID_C = t.TAG_ID_C and a.ACL_PERM_C = 'READ' and a.ACL_DELETEDATE_D is null ");
            criteriaList.add("a.ACL_ID_C is not null");
            parameterMap.put("targetIdList", criteria.getTargetIdList());
        }
        if (criteria.getDocumentId() != null) {
            sb.append(" join T_DOCUMENT_TAG dt on dt.DOT_IDTAG_C = t.TAG_ID_C and dt.DOT_DELETEDATE_D is null ");
            criteriaList.add("dt.DOT_IDDOCUMENT_C = :documentId");
            parameterMap.put("documentId", criteria.getDocumentId());
        }

        criteriaList.add("t.TAG_DELETEDATE_D is null");

        sb.append(" where ");
        sb.append(Joiner.on(" and ").join(criteriaList));

        // Perform the search
        QueryParam queryParam = QueryUtil.getSortedQueryParam(new QueryParam(sb.toString(), parameterMap), sortCriteria);
        @SuppressWarnings("unchecked")
        List<Object[]> l = QueryUtil.getNativeQuery(queryParam).getResultList();

        // Assemble results
        List<TagDto> tagDtoList = new ArrayList<>();
        for (Object[] o : l) {
            int i = 0;
            TagDto tagDto = new TagDto()
                    .setId((String) o[i++])
                    .setName((String) o[i++])
                    .setColor((String) o[i++])
                    .setParentId((String) o[i++])
                    .setCreator((String) o[i]);
            tagDtoList.add(tagDto);
        }

        return tagDtoList;
    }

    /**
     * True when the aggregate queries must be scoped to the caller's ACL.
     * Null target list or an admin target list (skipAclCheck) means unscoped.
     */
    private boolean isAclScoped(List<String> targetIdList) {
        return targetIdList != null && !SecurityUtil.skipAclCheck(targetIdList);
    }

    /**
     * Returns the subset of the given tag IDs the caller has READ access to.
     * Admin / unscoped callers get the list back unchanged.
     *
     * @param tagIds Tag IDs to filter
     * @param targetIdList Caller ACL target list
     * @return Visible subset of tagIds
     */
    @SuppressWarnings("unchecked")
    private List<String> filterVisibleTagIds(List<String> tagIds, List<String> targetIdList) {
        if (tagIds == null || tagIds.isEmpty() || !isAclScoped(targetIdList)) {
            return tagIds;
        }
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        Query q = em.createNativeQuery(
                "select distinct a.ACL_SOURCEID_C from T_ACL a " +
                "where a.ACL_SOURCEID_C in (:tagIds) and a.ACL_TARGETID_C in (:targetIdList) " +
                "and a.ACL_PERM_C = 'READ' and a.ACL_DELETEDATE_D is null");
        q.setParameter("tagIds", tagIds);
        q.setParameter("targetIdList", targetIdList);
        List<String> visible = new ArrayList<>();
        for (Object row : q.getResultList()) {
            visible.add((String) row);
        }
        return visible;
    }

    /**
     * Returns document counts per tag (only counting active documents),
     * scoped to the tags the caller can READ.
     *
     * @param targetIdList Caller ACL target list (null/admin = unscoped)
     * @return Map of tag ID to document count
     */
    public Map<String, Long> getTagDocumentCounts(List<String> targetIdList) {
        return getTagDocumentCounts(targetIdList, null);
    }

    /**
     * Returns document counts per tag (only counting active documents),
     * scoped to the tags the caller can READ, optionally excluding documents that
     * carry any of the given excluded tag IDs.
     *
     * @param targetIdList Caller ACL target list (null/admin = unscoped)
     * @param excludeTagIds Tag IDs whose documents are excluded from the counts (null/empty = none)
     * @return Map of tag ID to document count
     */
    @SuppressWarnings("unchecked")
    public Map<String, Long> getTagDocumentCounts(List<String> targetIdList, List<String> excludeTagIds) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        boolean acl = isAclScoped(targetIdList);
        List<String> excludeIds = visibleExcludeIds(excludeTagIds, targetIdList);
        StringBuilder sb = new StringBuilder(
                "select dt.DOT_IDTAG_C, count(distinct dt.DOT_IDDOCUMENT_C) ");
        appendFacetBase(sb, acl, excludeIds);
        sb.append("group by dt.DOT_IDTAG_C");
        Query q = em.createNativeQuery(sb.toString());
        if (acl) {
            q.setParameter("targetIdList", targetIdList);
        }
        if (excludeIds != null) {
            q.setParameter("excludeTagIds", excludeIds);
        }
        return rowsToCountMap(q.getResultList());
    }

    /**
     * Returns tags that co-occur with the given selected tags, with document counts.
     * Used for faceted tag navigation.
     *
     * @param selectedTagIds List of currently selected tag IDs
     * @return Map of tag ID to document count (excludes already-selected tags)
     */
    public Map<String, Long> getCoOccurringTagCounts(List<String> selectedTagIds, List<String> targetIdList) {
        return getCoOccurringTagCounts(selectedTagIds, targetIdList, null);
    }

    /**
     * Returns tags that co-occur (AND logic) with the given selected tags, optionally
     * excluding documents that carry any of the excluded tag IDs.
     *
     * @param selectedTagIds List of currently selected tag IDs
     * @param targetIdList Caller ACL target list (null/admin = unscoped)
     * @param excludeTagIds Tag IDs whose documents are excluded from the counts (null/empty = none)
     * @return Map of tag ID to document count (excludes already-selected tags)
     */
    @SuppressWarnings("unchecked")
    public Map<String, Long> getCoOccurringTagCounts(List<String> selectedTagIds, List<String> targetIdList, List<String> excludeTagIds) {
        if (selectedTagIds == null || selectedTagIds.isEmpty()) {
            return getTagDocumentCounts(targetIdList, excludeTagIds);
        }
        // AND semantics: an invisible selected tag means the caller cannot match on it,
        // so no document qualifies and no co-occurring tag should be returned.
        List<String> visibleSelected = filterVisibleTagIds(selectedTagIds, targetIdList);
        if (visibleSelected.size() < selectedTagIds.size()) {
            return new HashMap<>();
        }

        EntityManager em = ThreadLocalContext.get().getEntityManager();
        boolean acl = isAclScoped(targetIdList);
        List<String> excludeIds = visibleExcludeIds(excludeTagIds, targetIdList);
        StringBuilder sb = new StringBuilder(
                "select dt.DOT_IDTAG_C, count(distinct dt.DOT_IDDOCUMENT_C) ");
        appendFacetBase(sb, acl, excludeIds);
        sb.append("and dt.DOT_IDTAG_C not in (:selectedTagIds) " +
                "and dt.DOT_IDDOCUMENT_C in (" +
                "  select dt2.DOT_IDDOCUMENT_C from T_DOCUMENT_TAG dt2 " +
                "  where dt2.DOT_IDTAG_C in (:selectedTagIds) " +
                "  and dt2.DOT_DELETEDATE_D is null " +
                "  group by dt2.DOT_IDDOCUMENT_C " +
                "  having count(distinct dt2.DOT_IDTAG_C) = :selectedCount" +
                ") " +
                "group by dt.DOT_IDTAG_C");
        Query q = em.createNativeQuery(sb.toString());
        if (acl) {
            q.setParameter("targetIdList", targetIdList);
        }
        if (excludeIds != null) {
            q.setParameter("excludeTagIds", excludeIds);
        }
        q.setParameter("selectedTagIds", selectedTagIds);
        q.setParameter("selectedCount", (long) selectedTagIds.size());
        return rowsToCountMap(q.getResultList());
    }

    /**
     * Counts documents matching all the given tags (AND logic).
     *
     * @param tagIds Tag IDs
     * @return Number of matching documents
     */
    public long countDocumentsWithAllTags(List<String> tagIds, List<String> targetIdList) {
        return countDocumentsWithAllTags(tagIds, targetIdList, null);
    }

    /**
     * Counts documents matching all the given tags (AND logic), optionally excluding
     * documents that carry any of the excluded tag IDs.
     *
     * @param tagIds Tag IDs
     * @param targetIdList Caller ACL target list (null/admin = unscoped)
     * @param excludeTagIds Tag IDs whose documents are excluded from the count (null/empty = none)
     * @return Number of matching documents
     */
    public long countDocumentsWithAllTags(List<String> tagIds, List<String> targetIdList, List<String> excludeTagIds) {
        if (tagIds == null || tagIds.isEmpty()) {
            return 0;
        }
        // AND semantics: if any selected tag is not visible to the caller, no document matches.
        if (filterVisibleTagIds(tagIds, targetIdList).size() < tagIds.size()) {
            return 0;
        }
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        List<String> excludeIds = visibleExcludeIds(excludeTagIds, targetIdList);
        StringBuilder sb = new StringBuilder(
                "select count(*) from (" +
                "  select dt.DOT_IDDOCUMENT_C from T_DOCUMENT_TAG dt " +
                "  join T_DOCUMENT d on d.DOC_ID_C = dt.DOT_IDDOCUMENT_C and d.DOC_DELETEDATE_D is null " +
                "  where dt.DOT_IDTAG_C in (:tagIds) and dt.DOT_DELETEDATE_D is null ");
        if (excludeIds != null) {
            sb.append(excludeDocPredicate("dt.DOT_IDDOCUMENT_C"));
        }
        sb.append("  group by dt.DOT_IDDOCUMENT_C " +
                "  having count(distinct dt.DOT_IDTAG_C) = :tagCount" +
                ") c");
        Query q = em.createNativeQuery(sb.toString());
        q.setParameter("tagIds", tagIds);
        q.setParameter("tagCount", (long) tagIds.size());
        if (excludeIds != null) {
            q.setParameter("excludeTagIds", excludeIds);
        }
        return ((Number) q.getSingleResult()).longValue();
    }

    /**
     * Returns tags that co-occur with any of the given selected tags (OR logic).
     *
     * @param selectedTagIds List of currently selected tag IDs
     * @return Map of tag ID to document count (excludes already-selected tags)
     */
    public Map<String, Long> getCoOccurringTagCountsOr(List<String> selectedTagIds, List<String> targetIdList) {
        return getCoOccurringTagCountsOr(selectedTagIds, targetIdList, null);
    }

    /**
     * Returns tags that co-occur (OR logic) with any of the given selected tags, optionally
     * excluding documents that carry any of the excluded tag IDs.
     *
     * @param selectedTagIds List of currently selected tag IDs
     * @param targetIdList Caller ACL target list (null/admin = unscoped)
     * @param excludeTagIds Tag IDs whose documents are excluded from the counts (null/empty = none)
     * @return Map of tag ID to document count (excludes already-selected tags)
     */
    @SuppressWarnings("unchecked")
    public Map<String, Long> getCoOccurringTagCountsOr(List<String> selectedTagIds, List<String> targetIdList, List<String> excludeTagIds) {
        if (selectedTagIds == null || selectedTagIds.isEmpty()) {
            return getTagDocumentCounts(targetIdList, excludeTagIds);
        }
        // OR semantics: pivot only on selected tags the caller can see; invisible ones are dropped.
        List<String> visibleSelected = filterVisibleTagIds(selectedTagIds, targetIdList);
        if (visibleSelected.isEmpty()) {
            return new HashMap<>();
        }

        EntityManager em = ThreadLocalContext.get().getEntityManager();
        boolean acl = isAclScoped(targetIdList);
        List<String> excludeIds = visibleExcludeIds(excludeTagIds, targetIdList);
        StringBuilder sb = new StringBuilder(
                "select dt.DOT_IDTAG_C, count(distinct dt.DOT_IDDOCUMENT_C) ");
        appendFacetBase(sb, acl, excludeIds);
        sb.append("and dt.DOT_IDTAG_C not in (:selectedTagIds) " +
                "and dt.DOT_IDDOCUMENT_C in (" +
                "  select dt2.DOT_IDDOCUMENT_C from T_DOCUMENT_TAG dt2 " +
                "  where dt2.DOT_IDTAG_C in (:selectedTagIds) " +
                "  and dt2.DOT_DELETEDATE_D is null " +
                ") " +
                "group by dt.DOT_IDTAG_C");
        Query q = em.createNativeQuery(sb.toString());
        if (acl) {
            q.setParameter("targetIdList", targetIdList);
        }
        if (excludeIds != null) {
            q.setParameter("excludeTagIds", excludeIds);
        }
        q.setParameter("selectedTagIds", visibleSelected);
        return rowsToCountMap(q.getResultList());
    }

    /**
     * Returns the full co-occurrence matrix: for every pair of tags
     * that appear together on at least one non-deleted document, the count.
     *
     * @return List of {@link TagCoOccurrence} entries (tagIdA &lt; tagIdB lexicographically to avoid dupes)
     */
    @SuppressWarnings("unchecked")
    public List<TagCoOccurrence> getFullCoOccurrenceMatrix(List<String> targetIdList) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        boolean acl = isAclScoped(targetIdList);
        StringBuilder sb = new StringBuilder(
                "select dt1.DOT_IDTAG_C, dt2.DOT_IDTAG_C, count(distinct dt1.DOT_IDDOCUMENT_C) " +
                "from T_DOCUMENT_TAG dt1 " +
                "join T_DOCUMENT_TAG dt2 on dt1.DOT_IDDOCUMENT_C = dt2.DOT_IDDOCUMENT_C " +
                "  and dt1.DOT_IDTAG_C < dt2.DOT_IDTAG_C " +
                "  and dt2.DOT_DELETEDATE_D is null " +
                "join T_DOCUMENT d on d.DOC_ID_C = dt1.DOT_IDDOCUMENT_C and d.DOC_DELETEDATE_D is null ");
        if (acl) {
            // Both returned tag IDs must be READ-visible to the caller.
            sb.append("left join T_ACL a1 on a1.ACL_TARGETID_C in (:targetIdList) and a1.ACL_SOURCEID_C = dt1.DOT_IDTAG_C and a1.ACL_PERM_C = 'READ' and a1.ACL_DELETEDATE_D is null ");
            sb.append("left join T_ACL a2 on a2.ACL_TARGETID_C in (:targetIdList) and a2.ACL_SOURCEID_C = dt2.DOT_IDTAG_C and a2.ACL_PERM_C = 'READ' and a2.ACL_DELETEDATE_D is null ");
        }
        sb.append("where dt1.DOT_DELETEDATE_D is null ");
        if (acl) {
            sb.append("and a1.ACL_ID_C is not null and a2.ACL_ID_C is not null ");
        }
        sb.append("group by dt1.DOT_IDTAG_C, dt2.DOT_IDTAG_C");
        Query q = em.createNativeQuery(sb.toString());
        if (acl) {
            q.setParameter("targetIdList", targetIdList);
        }
        List<Object[]> rows = q.getResultList();
        List<TagCoOccurrence> result = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            result.add(new TagCoOccurrence((String) row[0], (String) row[1], ((Number) row[2]).longValue()));
        }
        return result;
    }

    /**
     * Counts documents matching any of the given tags (OR logic).
     *
     * @param tagIds Tag IDs
     * @return Number of matching documents
     */
    public long countDocumentsWithAnyTag(List<String> tagIds, List<String> targetIdList) {
        return countDocumentsWithAnyTag(tagIds, targetIdList, null);
    }

    /**
     * Counts documents matching any of the given tags (OR logic), optionally excluding
     * documents that carry any of the excluded tag IDs.
     *
     * @param tagIds Tag IDs
     * @param targetIdList Caller ACL target list (null/admin = unscoped)
     * @param excludeTagIds Tag IDs whose documents are excluded from the count (null/empty = none)
     * @return Number of matching documents
     */
    public long countDocumentsWithAnyTag(List<String> tagIds, List<String> targetIdList, List<String> excludeTagIds) {
        if (tagIds == null || tagIds.isEmpty()) {
            return 0;
        }
        // OR semantics: count only over selected tags the caller can see.
        List<String> visibleTagIds = filterVisibleTagIds(tagIds, targetIdList);
        if (visibleTagIds.isEmpty()) {
            return 0;
        }
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        List<String> excludeIds = visibleExcludeIds(excludeTagIds, targetIdList);
        StringBuilder sb = new StringBuilder(
                "select count(distinct dt.DOT_IDDOCUMENT_C) from T_DOCUMENT_TAG dt " +
                "join T_DOCUMENT d on d.DOC_ID_C = dt.DOT_IDDOCUMENT_C and d.DOC_DELETEDATE_D is null " +
                "where dt.DOT_IDTAG_C in (:tagIds) and dt.DOT_DELETEDATE_D is null ");
        if (excludeIds != null) {
            sb.append(excludeDocPredicate("dt.DOT_IDDOCUMENT_C"));
        }
        Query q = em.createNativeQuery(sb.toString());
        q.setParameter("tagIds", visibleTagIds);
        if (excludeIds != null) {
            q.setParameter("excludeTagIds", excludeIds);
        }
        return ((Number) q.getSingleResult()).longValue();
    }
}

