package com.sismics.docs.rest.resource;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.text.MessageFormat;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.glassfish.jersey.media.multipart.FormDataBodyPart;
import org.glassfish.jersey.media.multipart.FormDataParam;

import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.io.ByteStreams;
import com.sismics.docs.core.constant.PermType;
import com.sismics.docs.core.dao.AclDao;
import com.sismics.docs.core.dao.DocumentDao;
import com.sismics.docs.core.dao.FileDao;
import com.sismics.docs.core.dao.UserDao;
import com.sismics.docs.core.dao.dto.DocumentDto;
import com.sismics.docs.core.event.DocumentUpdatedAsyncEvent;
import com.sismics.docs.core.event.FileDeletedAsyncEvent;
import com.sismics.docs.core.event.FileUpdatedAsyncEvent;
import com.sismics.docs.core.model.context.AppContext;
import com.sismics.docs.core.model.jpa.File;
import com.sismics.docs.core.model.jpa.User;
import com.sismics.docs.core.util.DirectoryUtil;
import com.sismics.docs.core.util.EncryptionUtil;
import com.sismics.docs.core.util.FileUtil;
import com.sismics.docs.core.util.PreviousVersionMismatchException;
import com.sismics.docs.core.util.RasterGenerationUtil;
import com.sismics.docs.core.util.VersionConcurrencyException;
import com.sismics.docs.core.util.pdf.PdfPageOperationBusyException;
import com.sismics.docs.core.util.pdf.PdfPageOperationException;
import com.sismics.docs.core.util.pdf.PdfPageOperationService;
import com.sismics.rest.exception.ClientException;
import com.sismics.rest.exception.ConflictException;
import com.sismics.rest.exception.ForbiddenClientException;
import com.sismics.rest.exception.ServerException;
import com.sismics.rest.exception.TooManyRequestsException;
import com.sismics.rest.util.RestUtil;
import com.sismics.docs.core.util.ExportUtil;
import com.sismics.rest.util.ValidationUtil;
import com.sismics.util.EnvironmentUtil;
import com.sismics.util.HttpUtil;
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
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import jakarta.ws.rs.core.StreamingOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * File REST resources.
 *
 * @author bgamard
 */
@Path("/file")
public class FileResource extends BaseResource {
    /**
     * Logger.
     */
    private static final Logger log = LoggerFactory.getLogger(FileResource.class);

    /**
     * Add a file (with or without a document).
     *
     * @api {put} /file Add a file
     * @apiDescription A file can be added without associated document, and will go in a temporary storage waiting for one.
     * This resource accepts only multipart/form-data.
     * @apiName PutFile
     * @apiGroup File
     * @apiParam {String} [id] Document ID
     * @apiParam {String} [previousFileId] ID of the file to replace by this new version
     * @apiParam {String} file File data
     * @apiSuccess {String} status Status OK
     * @apiSuccess {String} id File ID
     * @apiSuccess {Number} size File size (in bytes)
     * @apiError (client) ForbiddenError Access denied
     * @apiError (client) ValidationError Validation error
     * @apiError (client) NotFound Document not found
     * @apiError (server) StreamError Error reading the input file
     * @apiError (server) ErrorGuessMime Error guessing mime type
     * @apiError (client) QuotaReached Quota limit reached
     * @apiError (server) FileError Error adding a file
     * @apiPermission user
     * @apiVersion 1.5.0
     *
     * @param documentId Document ID
     * @param fileBodyPart File to add
     * @return Response
     */
    @PUT
    @Consumes("multipart/form-data")
    public Response add(
            @FormDataParam("id") String documentId,
            @FormDataParam("previousFileId") String previousFileId,
            @FormDataParam("file") FormDataBodyPart fileBodyPart) {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }
        
        // Validate input data
        ValidationUtil.validateRequired(fileBodyPart, "file");

        // Get the document
        DocumentDto documentDto = null;
        if (Strings.isNullOrEmpty(documentId)) {
            documentId = null;
        } else {
            DocumentDao documentDao = new DocumentDao();
            documentDto = documentDao.getDocument(documentId, PermType.WRITE, getTargetIdList(null));
            if (documentDto == null) {
                throw new NotFoundException();
            }
        }
        
        // Keep unencrypted data temporary on disk
        String name = fileBodyPart.getContentDisposition() != null ?
                URLDecoder.decode(fileBodyPart.getContentDisposition().getFileName(), StandardCharsets.UTF_8) : null;
        long maxUploadSize = resolveMaxUploadSize();

        // The plaintext temp is created and populated here and its ownership is handed to
        // the async event queued by createFile. The cleanup scope covers creation, the
        // multipart stream copy, AND createFile: the flag starts false at creation, so ANY
        // failure before hand-off (stream IOException, oversize, quota, encryption, ...)
        // deletes the temp. It is deleted by FileProcessingAsyncListener once handed off.
        java.nio.file.Path unencryptedFile = null;
        boolean ownershipTransferred = false;
        try {
            long fileSize;
            try {
                unencryptedFile = AppContext.getInstance().getFileService().createTemporaryFile(name);
                try (InputStream in = fileBodyPart.getValueAs(InputStream.class);
                     java.io.OutputStream out = Files.newOutputStream(unencryptedFile)) {
                    long totalRead = 0;
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        totalRead += n;
                        if (totalRead > maxUploadSize) {
                            throw new ClientException("PayloadTooLarge",
                                    "File exceeds maximum upload size of " + (maxUploadSize / (1024 * 1024)) + " MB");
                        }
                        out.write(buf, 0, n);
                    }
                }
                fileSize = Files.size(unencryptedFile);
            } catch (IOException e) {
                throw new ServerException("StreamError", "Error reading the input file", e);
            }

