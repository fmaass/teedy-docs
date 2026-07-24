package com.sismics.docs.rest.resource;

import com.google.common.collect.Lists;
import com.sismics.docs.core.constant.AuditLogType;
import com.sismics.docs.core.constant.ConfigType;
import com.sismics.docs.core.constant.Constants;
import com.sismics.docs.core.constant.PermType;
import com.sismics.docs.core.dao.AclDao;
import com.sismics.docs.core.dao.AuditLogDao;
import com.sismics.docs.core.dao.DocumentDao;
import com.sismics.docs.core.dao.FavoriteDao;
import com.sismics.docs.core.dao.FileDao;
import com.sismics.docs.core.dao.TagDao;
import com.sismics.docs.core.dao.UserDao;
import com.sismics.docs.core.dao.criteria.DocumentCriteria;
import com.sismics.docs.core.dao.criteria.TagCriteria;
import com.sismics.docs.core.dao.dto.DocumentDto;
import com.sismics.docs.core.dao.dto.TagDto;
import com.sismics.docs.core.event.DocumentCreatedAsyncEvent;
import com.sismics.docs.core.event.DocumentRestoredAsyncEvent;
import com.sismics.docs.core.event.DocumentTrashedAsyncEvent;
import com.sismics.docs.core.event.DocumentUpdatedAsyncEvent;
import com.sismics.docs.core.model.context.AppContext;
import com.sismics.docs.core.model.jpa.AuditLog;
import com.sismics.docs.core.model.jpa.Document;
import com.sismics.docs.core.model.jpa.File;
import com.sismics.docs.core.model.jpa.User;
import com.sismics.docs.core.util.ConfigUtil;
import com.sismics.docs.core.util.ExportGuard;
import com.sismics.docs.core.util.ExportUtil;
import com.sismics.docs.core.util.DocumentUtil;
import com.sismics.docs.core.util.FileUtil;
import com.sismics.docs.core.util.MetadataUtil;
import com.sismics.docs.core.util.PdfUtil;
import com.sismics.docs.core.util.jpa.PaginatedList;
import com.sismics.docs.core.util.jpa.PaginatedLists;
import com.sismics.docs.core.util.jpa.SortCriteria;
import com.sismics.docs.rest.util.DocumentResourceHelper;
import com.sismics.docs.rest.util.DocumentSearchCriteriaUtil;
import com.sismics.rest.exception.ClientException;
import com.sismics.rest.exception.ForbiddenClientException;
import com.sismics.rest.exception.ServerException;
import com.sismics.rest.util.ValidationUtil;
import com.sismics.util.EmailUtil;
import com.sismics.util.JsonUtil;
import com.sismics.util.context.ThreadLocalContext;
import com.sismics.util.mime.MimeType;
import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObjectBuilder;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HEAD;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.glassfish.jersey.media.multipart.FormDataBodyPart;
import org.glassfish.jersey.media.multipart.FormDataParam;

import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.MessageFormat;
import java.util.Comparator;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Document REST resources.
 *
 * @author bgamard
 */
@Path("/document")
public class DocumentResource extends BaseResource {
    /**
     * Logger.
     */
    private static final Logger log = LoggerFactory.getLogger(DocumentResource.class);

    /**
     * Export a document to PDF.
     *
     * @api {get} /document/:id/pdf Export a document to PDF
     * @apiName GetDocumentPdf
     * @apiGroup Document
     * @apiParam {String} id Document ID
     * @apiParam {String} share Share ID
     * @apiParam {Boolean} metadata If true, export metadata
     * @apiParam {Boolean} fitimagetopage If true, fit the images to pages
     * @apiParam {Number} margin Margin around the pages, in millimeter
     * @apiSuccess {String} pdf The whole response is the PDF file
     * @apiError (client) NotFound Document not found
     * @apiError (client) ValidationError Validation error
     * @apiPermission none
     * @apiVersion 1.5.0
     *
     * @param documentId Document ID
     * @param shareId Share ID
     * @param metadata Export metadata
     * @param fitImageToPage Fit images to page
     * @param marginStr Margins
     * @return Response
     */
    @GET
    @Path("{id: [a-z0-9\\-]+}/pdf")
    public Response getPdf(
            @PathParam("id") String documentId,
            @QueryParam("share") String shareId,
            final @QueryParam("metadata") Boolean metadata,
            final @QueryParam("fitimagetopage") Boolean fitImageToPage,
            @QueryParam("margin") String marginStr) {
        authenticate();

        // Validate input
        final int margin = ValidationUtil.validateInteger(marginStr, "margin");

        // Get document and check read permission
        DocumentDao documentDao = new DocumentDao();
        final DocumentDto documentDto = documentDao.getDocument(documentId, PermType.READ, getTargetIdList(shareId));
        if (documentDto == null) {
            throw new NotFoundException();
        }

        // Get files
        FileDao fileDao = new FileDao();
        UserDao userDao = new UserDao();
        final List<File> fileList = fileDao.getByDocumentId(null, documentId);
        for (File file : fileList) {
            // A file is always encrypted by the creator of it
            // Store its private key to decrypt it
            User user = userDao.getById(file.getUserId());
            file.setPrivateKey(user.getPrivateKey());
        }

        // Convert to PDF
        StreamingOutput stream = outputStream -> {
            try {
                PdfUtil.convertToPdf(documentDto, fileList, fitImageToPage, metadata, margin, outputStream);
            } catch (Exception e) {
                throw new IOException(e);
            }
        };

        return Response.ok(stream)
                .header("Content-Type", MimeType.APPLICATION_PDF)
                .header("Content-Disposition", "inline; filename=\"" + documentDto.getTitle() + ".pdf\"")
                .build();
    }

