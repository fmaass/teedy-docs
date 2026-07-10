package com.sismics.docs.rest;

import com.sismics.util.filter.TokenBasedSecurityFilter;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Form;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;

/**
 * Exhaustive test of the vocabulary resource.
 *
 * @author bgamard
 */
public class TestVocabularyResource extends BaseJerseyTest {
    /**
     * Test the vocabulary resource.
     */
    @Test
    public void testVocabularyResource() {
        // Login vocabulary1 (non-admin)
        clientUtil.createUser("vocabulary1");
        String vocabulary1Token = clientUtil.login("vocabulary1");

        // Login admin
        String adminToken = adminToken();

        // A non-admin user can read seeded vocabularies (document dropdowns depend on this)
        JsonObject json = target().path("/vocabulary/type").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, vocabulary1Token)
                .get(JsonObject.class);
        Assertions.assertEquals(12, json.getJsonArray("entries").size());
        JsonObject entry = json.getJsonArray("entries").getJsonObject(0);
        Assertions.assertEquals("type-collection", entry.getString("id"));
        Assertions.assertEquals("type", entry.getString("name"));
        Assertions.assertEquals("Collection", entry.getString("value"));
        Assertions.assertEquals(0, entry.getJsonNumber("order").intValue());

        // The full 270-row seed is readable: coverage has 249 entries, ordered
        json = target().path("/vocabulary/coverage").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, vocabulary1Token)
                .get(JsonObject.class);
        Assertions.assertEquals(249, json.getJsonArray("entries").size());
        entry = json.getJsonArray("entries").getJsonObject(0);
        Assertions.assertEquals("coverage", entry.getString("name"));
        Assertions.assertEquals(0, entry.getJsonNumber("order").intValue());

        // Admin creates a vocabulary entry
        json = target().path("/vocabulary").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form()
                        .param("name", "test-voc-1")
                        .param("value", "First value")
                        .param("order", "0")), JsonObject.class);
        String vocabulary1Id = json.getString("id");
        Assertions.assertNotNull(vocabulary1Id);
        Assertions.assertEquals("test-voc-1", json.getString("name"));
        Assertions.assertEquals("First value", json.getString("value"));
        Assertions.assertEquals(0, json.getJsonNumber("order").intValue());

        // Invalid name is rejected
        Response response = target().path("/vocabulary").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form()
                        .param("name", "NOT_VALID")
                        .param("value", "First value")
                        .param("order", "0")));
        Assertions.assertEquals(Status.BAD_REQUEST.getStatusCode(), response.getStatus());

        // A non-admin user cannot create a vocabulary entry
        response = target().path("/vocabulary").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, vocabulary1Token)
                .put(Entity.form(new Form()
                        .param("name", "test-voc-forbidden")
                        .param("value", "Nope")
                        .param("order", "0")));
        Assertions.assertEquals(Status.FORBIDDEN.getStatusCode(), response.getStatus());

        // The non-admin user reads the created entry
        json = target().path("/vocabulary/test-voc-1").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, vocabulary1Token)
                .get(JsonObject.class);
        Assertions.assertEquals(1, json.getJsonArray("entries").size());
        entry = json.getJsonArray("entries").getJsonObject(0);
        Assertions.assertEquals(vocabulary1Id, entry.getString("id"));
        Assertions.assertEquals("First value", entry.getString("value"));
        Assertions.assertEquals(0, entry.getJsonNumber("order").intValue());

        // Admin updates the entry
        json = target().path("/vocabulary/" + vocabulary1Id).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form()
                        .param("name", "test-voc-1-updated")
                        .param("value", "First value updated")
                        .param("order", "1")), JsonObject.class);
        Assertions.assertEquals(vocabulary1Id, json.getString("id"));
        Assertions.assertEquals("test-voc-1-updated", json.getString("name"));
        Assertions.assertEquals("First value updated", json.getString("value"));
        Assertions.assertEquals(1, json.getJsonNumber("order").intValue());

        // A non-admin user cannot update a vocabulary entry
        response = target().path("/vocabulary/" + vocabulary1Id).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, vocabulary1Token)
                .post(Entity.form(new Form()
                        .param("value", "Hacked")));
        Assertions.assertEquals(Status.FORBIDDEN.getStatusCode(), response.getStatus());

        // The update is visible
        json = target().path("/vocabulary/test-voc-1-updated").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, vocabulary1Token)
                .get(JsonObject.class);
        Assertions.assertEquals(1, json.getJsonArray("entries").size());
        entry = json.getJsonArray("entries").getJsonObject(0);
        Assertions.assertEquals(vocabulary1Id, entry.getString("id"));
        Assertions.assertEquals("First value updated", entry.getString("value"));
        Assertions.assertEquals(1, entry.getJsonNumber("order").intValue());

        // A non-admin user cannot delete a vocabulary entry
        response = target().path("/vocabulary/" + vocabulary1Id).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, vocabulary1Token)
                .delete();
        Assertions.assertEquals(Status.FORBIDDEN.getStatusCode(), response.getStatus());

        // Admin deletes the entry
        target().path("/vocabulary/" + vocabulary1Id).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete(JsonObject.class);

        // The entry is gone
        json = target().path("/vocabulary/test-voc-1-updated").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, vocabulary1Token)
                .get(JsonObject.class);
        Assertions.assertEquals(0, json.getJsonArray("entries").size());
    }
}
