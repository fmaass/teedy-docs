package com.sismics.docs.rest;

import com.sismics.util.filter.TokenBasedSecurityFilter;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Form;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
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

    /**
     * Test the vocabulary-entry usage-count endpoint (GET /vocabulary/:id/usage).
     * Counts the distinct active documents whose VOCABULARY metadata value equals the
     * entry's value, across every metadata definition referencing the entry's vocabulary.
     */
    @Test
    public void testVocabularyUsage() {
        // Login admin
        String adminToken = adminToken();

        // Login vocusage1 (non-admin)
        clientUtil.createUser("vocusage1");
        String vocusage1Token = clientUtil.login("vocusage1");

        // Create a dedicated vocabulary namespace with a value we will reference.
        JsonObject json = target().path("/vocabulary").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form()
                        .param("name", "usage-voc")
                        .param("value", "Referenced")
                        .param("order", "0")), JsonObject.class);
        String referencedEntryId = json.getString("id");

        // A second, unreferenced entry in the same namespace.
        json = target().path("/vocabulary").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form()
                        .param("name", "usage-voc")
                        .param("value", "Unused")
                        .param("order", "1")), JsonObject.class);
        String unusedEntryId = json.getString("id");

        // Two distinct VOCABULARY metadata definitions both referencing usage-voc.
        json = target().path("/metadata").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form()
                        .param("name", "UsageFieldA")
                        .param("type", "VOCABULARY")
                        .param("vocabulary", "usage-voc")), JsonObject.class);
        String metaAId = json.getString("id");

        json = target().path("/metadata").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form()
                        .param("name", "UsageFieldB")
                        .param("type", "VOCABULARY")
                        .param("vocabulary", "usage-voc")), JsonObject.class);
        String metaBId = json.getString("id");

        // Zero references yet: usage must be 0.
        json = target().path("/vocabulary/" + referencedEntryId + "/usage").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class);
        Assertions.assertEquals(0, json.getJsonNumber("document_count").intValue());

        // A non-admin user cannot read usage (admin-only endpoint).
        Response response = target().path("/vocabulary/" + referencedEntryId + "/usage").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, vocusage1Token)
                .get();
        Assertions.assertEquals(Status.FORBIDDEN.getStatusCode(), response.getStatus());

        // Unknown id -> 404.
        response = target().path("/vocabulary/does-not-exist/usage").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get();
        Assertions.assertEquals(Status.NOT_FOUND.getStatusCode(), response.getStatus());

        // Document 1 references the value via definition A.
        String doc1Id = createDocWithMetadata(adminToken, "usage-doc-1", metaAId, "Referenced");
        json = target().path("/vocabulary/" + referencedEntryId + "/usage").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class);
        Assertions.assertEquals(1, json.getJsonNumber("document_count").intValue());

        // Document 2 references the SAME value under BOTH definitions A and B. Distinct
        // semantics: the document counts once, not twice.
        MultivaluedMap<String, String> doc2Form = new MultivaluedHashMap<>();
        doc2Form.putSingle("title", "usage-doc-2");
        doc2Form.putSingle("language", "eng");
        doc2Form.add("metadata_id", metaAId);
        doc2Form.add("metadata_value", "Referenced");
        doc2Form.add("metadata_id", metaBId);
        doc2Form.add("metadata_value", "Referenced");
        json = target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(doc2Form), JsonObject.class);
        String doc2Id = json.getString("id");

        json = target().path("/vocabulary/" + referencedEntryId + "/usage").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class);
        Assertions.assertEquals(2, json.getJsonNumber("document_count").intValue());

        // The unused entry in the same namespace stays at 0 (value-scoped, not name-scoped).
        json = target().path("/vocabulary/" + unusedEntryId + "/usage").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class);
        Assertions.assertEquals(0, json.getJsonNumber("document_count").intValue());

        // Soft-delete document 1: its reference must drop out of the count (active docs only).
        target().path("/document/" + doc1Id).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete(JsonObject.class);
        json = target().path("/vocabulary/" + referencedEntryId + "/usage").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class);
        Assertions.assertEquals(1, json.getJsonNumber("document_count").intValue());

        // Clean up documents and definitions so shared-DB tests are unaffected.
        target().path("/document/" + doc2Id).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete(JsonObject.class);
        target().path("/metadata/" + metaAId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete(JsonObject.class);
        target().path("/metadata/" + metaBId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete(JsonObject.class);
    }

    /**
     * Create a document carrying a single custom metadata value.
     *
     * @param adminToken Admin auth token
     * @param title Document title
     * @param metadataId Metadata definition ID
     * @param value Metadata value
     * @return Created document ID
     */
    private String createDocWithMetadata(String adminToken, String title, String metadataId, String value) {
        JsonObject json = target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form()
                        .param("title", title)
                        .param("language", "eng")
                        .param("metadata_id", metadataId)
                        .param("metadata_value", value)), JsonObject.class);
        return json.getString("id");
    }
}