    /**
     * Returns all documents, if a parameter is considered invalid, the search result will be empty.
     *
     * @api {get} /document/list Get documents
     * @apiName GetDocumentList
     * @apiGroup Document
     *
     * @apiParam {String} [limit] Total number of documents to return (default is <code>10</code>)
     * @apiParam {String} [offset] Start at this index (default is <code>0</code>)
     * @apiParam {Number} [sort_column] Column index to sort on
     * @apiParam {Boolean} [asc] If <code>true</code> sorts in ascending order
     * @apiParam {String} [search] Search query (see "Document search syntax" on the top of the page for explanations) when the input is entered by a human.
     * @apiParam {Boolean} [files] If <code>true</code> includes files information
     *
     * @apiParam {String} [search[after]] The document must have been created after or at the value moment, accepted format is <code>yyyy-MM-dd</code>
     * @apiParam {String} [search[before]] The document must have been created before or at the value moment, accepted format is <code>yyyy-MM-dd</code>
     * @apiParam {String} [search[by]] The document must have been created by the specified creator's username with an exact match, the user must not be deleted
     * @apiParam {String} [search[full]] Used as a search criteria for all fields including the document's files content, several comma-separated values can be specified and the document must match any of them
     * @apiParam {String} [search[lang]] The document must be of the specified language (example: <code>en</code>)
     * @apiParam {String} [search[mime]] The document must be of the specified mime type (example: <code>image/png</code>)
     * @apiParam {String} [search[simple]] Used as a search criteria for all fields except the document's files content, several comma-separated values can be specified and the document must match any of them
     * @apiParam {Boolean} [search[shared]] If <code>true</code> the document must be shared, else it is ignored
     * @apiParam {String} [search[tag]] The document must contain a tag or a child of a tag that starts with the value, case is ignored, several comma-separated values can be specified and the document must match all tag filters
     * @apiParam {String} [search[nottag]] The document must not contain a tag or a child of a tag that starts with the value, case is ignored, several comma-separated values can be specified and the document must match all tag filters
     * @apiParam {String} [search[title]] The document's title must be the value, several comma-separated values can be specified and the document must match any of the titles
     * @apiParam {String} [search[uafter]] The document must have been updated after or at the value moment, accepted format is <code>yyyy-MM-dd</code>
     * @apiParam {String} [search[ubefore]] The document must have been updated before or at the value moment, accepted format is <code>yyyy-MM-dd</code>
     * @apiParam {String} [search[workflow]] If the value is <code>me</code> the document must have an active route, for other values the criteria is ignored
     * @apiParam {String} [favorites] If the value is <code>me</code> the document must be favorited by the current user, for other values the criteria is ignored
     *
     * @apiSuccess {Number} total Total number of documents
     * @apiSuccess {Object[]} documents List of documents
     * @apiSuccess {String} documents.id ID
     * @apiSuccess {String} documents.highlight Search highlight (for fulltext search)
     * @apiSuccess {String} documents.file_id Main file ID
     * @apiSuccess {Number} documents.file_rotation Baked clockwise rotation of the main file's raster
     * @apiSuccess {String} documents.title Title
     * @apiSuccess {String} documents.description Description
     * @apiSuccess {Number} documents.create_date Create date (timestamp)
     * @apiSuccess {Number} documents.update_date Update date (timestamp)
     * @apiSuccess {String} documents.language Language
     * @apiSuccess {Boolean} documents.shared True if the document is shared
     * @apiSuccess {Boolean} documents.active_route True if a route is active on this document
     * @apiSuccess {Boolean} documents.current_step_name Name of the current route step
     * @apiSuccess {Boolean} documents.favorite True if the current user has favorited this document
     * @apiSuccess {Number} documents.file_count Number of files in this document
     * @apiSuccess {Object[]} documents.tags List of tags
     * @apiSuccess {String} documents.tags.id ID
     * @apiSuccess {String} documents.tags.name Name
     * @apiSuccess {String} documents.tags.color Color
     * @apiSuccess {Object[]} documents.files List of files
     * @apiSuccess {String} documents.files.id ID
     * @apiSuccess {String} documents.files.name File name
     * @apiSuccess {String} documents.files.version Zero-based version number
     * @apiSuccess {String} documents.files.mimetype MIME type
     * @apiSuccess {String} documents.files.create_date Create date (timestamp)
     * @apiSuccess {String} documents.files.creator Username of the current version's uploader
     * @apiSuccess {String[]} suggestions List of search suggestions
     *
     * @apiError (client) ForbiddenError Access denied
     * @apiError (server) SearchError Error searching in documents
     * @apiPermission user
     * @apiVersion 1.5.0
     *
     * @param limit Page limit
     * @param offset Page offset
     * @param sortColumn Sort column
     * @param asc Sorting
     * @param search Search query
     * @param files Files list
     * @return Response
     */
    @GET
    @Path("list")
    public Response list(
            @QueryParam("limit") Integer limit,
            @QueryParam("offset") Integer offset,
            @QueryParam("sort_column") Integer sortColumn,
            @QueryParam("asc") Boolean asc,
            @QueryParam("search") String search,
            @QueryParam("files") Boolean files,

            @QueryParam("search[after]") String searchCreatedAfter,
            @QueryParam("search[before]") String searchCreatedBefore,
            @QueryParam("search[by]") String searchBy,
            @QueryParam("search[full]") String searchFull,
            @QueryParam("search[lang]") String searchLang,
            @QueryParam("search[mime]") String searchMime,
            @QueryParam("search[shared]") Boolean searchShared,
            @QueryParam("search[simple]") String searchSimple,
            @QueryParam("search[tag]") String searchTag,
            @QueryParam("search[nottag]") String searchTagNot,
            @QueryParam("search[title]") String searchTitle,
            @QueryParam("search[uafter]") String searchUpdatedAfter,
            @QueryParam("search[ubefore]") String searchUpdatedBefore,
            @QueryParam("search[searchworkflow]") String searchWorkflow,
            @QueryParam("search[tagMode]") String searchTagMode,
            @QueryParam("favorites") String favorites
    ) {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }

