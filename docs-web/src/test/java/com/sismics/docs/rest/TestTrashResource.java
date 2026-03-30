package com.sismics.docs.rest;

import com.sismics.util.filter.TokenBasedSecurityFilter;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Form;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Date;

/**
 * Test trash (recycle bin) functionality.
 */
public class TestTrashResource extends BaseJerseyTest {
    @Test
    public void testTrashLifecycle() {
        String adminToken = adminToken();

        // Create a document
        JsonObject json = target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form()
                        .param("title", "Trash test doc")
                        .param("language", "eng")
                        .param("create_date", Long.toString(new Date().getTime()))), JsonObject.class);
        String docId = json.getString("id");

        // Verify document is visible
        Response response = target().path("/document/" + docId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get();
        Assertions.assertEquals(200, response.getStatus());

        // Trash is initially empty
        json = target().path("/document/trash").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class);
        Assertions.assertEquals(0, json.getInt("total"));

        // Delete (trash) the document
        json = target().path("/document/" + docId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete(JsonObject.class);
        Assertions.assertEquals("ok", json.getString("status"));

        // Document is no longer visible via GET
        response = target().path("/document/" + docId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get();
        Assertions.assertEquals(404, response.getStatus());

        // Document appears in trash
        json = target().path("/document/trash").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class);
        Assertions.assertEquals(1, json.getInt("total"));
        JsonArray trashDocs = json.getJsonArray("documents");
        Assertions.assertEquals(docId, trashDocs.getJsonObject(0).getString("id"));
        Assertions.assertEquals("Trash test doc", trashDocs.getJsonObject(0).getString("title"));
        Assertions.assertTrue(trashDocs.getJsonObject(0).containsKey("delete_date"));

        // Restore the document
        json = target().path("/document/" + docId + "/restore").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form()), JsonObject.class);
        Assertions.assertEquals("ok", json.getString("status"));

        // Document is visible again
        response = target().path("/document/" + docId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get();
        Assertions.assertEquals(200, response.getStatus());

        // Trash is empty again
        json = target().path("/document/trash").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class);
        Assertions.assertEquals(0, json.getInt("total"));

        // Trash again, then permanently delete
        target().path("/document/" + docId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete(JsonObject.class);

        json = target().path("/document/" + docId + "/permanent").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete(JsonObject.class);
        Assertions.assertEquals("ok", json.getString("status"));

        // Trash is empty
        json = target().path("/document/trash").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class);
        Assertions.assertEquals(0, json.getInt("total"));

        // Restore of permanently deleted doc should fail
        response = target().path("/document/" + docId + "/restore").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form()));
        Assertions.assertEquals(404, response.getStatus());
    }

    @Test
    public void testEmptyTrash() {
        String adminToken = adminToken();

        // Create and trash 3 documents
        for (int i = 0; i < 3; i++) {
            JsonObject json = target().path("/document").request()
                    .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                    .put(Entity.form(new Form()
                            .param("title", "Empty trash doc " + i)
                            .param("language", "eng")
                            .param("create_date", Long.toString(new Date().getTime()))), JsonObject.class);
            target().path("/document/" + json.getString("id")).request()
                    .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                    .delete(JsonObject.class);
        }

        // Verify 3 in trash
        JsonObject json = target().path("/document/trash").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class);
        Assertions.assertEquals(3, json.getInt("total"));

        // Empty trash
        json = target().path("/document/trash").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete(JsonObject.class);
        Assertions.assertEquals(3, json.getInt("deleted_count"));

        // Trash is empty
        json = target().path("/document/trash").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class);
        Assertions.assertEquals(0, json.getInt("total"));
    }

    @Test
    public void testTrashOwnership() {
        String adminToken = adminToken();

        // Create user
        clientUtil.createUser("trash_user1");
        String user1Token = clientUtil.login("trash_user1");

        // Admin creates and trashes a document
        JsonObject json = target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form()
                        .param("title", "Admin doc")
                        .param("language", "eng")
                        .param("create_date", Long.toString(new Date().getTime()))), JsonObject.class);
        String adminDocId = json.getString("id");

        target().path("/document/" + adminDocId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete(JsonObject.class);

        // User1 cannot restore admin's trashed doc
        Response response = target().path("/document/" + adminDocId + "/restore").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, user1Token)
                .post(Entity.form(new Form()));
        Assertions.assertEquals(404, response.getStatus());

        // User1 cannot permanently delete admin's trashed doc
        response = target().path("/document/" + adminDocId + "/permanent").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, user1Token)
                .delete();
        Assertions.assertEquals(404, response.getStatus());

        // Cleanup
        target().path("/document/" + adminDocId + "/permanent").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete(JsonObject.class);
        target().path("/user/trash_user1").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete();
    }
}