            String fileId = FileUtil.createFile(name, previousFileId, unencryptedFile, fileSize, documentDto == null ?
                    null : documentDto.getLanguage(), principal.getId(), documentId);
            ownershipTransferred = true;

            // Always return OK
            JsonObjectBuilder response = Json.createObjectBuilder()
                    .add("status", "ok")
                    .add("id", fileId)
                    .add("size", fileSize);
            return Response.ok().entity(response.build()).build();
        } catch (ClientException | ServerException e) {
            throw e;
        } catch (PreviousVersionMismatchException e) {
            // Unknown / cross-document previous version: a client error (400), never a 500.
            throw new ClientException("PreviousVersionMismatch", e.getMessage());
        } catch (VersionConcurrencyException e) {
            // Lost the single-writer race on the version chain: a retryable conflict (409).
            throw new ConflictException("VersionConflict", e.getMessage());
        } catch (IOException e) {
            throw new ClientException(e.getMessage(), e.getMessage(), e);
        } catch (Exception e) {
            throw new ServerException("FileError", "Error adding a file", e);
        } finally {
            if (!ownershipTransferred && unencryptedFile != null) {
                try {
                    Files.deleteIfExists(unencryptedFile);
                } catch (IOException e) {
                    log.warn("Unable to delete orphaned temporary file: " + unencryptedFile, e);
                }
            }
        }
    }
    
    /**
     * Attach a file to a document.
     *
     * @api {post} /file/:fileId/attach Attach a file to a document
     * @apiName PostFileAttach
     * @apiGroup File
     * @apiParam {String} fileId File ID
     * @apiParam {String} id Document ID
     * @apiSuccess {String} status Status OK
     * @apiError (client) ForbiddenError Access denied
     * @apiError (client) ValidationError Validation error
     * @apiError (client) IllegalFile File not orphan
     * @apiError (server) AttachError Error attaching file to document
     * @apiPermission user
     * @apiVersion 1.5.0
     *
     * @param id File ID
     * @return Response
     */
    @POST
    @Path("{id: [a-z0-9\\-]+}/attach")
    public Response attach(
            @PathParam("id") String id,
            @FormParam("id") String documentId) {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }

        // Validate input data
        ValidationUtil.validateRequired(documentId, "documentId");
        
        // Get the current user
        UserDao userDao = new UserDao();
        User user = userDao.getById(principal.getId());
        
        // Get the document and the file
        DocumentDao documentDao = new DocumentDao();
        FileDao fileDao = new FileDao();
        File file = fileDao.getFile(id, principal.getId());
        DocumentDto documentDto = documentDao.getDocument(documentId, PermType.WRITE, getTargetIdList(null));
        if (file == null || documentDto == null) {
            throw new NotFoundException();
        }
        
        // Check that the file is orphan
        if (file.getDocumentId() != null) {
            throw new ClientException("IllegalFile", MessageFormat.format("File not orphan: {0}", id));
        }
        
        // Update the file
        file.setDocumentId(documentId);
        file.setOrder(fileDao.getByDocumentId(principal.getId(), documentId).size());
        fileDao.update(file);
        
        // Raise a new file updated event and document updated event (it wasn't sent during file creation)
        java.nio.file.Path storedFile = DirectoryUtil.getStorageDirectory().resolve(id);
        java.nio.file.Path unencryptedFile = null;
        boolean ownershipTransferred = false;
        try {
            unencryptedFile = EncryptionUtil.decryptFile(storedFile, user.getPrivateKey());
            FileUtil.markProcessingWithRollbackCleanup(id, unencryptedFile);
            FileUpdatedAsyncEvent fileUpdatedAsyncEvent = new FileUpdatedAsyncEvent();
            fileUpdatedAsyncEvent.setUserId(principal.getId());
            fileUpdatedAsyncEvent.setLanguage(documentDto.getLanguage());
            fileUpdatedAsyncEvent.setFileId(file.getId());
            fileUpdatedAsyncEvent.setUnencryptedFile(unencryptedFile);
            ThreadLocalContext.get().addAsyncEvent(fileUpdatedAsyncEvent);
            ownershipTransferred = true;

            DocumentUpdatedAsyncEvent documentUpdatedAsyncEvent = new DocumentUpdatedAsyncEvent();
            documentUpdatedAsyncEvent.setUserId(principal.getId());
            documentUpdatedAsyncEvent.setDocumentId(documentId);
            ThreadLocalContext.get().addAsyncEvent(documentUpdatedAsyncEvent);
        } catch (Exception e) {
            throw new ServerException("AttachError", "Error attaching file to document", e);
        } finally {
            deleteOrphanTemp(unencryptedFile, storedFile, ownershipTransferred);
        }

        // Always return OK
        JsonObjectBuilder response = Json.createObjectBuilder()
                .add("status", "ok");
        return Response.ok().entity(response.build()).build();
    }

    /**
     * Update a file.
     *
     * @api {post} /file/:id Update a file
     * @apiName PostFile
     * @apiGroup File
     * @apiParam {String} id File ID
     * @apiParam {String} name Name
     * @apiSuccess {String} status Status OK
     * @apiError (client) ForbiddenError Access denied
     * @apiError (client) ValidationError Validation error
     * @apiPermission user
     * @apiVersion 1.6.0
     *
     * @param id File ID
     * @return Response
     */
    @POST
    @Path("{id: [a-z0-9\\-]+}")
    public Response update(@PathParam("id") String id,
                           @FormParam("name") String name) {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }

        // Get the file
        File file = findFile(id, null, PermType.WRITE);

        // Validate input data
        name = ValidationUtil.validateLength(name, "name", 1, 200, false);
        // Reject path separators / control chars in the new name (shared validator) so a rename can
        // never store a name that would later escape a ZIP/export extraction directory.
        ValidationUtil.validateFileName(name, "name");

        // Update the file
        FileDao fileDao = new FileDao();
        file.setName(name);
        fileDao.update(file);

        // Always return OK
        JsonObjectBuilder response = Json.createObjectBuilder()
                .add("status", "ok");
        return Response.ok().entity(response.build()).build();
    }

    /**
     * Rotate a file (persisted, non-destructive).
     *
     * @api {post} /file/:id/rotation Rotate a file
     * @apiDescription Store an absolute clockwise display rotation ({0,90,180,270}) for the file and
     * regenerate its web/thumbnail rasters from the ORIGINAL upright bytes. The original encrypted
     * upload is never re-encoded and OCR is not re-run, so the operation is reversible (rotation=0
     * regenerates upright) and idempotent (re-applying the same value never compounds).
     * @apiName PostFileRotation
     * @apiGroup File
     * @apiParam {String} id File ID
     * @apiParam {Number="0","90","180","270"} rotation Absolute clockwise rotation in degrees
     * @apiSuccess {String} status Status OK
     * @apiSuccess {Number} rotation The normalized stored rotation
     * @apiError (client) ForbiddenError Access denied
     * @apiError (client) ValidationError Rotation must be a multiple of 90
     * @apiError (server) ProcessingError The file is still processing or the raster regeneration failed
     * @apiPermission user
     * @apiVersion 1.12.0
     *
     * @param id File ID
     * @param rotationStr Absolute clockwise rotation in degrees
     * @return Response
     */
    @POST
    @Path("{id: [a-z0-9\\-]+}/rotation")
    public Response rotation(@PathParam("id") String id,
                             @FormParam("rotation") String rotationStr) {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }

        // WRITE on the parent document — a READ-only or share user must be rejected.
        File file = findFile(id, null, PermType.WRITE);

        // Validate: a multiple of 90, normalized to {0,90,180,270}.
        ValidationUtil.validateRequired(rotationStr, "rotation");
        int rotation;
        try {
            rotation = Integer.parseInt(rotationStr.trim());
        } catch (NumberFormatException e) {
            throw new ClientException("ValidationError", "rotation must be an integer multiple of 90");
        }
        if (rotation % 90 != 0) {
            throw new ClientException("ValidationError", "rotation must be a multiple of 90");
        }
        int normalized = ((rotation % 360) + 360) % 360;

        // The rasters must already exist before we can regenerate from the original — if the file's
        // initial async processing has not produced them yet, return a retriable error rather than
        // racing the processor.
        if (FileUtil.isProcessingFile(id)) {
            throw new ServerException("ProcessingError", "The file is still processing, retry shortly");
        }

        // Regenerate from the ORIGINAL bytes with the OWNER's key, baking in the new rotation, then
        // persist FIL_ROTATION_N as the LAST step inside the shared per-file lock (DB = source of
        // truth). On any failure before the DB commit the prior rasters + DB row are left intact.
        // Accepted residual risk: a crash in the swap→commit window can leave a cosmetically-stale
        // thumbnail (never data loss — the original bytes and OCR are untouched), self-healed by
        // re-rotating. See RasterGenerationUtil for the single-instance atomicity model.
        UserDao userDao = new UserDao();
        User owner = userDao.getById(file.getUserId());
        java.nio.file.Path storedFile = DirectoryUtil.getStorageDirectory().resolve(id);
        java.nio.file.Path unencryptedFile = null;
        try {
            unencryptedFile = EncryptionUtil.decryptFile(storedFile, owner.getPrivateKey());
            final java.nio.file.Path original = unencryptedFile;
            // The rotate endpoint IS the writer of intent: it bakes and persists the requested value.
            // The whole read/generate/swap/persist sequence runs under the shared per-file lock, and
            // the DB update is the LAST step inside it. The rotation is COMMITTED inside the lock (via
            // RasterGenerationUtil.commitRotation → TransactionUtil.commit, which commits the request
            // transaction then re-begins a fresh one) so a subsequent lock-holder — a reprocess reading
            // rotation fresh inside its own lock — always observes the committed value. Without this
            // in-lock commit the request transaction would only commit later in RequestContextFilter,
            // OUTSIDE the lock, and a reprocess that acquired the lock in that window would read the
            // stale committed rotation and install upright rasters over a DB that already says rotated.
            // The early commit is safe because NO fallible transactional work follows the rotation
            // commit in this endpoint (only the JSON response is built), so nothing after it can fail
            // and require rolling the rotation back.
            RasterGenerationUtil.regenerateRasters(id, original, file.getMimeType(), () -> normalized,
                    owner.getPrivateKey(), () -> RasterGenerationUtil.commitRotation(file, normalized));
        } catch (Exception e) {
            throw new ServerException("ProcessingError", "Error rotating this file", e);
        } finally {
            // Delete the decrypted plaintext temp on both success and failure. decryptFile returns
            // the stored file unchanged when the private key is null (tests) — never delete that.
            if (unencryptedFile != null && !unencryptedFile.equals(storedFile)) {
                try {
                    Files.deleteIfExists(unencryptedFile);
                } catch (IOException e) {
                    log.warn("Unable to delete temporary file: " + unencryptedFile, e);
                }
            }
        }

        JsonObjectBuilder response = Json.createObjectBuilder()
                .add("status", "ok")
                .add("rotation", normalized);
        return Response.ok().entity(response.build()).build();
    }

    /**
     * Apply a v1 page-operation manifest (reorder / delete / per-page rotate) to a PDF file, saving the
     * result as a new version. The heavy lifting (concurrency ceilings, decrypt, PDFBox rewrite,
     * validation, versioned create) lives in {@link PdfPageOperationService}; this method only
     * authenticates, enforces WRITE on the parent document, and maps the typed outcomes to HTTP.
     *
     * @param id File ID (the expected latest base — the compare-and-swap rejects a stale base with 409)
     * @param manifest The v1 page-operation manifest, as a JSON string
     * @return Response with the new file version ID
     */
    @POST
    @Path("{id: [a-z0-9\\-]+}/pages")
    public Response pages(@PathParam("id") String id,
                          @FormParam("manifest") String manifest) {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }

        // WRITE on the parent document — a READ-only or share user must be rejected.
        File file = findFile(id, null, PermType.WRITE);
        ValidationUtil.validateRequired(manifest, "manifest");

        try {
            String newFileId = PdfPageOperationService.applyPageOperations(file, manifest, principal.getId());
            JsonObjectBuilder pagesResponse = Json.createObjectBuilder()
                    .add("status", "ok")
                    .add("id", newFileId);
            return Response.ok().entity(pagesResponse.build()).build();
        } catch (ClientException | ServerException e) {
            throw e;
        } catch (PdfPageOperationBusyException e) {
            // A saturated concurrency ceiling is transient — the request was refused, not queued. Surface a
            // real 429 (retry shortly), distinct from the 400s below. Must precede the generic catch since
            // it is a PdfPageOperationException subtype.
            throw new TooManyRequestsException(e.getType(), e.getMessage());
        } catch (PdfPageOperationException e) {
            // A client-attributable page-operation failure (bad manifest, non-PDF, over-ceiling, signed,
            // encrypted, unprocessable PDF): a typed 400, never a 500 or a hang.
            throw new ClientException(e.getType(), e.getMessage());
        } catch (PreviousVersionMismatchException e) {
            // Unknown / cross-document previous version: a client error (400), never a 500.
            throw new ClientException("PreviousVersionMismatch", e.getMessage());
        } catch (VersionConcurrencyException e) {
            // Lost the single-writer race on the version chain: a retryable conflict (409).
            throw new ConflictException("VersionConflict", e.getMessage());
        } catch (Exception e) {
            throw new ServerException("FileError", "Error applying page operations", e);
        }
    }

    /**
     * Process a file manually.
     *
     * @api {post} /file/:id/process Process a file manually
     * @apiName PostFileProcess
     * @apiGroup File
     * @apiParam {String} id File ID
     * @apiSuccess {String} status Status OK
     * @apiError (client) ForbiddenError Access denied
     * @apiError (client) ValidationError Validation error
     * @apiError (server) ProcessingError Processing error
     * @apiPermission user
     * @apiVersion 1.6.0
     *
     * @param id File ID
     * @return Response
     */
    @POST
    @Path("{id: [a-z0-9\\-]+}/process")
    public Response process(@PathParam("id") String id) {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }

        // Get the document and the file
        DocumentDao documentDao = new DocumentDao();
        FileDao fileDao = new FileDao();
        File file = fileDao.getFile(id);
        if (file == null || file.getDocumentId() == null) {
            throw new NotFoundException();
        }
        DocumentDto documentDto = documentDao.getDocument(file.getDocumentId(), PermType.WRITE, getTargetIdList(null));
        if (documentDto == null) {
            throw new NotFoundException();
        }

        // Get the creating user
        UserDao userDao = new UserDao();
        User user = userDao.getById(file.getUserId());

        // Start the processing asynchronously
        java.nio.file.Path storedFile = DirectoryUtil.getStorageDirectory().resolve(id);
        java.nio.file.Path unencryptedFile = null;
        boolean ownershipTransferred = false;
        try {
            unencryptedFile = EncryptionUtil.decryptFile(storedFile, user.getPrivateKey());
            FileUtil.markProcessingWithRollbackCleanup(id, unencryptedFile);
            FileUpdatedAsyncEvent event = new FileUpdatedAsyncEvent();
            event.setUserId(principal.getId());
            event.setLanguage(documentDto.getLanguage());
            event.setFileId(file.getId());
            event.setUnencryptedFile(unencryptedFile);
            ThreadLocalContext.get().addAsyncEvent(event);
            ownershipTransferred = true;
        } catch (Exception e) {
            throw new ServerException("ProcessingError", "Error processing this file", e);
        } finally {
            deleteOrphanTemp(unencryptedFile, storedFile, ownershipTransferred);
        }

        // Always return OK
        JsonObjectBuilder response = Json.createObjectBuilder()
                .add("status", "ok");
        return Response.ok().entity(response.build()).build();
    }

    /**
     * Resolve the maximum upload size in bytes. Authoritative source is the
     * DOCS_MAX_UPLOAD_SIZE environment variable (default 500MB). The
     * {@code docs.max_upload_size} system property overrides it when set, providing a
     * deterministic test seam for the oversize enforcement path.
     *
     * @return Max upload size in bytes
     */
    static long resolveMaxUploadSize() {
        return EnvironmentUtil.getLongConfig("docs.max_upload_size", "DOCS_MAX_UPLOAD_SIZE", 500L * 1024 * 1024);
    }

    /**
     * Delete a decrypted plaintext temp file if ownership was not handed to a queued
     * async event. Never deletes the stored file itself (decryptFile returns the stored
     * file unchanged when the private key is null, e.g. in tests).
     *
     * @param unencryptedFile Decrypted temp file (may be null)
     * @param storedFile Encrypted stored file (must never be deleted here)
     * @param ownershipTransferred True if a queued event now owns the temp
     */
    private void deleteOrphanTemp(java.nio.file.Path unencryptedFile, java.nio.file.Path storedFile,
                                  boolean ownershipTransferred) {
        if (ownershipTransferred || unencryptedFile == null || unencryptedFile.equals(storedFile)) {
            return;
        }
        try {
            Files.deleteIfExists(unencryptedFile);
        } catch (IOException e) {
            log.warn("Unable to delete orphaned temporary file: " + unencryptedFile, e);
        }
    }

    /**
     * Reorder files.
     *
     * @api {post} /file/:reorder Reorder files
     * @apiName PostFileReorder
     * @apiGroup File
     * @apiParam {String} id Document ID
     * @apiParam {String[]} order List of files ID
     * @apiSuccess {String} status Status OK
     * @apiError (client) ForbiddenError Access denied
     * @apiError (client) ValidationError Validation error
     * @apiError (client) NotFound Document not found
     * @apiPermission user
     * @apiVersion 1.5.0
     *
     * @param documentId Document ID
     * @param idList List of files ID in the new order
     * @return Response
     */
    @POST
    @Path("reorder")
    public Response reorder(
            @FormParam("id") String documentId,
            @FormParam("order") List<String> idList) {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }
        
        // Validate input data
        ValidationUtil.validateRequired(documentId, "id");
        ValidationUtil.validateRequired(idList, "order");
        
        // Get the document
        AclDao aclDao = new AclDao();
        if (!aclDao.checkPermission(documentId, PermType.WRITE, getTargetIdList(null))) {
            throw new NotFoundException();
        }
        
        // Reorder files
        FileDao fileDao = new FileDao();
        for (File file : fileDao.getByDocumentId(principal.getId(), documentId)) {
            int order = idList.lastIndexOf(file.getId());
            if (order != -1) {
                file.setOrder(order);
            }
        }

        // Raise a document updated event
        DocumentUpdatedAsyncEvent event = new DocumentUpdatedAsyncEvent();
        event.setUserId(principal.getId());
        event.setDocumentId(documentId);
        ThreadLocalContext.get().addAsyncEvent(event);
        
        // Always return OK
        JsonObjectBuilder response = Json.createObjectBuilder()
                .add("status", "ok");
        return Response.ok().entity(response.build()).build();
    }
    
    /**
     * Returns files linked to a document or not linked to any document.
     *
     * @api {get} /file/list Get files
     * @apiName GetFileList
     * @apiGroup File
     * @apiParam {String} [id] Document ID
     * @apiParam {String} [share] Share ID
     * @apiSuccess {Object[]} files List of files
     * @apiSuccess {String} files.id ID
     * @apiSuccess {String} files.processing True if the file is currently processing
     * @apiSuccess {String} files.name File name
     * @apiSuccess {String} files.version Zero-based version number
     * @apiSuccess {String} files.mimetype MIME type
     * @apiSuccess {String} files.document_id Document ID
     * @apiSuccess {String} files.create_date Create date (timestamp)
     * @apiSuccess {Number} files.rotation Baked clockwise rotation of the file's raster
     * @apiSuccess {String} files.size File size (in bytes)
     * @apiError (client) ForbiddenError Access denied
     * @apiError (client) NotFound Document not found
     * @apiError (server) FileError Unable to get the size of a file
     * @apiPermission none
     * @apiVersion 1.5.0
     *
     * @param documentId Document ID
     * @param shareId Sharing ID
     * @return Response
     */
    @GET
    @Path("list")
    public Response list(
            @QueryParam("id") String documentId,
            @QueryParam("share") String shareId) {
        boolean authenticated = authenticate();
        
        // Check document visibility
        if (documentId != null) {
            AclDao aclDao = new AclDao();
            if (!aclDao.checkPermission(documentId, PermType.READ, getTargetIdList(shareId))) {
                throw new NotFoundException();
            }
        } else if (!authenticated) {
            throw new ForbiddenClientException();
        }

        FileDao fileDao = new FileDao();
        JsonArrayBuilder files = Json.createArrayBuilder();
        for (File fileDb : fileDao.getByDocumentId(principal.getId(), documentId)) {
            files.add(RestUtil.fileToJsonObjectBuilder(fileDb));
        }
        JsonObjectBuilder response = Json.createObjectBuilder()
                .add("files", files);

        return Response.ok().entity(response.build()).build();
    }

    /**
     * List all versions of a file.
     *
     * @api {get} /file/:id/versions Get versions of a file
     * @apiName GetFileVersions
     * @apiGroup File
     * @apiParam {String} id File ID
     * @apiSuccess {Object[]} files List of files
     * @apiSuccess {String} files.id ID
     * @apiSuccess {String} files.name File name
     * @apiSuccess {String} files.version Zero-based version number
     * @apiSuccess {String} files.mimetype MIME type
     * @apiSuccess {String} files.create_date Create date (timestamp)
     * @apiError (client) ForbiddenError Access denied
     * @apiError (client) NotFound File not found
     * @apiPermission user
     * @apiVersion 1.5.0
     *
     * @param id File ID
     * @return Response
     */
    @GET
    @Path("{id: [a-z0-9\\-]+}/versions")
    public Response versions(@PathParam("id") String id) {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }

        // Get versions
        File file = findFile(id, null, PermType.READ);
        FileDao fileDao = new FileDao();
        List<File> fileList = Lists.newArrayList(file);
        if (file.getVersionId() != null) {
            fileList = fileDao.getByVersionId(file.getVersionId());
        }

        JsonArrayBuilder files = Json.createArrayBuilder();
        for (File fileDb : fileList) {
            files.add(Json.createObjectBuilder()
                    .add("id", fileDb.getId())
                    .add("name", JsonUtil.nullable(fileDb.getName()))
                    .add("version", fileDb.getVersion())
                    .add("mimetype", fileDb.getMimeType())
                    .add("create_date", fileDb.getCreateDate().getTime()));
        }

        JsonObjectBuilder response = Json.createObjectBuilder()
                .add("files", files);
        return Response.ok().entity(response.build()).build();
    }
    
    /**
     * Deletes a file.
     *
     * @api {delete} /file/:id Delete a file
     * @apiName DeleteFile
     * @apiGroup File
     * @apiParam {String} id File ID
     * @apiSuccess {String} status Status OK
     * @apiError (client) ForbiddenError Access denied
     * @apiError (client) NotFound File or document not found
     * @apiPermission user
     * @apiVersion 1.5.0
     *
     * @param id File ID
     * @return Response
     */
    @DELETE
    @Path("{id: [a-z0-9\\-]+}")
    public Response delete(
            @PathParam("id") String id) {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }

        // Get the file
        File file = findFile(id, null, PermType.WRITE);

        // Reclaim the owner's storage quota synchronously, atomically with the delete, and EXACTLY once
        // even under two concurrent deletes of the same file. reclaimSingleFileOnDelete takes the GLOBAL
        // sentinel lock, re-reads the file ACTIVE under it, and reclaims only if THIS delete is still the
        // one seeing it active (a concurrent delete that already soft-deleted it reclaims nothing). It runs
        // BEFORE the fileDao.delete so the quota locks (GLOBAL then the owner's row) are taken before the
        // T_FILE row is dirtied — the canonical lock order, no deadlock inversion — and while the file
        // still exists on disk (needed to size a legacy UNKNOWN_SIZE row). Doing it in the delete
        // transaction, not the async FileDeletedAsyncEvent listener, also stops a retried event from
        // double-subtracting.
        FileUtil.reclaimSingleFileOnDelete(file.getId());

        // Delete the file. When it is the current latest version of a chain, FileDao.delete promotes the
        // immediately-prior active version to latest under an affected-row compare-and-swap, so deleting the
        // current version never leaves the chain with zero (or two) latest rows — the file stays visible
        // with its history.
        FileDao fileDao = new FileDao();
        fileDao.delete(file.getId(), principal.getId());

        // Raise a new file deleted event (index + storage cleanup only; quota already reclaimed above)
        FileDeletedAsyncEvent fileDeletedAsyncEvent = new FileDeletedAsyncEvent();
        fileDeletedAsyncEvent.setUserId(principal.getId());
        fileDeletedAsyncEvent.setFileId(file.getId());
        fileDeletedAsyncEvent.setFileSize(file.getSize());
        ThreadLocalContext.get().addAsyncEvent(fileDeletedAsyncEvent);
        
        if (file.getDocumentId() != null) {
            // Raise a new document updated
            DocumentUpdatedAsyncEvent documentUpdatedAsyncEvent = new DocumentUpdatedAsyncEvent();
            documentUpdatedAsyncEvent.setUserId(principal.getId());
            documentUpdatedAsyncEvent.setDocumentId(file.getDocumentId());
            ThreadLocalContext.get().addAsyncEvent(documentUpdatedAsyncEvent);
        }
        
        // Always return OK
        JsonObjectBuilder response = Json.createObjectBuilder()
                .add("status", "ok");
        return Response.ok().entity(response.build()).build();
    }
    
    /**
     * Returns a file.
     *
     * @api {get} /file/:id/data Get a file data
     * @apiName GetFile
     * @apiGroup File
     * @apiParam {String} id File ID
     * @apiParam {String} share Share ID
     * @apiParam {String="web","thumb","content"} [size] Size variation
     * @apiSuccess {Object} file The file data is the whole response
     * @apiError (client) SizeError Size must be web or thumb
     * @apiError (client) ForbiddenError Access denied or document not visible
     * @apiError (client) NotFound File not found
     * @apiError (server) ServiceUnavailable Error reading the file
     * @apiPermission none
     * @apiVersion 1.5.0
     *
     * @param fileId File ID
     * @return Response
     */
    @GET
    @Path("{id: [a-z0-9\\-]+}/data")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    public Response data(
            @PathParam("id") final String fileId,
            @QueryParam("share") String shareId,
            @QueryParam("size") String size) {
        authenticate();
        
        if (size != null && !Lists.newArrayList("web", "thumb", "content").contains(size)) {
            throw new ClientException("SizeError", "Size must be web, thumb or content");
        }

        // Get the file
        File file = findFile(fileId, shareId, PermType.READ);

        // Get the stored file
        UserDao userDao = new UserDao();
        java.nio.file.Path storedFile;
        String mimeType;
        boolean decrypt;
        if (size != null) {
            if (size.equals("content")) {
                return Response.ok(Strings.nullToEmpty(file.getContent()))
                        .header(HttpHeaders.CONTENT_TYPE, "text/plain; charset=utf-8")
                        .build();
            }

            storedFile = DirectoryUtil.getStorageDirectory().resolve(fileId + "_" + size);
            mimeType = MimeType.IMAGE_JPEG; // Thumbnails are JPEG
            decrypt = true; // Thumbnails are encrypted
            if (!Files.exists(storedFile)) {
                try {
                    storedFile = Paths.get(getClass().getResource("/image/file-" + size + ".png").toURI());
                } catch (URISyntaxException e) {
                    // Ignore
                }
                mimeType = MimeType.IMAGE_PNG;
                decrypt = false;
            }
        } else {
            storedFile = DirectoryUtil.getStorageDirectory().resolve(fileId);
            mimeType = file.getMimeType();
            decrypt = true; // Original files are encrypted
        }
        
        // Stream the output and decrypt it if necessary
        StreamingOutput stream;
        
        // A file is always encrypted by the creator of it
        User user = userDao.getById(file.getUserId());
        
        // Open the source stream INSIDE the StreamingOutput lambda: opening it eagerly here would leak
        // the file descriptor whenever the response entity is never consumed (client disconnect, an
        // error building the response). Nested try-with-resources opens the raw stream first and the
        // decrypted wrapper second, so Java closes the raw stream if decrypt initialization throws (a
        // double-close of the raw stream, when decrypt is disabled and both aliases refer to it, is
        // tolerated).
        final java.nio.file.Path sourceFile = storedFile;
        final boolean decryptSource = decrypt;
        stream = outputStream -> {
            try (InputStream rawInputStream = Files.newInputStream(sourceFile);
                 InputStream responseInputStream = decryptSource
                         ? EncryptionUtil.decryptInputStream(rawInputStream, user.getPrivateKey())
                         : rawInputStream) {
                ByteStreams.copy(responseInputStream, outputStream);
            } catch (IOException e) {
                throw e;
            } catch (Exception e) {
                throw new WebApplicationException(Response.status(Status.SERVICE_UNAVAILABLE).build());
            } finally {
                try {
                    outputStream.close();
                } catch (IOException e) {
                    // Ignore
                }
            }
        };

        // User-uploaded original content (size == null) carries a user-controlled MIME type and can be an
        // active document (HTML, SVG). Force it to download as an attachment and lock it down with a
        // restrictive CSP so it cannot execute same-origin (stored XSS). App-generated thumbnails and the
        // "web"/"thumb" size variations are trusted images and keep their inline disposition.
        boolean isOriginal = size == null;
        String disposition = isOriginal ? "attachment" : "inline";
        Response.ResponseBuilder builder = Response.ok(stream)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition + "; filename*=utf-8''" + filenameEncode(  file.getFullName("data") ))
                .header(HttpHeaders.CONTENT_TYPE, mimeType);
        if (isOriginal) {
            builder.header("Content-Security-Policy", "sandbox; default-src 'none'")
                    .header("X-Content-Type-Options", "nosniff");
        }
        if (decrypt) {
            // Cache real files
            builder.header(HttpHeaders.CACHE_CONTROL, "private")
                    .header(HttpHeaders.EXPIRES, HttpUtil.buildExpiresHeader(3_600_000L * 24L * 365L));
        } else {
            // Do not cache the temporary thumbnail
            builder.header(HttpHeaders.CACHE_CONTROL, "no-store, must-revalidate")
                    .header(HttpHeaders.EXPIRES, "0");
        }
        return builder.build();
    }

    private String filenameEncode(String name) {
        try {
            return java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException e) {
            e.printStackTrace();
            return name;
        }
    }

    /**
     * Returns all files from a document, zipped.
     *
     * @api {get} /file/zip Returns all files from a document, zipped.
     * @apiName GetFileZip
     * @apiGroup File
     * @apiParam {String} id Document ID
     * @apiParam {String} share Share ID
     * @apiSuccess {Object} file The ZIP file is the whole response
     * @apiError (client) NotFoundException Document not found
     * @apiError (server) InternalServerError Error creating the ZIP file
     * @apiPermission none
     * @apiVersion 1.5.0
     *
     * @param documentId Document ID
     * @param shareId Share ID
     * @return Response
     */
    @GET
    @Path("zip")
    @Produces({MediaType.APPLICATION_OCTET_STREAM, MediaType.TEXT_PLAIN})
    public Response zip(
            @QueryParam("id") String documentId,
            @QueryParam("share") String shareId) {
        authenticate();
        
        // Get the document
        DocumentDao documentDao = new DocumentDao();
        DocumentDto documentDto = documentDao.getDocument(documentId, PermType.READ, getTargetIdList(shareId));
        if (documentDto == null) {
            throw new NotFoundException();
        }

        // Get files associated with this document
        FileDao fileDao = new FileDao();
        final List<File> fileList = fileDao.getByDocumentId(principal.getId(), documentId);
        String zipFileName = documentDto.getTitle().replaceAll("\\W+", "_");
        return sendZippedFiles(zipFileName, fileList);
    }

    /**
     * Returns a list of files, zipped
     *
     * @api {post} /file/zip Returns a list of files, zipped
     * @apiName GetFilesZip
     * @apiGroup File
     * @apiParam {String[]} files IDs
     * @apiSuccess {Object} file The ZIP file is the whole response
     * @apiError (client) NotFoundException Files not found
     * @apiError (server) InternalServerError Error creating the ZIP file
     * @apiPermission none
     * @apiVersion 1.11.0
     *
     * @param filesIdsList Files IDs
     * @return Response
     */
    @POST
    @Path("zip")
    @Produces({MediaType.APPLICATION_OCTET_STREAM, MediaType.TEXT_PLAIN})
    public Response zip(
            @FormParam("files") List<String> filesIdsList) {
        authenticate();
        List<File> fileList = findFiles(filesIdsList);
        return sendZippedFiles("files", fileList);
    }

    /**
     * Sent the content of a list of files.
     */
    private Response sendZippedFiles(String zipFileName, List<File> fileList) {
        final UserDao userDao = new UserDao();

        // Create the ZIP stream
        StreamingOutput stream = outputStream -> {
            try (ZipOutputStream zipOutputStream = new ZipOutputStream(outputStream)) {
                // Add each file to the ZIP stream
                int index = 0;
                for (File file : fileList) {
                    java.nio.file.Path storedfile = DirectoryUtil.getStorageDirectory().resolve(file.getId());

                    // Files are encrypted by the creator of them
                    User user = userDao.getById(file.getUserId());

                    // Nested try-with-resources: open the raw stream FIRST and the decrypted wrapper
                    // second, so the raw stream is always closed even if decrypt initialization throws.
                    // Previously the raw stream was opened outside the try and leaked on a decrypt-setup
                    // failure (one descriptor per file in the ZIP).
                    try (InputStream rawInputStream = Files.newInputStream(storedfile);
                         InputStream decryptedStream = EncryptionUtil.decryptInputStream(rawInputStream, user.getPrivateKey())) {
                        // Sanitize the stored file name to a single safe path segment (a persisted or
                        // imported name may contain '/', '\' or traversal), preventing a zip-slip entry
                        // that escapes the extraction directory. The unique index prefix de-collides two
                        // names that sanitize to the same basename.
                        ZipEntry zipEntry = new ZipEntry(index + "-"
                                + ExportUtil.sanitizeFileName(file.getFullName(Integer.toString(index))));
                        zipOutputStream.putNextEntry(zipEntry);
                        ByteStreams.copy(decryptedStream, zipOutputStream);
                        zipOutputStream.closeEntry();
                    } catch (Exception e) {
                        throw new WebApplicationException(e);
                    }
                    index++;
                }
            }
            outputStream.close();
        };
        
        // Write to the output
        return Response.ok(stream)
                .header("Content-Type", "application/zip")
                .header("Content-Disposition", "attachment; filename=\"" + zipFileName + ".zip\"")
                .build();
    }

    /**
     * Find a file with access rights checking.
     *
     * @param fileId File ID
     * @param shareId Share ID
     * @return File
     */
    private File findFile(String fileId, String shareId, PermType permType) {
        FileDao fileDao = new FileDao();
        File file = fileDao.getFile(fileId);
        if (file == null) {
            throw new NotFoundException();
        }
        checkFileAccessible(shareId, file, permType);
        return file;
    }


    /**
     * Find a list of files with access rights checking.
     *
     * @param filesIds Files IDs
     * @return List<File>
     */
    private List<File> findFiles(List<String> filesIds) {
        FileDao fileDao = new FileDao();
        List<File> files = fileDao.getFiles(filesIds);
        for (File file : files) {
            checkFileAccessible(null, file, PermType.READ);
        }
        return files;
    }

    /**
     * Check if a file is accessible to the current user at the given permission level.
     * @param shareId Share ID
     * @param file File
     * @param permType Permission required on the parent document (READ to view, WRITE to mutate)
     */
    private void checkFileAccessible(String shareId, File file, PermType permType) {
        if (file.getDocumentId() == null) {
            // It's an orphan file
            if (!file.getUserId().equals(principal.getId())) {
                // But not ours
                throw new ForbiddenClientException();
            }
        } else {
            // Check document accessibility
            AclDao aclDao = new AclDao();
            if (!aclDao.checkPermission(file.getDocumentId(), permType, getTargetIdList(shareId))) {
                throw new ForbiddenClientException();
            }
        }
    }
}
