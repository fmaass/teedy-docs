package com.sismics.docs.core.dao;

import com.google.common.base.Joiner;
import com.google.common.collect.Lists;
import com.sismics.docs.core.constant.AuditLogType;
import com.sismics.docs.core.dao.criteria.AuditLogCriteria;
import com.sismics.docs.core.dao.dto.AuditLogDto;
import com.sismics.docs.core.dao.dto.AuditLogPage;
import com.sismics.docs.core.model.jpa.AuditLog;
import com.sismics.docs.core.util.jpa.PaginatedList;
import com.sismics.docs.core.util.jpa.PaginatedLists;
import com.sismics.docs.core.util.jpa.QueryParam;
import com.sismics.docs.core.util.jpa.QueryUtil;
import com.sismics.docs.core.util.jpa.SortCriteria;
import com.sismics.util.context.ThreadLocalContext;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.sql.Timestamp;
import java.util.*;

/**
 * Audit log DAO.
 * 
 * @author bgamard
 */
public class AuditLogDao {
    /**
     * Creates a new audit log.
     * 
     * @param auditLog Audit log
     * @return New ID
     */
    public String create(AuditLog auditLog) {
        // Create the UUID
        auditLog.setId(UUID.randomUUID().toString());
        
        // Create the audit log
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        auditLog.setCreateDate(new Date());
        em.persist(auditLog);
        
        return auditLog.getId();
    }
    
    /**
     * Searches audit logs by criteria.
     * 
     * @param paginatedList List of audit logs (updated by side effects)
     * @param criteria Search criteria
     * @param sortCriteria Sort criteria
     */
    public void findByCriteria(PaginatedList<AuditLogDto> paginatedList, AuditLogCriteria criteria, SortCriteria sortCriteria) {
        Map<String, Object> parameterMap = new HashMap<>();
        
        StringBuilder baseQuery = new StringBuilder("select l.LOG_ID_C c0, l.LOG_CREATEDATE_D c1, u.USE_USERNAME_C c2, l.LOG_IDENTITY_C c3, l.LOG_CLASSENTITY_C c4, l.LOG_TYPE_C c5, l.LOG_MESSAGE_C c6 from T_AUDIT_LOG l ");
        baseQuery.append(" join T_USER u on l.LOG_IDUSER_C = u.USE_ID_C ");
        List<String> queries = Lists.newArrayList();
        
        // Adds search criteria
        if (criteria.getDocumentId() != null) {
            // ACL on document is not checked here, rights have been checked before
            queries.add(baseQuery + " where l.LOG_IDENTITY_C = :documentId ");
            queries.add(baseQuery + " where l.LOG_IDENTITY_C in (select f.FIL_ID_C from T_FILE f where f.FIL_IDDOC_C = :documentId) ");
            queries.add(baseQuery + " where l.LOG_IDENTITY_C in (select c.COM_ID_C from T_COMMENT c where c.COM_IDDOC_C = :documentId) ");
            queries.add(baseQuery + " where l.LOG_IDENTITY_C in (select a.ACL_ID_C from T_ACL a where a.ACL_SOURCEID_C = :documentId) ");
            queries.add(baseQuery + " where l.LOG_IDENTITY_C in (select r.RTE_ID_C from T_ROUTE r where r.RTE_IDDOCUMENT_C = :documentId) ");
            parameterMap.put("documentId", criteria.getDocumentId());
        }
        
        if (criteria.getUserId() != null) {
            if (criteria.isAdmin()) {
                // For admin users, display all logs except ACL logs
                queries.add(baseQuery + " where l.LOG_CLASSENTITY_C != 'Acl' ");
            } else {
                // Get all logs originating from the user, not necessarly on owned items
                // Filter out ACL logs
                queries.add(baseQuery + " where l.LOG_IDUSER_C = :userId and l.LOG_CLASSENTITY_C != 'Acl' ");
                parameterMap.put("userId", criteria.getUserId());
            }
        }
        
        // Perform the search
        QueryParam queryParam = new QueryParam(Joiner.on(" union ").join(queries), parameterMap);
        List<Object[]> l = PaginatedLists.executePaginatedQuery(paginatedList, queryParam, sortCriteria);
        
        // Assemble results
        List<AuditLogDto> auditLogDtoList = new ArrayList<>();
        for (Object[] o : l) {
            int i = 0;
            AuditLogDto auditLogDto = new AuditLogDto();
            auditLogDto.setId((String) o[i++]);
            auditLogDto.setCreateTimestamp(((Timestamp) o[i++]).getTime());
            auditLogDto.setUsername((String) o[i++]);
            auditLogDto.setEntityId((String) o[i++]);
            auditLogDto.setEntityClass((String) o[i++]);
            auditLogDto.setType(AuditLogType.valueOf((String) o[i++]));
            auditLogDto.setMessage((String) o[i++]);
            auditLogDtoList.add(auditLogDto);
        }

        paginatedList.setResultList(auditLogDtoList);
    }

