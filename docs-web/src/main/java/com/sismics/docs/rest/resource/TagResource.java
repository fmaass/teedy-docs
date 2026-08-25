package com.sismics.docs.rest.resource;

import com.google.common.collect.Sets;
import com.sismics.docs.core.constant.PermType;
import com.sismics.docs.core.dao.AclDao;
import com.sismics.docs.core.dao.TagDao;
import com.sismics.docs.core.dao.criteria.TagCriteria;
import com.sismics.docs.core.dao.dto.TagCoOccurrence;
import com.sismics.docs.core.dao.dto.TagDto;
import com.sismics.docs.core.exception.InactiveOwnerException;
import com.sismics.docs.core.model.jpa.Tag;
import com.sismics.docs.core.util.TagCreationUtil;
import com.sismics.docs.core.util.jpa.SortCriteria;
import com.sismics.docs.rest.util.TagMaintenanceUtil;
import com.sismics.docs.rest.util.TagReductionUtil;
import com.sismics.rest.exception.ClientException;
import com.sismics.rest.exception.ForbiddenClientException;
import com.sismics.rest.util.AclUtil;
import com.sismics.rest.util.ValidationUtil;
import org.apache.commons.lang3.StringUtils;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObjectBuilder;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import java.text.MessageFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Tag REST resources.
 * 
 * @author bgamard
 */
