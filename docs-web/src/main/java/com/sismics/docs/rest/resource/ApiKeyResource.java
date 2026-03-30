package com.sismics.docs.rest.resource;

import com.sismics.docs.core.dao.ApiKeyDao;
import com.sismics.docs.core.model.jpa.ApiKey;
import com.sismics.rest.exception.ClientException;
import com.sismics.rest.exception.ForbiddenClientException;
import com.sismics.util.filter.ApiKeyBasedSecurityFilter;

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObjectBuilder;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;

/**
 * API key REST resource.
 */
@Path("/apikey")
public class ApiKeyResource extends BaseResource {
    private static final String API_KEY_PREFIX = "tdapi_";
    private static final int KEY_RANDOM_BYTES = 16;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /**
     * Lists the current user's API keys.
     *
     * @return Response
     */
    @GET
    public Response list() {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }

        ApiKeyDao apiKeyDao = new ApiKeyDao();
        List<ApiKey> keys = apiKeyDao.getByUserId(principal.getId());

        JsonArrayBuilder array = Json.createArrayBuilder();
        for (ApiKey key : keys) {
            JsonObjectBuilder obj = Json.createObjectBuilder()
                    .add("id", key.getId())
                    .add("name", key.getName())
                    .add("prefix", key.getPrefix())
                    .add("create_date", key.getCreateDate().getTime());
            if (key.getLastUsedDate() != null) {
                obj.add("last_used_date", key.getLastUsedDate().getTime());
            }
            array.add(obj);
        }

        return Response.ok().entity(Json.createObjectBuilder()
                .add("api_keys", array).build()).build();
    }

    /**
     * Creates a new API key. The raw key is returned only once.
     *
     * @param name Key name
     * @return Response with the full key
     */
    @PUT
    public Response create(@FormParam("name") String name) {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }

        if (name == null || name.isBlank() || name.length() > 100) {
            throw new ClientException("ValidationError", "name is required and must be <= 100 characters");
        }

        String rawKey = generateToken();
        String hash = ApiKeyBasedSecurityFilter.sha256Hex(rawKey);
        String prefix = rawKey.substring(0, Math.min(rawKey.length(), 12));

        ApiKey apiKey = new ApiKey();
        apiKey.setUserId(principal.getId());
        apiKey.setName(name.trim());
        apiKey.setKeyHash(hash);
        apiKey.setPrefix(prefix);

        ApiKeyDao apiKeyDao = new ApiKeyDao();
        String id = apiKeyDao.create(apiKey);

        return Response.ok().entity(Json.createObjectBuilder()
                .add("id", id)
                .add("name", apiKey.getName())
                .add("key", rawKey)
                .build()).build();
    }

    /**
     * Deletes an API key.
     *
     * @param id API key ID
     * @return Response
     */
    @DELETE
    @Path("{id: [a-z0-9\\-]+}")
    public Response delete(@PathParam("id") String id) {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }

        ApiKeyDao apiKeyDao = new ApiKeyDao();
        if (!apiKeyDao.delete(id, principal.getId())) {
            throw new NotFoundException();
        }

        return Response.ok().entity(Json.createObjectBuilder()
                .add("status", "ok").build()).build();
    }

    private static String generateToken() {
        byte[] bytes = new byte[KEY_RANDOM_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return API_KEY_PREFIX + HexFormat.of().formatHex(bytes);
    }
}
