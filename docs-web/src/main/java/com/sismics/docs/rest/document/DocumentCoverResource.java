package com.sismics.docs.rest.document;

import com.sismics.docs.application.document.ClearDocumentCoverCommand;
import com.sismics.docs.application.document.DocumentAccessDeniedException;
import com.sismics.docs.application.document.DocumentValidationException;
import com.sismics.docs.application.document.SetDocumentCoverCommand;
import com.sismics.docs.bootstrap.DocumentSliceModule;
import com.sismics.docs.rest.resource.BaseResource;
import com.sismics.rest.exception.ClientException;
import com.sismics.rest.exception.ForbiddenClientException;
import com.sismics.rest.util.ValidationUtil;

import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;

/**
 * The document cover sub-resource on the composition-root slice: {@code POST /document/{id}/cover} sets
 * the explicit cover file and {@code DELETE /document/{id}/cover} clears it. It is a sibling root
 * resource sharing the {@code document} base path with {@link LegacyDocumentResource}; Jersey scans
 * {@code com.sismics.docs.rest.document} and dispatches by the distinct {@code {id}/cover} sub-path, so
 * the two classes coexist without collision. Kept separate so every {@code rest.document} edge class
 * stays under the per-class size cap. Like the rest of the slice edge it validates HTTP input, invokes
 * the application handler inside a {@link com.sismics.docs.application.document.UnitOfWork}, and exits
 * every failure by THROW so the request filter's status-driven finalization rolls back as the legacy
 * path did.
 */
@Path("document")
public class DocumentCoverResource extends BaseResource {

    private final DocumentSliceModule module = DocumentSliceModule.get();

    /**
     * Sets a document's explicit cover file. The chosen file becomes the document's served cover (its
     * thumbnail in the list/gallery); the served pointer is reconciled synchronously in this request's
     * transaction and a document-updated event refreshes the search index and contributor list.
     *
     * @api {post} /document/:id/cover Set the document cover file
     * @apiName PostDocumentCover
     * @apiGroup Document
     * @apiParam {String} id Document ID
     * @apiParam {String} file File ID to use as the cover (must be attached to the document)
     * @apiSuccess {String} status Status OK
     * @apiError (client) ForbiddenError Access denied
     * @apiError (client) ValidationError The file is not attached to this document
     * @apiPermission user
     * @apiVersion 1.5.0
     *
     * @param documentId Document ID
     * @param fileId     File ID to use as the cover
     * @return Response
     */
    @POST
    @Path("{id: [a-z0-9\\-]+}/cover")
    public Response setCover(
            @PathParam("id") String documentId,
            @FormParam("file") String fileId) {
        // Guests are read-only, even where a WRITE ACL would otherwise resolve for the guest account.
        if (!authenticate() || principal.isGuest()) {
            throw new ForbiddenClientException();
        }

        // Validate input data
        ValidationUtil.validateRequired(fileId, "file");

        SetDocumentCoverCommand command = new SetDocumentCoverCommand(
                documentId, principal.getId(), getTargetIdList(null), fileId);
        try {
            module.unitOfWork().required(() -> module.documentCoverHandler().setCover(command));
        } catch (DocumentAccessDeniedException e) {
            throw new ForbiddenClientException();
        } catch (DocumentValidationException e) {
            throw new ClientException(e.getType(), e.getMessage());
        }

        return LegacyDocumentResponseMapper.statusOk();
    }

    /**
     * Clears a document's explicit cover file, restoring the derived (first-file-by-order) cover. The
     * served pointer is re-derived synchronously in this request's transaction and a document-updated
     * event refreshes the search index and contributor list.
     *
     * @api {delete} /document/:id/cover Clear the document cover file
     * @apiName DeleteDocumentCover
     * @apiGroup Document
     * @apiParam {String} id Document ID
     * @apiSuccess {String} status Status OK
     * @apiError (client) ForbiddenError Access denied
     * @apiPermission user
     * @apiVersion 1.5.0
     *
     * @param documentId Document ID
     * @return Response
     */
    @DELETE
    @Path("{id: [a-z0-9\\-]+}/cover")
    public Response clearCover(
            @PathParam("id") String documentId) {
        // Guests are read-only, even where a WRITE ACL would otherwise resolve for the guest account.
        if (!authenticate() || principal.isGuest()) {
            throw new ForbiddenClientException();
        }

        ClearDocumentCoverCommand command = new ClearDocumentCoverCommand(
                documentId, principal.getId(), getTargetIdList(null));
        try {
            module.unitOfWork().required(() -> module.documentCoverHandler().clearCover(command));
        } catch (DocumentAccessDeniedException e) {
            throw new ForbiddenClientException();
        }

        return LegacyDocumentResponseMapper.statusOk();
    }
}
