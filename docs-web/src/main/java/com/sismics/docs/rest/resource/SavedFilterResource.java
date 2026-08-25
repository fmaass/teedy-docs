package com.sismics.docs.rest.resource;

import com.sismics.docs.core.dao.SavedFilterDao;
import com.sismics.docs.core.dao.SavedFilterExistsException;
import com.sismics.docs.core.dao.dto.SavedFilterDto;
import com.sismics.docs.core.model.jpa.SavedFilter;
import com.sismics.docs.core.util.SavedFilterUtil;
import com.sismics.docs.rest.constant.BaseFunction;
import com.sismics.rest.exception.ClientException;
import com.sismics.rest.exception.ForbiddenClientException;
import com.sismics.rest.util.ValidationUtil;

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObjectBuilder;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Saved filter REST resource.
 *
 * <p>Document-list filters. The stored payload is the CANONICAL URL query string captured from the
 * documents route (the URL is the source of truth). A filter belongs to exactly one user, who may
 * PUBLISH it to the whole instance (#51); delete is a hard delete.
 *
 * <p>Publication draws the authorship/management line this resource enforces: any user may APPLY a
 * published filter, only its OWNER may edit, rename, delete, publish or withdraw it, and an
 * ADMINISTRATOR may additionally withdraw anyone's publication — governing what the instance is
 * shown, never what a filter says.
 */
@Path("/savedfilter")
public class SavedFilterResource extends BaseResource {
    /** The only query keys a saved filter may carry — the documents-route filter dimensions. */
    private static final Set<String> ALLOWED_KEYS = Set.of("tags", "exclude", "mode", "search", "workflow");

    /**
     * Lists the saved filters available to the current user, in two separate sections (#51):
     * {@code saved_filters} — the caller's OWN filters, each carrying whether the caller has
     * published it — and {@code shared_filters} — the filters OTHER users have published, each
     * naming its publisher and carrying {@code hidden_tag_count}, the number of tags it names that
     * the caller cannot read (0 = applicable). A filter with a non-zero count comes back with an
     * EMPTY query: the caller cannot apply it, so its criteria are not theirs to receive.
     *
     * @return Response
     */
    @GET
    public Response list() {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }

        SavedFilterDao dao = new SavedFilterDao();
        List<SavedFilter> filters = dao.getByUserId(principal.getId());

        JsonArrayBuilder array = Json.createArrayBuilder();
        for (SavedFilter filter : filters) {
            JsonObjectBuilder item = Json.createObjectBuilder()
                    .add("id", filter.getId())
                    .add("name", filter.getName())
                    .add("query", filter.getQuery())
                    .add("create_date", filter.getCreateDate().getTime())
                    .add("published", filter.getPublishDate() != null);
            if (filter.getPublishDate() == null) {
                item.addNull("publish_date");
            } else {
                item.add("publish_date", filter.getPublishDate().getTime());
            }
            array.add(item);
        }

        JsonArrayBuilder sharedArray = Json.createArrayBuilder();
        for (SavedFilterUtil.PublishedFilter published
                : SavedFilterUtil.listPublished(principal.getId(), getTargetIdList(null))) {
            SavedFilterDto filter = published.filter();
            sharedArray.add(Json.createObjectBuilder()
                    .add("id", filter.getId())
                    .add("name", filter.getName())
                    .add("query", filter.getQuery())
                    .add("username", filter.getUsername())
                    .add("create_date", filter.getCreateDate().getTime())
                    .add("publish_date", filter.getPublishDate().getTime())
                    .add("hidden_tag_count", published.hiddenTagCount()));
        }

        return Response.ok().entity(Json.createObjectBuilder()
                .add("saved_filters", array)
                .add("shared_filters", sharedArray).build()).build();
    }

    /**
     * Creates a saved filter for the current user.
     *
     * @param name Filter name (1-100 chars)
     * @param query Canonical URL query string (1-2000 chars; keys subset of the filter dimensions)
     * @return Response
     */
    @PUT
    public Response create(@FormParam("name") String name, @FormParam("query") String query) {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }

        name = ValidationUtil.validateLength(name, "name", 1, 100, false);
        query = ValidationUtil.validateLength(query, "query", 1, 2000, false);
        validateQueryString(query);

        SavedFilterDao dao = new SavedFilterDao();

        // Case-insensitive precheck: reject a duplicate name in the SAME request with a
        // friendly 400 (single-request UX). The DB unique index (exact-case) is the
        // concurrency backstop for a true race — a differently-cased duplicate under a
        // race is acceptable per the exact-case contract.
        for (SavedFilter existing : dao.getByUserId(principal.getId())) {
            if (existing.getName().equalsIgnoreCase(name)) {
                throw new ClientException("AlreadyExistingFilter",
                        "A saved filter with this name already exists");
            }
        }

        SavedFilter filter = new SavedFilter();
        filter.setUserId(principal.getId());
        filter.setName(name);
        filter.setQuery(query);

        String id;
        try {
            id = dao.create(filter);
        } catch (SavedFilterExistsException e) {
            // The unique index caught a concurrent duplicate the precheck raced past.
            throw new ClientException("AlreadyExistingFilter",
                    "A saved filter with this name already exists");
        }

        return Response.ok().entity(Json.createObjectBuilder()
                .add("id", id)
                .add("name", filter.getName())
                .add("query", filter.getQuery())
                .build()).build();
    }

    /**
     * Updates one of the current user's saved filters (rename and/or re-capture the query).
     *
     * <p>Teedy convention: {@code PUT} creates, {@code POST /{id}} updates. The create path's
     * validation is applied VERBATIM — an update that accepted an empty, overlong or
     * unsupported-key query would be a hole around the create contract, since a rename is the
     * natural way to smuggle one in. Only the name and the query are mutable; the owner, the id
     * and the create date are never touched (only those two values are passed on).</p>
     *
     * <p>The persistence conversation (ownership lookup, duplicate precheck, write) belongs to
     * {@link SavedFilterUtil#update} — the REST layer does not open new dependencies on the DAO
     * package. This method keeps validation, authentication and the HTTP status mapping.</p>
     *
     * @param id Saved filter ID
     * @param name New filter name (1-100 chars)
     * @param query New canonical URL query string (1-2000 chars; keys subset of the filter dimensions)
     * @return Response
     */
    @POST
    @Path("{id: [a-z0-9\\-]+}")
    public Response update(@PathParam("id") String id,
                           @FormParam("name") String name,
                           @FormParam("query") String query) {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }

        name = ValidationUtil.validateLength(name, "name", 1, 100, false);
        query = ValidationUtil.validateLength(query, "query", 1, 2000, false);
        validateQueryString(query);

        SavedFilter updated;
        try {
            updated = SavedFilterUtil.update(id, principal.getId(), name, query);
        } catch (SavedFilterExistsException e) {
            // Either the case-insensitive precheck or the DB unique index rejected the name.
            throw new ClientException("AlreadyExistingFilter",
                    "A saved filter with this name already exists");
        }
        if (updated == null) {
            // A foreign or unknown id yields 404 (never 403): the resource never confirms the
            // existence of another user's filter.
            throw new NotFoundException();
        }

        return Response.ok().entity(Json.createObjectBuilder()
                .add("id", updated.getId())
                .add("name", updated.getName())
                .add("query", updated.getQuery())
                .build()).build();
    }

    /**
     * Deletes one of the current user's saved filters.
     *
     * @param id Saved filter ID
     * @return Response
     */
    @DELETE
    @Path("{id: [a-z0-9\\-]+}")
    public Response delete(@PathParam("id") String id) {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }

        SavedFilterDao dao = new SavedFilterDao();
        // A foreign or unknown id yields 404 (never 403): the resource never confirms
        // the existence of another user's filter.
        if (!dao.delete(id, principal.getId())) {
            throw new NotFoundException();
        }

        return Response.ok().entity(Json.createObjectBuilder()
                .add("status", "ok").build()).build();
    }

    /**
     * Publishes one of the current user's saved filters to every user (#51).
     *
     * <p>Owner-only, because publishing is an act of AUTHORSHIP: a filter carries its owner's name
     * in the shared list, so letting anyone else put it there would attribute a decision to someone
     * who never made it. An administrator is no exception — they may withdraw a publication, not
     * create one. A foreign or unknown id therefore yields 404, exactly as the update path does:
     * this resource never confirms the existence of another user's filter.</p>
     *
     * <p>Publishing an already-published filter keeps the original publish date (the DAO's
     * contract): it is the same publication, so "shared since" must not reset.</p>
     *
     * @param id Saved filter ID
     * @return Response
     */
    @POST
    @Path("{id: [a-z0-9\\-]+}/publish")
    public Response publish(@PathParam("id") String id) {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }

        SavedFilter published = SavedFilterUtil.setPublished(id, principal.getId(), true);
        if (published == null) {
            throw new NotFoundException();
        }

        return Response.ok().entity(Json.createObjectBuilder()
                .add("status", "ok")
                .add("publish_date", published.getPublishDate().getTime())
                .build()).build();
    }

    /**
     * Withdraws a saved filter's publication (#51). The filter itself is untouched — it goes back
     * to being private to its owner.
     *
     * <p>Two callers may do this, for different reasons: the OWNER (it is theirs) and an
     * ADMINISTRATOR (governance — the reporter's "curator" who cleans up what the instance is
     * shown). An administrator explicitly may NOT edit, rename or delete it.</p>
     *
     * <p>The refusal codes are chosen so neither answer discloses anything the caller did not
     * already know. A PUBLISHED filter is in every user's shared list, so a plain user who tries to
     * withdraw one gets 403: they know it exists, they simply may not. An UNPUBLISHED filter that
     * is not theirs gets 404 — the same answer as an unknown id, so the route cannot be used to
     * probe for another user's private filters.</p>
     *
     * @param id Saved filter ID
     * @return Response
     */
    @DELETE
    @Path("{id: [a-z0-9\\-]+}/publish")
    public Response unpublish(@PathParam("id") String id) {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }

        SavedFilter filter = SavedFilterUtil.getById(id);
        boolean owner = filter != null && filter.getUserId().equals(principal.getId());
        boolean admin = hasBaseFunction(BaseFunction.ADMIN);
        if (filter == null || (!owner && !admin && filter.getPublishDate() == null)) {
            throw new NotFoundException();
        }
        if (!owner && !admin) {
            throw new ForbiddenClientException();
        }

        SavedFilterUtil.unpublish(id);

        return Response.ok().entity(Json.createObjectBuilder()
                .add("status", "ok").build()).build();
    }

    /**
     * Validates that the stored query is a parseable URL query string whose keys are a
     * subset of the allowed filter dimensions and where NO key repeats. vue-router yields
     * an ARRAY for a repeated key, but the frontend's initFromUrl assumes scalars — so a
     * repeated key is a malformed filter and is rejected (400).
     *
     * @param query Query string to validate
     * @throws ClientException if the query is malformed
     */
    private static void validateQueryString(String query) {
        Set<String> seen = new HashSet<>();
        for (String pair : query.split("&", -1)) {
            if (pair.isEmpty()) {
                // A leading/trailing/double '&' (empty pair) is malformed.
                throw new ClientException("ValidationError", "query contains an empty parameter");
            }
            int eq = pair.indexOf('=');
            String rawKey = eq >= 0 ? pair.substring(0, eq) : pair;
            String key;
            try {
                key = URLDecoder.decode(rawKey, StandardCharsets.UTF_8);
                // Decode the value too so a malformed %-escape is rejected consistently.
                if (eq >= 0) {
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                }
            } catch (IllegalArgumentException e) {
                throw new ClientException("ValidationError", "query is not a valid URL query string");
            }
            if (!ALLOWED_KEYS.contains(key)) {
                throw new ClientException("ValidationError", "query contains an unsupported parameter: " + key);
            }
            if (!seen.add(key)) {
                throw new ClientException("ValidationError", "query contains a repeated parameter: " + key);
            }
        }
    }
}
