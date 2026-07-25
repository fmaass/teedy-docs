package com.sismics.docs.rest.resource;

import com.google.common.base.Strings;
import com.sismics.docs.core.constant.AuditLogType;
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

import java.util.Arrays;
import java.util.Set;
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
     * Accepted values of the {@code class} filter.
     *
     * <p><b>ENUMERATION RULE — read this before adding an audit-log write.</b> LOG_CLASSENTITY_C is
     * written by TWO kinds of caller, and this set is the union of both. Deriving it from the
     * {@code Loggable} model alone is a FALSE ORACLE: it silently omits every direct writer, which
     * is how {@code Export} rows came to be listed in the history view while {@code ?class=Export}
     * answered 400.
     * <ol>
     *   <li><b>Via {@code AuditLogUtil.create}</b> — writes {@code loggable.getClass().getSimpleName()},
     *       so every {@link com.sismics.docs.core.model.jpa.Loggable} implementor is a possible value.</li>
     *   <li><b>Direct writers</b> — code that builds an {@code AuditLog} itself and calls
     *       {@code setEntityClass(&lt;literal&gt;)}. Today: {@code DocumentResource} ("Export"),
     *       {@code SecurityFilter} ("User", AUTHENTICATION rows) and {@code PrincipalDeletionUtil}
     *       ("Acl"). A new direct writer MUST add its literal here.</li>
     * </ol>
     *
     * <p>Both halves are enforced executably by {@code TestAuditLogFilters}: one test derives the
     * Loggable implementors by scanning the model package, another scans the production sources for
     * every {@code setEntityClass("…")} literal — so the next direct writer fails the build here
     * instead of shipping an unfilterable row type.
     *
     * <p>{@code Acl} is a legal value and is deliberately NOT special-cased: the authorization
     * predicate already excludes Acl rows from every user/admin-scoped branch, so an
     * {@code ?class=Acl} global request returns an empty page on its own. A DOCUMENT-scoped request
     * can legitimately reach Acl rows (the document's ACL branch carries no exclusion), and
     * rejecting the value would break that existing view. The SPA's global history simply does not
     * offer Acl as a choice.
     */
    public static final Set<String> ALLOWED_CLASSES = Set.of(
            "Acl", "Comment", "Document", "Export", "File", "Group", "Metadata", "Route", "RouteModel",
            "Tag", "User", "Webhook");

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
     * @apiParam {String="CREATE","UPDATE","DELETE","AUTHENTICATION"} [type] Narrow to one audit type
     * @apiParam {String="Acl","Comment","Document","Export","File","Group","Metadata","Route","RouteModel","Tag","User","Webhook"} [class] Narrow to one entity type
     * @apiParam {String} [user] Narrow to the rows authored by this username
     * @apiParam {Number} [after_date] Narrow to rows created at or after this instant (epoch millis, inclusive)
     * @apiSuccess {String} total Total number of logs (un-cursored, and reflecting the filters: the full count for the filtered scope)
     * @apiSuccess {Boolean} has_more True when older rows exist beyond this page
     * @apiSuccess {Object[]} logs List of logs
     * @apiSuccess {String} logs.id ID
     * @apiSuccess {String} logs.username Username
     * @apiSuccess {String} logs.target Entity ID
     * @apiSuccess {String="Acl","Comment","Document","Export","File","Group","Metadata","Route","RouteModel","Tag","User","Webhook"} logs.class Entity type
     * @apiSuccess {String="CREATE","UPDATE","DELETE","AUTHENTICATION"} logs.type Type
     * @apiSuccess {String} logs.message Message
     * @apiSuccess {Number} logs.create_date Create date (timestamp)
     * @apiError (client) ForbiddenError Access denied
     * @apiError (client) ValidationError Malformed or half-supplied cursor, or an unknown type/class filter value
     * @apiError (client) NotFound Document not found
     * @apiPermission user
     * @apiVersion 1.5.0
     *
     * @return Response
     */
    @GET
    public Response list(
            @QueryParam("document") String documentId,
            @QueryParam("limit") String limitParam,
            @QueryParam("before_date") String beforeDateParam,
            @QueryParam("before_id") String beforeId,
            @QueryParam("type") String typeParam,
            @QueryParam("class") String classParam,
            @QueryParam("user") String userParam,
            @QueryParam("after_date") String afterDateParam) {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }

        // Clamp the page size: default 20, floor of 1, ceiling of 100. limit is accepted as a String
        // and parsed here so a null/empty/non-numeric/out-of-range value falls back to the default
        // rather than triggering the framework's 404 for a failed Integer query-param conversion (or a
        // 500); a bad page size is never an error.
        int pageSize = DEFAULT_LIMIT;
        if (!Strings.isNullOrEmpty(limitParam)) {
            try {
                int parsed = Integer.parseInt(limitParam.trim());
                if (parsed > 0) {
                    pageSize = Math.min(parsed, MAX_LIMIT);
                }
            } catch (NumberFormatException e) {
                // Malformed or overflowing page size — keep the default.
            }
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

        // Narrowing filters. A blank (or whitespace-only) value means "no filter" for ALL of them —
        // an absent filter and an empty one are the same request, and a UI that clears a field
        // must not turn that into an error. A PRESENT but unknown value is a clean 400
        // ValidationError: AuditLogType.valueOf throws IllegalArgumentException on an unknown name,
        // which would otherwise escape as a 500 (same reasoning as before_date's manual parse).
        String typeValue = trimToNull(typeParam);
        AuditLogType type = null;
        if (typeValue != null) {
            try {
                type = AuditLogType.valueOf(typeValue);
            } catch (IllegalArgumentException e) {
                throw new ClientException("ValidationError", "type must be one of " + Arrays.toString(AuditLogType.values()));
            }
        }
        String entityClass = trimToNull(classParam);
        if (entityClass != null && !ALLOWED_CLASSES.contains(entityClass)) {
            throw new ClientException("ValidationError", "class must be a known loggable entity type");
        }
        String afterDateValue = trimToNull(afterDateParam);
        Long afterDate = null;
        if (afterDateValue != null) {
            try {
                afterDate = Long.parseLong(afterDateValue);
            } catch (NumberFormatException e) {
                throw new ClientException("ValidationError", "after_date must be a numeric timestamp");
            }
            if (afterDate < 0) {
                throw new ClientException("ValidationError", "after_date must be a non-negative timestamp");
            }
        }
        // The user filter is a plain value bound as a query parameter (never concatenated into SQL),
        // so it needs no shape guard: an unknown username simply matches nothing.
        String username = trimToNull(userParam);

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
        // Filters NARROW the scope set above; the DAO AND-composes them into every branch of it.
        criteria.setType(type);
        criteria.setEntityClass(entityClass);
        criteria.setUsername(username);
        criteria.setAfterDate(afterDate);

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

    /**
     * The trimmed value, or null when the parameter is absent, empty or whitespace-only — the three
     * ways a client says "no filter".
     */
    private static String trimToNull(String value) {
        return value == null ? null : Strings.emptyToNull(value.trim());
    }
}
