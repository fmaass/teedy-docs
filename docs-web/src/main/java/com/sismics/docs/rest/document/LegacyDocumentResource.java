package com.sismics.docs.rest.document;

import com.sismics.docs.application.document.DocumentAccessDeniedException;
import com.sismics.docs.application.document.DocumentFileAccessException;
import com.sismics.docs.application.document.DocumentNotFoundException;
import com.sismics.docs.application.document.DocumentValidationException;
import com.sismics.docs.application.document.DocumentView;
import com.sismics.docs.application.document.DuplicateDocumentCommand;
import com.sismics.docs.application.document.GetDocumentQuery;
import com.sismics.docs.application.document.UpdateDocumentCommand;
import com.sismics.docs.application.document.UpdatedDocumentResult;
import com.sismics.docs.bootstrap.DocumentSliceModule;
import com.sismics.docs.core.constant.Constants;
import com.sismics.docs.rest.resource.BaseResource;
import com.sismics.docs.rest.util.DocumentResourceHelper;
import com.sismics.rest.exception.ClientException;
import com.sismics.rest.exception.ForbiddenClientException;
import com.sismics.rest.exception.ServerException;
import com.sismics.rest.util.ValidationUtil;

import jakarta.json.Json;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * The two document endpoints migrated onto the composition-root slice: {@code GET /document/{id}} and
 * the partial-update {@code POST /document/{id}}. It extends {@link BaseResource} to keep the exact
 * inherited authentication context, media types, and both ACL target-list computations, parses and
 * validates HTTP input in the legacy order, invokes the application handler inside a
 * {@link com.sismics.docs.application.document.UnitOfWork}, and maps the result. Every failure exits
 * by THROW — a translated {@code WebApplicationException} that yields a non-2xx status — so the
 * request filter's status-driven finalization rolls back exactly as the legacy path; the edge never
 * constructs an error {@code Response}.
 */
@Path("document")
public class LegacyDocumentResource extends BaseResource {

    private final DocumentSliceModule module = DocumentSliceModule.get();

    /**
     * Returns a document.
     *
     * @param documentId Document ID
     * @param shareId    Share ID
     * @param files      If true includes files information
     * @return Response
     */
    @GET
    @Path("{id: [a-z0-9\\-]+}")
    public Response get(
            @PathParam("id") String documentId,
            @QueryParam("share") String shareId,
            @QueryParam("files") Boolean files) {
        // authenticate() returns false for the anonymous share principal; the return IS the anonymity
        // signal (its side effect installs the principal used by getTargetIdList).
        boolean anonymous = !authenticate();

        GetDocumentQuery query = new GetDocumentQuery(
                documentId,
                shareId,
                Boolean.TRUE == files,
                getTargetIdList(shareId),
                getTargetIdList(null),
                principal.getId(),
                anonymous);

        DocumentView view;
        try {
            view = module.unitOfWork().query(() -> module.getDocumentHandler().handle(query));
        } catch (DocumentNotFoundException e) {
            throw new NotFoundException();
        } catch (DocumentFileAccessException e) {
            throw new ServerException("FileError", e.getMessage());
        }
        return LegacyDocumentResponseMapper.toResponse(view);
    }

    /**
     * Partial-updates the document. Only fields present in the submitted form are modified; omitted
     * fields are preserved. Title and language are always required.
     *
     * @param id   Document ID
     * @param form Form parameters
     * @return Response
     */
    @POST
    @Path("{id: [a-z0-9\\-]+}")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response update(
            @PathParam("id") String id,
            MultivaluedMap<String, String> form) {
        String title = form.getFirst("title");
        String description = form.getFirst("description");
        String subject = form.getFirst("subject");
        String identifier = form.getFirst("identifier");
        String publisher = form.getFirst("publisher");
        String format = form.getFirst("format");
        String source = form.getFirst("source");
        String type = form.getFirst("type");
        String coverage = form.getFirst("coverage");
        String rights = form.getFirst("rights");
        List<String> tagList = form.get("tags");
        List<String> relationList = form.get("relations");
        List<String> metadataIdList = form.get("metadata_id");
        List<String> metadataValueList = form.get("metadata_value");
        String language = form.getFirst("language");
        String createDateStr = form.getFirst("create_date");
        // Authenticate BEFORE validation so a malformed anonymous request is a 403, not a 400.
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }

