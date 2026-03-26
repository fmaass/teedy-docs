package com.sismics.docs.rest.resource;

import com.sismics.docs.core.dao.TagDao;
import com.sismics.docs.core.dao.TagMatchRuleDao;
import com.sismics.docs.core.model.jpa.Tag;
import com.sismics.docs.core.model.jpa.TagMatchRule;
import com.sismics.docs.rest.constant.BaseFunction;
import com.sismics.rest.exception.ClientException;
import com.sismics.rest.exception.ForbiddenClientException;
import com.sismics.rest.util.ValidationUtil;

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObjectBuilder;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Tag match rule REST resources.
 *
 * @author fmaass
 */
@Path("/tagmatchrule")
public class TagMatchRuleResource extends BaseResource {
    private static final Set<String> VALID_RULE_TYPES = Set.of("TITLE_REGEX", "FILENAME_REGEX", "CONTENT_REGEX");

    @GET
    public Response list() {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }
        checkBaseFunction(BaseFunction.ADMIN);

        TagMatchRuleDao dao = new TagMatchRuleDao();
        List<TagMatchRule> rules = dao.findAll();

        JsonArrayBuilder rulesArray = Json.createArrayBuilder();
        for (TagMatchRule rule : rules) {
            rulesArray.add(Json.createObjectBuilder()
                    .add("id", rule.getId())
                    .add("tag_id", rule.getTagId())
                    .add("rule_type", rule.getRuleType())
                    .add("pattern", rule.getPattern())
                    .add("order", rule.getOrder())
                    .add("enabled", rule.isEnabled())
                    .add("create_date", rule.getCreateDate().getTime()));
        }

        return Response.ok().entity(Json.createObjectBuilder()
                .add("rules", rulesArray).build()).build();
    }

    @PUT
    public Response add(
            @FormParam("tag_id") String tagId,
            @FormParam("rule_type") String ruleType,
            @FormParam("pattern") String pattern,
            @FormParam("order") String orderStr,
            @FormParam("enabled") String enabledStr) {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }
        checkBaseFunction(BaseFunction.ADMIN);

        tagId = ValidationUtil.validateLength(tagId, "tag_id", 1, 36, false);
        ruleType = ValidationUtil.validateLength(ruleType, "rule_type", 1, 20, false);
        pattern = ValidationUtil.validateLength(pattern, "pattern", 1, 2000, false);

        Tag tag = new TagDao().getById(tagId);
        if (tag == null) {
            throw new ClientException("TagNotFound", "Tag not found: " + tagId);
        }
        if (!VALID_RULE_TYPES.contains(ruleType)) {
            throw new ClientException("ValidationError", "rule_type must be one of: " + VALID_RULE_TYPES);
        }
        validateRegex(pattern);

        int order = orderStr != null ? ValidationUtil.validateInteger(orderStr, "order") : 0;
        boolean enabled = enabledStr == null || Boolean.parseBoolean(enabledStr);

        TagMatchRuleDao dao = new TagMatchRuleDao();
        TagMatchRule rule = new TagMatchRule();
        rule.setTagId(tagId);
        rule.setRuleType(ruleType);
        rule.setPattern(pattern);
        rule.setOrder(order);
        rule.setEnabled(enabled);
        String id = dao.create(rule);

        return Response.ok().entity(Json.createObjectBuilder()
                .add("id", id).build()).build();
    }

    @POST
    @Path("{id: [a-z0-9\\-]+}")
    public Response update(
            @PathParam("id") String id,
            @FormParam("tag_id") String tagId,
            @FormParam("rule_type") String ruleType,
            @FormParam("pattern") String pattern,
            @FormParam("order") String orderStr,
            @FormParam("enabled") String enabledStr) {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }
        checkBaseFunction(BaseFunction.ADMIN);

        TagMatchRuleDao dao = new TagMatchRuleDao();
        TagMatchRule rule = dao.getActiveById(id);
        if (rule == null) {
            throw new NotFoundException();
        }

        if (tagId != null) {
            tagId = ValidationUtil.validateLength(tagId, "tag_id", 1, 36, false);
            Tag tag = new TagDao().getById(tagId);
            if (tag == null) {
                throw new ClientException("TagNotFound", "Tag not found: " + tagId);
            }
            rule.setTagId(tagId);
        }
        if (ruleType != null) {
            if (!VALID_RULE_TYPES.contains(ruleType)) {
                throw new ClientException("ValidationError", "rule_type must be one of: " + VALID_RULE_TYPES);
            }
            rule.setRuleType(ruleType);
        }
        if (pattern != null) {
            pattern = ValidationUtil.validateLength(pattern, "pattern", 1, 2000, false);
            validateRegex(pattern);
            rule.setPattern(pattern);
        }
        if (orderStr != null) {
            rule.setOrder(ValidationUtil.validateInteger(orderStr, "order"));
        }
        if (enabledStr != null) {
            rule.setEnabled(Boolean.parseBoolean(enabledStr));
        }

        dao.update(rule);

        return Response.ok().entity(Json.createObjectBuilder()
                .add("status", "ok").build()).build();
    }

    @DELETE
    @Path("{id: [a-z0-9\\-]+}")
    public Response delete(@PathParam("id") String id) {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }
        checkBaseFunction(BaseFunction.ADMIN);

        TagMatchRuleDao dao = new TagMatchRuleDao();
        TagMatchRule rule = dao.getActiveById(id);
        if (rule == null) {
            throw new NotFoundException();
        }

        dao.delete(id);

        return Response.ok().entity(Json.createObjectBuilder()
                .add("status", "ok").build()).build();
    }

    @POST
    @Path("test")
    public Response test(
            @FormParam("pattern") String pattern,
            @FormParam("text") String text) {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }
        checkBaseFunction(BaseFunction.ADMIN);

        pattern = ValidationUtil.validateLength(pattern, "pattern", 1, 2000, false);
        text = ValidationUtil.validateLength(text, "text", 1, 10000, false);
        validateRegex(pattern);

        boolean matches = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(text).find();

        return Response.ok().entity(Json.createObjectBuilder()
                .add("matches", matches).build()).build();
    }

    private void validateRegex(String pattern) {
        try {
            Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            throw new ClientException("ValidationError", "Invalid regex pattern: " + e.getDescription());
        }
    }
}
