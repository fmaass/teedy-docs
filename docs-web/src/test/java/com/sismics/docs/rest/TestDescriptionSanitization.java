package com.sismics.docs.rest;

import com.sismics.util.filter.TokenBasedSecurityFilter;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Form;

/**
 * End-to-end proof that a document description is sanitized SERVER-SIDE at the REST
 * boundary — a hostile payload submitted as a raw form parameter (bypassing the editor)
 * is stored inert, an allowed format survives, and the Lucene index sees only the
 * tag-stripped text (a word inside markup is findable; a stripped element name is not).
 */
public class TestDescriptionSanitization extends BaseJerseyTest {

    @Test
    public void hostileDescriptionIsSanitizedAtCreate() {
        clientUtil.createUser("sanitize_create");
        String token = clientUtil.login("sanitize_create");

        String hostile = "<p>invoicefindable</p><script>alert('xss')</script>"
                + "<img src=x onerror=\"alert(1)\"><a href=\"javascript:alert(2)\">bad</a>";

        JsonObject json = target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .put(Entity.form(new Form()
                        .param("title", "Sanitize create")
                        .param("description", hostile)
                        .param("language", "eng")), JsonObject.class);
        String documentId = json.getString("id");
        Assertions.assertNotNull(documentId);

        // Read the stored description back (authoritative read-back).
        JsonObject doc = target().path("/document/" + documentId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .get(JsonObject.class);
        String stored = doc.getString("description");

        // The legitimate paragraph survives; every hostile construct is gone.
        Assertions.assertTrue(stored.contains("invoicefindable"), stored);
        Assertions.assertFalse(stored.toLowerCase().contains("<script"), stored);
        Assertions.assertFalse(stored.contains("alert("), stored);
        Assertions.assertFalse(stored.toLowerCase().contains("onerror"), stored);
        Assertions.assertFalse(stored.toLowerCase().contains("<img"), stored);
        Assertions.assertFalse(stored.toLowerCase().contains("javascript:"), stored);

        // Lucene: the plain word inside markup is findable; the stripped "script" is not.
        Assertions.assertEquals(1, searchCount("invoicefindable", token), "word inside markup must be indexed");
        Assertions.assertEquals(0, searchCount("script", token), "a stripped element name must not be indexed");
    }

    @Test
    public void allowedFormattingSurvivesCreateAndUpdate() {
        clientUtil.createUser("sanitize_format");
        String token = clientUtil.login("sanitize_format");

        // Create with an allowed format (bold).
        JsonObject json = target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .put(Entity.form(new Form()
                        .param("title", "Sanitize format")
                        .param("description", "<p><strong>bold keeps</strong></p>")
                        .param("language", "eng")), JsonObject.class);
        String documentId = json.getString("id");

        JsonObject doc = target().path("/document/" + documentId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .get(JsonObject.class);
        Assertions.assertTrue(doc.getString("description").contains("<strong>"), doc.getString("description"));

        // Update with a code block containing a hostile-looking payload; the code text must
        // survive as inert entity-encoded content, never as executable markup.
        target().path("/document/" + documentId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .post(Entity.form(new Form()
                        .param("title", "Sanitize format")
                        .param("description", "<pre>&lt;script&gt;evilcode&lt;/script&gt;</pre>")
                        .param("language", "eng")), JsonObject.class);

        JsonObject updated = target().path("/document/" + documentId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .get(JsonObject.class);
        String stored = updated.getString("description");
        Assertions.assertTrue(stored.contains("<pre>"), stored);
        Assertions.assertTrue(stored.contains("evilcode"), stored);
        // No live script element made it through — the angle brackets stay encoded.
        Assertions.assertFalse(stored.contains("<script>evilcode"), stored);
    }

    @Test
    public void oversizedRawDescriptionIsRejected() {
        clientUtil.createUser("sanitize_oversize");
        String token = clientUtil.login("sanitize_oversize");

        // A raw payload beyond the 100000 pre-sanitization bound must be rejected (400).
        String huge = "<p>" + "x".repeat(100001) + "</p>";
        Assertions.assertEquals(400, target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .put(Entity.form(new Form()
                        .param("title", "Sanitize oversize")
                        .param("description", huge)
                        .param("language", "eng"))).getStatus());
    }

    private int searchCount(String query, String token) {
        JsonObject json = target().path("/document/list")
                .queryParam("search", query)
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .get(JsonObject.class);
        return json.getJsonArray("documents").size();
    }
}