        JsonObjectBuilder response = Json.createObjectBuilder();
        JsonArrayBuilder documents = Json.createArrayBuilder();

        TagDao tagDao = new TagDao();
        PaginatedList<DocumentDto> paginatedList = PaginatedLists.create(limit, offset);
        List<String> suggestionList = Lists.newArrayList();
        SortCriteria sortCriteria = new SortCriteria(sortColumn, asc);

        List<TagDto> allTagDtoList = tagDao.findByCriteria(new TagCriteria().setTargetIdList(getTargetIdList(null)), null);

        DocumentCriteria documentCriteria = DocumentSearchCriteriaUtil.parseSearchQuery(search, allTagDtoList);
        DocumentSearchCriteriaUtil.addHttpSearchParams(
                documentCriteria,
                searchBy,
                searchCreatedAfter,
                searchCreatedBefore,
                searchFull,
                searchLang,
                searchMime,
                searchShared,
                searchSimple,
                searchTag,
                searchTagNot,
                searchTitle,
                searchUpdatedAfter,
                searchUpdatedBefore,
                searchWorkflow,
                // favorites=me restricts the list to the current user's favorited documents. The
                // criteria carries the user id (resolved here from the principal); the T_FAVORITE
                // join is built in LuceneIndexingHandler. Any value other than "me" is ignored.
                "me".equals(favorites) ? principal.getId() : null,
                allTagDtoList);

        if ("or".equalsIgnoreCase(searchTagMode)) {
            documentCriteria.setTagMode("or");
        }

        documentCriteria.setTargetIdList(getTargetIdList(null));
        try {
            AppContext.getInstance().getIndexingHandler().findByCriteria(paginatedList, suggestionList, documentCriteria, sortCriteria);
        } catch (Exception e) {
            throw new ServerException("SearchError", "Error searching in documents", e);
        }

        // The current user's favorited document IDs, fetched once for the per-row favorite flag.
        Set<String> favoriteDocumentIds = new HashSet<>(
                new FavoriteDao().getDocumentIdsByUser(principal.getId()));

        // Find the files of the documents
        Iterable<String> documentsIds = CollectionUtils.collect(paginatedList.getResultList(), DocumentDto::getId);
        FileDao fileDao = new FileDao();
        List<File> filesList = null;
        Map<String, Long> filesCountByDocument = null;
        // Resolved once for the whole page so each file's creator is attached without a per-file lookup.
        Map<String, String> creatorsByUserId = null;
        if (Boolean.TRUE == files) {
            filesList = fileDao.getByDocumentsIds(documentsIds);
            creatorsByUserId = DocumentResourceHelper.resolveFileCreators(filesList);
        } else {
            filesCountByDocument = fileDao.countByDocumentsIds(documentsIds);
        }

        for (DocumentDto documentDto : paginatedList.getResultList()) {
            // Get tags accessible by the current user on this document
            List<TagDto> tagDtoList = tagDao.findByCriteria(new TagCriteria()
                    .setTargetIdList(getTargetIdList(null))
                    .setDocumentId(documentDto.getId()), new SortCriteria(1, true));

            Long filesCount;
            Collection<File> filesOfDocument = null;
            if (Boolean.TRUE == files) {
                // Find files matching the document
                filesOfDocument = CollectionUtils.select(filesList, file -> file.getDocumentId().equals(documentDto.getId()));
                filesCount = (long) filesOfDocument.size();
            } else {
                filesCount = filesCountByDocument.getOrDefault(documentDto.getId(), 0L);
            }

            JsonObjectBuilder documentObjectBuilder = DocumentResourceHelper.createDocumentObjectBuilder(documentDto)
                    .add("active_route", documentDto.isActiveRoute())
                    .add("current_step_name", JsonUtil.nullable(documentDto.getCurrentStepName()))
                    .add("highlight", JsonUtil.nullable(documentDto.getHighlight()))
                    .add("file_count", filesCount)
                    .add("favorite", favoriteDocumentIds.contains(documentDto.getId()))
                    .add("tags", DocumentResourceHelper.createTagsArrayBuilder(tagDtoList));

            if (Boolean.TRUE == files) {
                documentObjectBuilder.add("files", DocumentResourceHelper.buildFileArray(filesOfDocument, creatorsByUserId));
            }
            documents.add(documentObjectBuilder);
        }

