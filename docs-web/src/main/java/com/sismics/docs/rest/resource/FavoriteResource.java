package com.sismics.docs.rest.resource;

import com.google.common.collect.Lists;
import com.sismics.docs.core.constant.PermType;
import com.sismics.docs.core.dao.DocumentDao;
import com.sismics.docs.core.dao.FavoriteDao;
import com.sismics.docs.core.dao.criteria.DocumentCriteria;
import com.sismics.docs.core.dao.dto.DocumentDto;
import com.sismics.docs.core.model.context.AppContext;
import com.sismics.docs.core.util.jpa.PaginatedList;
import com.sismics.docs.core.util.jpa.PaginatedLists;
import com.sismics.docs.core.util.jpa.SortCriteria;
import com.sismics.docs.rest.util.DocumentResourceHelper;
import com.sismics.rest.exception.ForbiddenClientException;
import com.sismics.rest.exception.ServerException;

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObjectBuilder;
import jakarta.persistence.EntityNotFoundException;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;

/**
 * Per-user document favorites REST resource.
 *
 * <p>Favorites are PRIVATE: a user's stars are never visible to, nor countable by, any
 * other user (including admins). There are no favorite counts, no ACL-visible surfaces,
 * and no audit-log entries. Star membership is a hard-deletable row; a repeat star is an
 * idempotent no-op.
 */
@Path("/favorite")
public class FavoriteResource extends BaseResource {
    /**
     * Favorites the given document for the current user (idempotent).
     *
     * <p>Starring requires READ on the document. A document the caller cannot read — or
     * that does not exist — yields 404 (never confirming another user's document). A repeat
     * star on an already-favorited document returns 200 with no new row (see
     * {@link FavoriteDao#create}).
     *
     * @param documentId Document ID
     * @return Response
     */
    @PUT
    @Path("{documentId: [a-z0-9\\-]+}")
    public Response add(@PathParam("documentId") String documentId) {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }

        // Star requires READ on an EXISTING (non-trashed) document. getDocument returns null for
        // a missing, trashed, or unreadable document — all 404, never confirming existence to a
        // user without access, and never letting an unresolvable FAV_IDDOCUMENT_C reach the DB.
        DocumentDao documentDao = new DocumentDao();
        if (documentDao.getDocument(documentId, PermType.READ, getTargetIdList(null)) == null) {
            throw new NotFoundException();
        }

        FavoriteDao favoriteDao = new FavoriteDao();
        try {
            favoriteDao.create(principal.getId(), documentId);
        } catch (EntityNotFoundException e) {
            // The document was purged between the READ precheck and the insert: 404, never a 500.
            throw new NotFoundException();
        }

        return Response.ok().entity(Json.createObjectBuilder()
                .add("status", "ok").build()).build();
    }

    /**
     * Removes the current user's favorite of the given document.
     *
     * <p>A document the caller has not favorited yields 404 — the resource never confirms
     * another user's favorite state.
     *
     * @param documentId Document ID
     * @return Response
     */
    @DELETE
    @Path("{documentId: [a-z0-9\\-]+}")
    public Response remove(@PathParam("documentId") String documentId) {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }

        FavoriteDao favoriteDao = new FavoriteDao();
        if (!favoriteDao.delete(principal.getId(), documentId)) {
            throw new NotFoundException();
        }

        return Response.ok().entity(Json.createObjectBuilder()
                .add("status", "ok").build()).build();
    }

    /**
     * Lists the current user's favorited documents as ACL-filtered document DTOs, paginated.
     *
     * <p>Backed by the same ACL-filtered criteria search as {@code /document/list?favorites=me}:
     * an inner join to T_FAVORITE scopes the result to the caller's stars, and the search's
     * READ-ACL filter silently omits any favorite whose document the caller can no longer read
     * (never an error) — so a favorite never leaks a document the user has lost access to, and
     * the query is a single bounded, paginated statement rather than one lookup per favorite.
     *
     * @param limit Page size (default per {@link PaginatedLists})
     * @param offset Page offset
     * @return Response
     */
    @GET
    public Response list(
            @QueryParam("limit") Integer limit,
            @QueryParam("offset") Integer offset) {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }

        PaginatedList<DocumentDto> paginatedList = PaginatedLists.create(limit, offset);
        SortCriteria sortCriteria = new SortCriteria(null, null);

        DocumentCriteria documentCriteria = new DocumentCriteria();
        documentCriteria.setTargetIdList(getTargetIdList(null));
        documentCriteria.setFavoriteUserId(principal.getId());
        try {
            AppContext.getInstance().getIndexingHandler()
                    .findByCriteria(paginatedList, Lists.newArrayList(), documentCriteria, sortCriteria);
        } catch (Exception e) {
            throw new ServerException("SearchError", "Error searching in documents", e);
        }

        JsonArrayBuilder documents = Json.createArrayBuilder();
        for (DocumentDto documentDto : paginatedList.getResultList()) {
            documents.add(DocumentResourceHelper.createDocumentObjectBuilder(documentDto)
                    .add("favorite", true));
        }

        return Response.ok().entity(Json.createObjectBuilder()
                .add("total", paginatedList.getResultCount())
                .add("favorites", documents).build()).build();
    }
}
