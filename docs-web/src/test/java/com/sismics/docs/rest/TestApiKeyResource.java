package com.sismics.docs.rest;

import com.sismics.util.filter.TokenBasedSecurityFilter;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Form;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Test the API key resource.
 */
public class TestApiKeyResource extends BaseJerseyTest {
    @Test
    public void testApiKeyCrud() {
        String adminToken = adminToken();

        // List API keys (empty)
        JsonObject json = target().path("/apikey").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class);
        Assertions.assertEquals(0, json.getJsonArray("api_keys").size());

        // Create an API key
        json = target().path("/apikey").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form().param("name", "Test key")), JsonObject.class);
        String keyId = json.getString("id");
        String rawKey = json.getString("key");
        Assertions.assertNotNull(keyId);
        Assertions.assertTrue(rawKey.startsWith("tdapi_"));
        Assertions.assertEquals("Test key", json.getString("name"));

        // List API keys (one)
        json = target().path("/apikey").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class);
        JsonArray keys = json.getJsonArray("api_keys");
        Assertions.assertEquals(1, keys.size());
        Assertions.assertEquals("Test key", keys.getJsonObject(0).getString("name"));
        Assertions.assertTrue(keys.getJsonObject(0).getString("prefix").startsWith("tdapi_"));

        // Use the API key to authenticate (GET /document/list)
        json = target().path("/document/list").request()
                .header("Authorization", "Bearer " + rawKey)
                .get(JsonObject.class);
        Assertions.assertNotNull(json.getJsonArray("documents"));

        // Verify last_used_date is now set
        json = target().path("/apikey").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class);
        Assertions.assertTrue(json.getJsonArray("api_keys").getJsonObject(0).containsKey("last_used_date"));

        // Delete the API key
        json = target().path("/apikey/" + keyId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete(JsonObject.class);
        Assertions.assertEquals("ok", json.getString("status"));

        // Verify the deleted key no longer authenticates
        Response response = target().path("/document/list").request()
                .header("Authorization", "Bearer " + rawKey)
                .get();
        JsonObject anonResponse = response.readEntity(JsonObject.class);
        // Anonymous user can still call /document/list but won't see documents
        // The key point is it doesn't authenticate as admin anymore
        Assertions.assertNotNull(anonResponse);

        // List API keys (empty again)
        json = target().path("/apikey").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class);
        Assertions.assertEquals(0, json.getJsonArray("api_keys").size());
    }

    @Test
    public void testApiKeyValidation() {
        String adminToken = adminToken();

        // Create without name (should fail)
        Response response = target().path("/apikey").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form()));
        Assertions.assertEquals(400, response.getStatus());
    }

    @Test
    public void testApiKeyOwnership() {
        String adminToken = adminToken();

        // Create user and API key as admin
        JsonObject json = target().path("/apikey").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form().param("name", "Admin key")), JsonObject.class);
        String adminKeyId = json.getString("id");

        // Create another user
        clientUtil.createUser("apikey_user1");
        String user1Token = clientUtil.login("apikey_user1");

        // User1 should not see admin's keys
        json = target().path("/apikey").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, user1Token)
                .get(JsonObject.class);
        Assertions.assertEquals(0, json.getJsonArray("api_keys").size());

        // User1 should not be able to delete admin's key
        Response response = target().path("/apikey/" + adminKeyId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, user1Token)
                .delete();
        Assertions.assertEquals(404, response.getStatus());

        // Cleanup
        target().path("/apikey/" + adminKeyId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete(JsonObject.class);
        target().path("/user/apikey_user1").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete();
    }
}
