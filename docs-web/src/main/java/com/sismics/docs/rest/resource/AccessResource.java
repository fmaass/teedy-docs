package com.sismics.docs.rest.resource;

import com.sismics.docs.rest.constant.BaseFunction;
import com.sismics.docs.rest.util.AccessResourceHelper;
import com.sismics.rest.exception.ForbiddenClientException;
import com.sismics.rest.util.ValidationUtil;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;

/**
 * Access counters (#300): how often documents and files have been opened.
 *
 * <p>The visibility split is enforced here and nowhere else in the client's reach. The personal
 * endpoint answers for the AUTHENTICATED CALLER and takes no user parameter, so no request shape can
 * ask it for someone else's numbers. The aggregate endpoint — per-user counts and the most-used
 * ranking — is administrator-only, checked server-side, so hiding its navigation is never the defence.</p>
 *
 * <p>Nothing here records anything: the events are written by the endpoints that actually serve a
 * document or a file. These are pure reads over the recorded events.</p>
 */
@Path("/access")
public class AccessResource extends BaseResource {
    /** Ranking size when the caller does not ask for one. */
    private static final int DEFAULT_RANKING_LIMIT = 10;

    /**
     * Returns the calling user's own access counts for a document and each of its files.
     *
     * @api {get} /access/document/:id Get the caller's own access counts for a document
     * @apiName GetAccessDocument
     * @apiGroup Access
     * @apiParam {String} id Document ID
     * @apiSuccess {Number} count Number of times the CALLER opened this document
     * @apiSuccess {Object[]} files Per-file counts for the caller
     * @apiSuccess {String} files.id File ID
     * @apiSuccess {Number} files.count Number of times the CALLER accessed this file
     * @apiError (client) ForbiddenError Access denied
     * @apiError (client) NotFound Document not found
     * @apiPermission user
     * @apiVersion 1.5.0
     *
     * @param documentId Document ID
     * @return Response
     */
    @GET
    @Path("document/{id: [a-z0-9\\-]+}")
    public Response document(@PathParam("id") String documentId) {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }

        return Response.ok().entity(
                AccessResourceHelper.personalCounts(principal.getId(), documentId, getTargetIdList(null))).build();
    }

    /**
     * Returns the aggregate access statistics: global totals and the most-accessed documents with their
     * per-user breakdown.
     *
     * @api {get} /access/stats Get aggregate access statistics
     * @apiName GetAccessStats
     * @apiGroup Access
     * @apiParam {Number} [limit] Maximum number of documents to rank (default 10)
     * @apiSuccess {Number} total_document_accesses Total recorded document accesses, all users
     * @apiSuccess {Number} total_file_accesses Total recorded file accesses, all users
     * @apiSuccess {Object[]} documents Most-accessed documents the caller may read
     * @apiSuccess {String} documents.id Document ID
     * @apiSuccess {String} documents.title Title
     * @apiSuccess {Number} documents.total Accesses by every user
     * @apiSuccess {Object[]} documents.users Per-user breakdown
     * @apiSuccess {String} documents.users.username Username
     * @apiSuccess {Number} documents.users.count Accesses by that user
     * @apiError (client) ForbiddenError Access denied
     * @apiError (client) ValidationError Validation error
     * @apiPermission admin
     * @apiVersion 1.5.0
     *
     * @param limitStr Ranking size
     * @return Response
     */
    @GET
    @Path("stats")
    public Response stats(@QueryParam("limit") String limitStr) {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }
        checkBaseFunction(BaseFunction.ADMIN);

        int limit = limitStr == null ? DEFAULT_RANKING_LIMIT : ValidationUtil.validateInteger(limitStr, "limit");

        return Response.ok().entity(AccessResourceHelper.adminStats(getTargetIdList(null), limit)).build();
    }
}