@Path("/tag")
public class TagResource extends BaseResource {
    /**
     * Returns the list of all visible tags.
     *
     * @api {get} /tag/list Get tags
     * @apiName GetTagList
     * @apiGroup Tag
     * @apiSuccess {Object[]} tags List of tags
     * @apiSuccess {String} tags.id ID
     * @apiSuccess {String} tags.name Name
     * @apiSuccess {String} tags.color Color
     * @apiSuccess {String} tags.parent Parent
     * @apiError (client) ForbiddenError Access denied
     * @apiPermission user
     * @apiVersion 1.5.0
     *
     * @return Response
     */
    @GET
    @Path("/list")
    @Operation(
            summary = "Get tags",
            description = "Returns the list of all visible tags.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Success",
                            content = @Content(schema = @Schema(implementation = TagListResult.class))),
                    @ApiResponse(responseCode = "403", description = "ForbiddenError - Access denied")
            }
    )
    public Response list() {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }
        
        TagDao tagDao = new TagDao();
        List<TagDto> tagDtoList = tagDao.findByCriteria(new TagCriteria().setTargetIdList(getTargetIdList(null)), new SortCriteria(1, true));

        // Extract tag IDs
        Set<String> tagIdSet = Sets.newHashSet();
        for (TagDto tagDto : tagDtoList) {
            tagIdSet.add(tagDto.getId());
        }

        // Build the response
        JsonArrayBuilder items = Json.createArrayBuilder();
        for (TagDto tagDto : tagDtoList) {
            JsonObjectBuilder item = Json.createObjectBuilder()
                    .add("id", tagDto.getId())
                    .add("name", tagDto.getName())
                    .add("color", tagDto.getColor());
            if (tagIdSet.contains(tagDto.getParentId())) {
                item.add("parent", tagDto.getParentId());
            }
            items.add(item);
        }
        
        JsonObjectBuilder response = Json.createObjectBuilder()
                .add("tags", items);
        return Response.ok().entity(response.build()).build();
    }

    /**
     * Returns a tag.
     *
     * @api {get} /tag/:id Get a tag
     * @apiName GetTag
     * @apiGroup Tag
     * @apiSuccess {String} id ID
     * @apiSuccess {String} name Name
     * @apiSuccess {String} creator Username of the creator
     * @apiSuccess {String} color Color
     * @apiSuccess {String} parent Parent
     * @apiSuccess {Boolean} writable True if the tag is writable by the current user
     * @apiSuccess {Object[]} acls List of ACL
     * @apiSuccess {String} acls.id ID
     * @apiSuccess {String="READ","WRITE"} acls.perm Permission
     * @apiSuccess {String} acls.name Target name
     * @apiSuccess {String="USER","GROUP","SHARE"} acls.type Target type
     * @apiError (client) ForbiddenError Access denied
     * @apiError (client) NotFound Tag not found
     * @apiPermission user
     * @apiVersion 1.5.0
     *
     * @param id Tag ID
     * @return Response
     */
    @GET
    @Path("{id: [a-z0-9\\-]+}")
    @Operation(
            summary = "Get a tag",
            description = "Returns a tag.",
            parameters = {
                    @Parameter(name = "id", in = ParameterIn.PATH, required = true,
                            description = "Tag ID", schema = @Schema(type = "string"))
            },
            responses = {
                    @ApiResponse(responseCode = "200", description = "Success",
                            content = @Content(schema = @Schema(implementation = TagDetail.class))),
                    @ApiResponse(responseCode = "403", description = "ForbiddenError - Access denied"),
                    @ApiResponse(responseCode = "404", description = "NotFound - Tag not found")
            }
    )
    public Response get(@PathParam("id") String id) {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }

        TagDao tagDao = new TagDao();
        List<TagDto> tagDtoList = tagDao.findByCriteria(new TagCriteria().setTargetIdList(getTargetIdList(null)).setId(id), null);
        if (tagDtoList.isEmpty()) {
            throw new NotFoundException();
        }

        // Add tag informatiosn
        TagDto tagDto = tagDtoList.get(0);
        JsonObjectBuilder tag = Json.createObjectBuilder()
                .add("id", tagDto.getId())
                .add("creator", tagDto.getCreator())
                .add("name", tagDto.getName())
                .add("color", tagDto.getColor());

        // Add the parent if its visible
        if (tagDto.getParentId() != null) {
            AclDao aclDao = new AclDao();
            if (aclDao.checkPermission(tagDto.getParentId(), PermType.READ, getTargetIdList(null))) {
                tag.add("parent", tagDto.getParentId());
            }
        }

        // Add ACL
        AclUtil.addAcls(tag, id, getTargetIdList(null));

        return Response.ok().entity(tag.build()).build();
    }

    /**
     * Creates a new tag.
     *
     * @api {put} /tag Create a tag
     * @apiName PutTag
     * @apiGroup Tag
     * @apiParam {String} name Name
     * @apiParam {String} color Color
     * @apiParam {String} parent Parent ID
     * @apiSuccess {String} id Tag ID
     * @apiError (client) ForbiddenError Access denied
     * @apiError (client) ValidationError Validation error
     * @apiError (client) IllegalTagName Spaces, colons and asterisks are not allowed inside a tag name;
     *                                 invisible format characters are removed and the edges are trimmed, and a
     *                                 name left empty by that normalization is refused
     * @apiError (client) ParentNotFound Parent not found
     * @apiPermission user
     * @apiVersion 1.5.0
     *
     * @param name Name
     * @param color Color
     * @param parentId Parent ID
     * @return Response
     */
    @PUT
    @Operation(
            summary = "Create a tag",
            description = "Creates a new tag.",
            requestBody = @RequestBody(content = @Content(
                    schema = @Schema(implementation = TagWriteForm.class))),
            responses = {
                    @ApiResponse(responseCode = "200", description = "Success",
                            content = @Content(schema = @Schema(implementation = TagIdResult.class))),
                    @ApiResponse(responseCode = "403", description = "ForbiddenError - Access denied"),
                    @ApiResponse(responseCode = "400", description = "ValidationError - Validation error; "
                            + "IllegalTagName - Spaces, colons and asterisks are not allowed inside a tag name "
                            + "(invisible format characters are removed and the edges trimmed instead, and a name "
                            + "left empty by that normalization is refused); "
                            + "ParentNotFound - Parent not found")
            }
    )
    public Response add(
            @Parameter(description = "Name") @FormParam("name") String name,
            @Parameter(description = "Color") @FormParam("color") String color,
            @Parameter(name = "parent", description = "Parent ID") @FormParam("parent") String parentId) {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }
        
        // Validate input data
        // #305: normalization runs FIRST and owns the whole name rule (invisible characters removed,
        // edges trimmed, interior whitespace refused). The length bound is then measured on the name
        // that will actually be stored — a 36-character name carrying a zero-width character is 36
        // characters, not 37 — and the returned value is what gets persisted.
        name = ValidationUtil.validateTagName(name);
        name = ValidationUtil.validateLength(name, "name", 1, 36, false);
        ValidationUtil.validateHexColor(color, "color", true);

        // Check the parent
        if (StringUtils.isEmpty(parentId)) {
            parentId = null;
        } else {
            AclDao aclDao = new AclDao();
            if (!aclDao.checkPermission(parentId, PermType.READ, getTargetIdList(null))) {
                throw new ClientException("ParentNotFound", MessageFormat.format("Parent not found: {0}", parentId));
            }
        }

        // Create the tag and its base ACLs. #185: TagCreationUtil takes the owner's row lock FOR UPDATE
        // before the insert, so a tag cannot be created under an owner a concurrent deletion is about to
        // soft-delete. Its InactiveOwnerException means the caller's own account stopped being active
        // mid-request, so the create is refused as a client error — mapping it here is NOT a
        // rest.resource -> core.dao dependency, so the frozen layering web is unchanged.
        Tag tag = new Tag();
        tag.setName(name);
        tag.setColor(color);
        tag.setUserId(principal.getId());
        tag.setParentId(parentId);
        String id;
        try {
            id = TagCreationUtil.createTag(tag, principal.getId());
        } catch (InactiveOwnerException e) {
            throw new ForbiddenClientException();
        }

        JsonObjectBuilder response = Json.createObjectBuilder()
                .add("id", id);
        return Response.ok().entity(response.build()).build();
    }
    
    /**
     * Update a tag.
     *
     * @api {post} /tag/:id Update a tag
     * @apiName PostTag
     * @apiGroup Tag
     * @apiParam {String} id Tag ID
     * @apiParam {String} name Name
     * @apiParam {String} color Color
     * @apiParam {String} parent Parent ID
     * @apiSuccess {String} id Tag ID
     * @apiError (client) ForbiddenError Access denied
     * @apiError (client) ValidationError Validation error
     * @apiError (client) IllegalTagName Spaces, colons and asterisks are not allowed inside a tag name;
     *                                 invisible format characters are removed and the edges are trimmed, and a
     *                                 name left empty by that normalization is refused
     * @apiError (client) ParentNotFound Parent not found
     * @apiError (client) CircularReference Circular reference in parent tag
     * @apiError (client) NotFound Tag not found
     * @apiPermission user
     * @apiVersion 1.5.0
     *
     * @param name Name
     * @param color Color
     * @param parentId Parent ID
     * @return Response
     */
    @POST
    @Path("{id: [a-z0-9\\-]+}")
    @Operation(
            summary = "Update a tag",
            description = "Update a tag.",
            parameters = {
                    @Parameter(name = "id", in = ParameterIn.PATH, required = true,
                            description = "Tag ID", schema = @Schema(type = "string"))
            },
            requestBody = @RequestBody(content = @Content(
                    schema = @Schema(implementation = TagWriteForm.class))),
            responses = {
                    @ApiResponse(responseCode = "200", description = "Success",
                            content = @Content(schema = @Schema(implementation = TagIdResult.class))),
                    @ApiResponse(responseCode = "403", description = "ForbiddenError - Access denied"),
                    @ApiResponse(responseCode = "404", description = "NotFound - Tag not found"),
                    @ApiResponse(responseCode = "400", description = "ValidationError - Validation error; "
                            + "IllegalTagName - Spaces, colons and asterisks are not allowed inside a tag name "
                            + "(invisible format characters are removed and the edges trimmed instead, and a name "
                            + "left empty by that normalization is refused); "
                            + "ParentNotFound - Parent not found; "
                            + "CircularReference - Circular reference in parent tag")
            }
    )
    public Response update(
            @PathParam("id") String id,
            @Parameter(description = "Name") @FormParam("name") String name,
            @Parameter(description = "Color") @FormParam("color") String color,
            @Parameter(name = "parent", description = "Parent ID") @FormParam("parent") String parentId) {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }
        
        // Validate input data
        // #305: same order as create — a rename must not be a back door for the characters create
        // refuses, and the length bound must measure the normalized name it returns.
        name = ValidationUtil.validateTagName(name);
        name = ValidationUtil.validateLength(name, "name", 1, 36, true);
        ValidationUtil.validateHexColor(color, "color", true);

        // Check permission
        AclDao aclDao = new AclDao();
        if (!aclDao.checkPermission(id, PermType.WRITE, getTargetIdList(null))) {
            throw new NotFoundException();
        }
        
        // Check the parent
        TagDao tagDao = new TagDao();
        if (StringUtils.isEmpty(parentId)) {
            parentId = null;
        } else {
            if (!aclDao.checkPermission(parentId, PermType.READ, getTargetIdList(null))) {
                throw new ClientException("ParentNotFound", MessageFormat.format("Parent not found: {0}", parentId));
            }

            String parentTagId = parentId;
            do {
                Tag parentTag = tagDao.getById(parentTagId);
                parentTagId = parentTag.getParentId();
                if (parentTag.getId().equals(id)) {
                    throw new ClientException("CircularReference", "Circular reference in parent tag");
                }
            } while (parentTagId != null);
        }

        // Update the tag
        Tag tag = tagDao.getById(id);
        if (!StringUtils.isEmpty(name)) {
            tag.setName(name);
        }
        if (!StringUtils.isEmpty(color)) {
            tag.setColor(color);
        }
        // Parent tag is always updated to have the possibility to delete it
        tag.setParentId(parentId);
        
        tagDao.update(tag, principal.getId());
        
        JsonObjectBuilder response = Json.createObjectBuilder()
                .add("id", id);
        return Response.ok().entity(response.build()).build();
    }
    
    /**
     * Delete a tag.
     *
     * @api {delete} /tag/:id Delete a tag
     * @apiName DeleteTag
     * @apiGroup Tag
     * @apiParam {String} id Tag ID
     * @apiSuccess {String} status Status OK
     * @apiError (client) ForbiddenError Access denied
     * @apiError (client) NotFound Tag not found
     * @apiPermission user
     * @apiVersion 1.5.0
     * 
     * @param id Tag ID
     * @return Response
     */
    @DELETE
    @Path("{id: [a-z0-9\\-]+}")
    @Operation(
            summary = "Delete a tag",
            description = "Delete a tag.",
            parameters = {
                    @Parameter(name = "id", in = ParameterIn.PATH, required = true,
                            description = "Tag ID", schema = @Schema(type = "string"))
            },
            responses = {
                    @ApiResponse(responseCode = "200", description = "Success",
                            content = @Content(schema = @Schema(implementation = StatusResult.class))),
                    @ApiResponse(responseCode = "403", description = "ForbiddenError - Access denied"),
                    @ApiResponse(responseCode = "404", description = "NotFound - Tag not found")
            }
    )
    public Response delete(
            @PathParam("id") String id) {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }
        
        // Get the tag
        AclDao aclDao = new AclDao();
        if (!aclDao.checkPermission(id, PermType.WRITE, getTargetIdList(null))) {
            throw new NotFoundException();
        }

        // Delete the tag
        TagDao tagDao = new TagDao();
        tagDao.delete(id, principal.getId());
        
        // Always return OK
        JsonObjectBuilder response = Json.createObjectBuilder()
                .add("status", "ok");
        return Response.ok().entity(response.build()).build();
    }

    /**
     * Returns the maintenance status of every visible tag: whether its whole subtree is unused and
     * may therefore be removed, and when it may not, why.
     *
     * <p>This is the PREVIEW half of the unused-tag cleanup as well as the source the management
     * tree reads to enable or disable its per-node delete action — one request answers both, and a
     * divergence between what the cleanup previews and what it deletes is not representable
     * because both are read off this same verdict.</p>
     *
     * @return Response with the status of every visible tag
     */
    @GET
    @Path("/maintenance")
    @Operation(
            summary = "Get tag maintenance status",
            description = "Returns, for every visible tag, whether its whole subtree is unused and can "
                    + "be deleted, and when it cannot, why. Nothing is modified.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Success",
                            content = @Content(schema = @Schema(implementation = TagMaintenanceResult.class))),
                    @ApiResponse(responseCode = "403", description = "ForbiddenError - Access denied")
            }
    )
    public Response maintenance() {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }

        JsonArrayBuilder items = Json.createArrayBuilder();
        for (TagMaintenanceUtil.TagStatus status : TagMaintenanceUtil.status(getTargetIdList(null))) {
            JsonObjectBuilder item = tagMaintenanceItem(status)
                    .add("deletable", status.deletable())
                    .add("root", status.root())
                    .add("subtreeDocuments", status.subtreeDocumentCount());
            if (status.reason() != null) {
                item.add("reason", status.reason().name().toLowerCase(Locale.ROOT));
            }
            items.add(item);
        }

        return Response.ok().entity(Json.createObjectBuilder().add("tags", items).build()).build();
    }

    /**
     * Deletes every fully-unused tag subtree the caller may remove, and reports what went.
     *
     * <p>The CONFIRM half of the cleanup previewed by {@code GET /tag/maintenance}. The set is
     * recomputed here rather than taken from the client, so a tag that gained a document since the
     * preview was rendered survives the confirm. Nothing still attached to a document is ever
     * deleted, and nothing is un-assigned to make a tag deletable.</p>
     *
     * @return Response with the deleted tags and their count
     */
    @DELETE
    @Path("/maintenance")
    @Operation(
            summary = "Delete all unused tags",
            description = "Deletes every tag whose entire subtree carries no document, and reports "
                    + "exactly which tags were deleted. Tags still carrying documents are never touched.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Success",
                            content = @Content(schema = @Schema(implementation = TagDeletionResult.class))),
                    @ApiResponse(responseCode = "403", description = "ForbiddenError - Access denied")
            }
    )
    public Response deleteUnused() {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }

        TagMaintenanceUtil.Sweep sweep = TagMaintenanceUtil.deleteUnused(getTargetIdList(null), principal.getId());
        return tagDeletionResponse(sweep.deleted(), sweep.blocked());
    }

    /**
     * Deletes a tag and its whole subtree, provided nothing in that subtree is in use.
     *
     * <p>The maintenance delete offered by the tag management tree (#298 part 1). It is NOT the
     * same operation as {@code DELETE /tag/:id}, which removes a single tag, un-assigns it from
     * every document and re-parents its children; this one refuses outright unless the tag and
     * every descendant carry no document at all, and then removes the branch whole.</p>
     *
     * @param id Tag ID at the root of the subtree
     * @return Response with the deleted tags and their count
     */
    @DELETE
    @Path("{id: [a-z0-9\\-]+}/subtree")
    @Operation(
            summary = "Delete an unused tag subtree",
            description = "Deletes a tag and all its descendants, but only when none of them carries a "
                    + "document. Unlike DELETE /tag/{id} this never un-assigns a tag from a document.",
            parameters = {
                    @Parameter(name = "id", in = ParameterIn.PATH, required = true,
                            description = "Tag ID", schema = @Schema(type = "string"))
            },
            responses = {
                    @ApiResponse(responseCode = "200", description = "Success",
                            content = @Content(schema = @Schema(implementation = TagDeletionResult.class))),
                    @ApiResponse(responseCode = "403", description = "ForbiddenError - Access denied"),
                    @ApiResponse(responseCode = "404", description = "NotFound - Tag not found"),
                    @ApiResponse(responseCode = "400", description = "TagSubtreeInUse - The subtree still carries documents; "
                            + "TagSubtreeInRule - The subtree holds a tag an auto-tagging rule points at; "
                            + "TagNotDeletable - Refused without a reason the caller is told")
            }
    )
    public Response deleteSubtree(@PathParam("id") String id) {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }

        TagMaintenanceUtil.DeleteResult result =
                TagMaintenanceUtil.deleteSubtree(id, getTargetIdList(null), principal.getId());
        switch (result.outcome()) {
            case NOT_FOUND -> throw new NotFoundException();
            case IN_USE -> throw new ClientException("TagSubtreeInUse",
                    "This tag or one of its sub-tags is still on a document, or on one in the trash");
            case IN_RULE -> throw new ClientException("TagSubtreeInRule",
                    "This tag or one of its sub-tags is used by an auto-tagging rule");
            // Deliberately unexplained: this is the branch a subtree holding a tag the caller
            // cannot read or write ends up in, and naming that reason would confirm to them that a
            // tag they cannot see exists under a tag they own.
            case NOT_DELETABLE -> throw new ClientException("TagNotDeletable",
                    "This tag cannot be deleted");
            case DELETED -> { }
        }

        return tagDeletionResponse(result.deleted(), List.of());
    }

    /**
     * Removes from each selected document every tag that a tag BELOW it on the same document
     * already implies (#293).
     *
     * <p>Both halves of the reporter's contract live in this one operation. It defaults to a
     * PREVIEW — {@code dryRun} absent means nothing is modified — so the destructive pass is only
     * ever reached by an explicit {@code dryRun=false}, and the two passes derive their removal set
     * through exactly the same code on the same freshly read state. The client sends document IDs
     * and nothing else: a preview that has gone stale, or a tampered one, cannot name a tag for
     * removal that the rule does not call redundant at execute time.</p>
     *
     * <p>It is deliberately NOT instance-wide. The selection is the current one in the document
     * list, which is how the reporter asked to control the batch ("apply a filter, select the
     * documents, run it on them, without hitting too much at once" — #293).</p>
     *
     * @param documentIdList Selected document IDs
     * @param dryRun False to actually remove; absent or true previews
     * @return Response with what was (or would be) removed per document, and what was skipped
     */
    @POST
    @Path("/reduce")
    @Operation(
            summary = "Reduce redundant tags on documents",
            description = "Removes from each of the given documents every tag that has a descendant tag on "
                    + "that same document, so a document tagged Insurance / Car / 2026 in full keeps only "
                    + "2026. Defaults to a dry run: nothing is modified unless dryRun is false. The removal "
                    + "set is always derived server-side from the current state — the client sends document "
                    + "IDs only. Tags the caller cannot read never cause a removal, and documents the caller "
                    + "cannot write are reported as skipped rather than modified.",
            requestBody = @RequestBody(content = @Content(
                    schema = @Schema(implementation = TagReductionForm.class))),
            responses = {
                    @ApiResponse(responseCode = "200", description = "Success",
                            content = @Content(schema = @Schema(implementation = TagReductionResult.class))),
                    @ApiResponse(responseCode = "403", description = "ForbiddenError - Access denied"),
                    @ApiResponse(responseCode = "400", description = "ValidationError - Too many documents "
                            + "in one run")
            }
    )
    public Response reduce(
            @Parameter(name = "documents", description = "Document IDs to reduce")
            @FormParam("documents") List<String> documentIdList,
            @Parameter(name = "dryRun", description = "False to remove; absent or true previews")
            @FormParam("dryRun") String dryRun) {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }

        List<String> documentIdParams = documentIdList == null ? List.of() : documentIdList;
        // The list is client-supplied and feeds batched `in (…)` reads: unbounded, it is both a
        // database parameter-limit hazard and unbounded work on one request thread. A run is one
        // page of the document list, which no UI can push past this.
        if (documentIdParams.size() > TagReductionUtil.MAX_DOCUMENTS) {
            throw new ClientException("ValidationError", MessageFormat.format(
                    "Too many documents in one tag reduction run: at most {0}",
                    String.valueOf(TagReductionUtil.MAX_DOCUMENTS)));
        }

        // Fail SAFE on anything but the documented word, and read the flag as a STRING to be able
        // to. Taken as a Boolean, JAX-RS would hand it to Boolean.valueOf, which answers false to
        // everything that is not "true" — so a typo'd or truncated flag would silently become a
        // real removal. The comparison is EXACT rather than lenient for the same reason: "FALSE" or
        // a padded " false " is not the request this endpoint documents, and the safe reading of an
        // unclear request on a destructive operation is the one that changes nothing.
        boolean dryRunEffective = !"false".equals(dryRun);
        TagReductionUtil.Reduction reduction = TagReductionUtil.reduce(documentIdParams,
                getTargetIdList(null), principal.getId(), dryRunEffective);

        int count = 0;
        JsonArrayBuilder documents = Json.createArrayBuilder();
        for (TagReductionUtil.DocumentReduction document : reduction.documents()) {
            JsonArrayBuilder tags = Json.createArrayBuilder();
            for (TagReductionUtil.RemovedTag tag : document.tags()) {
                tags.add(Json.createObjectBuilder()
                        .add("id", tag.id())
                        .add("name", tag.name())
                        .add("path", tag.path()));
                count++;
            }
            documents.add(Json.createObjectBuilder()
                    .add("id", document.documentId())
                    .add("tags", tags));
        }
        JsonArrayBuilder skipped = Json.createArrayBuilder();
        for (String documentId : reduction.skipped()) {
            skipped.add(documentId);
        }

        return Response.ok().entity(Json.createObjectBuilder()
                .add("status", "ok")
                .add("dryRun", dryRunEffective)
                .add("count", count)
                .add("documents", documents)
                .add("skipped", skipped).build()).build();
    }

    /** The identity half of a maintenance item, shared by the status and deletion responses. */
    private static JsonObjectBuilder tagMaintenanceItem(TagMaintenanceUtil.TagStatus status) {
        return Json.createObjectBuilder()
                .add("id", status.id())
                .add("name", status.name())
                .add("path", status.path());
    }

    /**
     * Reports exactly which tags a destructive maintenance action removed, and which ones it kept.
     *
     * <p>{@code blocked} is the sweep's honest half: every tag is re-checked against freshly read
     * state immediately before it is removed, and one that became used in the meantime is kept
     * rather than deleted — reporting only the successes would make that indistinguishable from a
     * tag that was never in the run. It is always present, empty when nothing was kept.</p>
     */
    private static Response tagDeletionResponse(List<TagMaintenanceUtil.TagStatus> deleted,
                                                List<TagMaintenanceUtil.TagStatus> blocked) {
        JsonArrayBuilder items = Json.createArrayBuilder();
        for (TagMaintenanceUtil.TagStatus status : deleted) {
            items.add(tagMaintenanceItem(status));
        }
        JsonArrayBuilder keptItems = Json.createArrayBuilder();
        for (TagMaintenanceUtil.TagStatus status : blocked) {
            keptItems.add(tagMaintenanceItem(status));
        }
        return Response.ok().entity(Json.createObjectBuilder()
                .add("status", "ok")
                .add("count", deleted.size())
                .add("tags", items)
                .add("blocked", keptItems).build()).build();
    }

    /**
     * Returns document counts per tag.
     *
     * @return Response with tag ID to document count mapping
     */
    @GET
    @Path("/stats")
    @Operation(
            summary = "Get tag statistics",
            description = "Returns document counts per tag.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Success",
                            content = @Content(schema = @Schema(implementation = TagStatsResult.class))),
                    @ApiResponse(responseCode = "403", description = "ForbiddenError - Access denied")
            }
    )
    public Response stats() {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }

        TagDao tagDao = new TagDao();
        java.util.Map<String, Long> counts = tagDao.getTagDocumentCounts(getTargetIdList(null));

        JsonObjectBuilder stats = Json.createObjectBuilder();
        for (java.util.Map.Entry<String, Long> entry : counts.entrySet()) {
            stats.add(entry.getKey(), entry.getValue());
        }

        return Response.ok().entity(Json.createObjectBuilder()
                .add("stats", stats).build()).build();
    }

    /**
     * Returns co-occurring tags for faceted navigation.
     * Given selected tags, returns other tags that appear on matching documents with counts.
     *
     * @param tagsParam Comma-separated tag IDs (optional, empty = all tags)
     * @param modeParam Tag combination mode: "and" (default) or "or"
     * @param excludeParams Excluded tag IDs (optional, repeated). Documents carrying any
     *                      excluded tag are removed from the facet and total counts, mirroring
     *                      the SPA's {@code !tag:} filter. Empty/blank ids are ignored.
     * @return Response with facet counts and total matching documents
     */
    @GET
    @Path("/facets")
    @Operation(
            summary = "Get tag facet counts",
            description = "Returns co-occurring tags for faceted navigation. Given selected tags, "
                    + "returns other tags that appear on matching documents with counts.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Success",
                            content = @Content(schema = @Schema(implementation = TagFacetsResult.class))),
                    @ApiResponse(responseCode = "403", description = "ForbiddenError - Access denied")
            }
    )
    public Response facets(@Parameter(name = "tags", in = ParameterIn.QUERY,
                                   description = "Comma-separated tag IDs (optional, empty = all tags)")
                           @QueryParam("tags") String tagsParam,
                           @Parameter(name = "mode", in = ParameterIn.QUERY,
                                   description = "Tag combination mode: \"and\" (default) or \"or\"")
                           @QueryParam("mode") String modeParam,
                           @Parameter(name = "exclude", in = ParameterIn.QUERY,
                                   description = "Excluded tag IDs (optional, repeated)")
                           @QueryParam("exclude") List<String> excludeParams) {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }

        boolean orMode = "or".equalsIgnoreCase(modeParam);

        TagDao tagDao = new TagDao();
        java.util.List<String> selectedTagIds = new java.util.ArrayList<>();
        if (tagsParam != null && !tagsParam.isBlank()) {
            for (String id : tagsParam.split(",")) {
                String trimmed = id.trim();
                if (!trimmed.isEmpty()) {
                    selectedTagIds.add(trimmed);
                }
            }
        }

        // Excluded tag ids arrive as repeated ?exclude=<id> params; each value may itself be a
        // comma-separated list. Sanitise the same way as the selected ids (drop blanks, trim).
        java.util.List<String> excludeTagIds = new java.util.ArrayList<>();
        if (excludeParams != null) {
            for (String param : excludeParams) {
                if (param == null) {
                    continue;
                }
                for (String id : param.split(",")) {
                    String trimmed = id.trim();
                    if (!trimmed.isEmpty()) {
                        excludeTagIds.add(trimmed);
                    }
                }
            }
        }

        java.util.List<String> targetIdList = getTargetIdList(null);
        java.util.Map<String, Long> counts = orMode
                ? tagDao.getCoOccurringTagCountsOr(selectedTagIds, targetIdList, excludeTagIds)
                : tagDao.getCoOccurringTagCounts(selectedTagIds, targetIdList, excludeTagIds);
        long total = selectedTagIds.isEmpty() ? 0
                : orMode ? tagDao.countDocumentsWithAnyTag(selectedTagIds, targetIdList, excludeTagIds)
                         : tagDao.countDocumentsWithAllTags(selectedTagIds, targetIdList, excludeTagIds);

        JsonObjectBuilder facets = Json.createObjectBuilder();
        for (java.util.Map.Entry<String, Long> entry : counts.entrySet()) {
            facets.add(entry.getKey(), entry.getValue());
        }

        return Response.ok().entity(Json.createObjectBuilder()
                .add("facets", facets)
                .add("total", total).build()).build();
    }

    /**
     * Returns the full tag co-occurrence matrix.
     * Each entry is a pair of tag IDs and how many documents share both tags.
     *
     * @api {get} /tag/co-occurrence Get tag co-occurrence matrix
     * @apiName GetTagCoOccurrence
     * @apiGroup Tag
     * @apiSuccess {Object[]} pairs List of co-occurring tag pairs
     * @apiSuccess {String} pairs.tagA First tag ID
     * @apiSuccess {String} pairs.tagB Second tag ID
     * @apiSuccess {Number} pairs.count Document count sharing both tags
     * @apiError (client) ForbiddenError Access denied
     * @apiPermission user
     */
    @GET
    @Path("/co-occurrence")
    @Operation(
            summary = "Get the tag co-occurrence matrix",
            description = "Returns the full tag co-occurrence matrix. Each entry is a pair of tag IDs "
                    + "and how many documents share both tags.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Success",
                            content = @Content(schema = @Schema(implementation = TagCoOccurrenceResult.class))),
                    @ApiResponse(responseCode = "403", description = "ForbiddenError - Access denied")
            }
    )
    public Response coOccurrence() {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }

        TagDao tagDao = new TagDao();
        List<TagCoOccurrence> matrix = tagDao.getFullCoOccurrenceMatrix(getTargetIdList(null));

        JsonArrayBuilder pairs = Json.createArrayBuilder();
        for (TagCoOccurrence pair : matrix) {
            pairs.add(Json.createObjectBuilder()
                    .add("tagA", pair.tagIdA())
                    .add("tagB", pair.tagIdB())
                    .add("count", pair.count()));
        }

        return Response.ok().entity(Json.createObjectBuilder()
                .add("pairs", pairs).build()).build();
    }

    // ---------------------------------------------------------------------------------------------
    // OpenAPI schema models (v3.5 build-time generation spike). Documentation-only DTOs referenced
    // by the @Schema annotations above; they mirror the JSON shapes the resource actually returns.
    // Not used at runtime — the endpoints build their JSON via Json.createObjectBuilder.
    // ---------------------------------------------------------------------------------------------

    @Schema(name = "TagWriteForm", description = "Tag create/update form body")
    private static class TagWriteForm {
        @Schema(description = "Name")
        public String name;
        @Schema(description = "Color")
        public String color;
        @Schema(name = "parent", description = "Parent ID")
        public String parent;
    }

    @Schema(name = "TagIdResult", description = "Tag ID envelope")
    private static class TagIdResult {
        @Schema(description = "Tag ID")
        public String id;
    }

    @Schema(name = "StatusResult", description = "Status envelope")
    private static class StatusResult {
        @Schema(description = "Status OK")
        public String status;
    }

    @Schema(name = "TagListItem", description = "A visible tag")
    private static class TagListItem {
        @Schema(description = "ID")
        public String id;
        @Schema(description = "Name")
        public String name;
        @Schema(description = "Color")
        public String color;
        @Schema(description = "Parent")
        public String parent;
    }

    @Schema(name = "TagListResult", description = "List of tags")
    private static class TagListResult {
        @Schema(description = "List of tags")
        public List<TagListItem> tags;
    }

    @Schema(name = "TagAcl", description = "A tag ACL entry")
    private static class TagAcl {
        @Schema(description = "ID")
        public String id;
        @Schema(description = "Permission", allowableValues = {"READ", "WRITE"})
        public String perm;
        @Schema(description = "Target name")
        public String name;
        @Schema(description = "Target type", allowableValues = {"USER", "GROUP", "SHARE"})
        public String type;
    }

    @Schema(name = "TagDetail", description = "A tag with ACLs")
    private static class TagDetail {
        @Schema(description = "ID")
        public String id;
        @Schema(description = "Name")
        public String name;
        @Schema(description = "Username of the creator")
        public String creator;
        @Schema(description = "Color")
        public String color;
        @Schema(description = "Parent")
        public String parent;
        @Schema(description = "True if the tag is writable by the current user")
        public Boolean writable;
        @Schema(description = "List of ACL")
        public List<TagAcl> acls;
    }

    @Schema(name = "TagStatsResult", description = "Document counts per tag (tag ID to count)")
    private static class TagStatsResult {
        @Schema(description = "Tag ID to document count mapping")
        public Map<String, Long> stats;
    }

    @Schema(name = "TagFacetsResult", description = "Facet counts and total matching documents")
    private static class TagFacetsResult {
        @Schema(description = "Tag ID to co-occurrence count mapping")
        public Map<String, Long> facets;
        @Schema(description = "Total matching documents")
        public Long total;
    }

    @Schema(name = "TagCoOccurrencePair", description = "A pair of co-occurring tags")
    private static class TagCoOccurrencePair {
        @Schema(description = "First tag ID")
        public String tagA;
        @Schema(description = "Second tag ID")
        public String tagB;
        @Schema(description = "Document count sharing both tags")
        public Long count;
    }

    @Schema(name = "TagCoOccurrenceResult", description = "List of co-occurring tag pairs")
    private static class TagCoOccurrenceResult {
        @Schema(description = "List of co-occurring tag pairs")
        public List<TagCoOccurrencePair> pairs;
    }

    @Schema(name = "TagMaintenanceItem", description = "A tag's maintenance verdict")
    private static class TagMaintenanceItem {
        @Schema(description = "ID")
        public String id;
        @Schema(description = "Name")
        public String name;
        @Schema(description = "Slash-joined chain of visible ancestor names, this tag last")
        public String path;
        @Schema(description = "True if this tag and all its descendants carry no document and can be deleted")
        public Boolean deletable;
        @Schema(description = "True if this tag is the topmost deletable tag of its branch")
        public Boolean root;
        @Schema(description = "Documents on this tag and its readable descendants")
        public Long subtreeDocuments;
        @Schema(description = "Why the tag is not deletable; absent when it is",
                allowableValues = {"documents", "trash", "rule", "other"})
        public String reason;
    }

    @Schema(name = "TagMaintenanceResult", description = "Maintenance status of every visible tag")
    private static class TagMaintenanceResult {
        @Schema(description = "Maintenance status of every visible tag")
        public List<TagMaintenanceItem> tags;
    }

    @Schema(name = "TagDeletedItem", description = "A deleted tag")
    private static class TagDeletedItem {
        @Schema(description = "ID")
        public String id;
        @Schema(description = "Name")
        public String name;
        @Schema(description = "Slash-joined chain of visible ancestor names, this tag last")
        public String path;
    }

    @Schema(name = "TagReductionForm", description = "The documents to reduce, and whether to preview")
    private static class TagReductionForm {
        @Schema(name = "documents", description = "Document IDs to reduce")
        public List<String> documents;
        @Schema(name = "dryRun", description = "False to remove; absent or true previews")
        public Boolean dryRun;
    }

    @Schema(name = "TagReductionDocument", description = "One document's redundant tags")
    private static class TagReductionDocument {
        @Schema(description = "Document ID")
        public String id;
        @Schema(description = "The tags removed from it, or that would be, shallowest first")
        public List<TagDeletedItem> tags;
    }

    @Schema(name = "TagReductionResult", description = "What a tag reduction run removed, or would remove")
    private static class TagReductionResult {
        @Schema(description = "Status OK")
        public String status;
        @Schema(description = "True when nothing was modified")
        public Boolean dryRun;
        @Schema(description = "Total number of tags removed, or that would be")
        public Integer count;
        @Schema(description = "The documents with something to remove; documents with nothing are absent")
        public List<TagReductionDocument> documents;
        @Schema(description = "IDs of selected documents left untouched because the caller cannot write "
                + "them, or they no longer exist")
        public List<String> skipped;
    }

    @Schema(name = "TagDeletionResult", description = "What a destructive tag maintenance action deleted")
    private static class TagDeletionResult {
        @Schema(description = "Status OK")
        public String status;
        @Schema(description = "Number of tags deleted")
        public Integer count;
        @Schema(description = "The tags that were deleted")
        public List<TagDeletedItem> tags;
        @Schema(description = "Tags the pre-delete re-check kept because they became used; empty when none")
        public List<TagDeletedItem> blocked;
    }
}
