package com.sismics.docs.rest.resource;

import com.google.common.collect.Sets;
import com.sismics.docs.core.constant.AclType;
import com.sismics.docs.core.constant.PermType;
import com.sismics.docs.core.dao.AclDao;
import com.sismics.docs.core.dao.TagDao;
import com.sismics.docs.core.dao.criteria.TagCriteria;
import com.sismics.docs.core.dao.dto.TagCoOccurrence;
import com.sismics.docs.core.dao.dto.TagDto;
import com.sismics.docs.core.model.jpa.Acl;
import com.sismics.docs.core.model.jpa.Tag;
import com.sismics.docs.core.util.jpa.SortCriteria;
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
     * @apiError (client) IllegalTagName Spaces and colons are not allowed in tag name
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
                            + "IllegalTagName - Spaces and colons are not allowed in tag name; "
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
        name = ValidationUtil.validateLength(name, "name", 1, 36, false);
        ValidationUtil.validateHexColor(color, "color", true);
        ValidationUtil.validateTagName(name);

        // Check the parent
        if (StringUtils.isEmpty(parentId)) {
            parentId = null;
        } else {
            AclDao aclDao = new AclDao();
            if (!aclDao.checkPermission(parentId, PermType.READ, getTargetIdList(null))) {
                throw new ClientException("ParentNotFound", MessageFormat.format("Parent not found: {0}", parentId));
            }
        }

        // Create the tag
        TagDao tagDao = new TagDao();
        Tag tag = new Tag();
        tag.setName(name);
        tag.setColor(color);
        tag.setUserId(principal.getId());
        tag.setParentId(parentId);
        String id = tagDao.create(tag, principal.getId());

        // Create read ACL
        AclDao aclDao = new AclDao();
        Acl acl = new Acl();
        acl.setPerm(PermType.READ);
        acl.setType(AclType.USER);
        acl.setSourceId(id);
        acl.setTargetId(principal.getId());
        aclDao.create(acl, principal.getId());

        // Create write ACL
        acl = new Acl();
        acl.setPerm(PermType.WRITE);
        acl.setType(AclType.USER);
        acl.setSourceId(id);
        acl.setTargetId(principal.getId());
        aclDao.create(acl, principal.getId());
        
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
     * @apiError (client) IllegalTagName Spaces and colons are not allowed in tag name
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
                            + "IllegalTagName - Spaces and colons are not allowed in tag name; "
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
        name = ValidationUtil.validateLength(name, "name", 1, 36, true);
        ValidationUtil.validateHexColor(color, "color", true);
        ValidationUtil.validateTagName(name);

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
}
