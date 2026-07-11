package com.sismics.docs.rest.resource;

import com.sismics.docs.core.dao.SavedFilterDao;
import com.sismics.docs.core.dao.SavedFilterExistsException;
import com.sismics.docs.core.model.jpa.SavedFilter;
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
 * <p>Per-user document-list filters. The stored payload is the CANONICAL URL query
 * string captured from the documents route (the URL is the source of truth). Filters
 * are never shared between users; delete is a hard delete.
 */
@Path("/savedfilter")
public class SavedFilterResource extends BaseResource {
    /** The only query keys a saved filter may carry — the documents-route filter dimensions. */
    private static final Set<String> ALLOWED_KEYS = Set.of("tags", "exclude", "mode", "search", "workflow");

    /**
     * Lists the current user's saved filters (ordered by name).
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
            array.add(Json.createObjectBuilder()
                    .add("id", filter.getId())
                    .add("name", filter.getName())
                    .add("query", filter.getQuery())
                    .add("create_date", filter.getCreateDate().getTime()));
        }

        return Response.ok().entity(Json.createObjectBuilder()
                .add("saved_filters", array).build()).build();
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
