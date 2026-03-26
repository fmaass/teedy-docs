package com.sismics.docs.rest;

import com.sismics.util.filter.TokenBasedSecurityFilter;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Form;


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
}