        JsonArrayBuilder suggestions = Json.createArrayBuilder();
        for (String suggestion : suggestionList) {
            suggestions.add(suggestion);
        }

        response.add("total", paginatedList.getResultCount())
                .add("documents", documents)
                .add("suggestions", suggestions);

        return Response.ok().entity(response.build()).build();
    }

    /**
     * Export the current user's documents as a ZIP archive with a JSON manifest.
     *
     * @api {get} /document/export Export the current user's documents
     * @apiDescription Streams a ZIP containing every document owned (created) by the caller,
     * each document's files in a per-document folder, plus a manifest.json describing them.
     * The export is scoped by creator, so it never leaks another user's documents.
     * @apiName GetDocumentExport
     * @apiGroup Document
     * @apiSuccess {Object} zip The ZIP archive is the whole response
     * @apiError (client) ForbiddenError Access denied
     * @apiPermission user
     * @apiVersion 1.12.0
     *
     * @return Response
     */
    @GET
    @Path("export")
    @Produces({MediaType.APPLICATION_OCTET_STREAM, MediaType.TEXT_PLAIN})
    public Response export() {
        // Jersey serves a HEAD for this @GET method by invoking it with the entity suppressed. Short-circuit
        // a HEAD BEFORE any side effect (export-permit acquisition, audit-row write): the StreamingOutput
        // that releases the permit never runs on a HEAD, so proceeding would leak a permit on every HEAD
        // (an export-capacity DoS). BaseResource sees the real servlet method via @Context, not Jersey's
        // implicit-HEAD dispatch.
        if (!"GET".equals(request.getMethod())) {
            return Response.ok().build();
        }

        if (!authenticate()) {
            throw new ForbiddenClientException();
        }

        // Optional kill switch for the whole endpoint (default enabled).
        if (!ExportGuard.isEnabled()) {
            throw new ClientException("ExportDisabled", "The export feature is disabled");
        }

        final String userId = principal.getId();
        final String username = principal.getName();
        final DocumentDao documentDao = new DocumentDao();

        // PREFLIGHT size cap: count the caller's exportable documents (a COUNT query, not
        // a full load) and reject BEFORE the eager load if it exceeds the configured cap.
        // This is what prevents an OOM: we never materialize an over-cap document list.
        final int maxDocuments = ExportGuard.getMaxDocuments();
        final long documentCount = documentDao.countByUserId(userId);
        if (documentCount > maxDocuments) {
            throw new ClientException("ExportTooLarge", MessageFormat.format(
                    "Export of {0} documents exceeds the maximum of {1}", documentCount, maxDocuments));
        }

        // Concurrency cap: only a bounded number of exports may buffer a manifest at once.
        // Reject (rather than queue) when the cap is reached; the permit is released in the
        // streaming finally so it spans the whole heap-buffering window, not just this method.
        // Hold the exact semaphore instance the permit was acquired on: a mid-flight config
        // change can rebuild the semaphore, and release must target that SAME instance.
        final java.util.concurrent.Semaphore exportPermit = ExportGuard.tryAcquire();
        if (exportPermit == null) {
            // Expected, retryable throttle: answer 503 with a Retry-After hint rather than a 500.
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .header("Retry-After", 30)
                    .entity(Json.createObjectBuilder()
                            .add("type", "ExportBusy")
                            .add("message", "Too many concurrent exports, try again later").build())
                    .build();
        }

        // Audit the export. It is a read, but the spec requires it be recorded; write the
        // row here inside the request transaction (the streaming output runs after commit),
        // keyed to the caller's own user entity.
        boolean released = false;
        try {
            AuditLog exportAuditLog = new AuditLog();
            exportAuditLog.setUserId(userId);
            exportAuditLog.setEntityId(userId);
            exportAuditLog.setEntityClass("Export");
            exportAuditLog.setType(AuditLogType.CREATE);
            exportAuditLog.setMessage(MessageFormat.format(
                    "Exported {0} document(s)", documentCount));
            new AuditLogDao().create(exportAuditLog);

            // The caller's own (non-trashed) documents, scoped by creator so that an admin
            // cannot accidentally export another user's data through this endpoint.
            final List<Document> documentList = documentDao.findByUserId(userId);

            StreamingOutput stream = outputStream -> {
            // Release the concurrency permit once the manifest buffering / streaming is done,
            // whichever way it ends. This spans the heap-heavy window, not just the resource
            // method (which returns before the stream is serialized).
            try {
                ExportUtil.writeExportZip(userId, username, documentList, outputStream);
            } catch (Exception e) {
                throw new IOException("Error writing export archive", e);
            } finally {
                ExportGuard.release(exportPermit);
            }
        };

        // The stream now owns releasing the permit; from here a normal return hands it off.
        released = true;
        return Response.ok(stream)
                .header("Content-Type", "application/zip")
                .header("Content-Disposition", "attachment; filename=\"teedy-export.zip\"")
                .build();
        } finally {
            // If we acquired a permit but never handed it to a running stream (an exception
            // between acquire and return), release it here to avoid leaking a permit.
            if (!released) {
                ExportGuard.release(exportPermit);
            }
        }
    }

    /**
     * Returns all documents.
     *
     * @api {post} /document/list Get documents
     * @apiDescription Get documents exposed as a POST endpoint to allow longer search parameters, see the GET endpoint for the API info
     * @apiName PostDocumentList
     * @apiGroup Document
     * @apiVersion 1.12.0
     *
     * @param limit      Page limit
     * @param offset     Page offset
     * @param sortColumn Sort column
     * @param asc        Sorting
     * @param search     Search query
     * @param files      Files list
     * @return Response
     */
    @POST
    @Path("list")
    public Response listPost(
            @FormParam("limit") Integer limit,
            @FormParam("offset") Integer offset,
            @FormParam("sort_column") Integer sortColumn,
            @FormParam("asc") Boolean asc,
            @FormParam("search") String search,
            @FormParam("files") Boolean files,
            @FormParam("search[after]") String searchCreatedAfter,
            @FormParam("search[before]") String searchCreatedBefore,
            @FormParam("search[by]") String searchBy,
            @FormParam("search[full]") String searchFull,
            @FormParam("search[lang]") String searchLang,
            @FormParam("search[mime]") String searchMime,
            @FormParam("search[shared]") Boolean searchShared,
            @FormParam("search[simple]") String searchSimple,
            @FormParam("search[tag]") String searchTag,
            @FormParam("search[nottag]") String searchTagNot,
            @FormParam("search[title]") String searchTitle,
            @FormParam("search[uafter]") String searchUpdatedAfter,
            @FormParam("search[ubefore]") String searchUpdatedBefore,
            @FormParam("search[searchworkflow]") String searchWorkflow,
            @FormParam("search[tagMode]") String searchTagMode,
            @FormParam("favorites") String favorites
    ) {
        return list(
                limit,
                offset,
                sortColumn,
                asc,
                search,
                files,
                searchCreatedAfter,
                searchCreatedBefore,
                searchBy,
                searchFull,
                searchLang,
                searchMime,
                searchShared,
                searchSimple,
                searchTag,
                searchTagNot,
                searchTitle,
                searchUpdatedAfter,
                searchUpdatedBefore,
                searchWorkflow,
                searchTagMode,
                favorites
        );
    }

    /**
     * Creates a new document.
     *
     * @api {put} /document Add a document
     * @apiName PutDocument
     * @apiGroup Document
     * @apiParam {String} title Title
     * @apiParam {String} [description] Description
     * @apiParam {String} [subject] Subject
     * @apiParam {String} [identifier] Identifier
     * @apiParam {String} [publisher] Publisher
     * @apiParam {String} [format] Format
     * @apiParam {String} [source] Source
     * @apiParam {String} [type] Type
     * @apiParam {String} [coverage] Coverage
     * @apiParam {String} [rights] Rights
     * @apiParam {String[]} [tags] List of tags ID
     * @apiParam {String[]} [relations] List of related documents ID
     * @apiParam {String[]} [metadata_id] List of metadata ID
     * @apiParam {String[]} [metadata_value] List of metadata values
     * @apiParam {String} language Language
     * @apiParam {Number} [create_date] Create date (timestamp)
     * @apiSuccess {String} id Document ID
     * @apiError (client) ForbiddenError Access denied
     * @apiError (client) ValidationError Validation error
     * @apiPermission user
     * @apiVersion 1.5.0
     *
     * @param title Title
     * @param description Description
     * @param subject Subject
     * @param identifier Identifier
     * @param publisher Publisher
     * @param format Format
     * @param source Source
     * @param type Type
     * @param coverage Coverage
     * @param rights Rights
     * @param tagList Tags
     * @param relationList Relations
     * @param metadataIdList Metadata ID list
     * @param metadataValueList Metadata value list
     * @param language Language
     * @param createDateStr Creation date
     * @return Response
     */
    @PUT
    public Response add(
            @FormParam("title") String title,
            @FormParam("description") String description,
            @FormParam("subject") String subject,
            @FormParam("identifier") String identifier,
            @FormParam("publisher") String publisher,
            @FormParam("format") String format,
            @FormParam("source") String source,
            @FormParam("type") String type,
            @FormParam("coverage") String coverage,
            @FormParam("rights") String rights,
            @FormParam("tags") List<String> tagList,
            @FormParam("relations") List<String> relationList,
            @FormParam("metadata_id") List<String> metadataIdList,
            @FormParam("metadata_value") List<String> metadataValueList,
            @FormParam("language") String language,
            @FormParam("create_date") String createDateStr) {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }

        // Validate input data
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
        if (!Constants.SUPPORTED_LANGUAGES.contains(language)) {
            throw new ClientException("ValidationError", MessageFormat.format("{0} is not a supported language", language));
        }

        // Create the document
        Document document = new Document();
        document.setUserId(principal.getId());
        document.setTitle(title);
        document.setDescription(description);
        document.setSubject(subject);
        document.setIdentifier(identifier);
        document.setPublisher(publisher);
        document.setFormat(format);
        document.setSource(source);
        document.setType(type);
        document.setCoverage(coverage);
        document.setRights(rights);
        document.setLanguage(language);
        if (createDate == null) {
            document.setCreateDate(new Date());
        } else {
            document.setCreateDate(createDate);
        }

        // Save the document, create the base ACLs
        document = DocumentUtil.createDocument(document, principal.getId());

        // Update tags
        DocumentResourceHelper.updateTagList(document.getId(), tagList, getTargetIdList(null));

        // Update relations
        DocumentResourceHelper.updateRelationList(document.getId(), relationList);

        // Update custom metadata
        try {
            MetadataUtil.updateMetadata(document.getId(), metadataIdList, metadataValueList);
        } catch (Exception e) {
            throw new ClientException("ValidationError", e.getMessage());
        }

        // Raise a document created event
        DocumentCreatedAsyncEvent documentCreatedAsyncEvent = new DocumentCreatedAsyncEvent();
        documentCreatedAsyncEvent.setUserId(principal.getId());
        documentCreatedAsyncEvent.setDocumentId(document.getId());
        ThreadLocalContext.get().addAsyncEvent(documentCreatedAsyncEvent);

        JsonObjectBuilder response = Json.createObjectBuilder()
                .add("id", document.getId());
        return Response.ok().entity(response.build()).build();
    }

    /**
     * Import a new document from an EML file.
     *
     * @api {put} /document/eml Import a new document from an EML file
     * @apiName PutDocumentEml
     * @apiGroup Document
     * @apiParam {String} file File data
     * @apiError (client) ForbiddenError Access denied
     * @apiError (client) ValidationError Validation error
     * @apiError (server) StreamError Error reading the input file
     * @apiError (server) ErrorGuessMime Error guessing mime type
     * @apiError (client) QuotaReached Quota limit reached
     * @apiError (server) FileError Error adding a file
     * @apiPermission user
     * @apiVersion 1.5.0
     *
     * @param fileBodyPart File to import
     * @return Response
     */
    @PUT
    @Path("eml")
    @Consumes("multipart/form-data")
    public Response importEml(@FormDataParam("file") FormDataBodyPart fileBodyPart) {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }

        // Validate input data
        ValidationUtil.validateRequired(fileBodyPart, "file");

        // Read the EML file and add its files. The original EML temp is created inside the
        // cleanup scope so a failure during its multipart copy also deletes it. EmailUtil
        // extracts each attachment into its own plaintext temp file; ownership of a temp
        // transfers to the async event queued by FileUtil.createFile only once that call
        // returns normally. Any temp not handed off (copy/parse/createFile failure) is
        // deleted by the finally below.
        java.nio.file.Path unencryptedFile = null;
        Properties props = new Properties();
        Session mailSession = Session.getDefaultInstance(props, null);
        EmailUtil.MailContent mailContent = new EmailUtil.MailContent();
        Set<java.nio.file.Path> handedOffTemps = new HashSet<>();
        try {
            // Save the uploaded EML to a temporary file
            try {
                unencryptedFile = AppContext.getInstance().getFileService().createTemporaryFile();
                Files.copy(fileBodyPart.getValueAs(InputStream.class), unencryptedFile, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new ServerException("StreamError", "Error reading the input file", e);
            }

            try (InputStream inputStream = Files.newInputStream(unencryptedFile)) {
                Message message = new MimeMessage(mailSession, inputStream);
                mailContent.setSubject(message.getSubject());
                mailContent.setDate(message.getSentDate());
                EmailUtil.parseMailContent(message, mailContent);
            } catch (IOException | MessagingException e) {
                throw new ServerException("StreamError", "Error reading the temporary file", e);
            }

            // Create the document
            Document document = new Document();
            document.setUserId(principal.getId());
            if (mailContent.getSubject() == null) {
                document.setTitle("Imported email from EML file");
            } else {
                document.setTitle(StringUtils.abbreviate(mailContent.getSubject(), 100));
            }
            document.setDescription(DocumentResourceHelper.sanitizeDescription(StringUtils.abbreviate(mailContent.getMessage(), 4000)));
            document.setSubject(StringUtils.abbreviate(mailContent.getSubject(), 500));
            document.setFormat("EML");
            document.setSource("Email");
            document.setLanguage(ConfigUtil.getConfigStringValue(ConfigType.DEFAULT_LANGUAGE));
            if (mailContent.getDate() == null) {
                document.setCreateDate(new Date());
            } else {
                document.setCreateDate(mailContent.getDate());
            }

            // Save the document, create the base ACLs
            DocumentUtil.createDocument(document, principal.getId());

            // Raise a document created event
            DocumentCreatedAsyncEvent documentCreatedAsyncEvent = new DocumentCreatedAsyncEvent();
            documentCreatedAsyncEvent.setUserId(principal.getId());
            documentCreatedAsyncEvent.setDocumentId(document.getId());
            ThreadLocalContext.get().addAsyncEvent(documentCreatedAsyncEvent);

            // Add files to the document
            try {
                for (EmailUtil.FileContent fileContent : mailContent.getFileContentList()) {
                    FileUtil.createFile(fileContent.getName(), null, fileContent.getFile(), fileContent.getSize(),
                            document.getLanguage(), principal.getId(), document.getId());
                    // Ownership transferred to the async event queued by createFile
                    handedOffTemps.add(fileContent.getFile());
                }
            } catch (IOException e) {
                throw new ClientException(e.getMessage(), e.getMessage(), e);
            } catch (Exception e) {
                throw new ServerException("FileError", "Error adding a file", e);
            }

            JsonObjectBuilder response = Json.createObjectBuilder()
                    .add("id", document.getId());
            return Response.ok().entity(response.build()).build();
        } finally {
            // Delete the original EML temp (only needed for parsing) and any attachment
            // temp whose ownership was not handed off to a queued async event.
            deleteTempIfExists(unencryptedFile, "EML");
            for (EmailUtil.FileContent fileContent : mailContent.getFileContentList()) {
                if (!handedOffTemps.contains(fileContent.getFile())) {
                    deleteTempIfExists(fileContent.getFile(), "EML attachment");
                }
            }
        }
    }

    /**
     * Delete a plaintext temporary file, logging on failure.
     *
     * @param path Temp file (may be null)
     * @param label Human-readable label for logging
     */
    private void deleteTempIfExists(java.nio.file.Path path, String label) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Unable to delete temporary " + label + " file: " + path, e);
        }
    }

    /**
     * Moves a document to trash (soft-delete).
     *
     * @api {delete} /document/:id Move document to trash
     * @apiName DeleteDocument
     * @apiGroup Document
     * @apiParam {String} id ID
     * @apiSuccess {String} status Status OK
     * @apiError (client) ForbiddenError Access denied
     * @apiError (client) NotFound Document not found
     * @apiPermission user
     * @apiVersion 1.5.0
     *
     * @param id Document ID
     * @return Response
     */
    @DELETE
    @Path("{id: [a-z0-9\\-]+}")
    public Response delete(
            @PathParam("id") String id) {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }

        DocumentDao documentDao = new DocumentDao();
        AclDao aclDao = new AclDao();
        if (!aclDao.checkPermission(id, PermType.WRITE, getTargetIdList(null))) {
            throw new NotFoundException();
        }

        documentDao.delete(id, principal.getId());

        DocumentTrashedAsyncEvent trashedEvent = new DocumentTrashedAsyncEvent();
        trashedEvent.setUserId(principal.getId());
        trashedEvent.setDocumentId(id);
        ThreadLocalContext.get().addAsyncEvent(trashedEvent);

        JsonObjectBuilder response = Json.createObjectBuilder()
                .add("status", "ok");
        return Response.ok().entity(response.build()).build();
    }

    /**
     * Sets a document's explicit cover file. The chosen file becomes the document's served cover
     * (its thumbnail in the list/gallery); a document-updated event reconciles the served pointer.
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
     * @param fileId File ID to use as the cover
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

        AclDao aclDao = new AclDao();
        if (!aclDao.checkPermission(documentId, PermType.WRITE, getTargetIdList(null))) {
            throw new ForbiddenClientException();
        }

        // The chosen file must be attached to THIS document (latest version, not deleted). Setting a
        // cover to a file that does not belong to the document is a client error, not a silent no-op.
        FileDao fileDao = new FileDao();
        boolean attached = fileDao.getByDocumentId(null, documentId).stream()
                .anyMatch(file -> file.getId().equals(fileId));
        if (!attached) {
            throw new ClientException("ValidationError", MessageFormat.format("File not found: {0}", fileId));
        }

        // Reconcile the served pointer synchronously in this transaction so an immediate read-back
        // reflects the new cover without waiting for the async event; the row lock inside
        // reconcileServingCover keeps this atomic against concurrent file operations. The event still
        // fires to refresh the search index and contributor list.
        DocumentDao documentDao = new DocumentDao();
        documentDao.updateCoverFileId(documentId, fileId);
        documentDao.reconcileServingCover(documentId);

        DocumentUpdatedAsyncEvent event = new DocumentUpdatedAsyncEvent();
        event.setUserId(principal.getId());
        event.setDocumentId(documentId);
        ThreadLocalContext.get().addAsyncEvent(event);

        JsonObjectBuilder response = Json.createObjectBuilder()
                .add("status", "ok");
        return Response.ok().entity(response.build()).build();
    }

    /**
     * Clears a document's explicit cover file, restoring the derived (first-file-by-order) cover.
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

        AclDao aclDao = new AclDao();
        if (!aclDao.checkPermission(documentId, PermType.WRITE, getTargetIdList(null))) {
            throw new ForbiddenClientException();
        }

        // Re-derive the served pointer synchronously in this transaction (under the row lock) so an
        // immediate read-back shows the restored first-file cover; the event refreshes index/contributors.
        DocumentDao documentDao = new DocumentDao();
        documentDao.updateCoverFileId(documentId, null);
        documentDao.reconcileServingCover(documentId);

        DocumentUpdatedAsyncEvent event = new DocumentUpdatedAsyncEvent();
        event.setUserId(principal.getId());
        event.setDocumentId(documentId);
        ThreadLocalContext.get().addAsyncEvent(event);

        JsonObjectBuilder response = Json.createObjectBuilder()
                .add("status", "ok");
        return Response.ok().entity(response.build()).build();
    }

    /**
     * Lists documents in the trash.
     *
     * @api {get} /document/trash List trashed documents
     * @apiName GetDocumentTrash
     * @apiGroup Document
     * @apiParam {Number} [limit] Total number of documents to return
     * @apiParam {Number} [offset] Start offset
     * @apiParam {Boolean} [asc] If true, order by delete date ascending (default: descending, most-recently-trashed first)
     * @apiSuccess {Object[]} documents List of trashed documents
     * @apiSuccess {Number} total Total number of trashed documents
     * @apiPermission user
     *
     * @return Response
     */
    @GET
    @Path("trash")
    public Response listTrash(
            @QueryParam("limit") Integer limit,
            @QueryParam("offset") Integer offset,
            @QueryParam("asc") Boolean asc) {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }

        // Scope to the caller's own trashed documents. Selecting by ACL is wrong here:
        // trashing soft-deletes a document's ACLs, and the search's ACL join requires a
        // live READ ACL, so an ACL-filtered trash list is always empty for a non-admin
        // owner. Ownership scoping mirrors emptyTrash/restore/permanentDelete, which are
        // all keyed on the owner (== caller).
        DocumentDao documentDao = new DocumentDao();
        List<Document> deletedList = documentDao.findDeletedByUserId(principal.getId());

        // Order by delete date; most-recently-trashed first unless ascending is requested.
        Comparator<Document> byDeleteDate = Comparator.comparing(Document::getDeleteDate);
        deletedList.sort(Boolean.TRUE.equals(asc) ? byDeleteDate : byDeleteDate.reversed());

        int total = deletedList.size();
        int fromIndex = Math.min(offset == null ? 0 : Math.max(0, offset), total);
        // Use long arithmetic for the upper bound so a large limit cannot overflow int and yield a negative toIndex.
        int toIndex = limit == null ? total : (int) Math.min((long) fromIndex + Math.max(0, limit), (long) total);

        JsonArrayBuilder documents = Json.createArrayBuilder();
        for (Document document : deletedList.subList(fromIndex, toIndex)) {
            documents.add(Json.createObjectBuilder()
                    .add("id", document.getId())
                    .add("title", document.getTitle())
                    .add("description", JsonUtil.nullable(document.getDescription()))
                    .add("language", document.getLanguage())
                    .add("create_date", document.getCreateDate().getTime())
                    .add("delete_date", document.getDeleteDate().getTime()));
        }

        JsonObjectBuilder response = Json.createObjectBuilder()
                .add("documents", documents)
                .add("total", total);
        return Response.ok().entity(response.build()).build();
    }

    /**
     * Restores a document from the trash.
     *
     * @api {post} /document/:id/restore Restore a document from trash
     * @apiName RestoreDocument
     * @apiGroup Document
     * @apiParam {String} id ID
     * @apiSuccess {String} status Status OK
     * @apiError (client) ForbiddenError Access denied
     * @apiError (client) NotFound Document not found in trash
     * @apiPermission user
     *
     * @param id Document ID
     * @return Response
     */
    @POST
    @Path("{id: [a-z0-9\\-]+}/restore")
    public Response restore(
            @PathParam("id") String id) {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }

        DocumentResourceHelper.restoreOwnedDocument(principal.getId(), id);

        DocumentRestoredAsyncEvent restoredEvent = new DocumentRestoredAsyncEvent();
        restoredEvent.setUserId(principal.getId());
        restoredEvent.setDocumentId(id);
        ThreadLocalContext.get().addAsyncEvent(restoredEvent);

        JsonObjectBuilder response = Json.createObjectBuilder()
                .add("status", "ok");
        return Response.ok().entity(response.build()).build();
    }

    /**
     * Permanently deletes a document from the trash.
     *
     * @api {delete} /document/:id/permanent Permanently delete a document
     * @apiName PermanentDeleteDocument
     * @apiGroup Document
     * @apiParam {String} id ID
     * @apiSuccess {String} status Status OK
     * @apiError (client) ForbiddenError Access denied
     * @apiError (client) NotFound Document not found in trash
     * @apiPermission user
     *
     * @param id Document ID
     * @return Response
     */
    @DELETE
    @Path("{id: [a-z0-9\\-]+}/permanent")
    public Response permanentDelete(
            @PathParam("id") String id) {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }

        DocumentDao documentDao = new DocumentDao();
        FileDao fileDao = new FileDao();
        // Owner-scoped fetch: another user's trashed document resolves to null (404),
        // making a cross-user permanent delete impossible at the DAO level.
        Document document = documentDao.getDeletedById(id, principal.getId());
        if (document == null) {
            throw new NotFoundException();
        }

        DocumentResourceHelper.fireFileAndDocumentDeletedEvents(documentDao, fileDao, principal.getId(), id);
        documentDao.permanentDelete(id);

        JsonObjectBuilder response = Json.createObjectBuilder()
                .add("status", "ok");
        return Response.ok().entity(response.build()).build();
    }

    /**
     * Empties the entire trash (permanently deletes all trashed documents).
     *
     * @api {delete} /document/trash Empty trash
     * @apiName EmptyTrash
     * @apiGroup Document
     * @apiSuccess {Number} deleted_count Number of permanently deleted documents
     * @apiPermission user
     *
     * @return Response
     */
    @DELETE
    @Path("trash")
    public Response emptyTrash() {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }

        DocumentDao documentDao = new DocumentDao();
        FileDao fileDao = new FileDao();

        // Scope to the caller's own trashed documents. Selecting by ACL is wrong here:
        // trashing soft-deletes a document's ACLs, so an ACL-filtered search returns
        // nothing for a non-admin owner and every owner's trash for an admin (who skips
        // the ACL check). Ownership scoping mirrors the single-doc permanentDelete above
        // and keeps deletion/quota events attributed to the real owner (the caller).
        int count = 0;
        for (Document document : documentDao.findDeletedByUserId(principal.getId())) {
            DocumentResourceHelper.fireFileAndDocumentDeletedEvents(documentDao, fileDao, principal.getId(), document.getId());
            documentDao.permanentDelete(document.getId());
            count++;
        }

        JsonObjectBuilder response = Json.createObjectBuilder()
                .add("deleted_count", count);
        return Response.ok().entity(response.build()).build();
    }

}
