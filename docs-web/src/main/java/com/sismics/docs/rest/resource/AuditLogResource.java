package com.sismics.docs.rest.resource;

import com.google.common.base.Strings;
import com.sismics.docs.core.constant.PermType;
import com.sismics.docs.core.dao.AclDao;
import com.sismics.docs.core.dao.AuditLogDao;
import com.sismics.docs.core.dao.criteria.AuditLogCriteria;
import com.sismics.docs.core.dao.dto.AuditLogDto;
import com.sismics.docs.core.dao.dto.AuditLogPage;
import com.sismics.docs.core.util.SecurityUtil;
import com.sismics.rest.exception.ClientException;
import com.sismics.rest.exception.ForbiddenClientException;
import com.sismics.util.JsonUtil;

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObjectBuilder;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;

import java.util.regex.Pattern;

/**
 * Audit log REST resources.
 * 
 * @author bgamard
 */
@Path("/auditlog")
public class AuditLogResource extends BaseResource {
    /**
     * Default page size (preserves the historical behaviour before keyset pagination).
     */
    private static final int DEFAULT_LIMIT = 20;

    /**
     * Maximum page size, to cap the cost of a single request.
     */
    private static final int MAX_LIMIT = 100;

    /**
     * Accepted shape of a cursor id (LOG_ID_C is a 36-char UUID): a bounded alphanumeric/hyphen
     * token. The value is a bound query parameter (never concatenated into SQL), so this guards
     * shape only — a malformed cursor is rejected rather than silently ignored.
     */
    private static final Pattern BEFORE_ID_PATTERN = Pattern.compile("[a-zA-Z0-9-]{1,36}");

    /**
     * Returns one page of logs for a document or user, newest first, with keyset pagination.
     *
     * @api {get} /auditlog Get audit logs
     * @apiDescription If no document ID is provided, logs for the current user will be returned.
     * Rows are ordered by (create_date DESC, id DESC). Paging is done with a keyset cursor rather
     * than an offset: pass the oldest currently-loaded row's create_date and id as before_date and
     * before_id to fetch the next (older) page. Both cursor parts must be supplied together; a
     * missing or malformed cursor is a validation error, not a silent first page.
     * @apiName GetAuditlog
     * @apiGroup Auditlog
     * @apiParam {String} [document] Document ID
     * @apiParam {Number} [limit=20] Page size (1-100; values &le;0 or absent default to 20, &gt;100 clamp to 100)
     * @apiParam {Number} [before_date] Keyset cursor: create_date (epoch millis) of the previous page's last row. Must be paired with before_id.
     * @apiParam {String} [before_id] Keyset cursor: id of the previous page's last row. Must be paired with before_date.
     * @apiSuccess {String} total Total number of logs (un-cursored: the full count for the scope)
     * @apiSuccess {Boolean} has_more True when older rows exist beyond this page
     * @apiSuccess {Object[]} logs List of logs
     * @apiSuccess {String} logs.id ID
     * @apiSuccess {String} logs.username Username
     * @apiSuccess {String} logs.target Entity ID
     * @apiSuccess {String="Acl","Comment","Document","File","Group","Tag","User"} logs.class Entity type
     * @apiSuccess {String="CREATE","UPDATE","DELETE"} logs.type Type
     * @apiSuccess {String} logs.message Message
     * @apiSuccess {Number} logs.create_date Create date (timestamp)
     * @apiError (client) ForbiddenError Access denied
     * @apiError (client) ValidationError Malformed or half-supplied cursor
     * @apiError (client) NotFound Document not found
     * @apiPermission user
     * @apiVersion 1.5.0
     *
     * @return Response
     */
    @GET
    public Response list(
            @QueryParam("document") String documentId,
            @QueryParam("limit") Integer limit,
            @QueryParam("before_date") String beforeDateParam,
            @QueryParam("before_id") String beforeId) {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }

        // Clamp the page size: default 20, floor of 1, ceiling of 100. A null/<=0 value is not an
        // error (it just falls back to the default) so a bad limit never yields a 500.
        int pageSize = DEFAULT_LIMIT;
        if (limit != null && limit > 0) {
            pageSize = Math.min(limit, MAX_LIMIT);
        }

        // Parse before_date ourselves (accepted as a String) so a non-numeric value is a clean 400
        // ValidationError rather than the framework's 404 for a failed Long query-param conversion.
        Long beforeDate = null;
        if (!Strings.isNullOrEmpty(beforeDateParam)) {
            try {
                beforeDate = Long.parseLong(beforeDateParam.trim());
            } catch (NumberFormatException e) {
                throw new ClientException("ValidationError", "before_date must be a numeric timestamp");
            }
        }

        // Validate the optional keyset cursor: BOTH parts or NEITHER. A half-supplied or malformed
        // cursor is a clean 400, never a silent first-page fallback.
        boolean hasBeforeDate = beforeDate != null;
        boolean hasBeforeId = !Strings.isNullOrEmpty(beforeId);
        if (hasBeforeDate != hasBeforeId) {
            throw new ClientException("ValidationError", "before_date and before_id must be supplied together");
        }
        if (hasBeforeDate && beforeDate < 0) {
            throw new ClientException("ValidationError", "before_date must be a non-negative timestamp");
        }
        if (hasBeforeId && !BEFORE_ID_PATTERN.matcher(beforeId).matches()) {
            throw new ClientException("ValidationError", "before_id is malformed");
        }

        // On a document or a user?
        AuditLogCriteria criteria = new AuditLogCriteria();
        if (Strings.isNullOrEmpty(documentId)) {
            // Search logs for a user
            criteria.setUserId(principal.getId());
            criteria.setAdmin(SecurityUtil.skipAclCheck(getTargetIdList(null)));
        } else {
            // Check ACL on the document
            AclDao aclDao = new AclDao();
            if (!aclDao.checkPermission(documentId, PermType.READ, getTargetIdList(null))) {
                throw new NotFoundException();
            }
            criteria.setDocumentId(documentId);
        }
        if (hasBeforeDate) {
            criteria.setBeforeDate(beforeDate);
            criteria.setBeforeId(beforeId);
        }

        // Search the logs (un-cursored total + a limit+1 keyset fetch)
        AuditLogDao auditLogDao = new AuditLogDao();
        AuditLogPage page = auditLogDao.findPage(criteria, pageSize);

        // Assemble the results
        JsonArrayBuilder logs = Json.createArrayBuilder();
        for (AuditLogDto auditLogDto : page.getLogs()) {
            logs.add(Json.createObjectBuilder()
                    .add("id", auditLogDto.getId())
                    .add("username", auditLogDto.getUsername())
                    .add("target", auditLogDto.getEntityId())
                    .add("class", auditLogDto.getEntityClass())
                    .add("type", auditLogDto.getType().name())
                    .add("message", JsonUtil.nullable(auditLogDto.getMessage()))
                    .add("create_date", auditLogDto.getCreateTimestamp()));
        }

        // Send the response
        JsonObjectBuilder response = Json.createObjectBuilder()
                .add("logs", logs)
                .add("total", page.getTotal())
                .add("has_more", page.isHasMore());
        return Response.ok().entity(response.build()).build();
    }
}
