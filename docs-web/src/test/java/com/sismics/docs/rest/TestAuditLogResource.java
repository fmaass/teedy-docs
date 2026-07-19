package com.sismics.docs.rest;

import com.sismics.docs.core.constant.AclType;
import com.sismics.docs.core.constant.PermType;
import com.sismics.docs.core.dao.AclDao;
import com.sismics.docs.core.dao.UserDao;
import com.sismics.docs.core.model.jpa.Acl;
import com.sismics.docs.core.util.TransactionUtil;
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
     * ACL audit rows capture the grantee name when each grant or revocation is written.
     */
    @Test
    public void testAclAuditLogMessagesContainGrantee() {
        String owner = "audit_acl_owner";
        String targetUser = "audit_acl_user";
        String targetGroup = "audit_acl_group";
        String shareName = "Audit ACL share";
        String rawTargetId = "00000000-0000-4000-8000-000000000113";
        clientUtil.createUser(owner);
        clientUtil.createUser(targetUser);
        clientUtil.createGroup(targetGroup);
        String ownerToken = clientUtil.login(owner);

        JsonObject json = target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, ownerToken)
                .put(Entity.form(new Form()
                        .param("title", "ACL audit message document")
                        .param("language", "eng")
                        .param("create_date", Long.toString(new Date().getTime()))), JsonObject.class);
        String documentId = json.getString("id");

        String userId = grantAcl(documentId, "READ", targetUser, "USER", ownerToken);
        grantAcl(documentId, "WRITE", targetUser, "USER", ownerToken);
        String groupId = grantAcl(documentId, "READ", targetGroup, "GROUP", ownerToken);
        grantAcl(documentId, "WRITE", targetGroup, "GROUP", ownerToken);

        json = target().path("/share").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, ownerToken)
                .put(Entity.form(new Form()
                        .param("id", documentId)
                        .param("name", shareName)), JsonObject.class);
        String shareId = json.getString("id");

        json = target().path("/share").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, ownerToken)
                .put(Entity.form(new Form().param("id", documentId)), JsonObject.class);
        String unnamedShareId = json.getString("id");

        TransactionUtil.handle(() -> {
            Acl acl = new Acl();
            acl.setSourceId(documentId);
            acl.setPerm(PermType.WRITE);
            acl.setType(AclType.USER);
            acl.setTargetId(shareId);
            new AclDao().create(acl, new UserDao().getActiveByUsername(owner).getId());

            acl = new Acl();
            acl.setSourceId(documentId);
            acl.setPerm(PermType.READ);
            acl.setType(AclType.USER);
            acl.setTargetId(rawTargetId);
            new AclDao().create(acl, new UserDao().getActiveByUsername(owner).getId());
        });

        revokeAcl(documentId, "READ", userId, ownerToken);
        revokeAcl(documentId, "WRITE", userId, ownerToken);
        revokeAcl(documentId, "READ", groupId, ownerToken);
        revokeAcl(documentId, "WRITE", groupId, ownerToken);
        revokeAcl(documentId, "READ", shareId, ownerToken);
        revokeAcl(documentId, "WRITE", shareId, ownerToken);
        revokeAcl(documentId, "READ", unnamedShareId, ownerToken);
        revokeAcl(documentId, "READ", rawTargetId, ownerToken);

        json = target().path("/auditlog")
                .queryParam("document", documentId)
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, ownerToken)
                .get(JsonObject.class);
        JsonArray logs = json.getJsonArray("logs");
        for (String perm : new String[] { "READ", "WRITE" }) {
            assertAclLog(logs, "CREATE", perm + " granted to " + targetUser);
            assertAclLog(logs, "DELETE", perm + " revoked from " + targetUser);
            assertAclLog(logs, "CREATE", perm + " granted to " + targetGroup);
            assertAclLog(logs, "DELETE", perm + " revoked from " + targetGroup);
            assertAclLog(logs, "CREATE", perm + " granted to " + shareName);
            assertAclLog(logs, "DELETE", perm + " revoked from " + shareName);
        }
        assertAclLog(logs, "CREATE", "READ granted to " + unnamedShareId);
        assertAclLog(logs, "DELETE", "READ revoked from " + unnamedShareId);
        assertAclLog(logs, "CREATE", "READ granted to " + rawTargetId);
        assertAclLog(logs, "DELETE", "READ revoked from " + rawTargetId);

        String adminToken = adminToken();
        target().path("/user/" + owner)
                .queryParam("reassign_to_username", "admin").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete();
        target().path("/user/" + targetUser)
                .queryParam("reassign_to_username", "admin").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete();
    }

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
        target().path("/user/auditlog1")
                .queryParam("reassign_to_username", "admin").request()
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
        target().path("/user/audit_victim")
                .queryParam("reassign_to_username", "admin").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete();
        target().path("/user/audit_attacker")
                .queryParam("reassign_to_username", "admin").request()
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

    private String grantAcl(String sourceId, String perm, String targetName, String type, String token) {
        JsonObject json = target().path("/acl").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .put(Entity.form(new Form()
                        .param("source", sourceId)
                        .param("perm", perm)
                        .param("target", targetName)
                        .param("type", type)), JsonObject.class);
        return json.getString("id");
    }

    private void revokeAcl(String sourceId, String perm, String targetId, String token) {
        JsonObject json = target().path("/acl/" + sourceId + "/" + perm + "/" + targetId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .delete(JsonObject.class);
        Assertions.assertEquals("ok", json.getString("status"));
    }

    private void assertAclLog(JsonArray logs, String type, String message) {
        int count = 0;
        for (int i = 0; i < logs.size(); i++) {
            JsonObject log = logs.getJsonObject(i);
            if ("Acl".equals(log.getString("class"))
                    && type.equals(log.getString("type"))
                    && message.equals(log.getString("message"))) {
                count++;
            }
        }
        Assertions.assertEquals(1, count, type + " ACL audit message: " + message);
    }
}
