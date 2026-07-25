package com.sismics.docs.rest.document;

import com.sismics.docs.application.document.DocumentAccessDeniedException;
import com.sismics.docs.application.document.DocumentNotFoundException;
import com.sismics.docs.application.document.SwapDocumentRelationCommand;
import com.sismics.docs.bootstrap.DocumentSliceModule;
import com.sismics.docs.rest.resource.BaseResource;
import com.sismics.rest.exception.ForbiddenClientException;
import com.sismics.rest.util.ValidationUtil;

import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;

/**
 * The document relation sub-resource on the composition-root slice: {@code POST /document/relation/swap}
 * reverses the direction of the link between two documents. It is a sibling root resource sharing the
 * {@code document} base path with {@link LegacyDocumentResource} and {@link DocumentCoverResource}; its
 * fully literal {@code relation/swap} sub-path sorts ahead of the legacy {@code {id}} template, so the
 * classes coexist without collision. Kept separate so every {@code rest.document} edge class stays under
 * the per-class size cap. Like the rest of the slice edge it validates HTTP input, invokes the application
 * handler inside a {@link com.sismics.docs.application.document.UnitOfWork}, and exits every failure by
 * THROW so the request filter's status-driven finalization rolls back as the legacy path did.
 */
@Path("document")
public class DocumentRelationResource extends BaseResource {

    private final DocumentSliceModule module = DocumentSliceModule.get();

    /**
     * Reverses the relation between two documents: the link currently reading {@code id -> target} ends up
     * reading {@code target -> id}. The two documents are named in their CURRENT orientation, so the caller
     * always passes the row exactly as it is displayed. WRITE on BOTH documents is required, because the
     * link leaves one document's outgoing list and joins the other's.
     *
     * <p>The operation is idempotent and total over the states the schema allows: with the reverse link
     * already present the forward one is dropped rather than duplicated, duplicate forward rows collapse
     * onto the single flipped row, and a pair that already points the requested way is left untouched and
     * still answers 200.</p>
     *
     * @api {post} /document/relation/swap Reverse the direction of a document relation
     * @apiName PostDocumentRelationSwap
     * @apiGroup Document
     * @apiParam {String} id Document the relation currently points from
     * @apiParam {String} target Document the relation currently points at
     * @apiSuccess {String} status Status OK
     * @apiError (client) ForbiddenError Access denied
     * @apiError (client) ValidationError Validation error
     * @apiError (client) NotFound Document not found, or the two documents are unrelated
     * @apiPermission user
     * @apiVersion 1.5.0
     *
     * @param documentId       Document the relation currently points from
     * @param targetDocumentId Document the relation currently points at
     * @return Response
     */
    @POST
    @Path("relation/swap")
    public Response swap(
            @FormParam("id") String documentId,
            @FormParam("target") String targetDocumentId) {
        // Guests are read-only, even where a WRITE ACL would otherwise resolve for the guest account.
        if (!authenticate() || principal.isGuest()) {
            throw new ForbiddenClientException();
        }

        // Validate input data
        ValidationUtil.validateRequired(documentId, "id");
        ValidationUtil.validateRequired(targetDocumentId, "target");

        SwapDocumentRelationCommand command = new SwapDocumentRelationCommand(
                documentId, targetDocumentId, principal.getId(), getTargetIdList(null));
        try {
            module.unitOfWork().required(() -> module.swapDocumentRelationHandler().swap(command));
        } catch (DocumentAccessDeniedException | DocumentNotFoundException e) {
            // 404 discipline, mirroring POST /file/reorder rather than POST /file/{id}/move: a document the
            // caller may not write is reported exactly like one that does not exist, so this endpoint
            // cannot be used to probe for the existence of documents outside the caller's reach. The
            // handler keeps the two cases distinct; merging them is an edge policy.
            throw new NotFoundException();
        }

        return LegacyDocumentResponseMapper.statusOk();
    }
}
