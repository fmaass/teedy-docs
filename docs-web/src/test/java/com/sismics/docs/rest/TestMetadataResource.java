package com.sismics.docs.rest;

import com.sismics.util.filter.TokenBasedSecurityFilter;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Form;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;


/**
 * Test the metadata resource.
 *
 * @author bgamard
 */
public class TestMetadataResource extends BaseJerseyTest {
    /**
     * Test the metadata resource.
     */
    @Test
    public void testMetadataResource() {
        // Login admin
        String adminToken = adminToken();

        // Get all metadata with admin
        JsonObject json = target().path("/metadata")
                .queryParam("sort_column", "2")
                .queryParam("asc", "false")
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class);
        JsonArray metadata = json.getJsonArray("metadata");
        Assertions.assertEquals(0, metadata.size());

        // Create a metadata with admin
        json = target().path("/metadata").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form()
                        .param("name", "ISBN 13")
                        .param("type", "STRING")), JsonObject.class);
        String metadataIsbnId = json.getString("id");
        Assertions.assertNotNull(metadataIsbnId);
        Assertions.assertEquals("ISBN 13", json.getString("name"));
        Assertions.assertEquals("STRING", json.getString("type"));

        // Get all metadata with admin
        json = target().path("/metadata")
                .queryParam("sort_column", "2")
                .queryParam("asc", "false")
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class);
        metadata = json.getJsonArray("metadata");
        Assertions.assertEquals(1, metadata.size());

        // Update a metadata with admin
        json = target().path("/metadata/" + metadataIsbnId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form()
                        .param("name", "ISBN 10")), JsonObject.class);
        Assertions.assertEquals(metadataIsbnId, json.getString("id"));
        Assertions.assertEquals("ISBN 10", json.getString("name"));
        Assertions.assertEquals("STRING", json.getString("type"));

        // Delete a metadata with admin
        target().path("/metadata/" + metadataIsbnId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete(JsonObject.class);

        // Get all metadata with admin
        json = target().path("/metadata")
                .queryParam("sort_column", "2")
                .queryParam("asc", "false")
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class);
        metadata = json.getJsonArray("metadata");
        Assertions.assertEquals(0, metadata.size());
    }

    /**
     * Test VOCABULARY-typed metadata and the vocabulary-name list endpoint.
     */
    @Test
    public void testVocabularyMetadata() {
        // Login admin
        String adminToken = adminToken();

        // Login metavoc1 (non-admin)
        clientUtil.createUser("metavoc1");
        String metavoc1Token = clientUtil.login("metavoc1");

        // GET /vocabulary lists the distinct seeded names (admin-only)
        JsonObject json = target().path("/vocabulary").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class);
        JsonArray names = json.getJsonArray("names");
        Assertions.assertTrue(names.size() >= 3);
        boolean hasType = false;
        for (int i = 0; i < names.size(); i++) {
            if ("type".equals(names.getString(i))) {
                hasType = true;
            }
        }
        Assertions.assertTrue(hasType);

        // A non-admin user cannot list vocabulary names
        Response response = target().path("/vocabulary").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, metavoc1Token)
                .get();
        Assertions.assertEquals(Status.FORBIDDEN.getStatusCode(), response.getStatus());

        // Create a VOCABULARY metadata referencing a real vocabulary ('type')
        json = target().path("/metadata").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form()
                        .param("name", "DocKind")
                        .param("type", "VOCABULARY")
                        .param("vocabulary", "type")), JsonObject.class);
        Assertions.assertEquals("VOCABULARY", json.getString("type"));
        Assertions.assertEquals("type", json.getString("vocabulary"));
        String docKindId = json.getString("id");

        // Renaming a VOCABULARY field WITHOUT resending the vocabulary must succeed and
        // preserve the stored vocabulary (a plain rename must not 400 nor drop it).
        json = target().path("/metadata/" + docKindId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form()
                        .param("name", "DocKindRenamed")), JsonObject.class);
        Assertions.assertEquals("DocKindRenamed", json.getString("name"));
        Assertions.assertEquals("VOCABULARY", json.getString("type"));
        Assertions.assertEquals("type", json.getString("vocabulary"));

        // The referenced vocabulary can be changed by supplying a new (existing) name.
        json = target().path("/metadata/" + docKindId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form()
                        .param("name", "DocKindRenamed")
                        .param("vocabulary", "coverage")), JsonObject.class);
        Assertions.assertEquals("coverage", json.getString("vocabulary"));

        // Changing it to an UNKNOWN vocabulary is rejected.
        response = target().path("/metadata/" + docKindId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form()
                        .param("name", "DocKindRenamed")
                        .param("vocabulary", "does-not-exist")));
        Assertions.assertEquals(Status.BAD_REQUEST.getStatusCode(), response.getStatus());

        // Creating a VOCABULARY metadata referencing an UNKNOWN vocabulary is rejected
        response = target().path("/metadata").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form()
                        .param("name", "BadKind")
                        .param("type", "VOCABULARY")
                        .param("vocabulary", "does-not-exist")));
        Assertions.assertEquals(Status.BAD_REQUEST.getStatusCode(), response.getStatus());

        // A VOCABULARY metadata with no vocabulary name is rejected
        response = target().path("/metadata").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form()
                        .param("name", "NoVoc")
                        .param("type", "VOCABULARY")));
        Assertions.assertEquals(Status.BAD_REQUEST.getStatusCode(), response.getStatus());

        // Clean up: addMetadata() returns EVERY defined field, so a leaked definition
        // would inflate the metadata-array size other shared-DB tests assert on
        // (e.g. TestDocumentResource.testCustomMetadata). Only DocKind was persisted;
        // the BadKind/NoVoc creations were rejected and left nothing.
        target().path("/metadata/" + docKindId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete(JsonObject.class);
    }
}