    /**
     * Searches one page of audit logs with keyset (cursor) pagination on a deterministic
     * (create_date DESC, id DESC) order.
     *
     * <p>The query is a UNION across the audit-log sources for the scope (the document row and
     * its file/comment/acl/route rows, or the per-user unscoped view). The deterministic order
     * and the optional cursor predicate are applied identically to EVERY branch of the UNION so
     * no source's rows can escape the ordering or the cursor.
     *
     * <p>{@code total} is a SEPARATE un-cursored count (it excludes the cursor predicate) so it
     * keeps its historical full-result meaning. Termination is driven by {@code hasMore}, computed
     * by fetching {@code limit + 1} rows and reporting whether the extra row existed.
     *
     * @param criteria Search criteria (scope + optional beforeDate/beforeId cursor)
     * @param limit Maximum number of rows to return for this page (already clamped by the caller)
     * @return A page of audit logs with the un-cursored total and the has-more flag
     */
    public AuditLogPage findPage(AuditLogCriteria criteria, int limit) {
        String baseQuery = "select l.LOG_ID_C c0, l.LOG_CREATEDATE_D c1, u.USE_USERNAME_C c2, l.LOG_IDENTITY_C c3, l.LOG_CLASSENTITY_C c4, l.LOG_TYPE_C c5, l.LOG_MESSAGE_C c6 from T_AUDIT_LOG l "
                + " join T_USER u on l.LOG_IDUSER_C = u.USE_ID_C ";

        // Per-source WHERE bodies; each becomes one branch of the UNION.
        List<String> whereClauses = new ArrayList<>();
        Map<String, Object> scopeParams = new HashMap<>();
        if (criteria.getDocumentId() != null) {
            // ACL on document is not checked here, rights have been checked before
            whereClauses.add(" l.LOG_IDENTITY_C = :documentId ");
            whereClauses.add(" l.LOG_IDENTITY_C in (select f.FIL_ID_C from T_FILE f where f.FIL_IDDOC_C = :documentId) ");
            whereClauses.add(" l.LOG_IDENTITY_C in (select c.COM_ID_C from T_COMMENT c where c.COM_IDDOC_C = :documentId) ");
            whereClauses.add(" l.LOG_IDENTITY_C in (select a.ACL_ID_C from T_ACL a where a.ACL_SOURCEID_C = :documentId) ");
            whereClauses.add(" l.LOG_IDENTITY_C in (select r.RTE_ID_C from T_ROUTE r where r.RTE_IDDOCUMENT_C = :documentId) ");
            scopeParams.put("documentId", criteria.getDocumentId());
        }

        if (criteria.getUserId() != null) {
            if (criteria.isAdmin()) {
                // For admin users, display all logs except ACL logs
                whereClauses.add(" l.LOG_CLASSENTITY_C != 'Acl' ");
            } else {
                // Get all logs originating from the user, not necessarly on owned items
                // Filter out ACL logs
                whereClauses.add(" l.LOG_IDUSER_C = :userId and l.LOG_CLASSENTITY_C != 'Acl' ");
                scopeParams.put("userId", criteria.getUserId());
            }
        }

        // Un-cursored total: the full result count for this scope, INDEPENDENT of the cursor, so
        // the total the resource emits keeps its historical "all matching rows" meaning.
        String countSql = "select count(*) as result_count from (" + buildUnion(baseQuery, whereClauses, "") + ") as t1";
        Query countQuery = QueryUtil.getNativeQuery(new QueryParam(countSql, scopeParams));
        int total = ((Number) countQuery.getSingleResult()).intValue();

        // Keyset predicate on the DESC order: only rows strictly older than the cursor tuple.
        // Applied to EVERY branch of the UNION. Both parts of the cursor must be present (the
        // caller rejects a half cursor); absent => first page.
        String cursorClause = "";
        Map<String, Object> fetchParams = new HashMap<>(scopeParams);
        if (criteria.getBeforeDate() != null && criteria.getBeforeId() != null) {
            cursorClause = " and (l.LOG_CREATEDATE_D < :beforeDate or (l.LOG_CREATEDATE_D = :beforeDate and l.LOG_ID_C < :beforeId)) ";
            fetchParams.put("beforeDate", new Timestamp(criteria.getBeforeDate()));
            fetchParams.put("beforeId", criteria.getBeforeId());
        }

        // Fetch limit+1 rows: the extra row (if any) proves a further page exists without a second
        // (cursored) count. total cannot drive termination — it counts rows above the cursor too.
        String fetchSql = buildUnion(baseQuery, whereClauses, cursorClause) + " order by c1 desc, c0 desc";
        Query fetchQuery = QueryUtil.getNativeQuery(new QueryParam(fetchSql, fetchParams));
        fetchQuery.setMaxResults(limit + 1);
        @SuppressWarnings("unchecked")
        List<Object[]> resultList = fetchQuery.getResultList();

        boolean hasMore = resultList.size() > limit;
        int pageSize = Math.min(resultList.size(), limit);
        List<AuditLogDto> auditLogDtoList = new ArrayList<>();
        for (int idx = 0; idx < pageSize; idx++) {
            Object[] o = resultList.get(idx);
            int i = 0;
            AuditLogDto auditLogDto = new AuditLogDto();
            auditLogDto.setId((String) o[i++]);
            auditLogDto.setCreateTimestamp(((Timestamp) o[i++]).getTime());
            auditLogDto.setUsername((String) o[i++]);
            auditLogDto.setEntityId((String) o[i++]);
            auditLogDto.setEntityClass((String) o[i++]);
            auditLogDto.setType(AuditLogType.valueOf((String) o[i++]));
            auditLogDto.setMessage((String) o[i++]);
            auditLogDtoList.add(auditLogDto);
        }

        return new AuditLogPage(auditLogDtoList, total, hasMore);
    }

    /**
     * Joins the per-source WHERE bodies into a UNION, appending {@code extra} (the cursor
     * predicate, or empty) to EVERY branch so the cursor applies uniformly across sources.
     *
     * @param baseQuery Shared select/from/join prefix
     * @param whereClauses Per-source WHERE bodies
     * @param extra Extra predicate appended to each branch (cursor clause, or empty)
     * @return The joined UNION query string
     */
    private static String buildUnion(String baseQuery, List<String> whereClauses, String extra) {
        List<String> branches = Lists.newArrayList();
        for (String where : whereClauses) {
            branches.add(baseQuery + " where " + where + extra);
        }
        return Joiner.on(" union ").join(branches);
    }
}
