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
import java.util.Date;

/**
 * Test the audit log resource.
 * 
 * @author bgamard
 */
public class TestAuditLogResource extends BaseJerseyTest {
    /**
     * Test the audit log resource.
     *
     * @throws Exception e
     */
    @Test
    public void testAuditLogResource() throws Exception {
        // Login auditlog1
        clientUtil.createUser("auditlog1");
        String auditlog1Token = clientUtil.login("auditlog1");
        
        // Create a tag
        JsonObject json = target().path("/tag").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, auditlog1Token)
                .put(Entity.form(new Form()
                        .param("name", "SuperTag")
                        .param("color", "#ffff00")), JsonObject.class);
        String tag1Id = json.getString("id");
        Assertions.assertNotNull(tag1Id);

        // Create a document
        long create1Date = new Date().getTime();
        json = target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, auditlog1Token)
                .put(Entity.form(new Form()
                        .param("title", "My super title document 1")
                        .param("description", "My super description for document 1")
                        .param("tags", tag1Id)
                        .param("language", "eng")
                        .param("create_date", Long.toString(create1Date))), JsonObject.class);
        String document1Id = json.getString("id");
        Assertions.assertNotNull(document1Id);
        
        // Get all logs for the document
        json = target().path("/auditlog")
                .queryParam("document", document1Id)
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, auditlog1Token)
                .get(JsonObject.class);
        JsonArray logs = json.getJsonArray("logs");
        Assertions.assertEquals(3, logs.size());
        Assertions.assertEquals(countByClass(logs, "Document"), 1);
        Assertions.assertEquals(countByClass(logs, "Acl"), 2);
        Assertions.assertEquals("auditlog1", logs.getJsonObject(0).getString("username"));
        Assertions.assertNotNull(logs.getJsonObject(0).getString("id"));
        Assertions.assertNotNull(logs.getJsonObject(0).getString("target"));
        Assertions.assertNotNull(logs.getJsonObject(0).getString("type"));
        Assertions.assertNotNull(logs.getJsonObject(0).getString("message"));
        Assertions.assertNotNull(logs.getJsonObject(0).getJsonNumber("create_date"));
        Assertions.assertEquals("auditlog1", logs.getJsonObject(1).getString("username"));
        Assertions.assertEquals("auditlog1", logs.getJsonObject(2).getString("username"));
        
        // Get all logs for the current user
        json = target().path("/auditlog").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, auditlog1Token)
                .get(JsonObject.class);
        logs = json.getJsonArray("logs");
        Assertions.assertEquals(2, logs.size());
        Assertions.assertEquals(countByClass(logs, "Document"), 1);
        Assertions.assertEquals(countByClass(logs, "Tag"), 1);
        
        // Deletes a tag
        json = target().path("/tag/" + tag1Id).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, auditlog1Token)
                .delete(JsonObject.class);
        Assertions.assertEquals("ok", json.getString("status"));
        
        // Get all logs for the current user
        json = target().path("/auditlog").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, auditlog1Token)
                .get(JsonObject.class);
        logs = json.getJsonArray("logs");
        Assertions.assertEquals(3, logs.size());
        Assertions.assertEquals(countByClass(logs, "Document"), 1);
        Assertions.assertEquals(countByClass(logs, "Tag"), 2);

        // Get document 1
        json = target().path("/document/" + document1Id).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, auditlog1Token)
                .get(JsonObject.class);
        long update1Date = json.getJsonNumber("update_date").longValue();

        // Add a file to the document
        clientUtil.addFileToDocument(FILE_WIKIPEDIA_PDF, auditlog1Token, document1Id);

        // Get document 1
        json = target().path("/document/" + document1Id).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, auditlog1Token)
                .get(JsonObject.class);
        Assertions.assertTrue(json.getJsonNumber("update_date").longValue() > update1Date); // Adding a file to a document updates it

        // Get all logs for the document
        json = target().path("/auditlog")
                .queryParam("document", document1Id)
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, auditlog1Token)
                .get(JsonObject.class);
        logs = json.getJsonArray("logs");
        Assertions.assertEquals(4, logs.size());
        Assertions.assertEquals(countByClass(logs, "Document"), 1);
        Assertions.assertEquals(countByClass(logs, "Acl"), 2);
        Assertions.assertEquals(countByClass(logs, "File"), 1);

        // Delete auditlog1
        String adminToken = adminToken();
        target().path("/user/auditlog1").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete();
    }
    
    /**
     * A non-admin must not be able to read another user's audit log entries:
     *   - Querying a document they have no READ ACL on returns NOT_FOUND (the
     *     document-scoped ACL guard). Fails if that guard is removed.
     *   - The unscoped (per-user) log is filtered to the caller's own principal, so
     *     it never contains another user's entries.
     */
    @Test
    public void testAuditLogCrossUserDenied() throws Exception {
        String adminToken = adminToken();

        // Victim creates a document (generating audit log entries)
        clientUtil.createUser("audit_victim");
        String victimToken = clientUtil.login("audit_victim");
        JsonObject json = target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, victimToken)
                .put(Entity.form(new Form()
                        .param("title", "Victim private document")
                        .param("language", "eng")
                        .param("create_date", Long.toString(new Date().getTime()))), JsonObject.class);
        String victimDocId = json.getString("id");

        // Attacker has no ACL on the victim's document
        clientUtil.createUser("audit_attacker");
        String attackerToken = clientUtil.login("audit_attacker");

        // Attacker cannot read the victim's document audit log
        Response response = target().path("/auditlog")
                .queryParam("document", victimDocId)
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, attackerToken)
                .get();
        Assertions.assertEquals(Status.NOT_FOUND, Status.fromStatusCode(response.getStatus()),
                "a non-admin must not read another user's document audit log");

        // Attacker's own (unscoped) audit log must not leak the victim's entries
        json = target().path("/auditlog").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, attackerToken)
                .get(JsonObject.class);
        JsonArray logs = json.getJsonArray("logs");
        for (int i = 0; i < logs.size(); i++) {
            Assertions.assertEquals("audit_attacker", logs.getJsonObject(i).getString("username"),
                    "the per-user audit log must only contain the caller's own entries");
        }

        // Cleanup
        target().path("/user/audit_victim").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete();
        target().path("/user/audit_attacker").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete();
    }

    /**
     * Count logs by class.
     *
     * @param logs Logs
     * @param clazz Class
     * @return Count by class
     */
    private int countByClass(JsonArray logs, String clazz) {
        int count = 0;
        for (int i = 0; i < logs.size(); i++) {
            if (logs.getJsonObject(i).getString("class").equals(clazz)) {
                count++;
            }
        }
        return count;
    }
}