        // Validate input data (field-specific trimming preserved: validateLength strips, validateDate
        // does not, and the reset sentinels are read raw).
        title = ValidationUtil.validateLength(title, "title", 1, 100, false);
        language = ValidationUtil.validateLength(language, "language", 3, 7, false);
        description = DocumentResourceHelper.sanitizeDescription(description);
        subject = ValidationUtil.validateLength(subject, "subject", 0, 500, true);
        identifier = ValidationUtil.validateLength(identifier, "identifier", 0, 500, true);
        publisher = ValidationUtil.validateLength(publisher, "publisher", 0, 500, true);
        format = ValidationUtil.validateLength(format, "format", 0, 500, true);
        source = ValidationUtil.validateLength(source, "source", 0, 500, true);
        type = ValidationUtil.validateLength(type, "type", 0, 100, true);
        coverage = ValidationUtil.validateLength(coverage, "coverage", 0, 100, true);
        rights = ValidationUtil.validateLength(rights, "rights", 0, 100, true);
        Date createDate = ValidationUtil.validateDate(createDateStr, "create_date", true);
        if (language != null && !Constants.SUPPORTED_LANGUAGES.contains(language)) {
            throw new ClientException("ValidationError", MessageFormat.format("{0} is not a supported language", language));
        }

        List<String> writeTargetIds = getTargetIdList(null);
        boolean tagsReset = Boolean.parseBoolean(form.getFirst("tags_reset"));
        boolean relationsReset = Boolean.parseBoolean(form.getFirst("relations_reset"));
        boolean metadataReset = Boolean.parseBoolean(form.getFirst("metadata_reset"));
        boolean applyTags = form.containsKey("tags") || tagsReset;
        boolean applyRelations = form.containsKey("relations") || relationsReset;
        List<String> effectiveTags = tagList != null ? tagList : new ArrayList<>();
        List<String> effectiveRelations = relationList != null ? relationList : new ArrayList<>();

        UpdateDocumentCommand command = new UpdateDocumentCommand(
                id,
                principal.getId(),
                writeTargetIds,
                title,
                language,
                description,
                subject,
                identifier,
                publisher,
                format,
                source,
                type,
                coverage,
                rights,
                createDateStr != null,
                createDate,
                applyTags,
                effectiveTags,
                applyRelations,
                effectiveRelations,
                form.containsKey("metadata_id"),
                metadataIdList,
                metadataValueList,
                metadataReset);

        UpdatedDocumentResult result;
        try {
            // Block-bodied lambda: value-compatible only, so it resolves to the result-bearing
            // required(Supplier) overload rather than required(Runnable).
            result = module.unitOfWork().required(() -> {
                return module.updateDocumentHandler().handle(command);
            });
        } catch (DocumentAccessDeniedException e) {
            throw new ForbiddenClientException();
        } catch (DocumentNotFoundException e) {
            throw new NotFoundException();
        } catch (DocumentValidationException e) {
            throw new ClientException(e.getType(), e.getMessage());
        }

        return LegacyDocumentResponseMapper.toResponse(result);
    }

    /**
     * Duplicates a document into a new document owned by the requester.
     *
     * @param documentId Document ID to duplicate
     * @return Response carrying the new document id
     */
    @POST
    @Path("{id: [a-z0-9\\-]+}/duplicate")
    public Response duplicate(@PathParam("id") String documentId) {
        // Guests and share-token sessions are read-only, even where a READ ACL would resolve for them.
        if (!authenticate() || principal.isGuest()) {
            throw new ForbiddenClientException();
        }

        DuplicateDocumentCommand command = new DuplicateDocumentCommand(
                documentId, principal.getId(), getTargetIdList(null));

        String newDocumentId;
        try {
            // Block-bodied lambda: value-compatible only, so it resolves to the result-bearing
            // required(Supplier) overload rather than required(Runnable).
            newDocumentId = module.unitOfWork().required(() -> {
                return module.duplicateDocumentHandler().handle(command);
            });
        } catch (DocumentAccessDeniedException e) {
            throw new ForbiddenClientException();
        } catch (DocumentNotFoundException e) {
            throw new NotFoundException();
        } catch (DocumentValidationException e) {
            throw new ClientException(e.getType(), e.getMessage());
        } catch (DocumentFileAccessException e) {
            throw new ServerException("FileError", e.getMessage());
        }

        return Response.ok().entity(Json.createObjectBuilder().add("id", newDocumentId).build()).build();
    }
}
