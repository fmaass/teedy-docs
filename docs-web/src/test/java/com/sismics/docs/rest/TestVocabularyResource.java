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
        // Login vocabulary1
        clientUtil.createUser("vocabulary1");
        String vocabulary1Token = clientUtil.login("vocabulary1");
        
        // Login admin
        String adminToken = adminToken();
        
        // Get coverage vocabularies entries
        JsonObject json = target().path("/vocabulary/coverage").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, vocabulary1Token)
                .get(JsonObject.class);
        Assertions.assertEquals(249, json.getJsonArray("entries").size());
        JsonObject entry = json.getJsonArray("entries").getJsonObject(0);
        Assertions.assertEquals("coverage-afg", entry.getString("id"));
        Assertions.assertEquals("coverage", entry.getString("name"));
        Assertions.assertEquals("Afghanistan", entry.getString("value"));
        Assertions.assertEquals(0, entry.getJsonNumber("order").intValue());
        entry = json.getJsonArray("entries").getJsonObject(248);
        Assertions.assertEquals("coverage-zwe", entry.getString("id"));
        Assertions.assertEquals("coverage", entry.getString("name"));
        Assertions.assertEquals("Zimbabwe", entry.getString("value"));
        Assertions.assertEquals(248, entry.getJsonNumber("order").intValue());
        
        // Create a vocabulary entry with admin
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
        
        // Create a vocabulary entry with admin
        Response response = target().path("/vocabulary").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form()
                        .param("name", "NOT_VALID")
                        .param("value", "First value")
                        .param("order", "0")));
        Assertions.assertEquals(Status.BAD_REQUEST.getStatusCode(), response.getStatus());
        
        // Get test-voc-1 vocabularies entries
        json = target().path("/vocabulary/test-voc-1").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, vocabulary1Token)
                .get(JsonObject.class);
        Assertions.assertEquals(1, json.getJsonArray("entries").size());
        entry = json.getJsonArray("entries").getJsonObject(0);
        Assertions.assertEquals(vocabulary1Id, entry.getString("id"));
        Assertions.assertEquals("First value", entry.getString("value"));
        Assertions.assertEquals(0, entry.getJsonNumber("order").intValue());
        
        // Update a vocabulary entry with admin
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
        
        // Get test-voc-1-updated vocabularies entries
        json = target().path("/vocabulary/test-voc-1-updated").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, vocabulary1Token)
                .get(JsonObject.class);
        Assertions.assertEquals(1, json.getJsonArray("entries").size());
        entry = json.getJsonArray("entries").getJsonObject(0);
        Assertions.assertEquals(vocabulary1Id, entry.getString("id"));
        Assertions.assertEquals("First value updated", entry.getString("value"));
        Assertions.assertEquals(1, entry.getJsonNumber("order").intValue());
        
        // Delete a vocabulary entry with admin
        target().path("/vocabulary/" + vocabulary1Id).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete(JsonObject.class);
        
        // Get test-voc-1-updated vocabularies entries
        json = target().path("/vocabulary/test-voc-1-updated").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, vocabulary1Token)
                .get(JsonObject.class);
        Assertions.assertEquals(0, json.getJsonArray("entries").size());
    }
}
