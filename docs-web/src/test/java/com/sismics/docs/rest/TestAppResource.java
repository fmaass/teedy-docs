package com.sismics.docs.rest;

import java.io.File;

import com.google.common.io.Resources;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetup;
import com.sismics.docs.core.model.context.AppContext;
import com.sismics.util.filter.TokenBasedSecurityFilter;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Form;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import org.apache.directory.api.ldap.model.name.Dn;
import org.apache.directory.server.core.api.DirectoryService;
import org.apache.directory.server.core.api.partition.Partition;
import org.apache.directory.server.core.factory.DefaultDirectoryServiceFactory;
import org.apache.directory.server.core.factory.DirectoryServiceFactory;
import org.apache.directory.server.core.partition.impl.avl.AvlPartition;
import org.apache.directory.server.ldap.LdapServer;
import org.apache.directory.server.protocol.shared.store.LdifFileLoader;
import org.apache.directory.server.protocol.shared.transport.TcpTransport;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;


/**
 * Test the app resource.
 *
 * @author jtremeaux
 */
public class TestAppResource extends BaseJerseyTest {
    /**
     * Test the API resource.
     */

    // Record if config has been changed by previous test runs
    private static boolean configInboxChanged = false;
    private static boolean configSmtpChanged = false;
    private static boolean configLdapChanged = false;

    @Test
    public void testAppResource() {
        // Login admin
        String adminToken = adminToken();

        // Check the application info
        JsonObject json = target().path("/app").request()
                .get(JsonObject.class);
        Assertions.assertNotNull(json.getString("current_version"));
        Assertions.assertNotNull(json.getString("min_version"));
        Long freeMemory = json.getJsonNumber("free_memory").longValue();
        Assertions.assertTrue(freeMemory > 0);
        Long totalMemory = json.getJsonNumber("total_memory").longValue();
        Assertions.assertTrue(totalMemory > 0 && totalMemory > freeMemory);
        Assertions.assertEquals(0, json.getJsonNumber("queued_tasks").intValue());
        Assertions.assertFalse(json.getBoolean("guest_login"));
        Assertions.assertTrue(json.getBoolean("ocr_enabled"));
        Assertions.assertEquals("eng", json.getString("default_language"));
        Assertions.assertTrue(json.containsKey("global_storage_current"));
        Assertions.assertTrue(json.getJsonNumber("active_user_count").longValue() > 0);

        // Rebuild Lucene index
        Response response = target().path("/app/batch/reindex").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form()));
        Assertions.assertEquals(Status.OK, Status.fromStatusCode(response.getStatus()));

        // Clean storage
        response = target().path("/app/batch/clean_storage").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form()));
        Assertions.assertEquals(Status.OK, Status.fromStatusCode(response.getStatus()));

        // Change the default language
        response = target().path("/app/config").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form().param("default_language", "fra")));
        Assertions.assertEquals(Status.OK, Status.fromStatusCode(response.getStatus()));

        // Check the application info
        json = target().path("/app").request()
                .get(JsonObject.class);
        Assertions.assertEquals("fra", json.getString("default_language"));

        // Change the default language
        response = target().path("/app/config").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form().param("default_language", "eng")));
        Assertions.assertEquals(Status.OK, Status.fromStatusCode(response.getStatus()));

        // Check the application info
        json = target().path("/app").request()
                .get(JsonObject.class);
        Assertions.assertEquals("eng", json.getString("default_language"));
    }

    /**
     * Test the log resource.
     */
    @Test
    public void testLogResource() {
        // Login admin
        String adminToken = adminToken();

        // Check the logs (page 1)
        JsonObject json = target().path("/app/log")
                .queryParam("level", "DEBUG")
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class);
        JsonArray logs = json.getJsonArray("logs");
        Assertions.assertTrue(logs.size() > 0);
        Long date1 = logs.getJsonObject(0).getJsonNumber("date").longValue();
        Long date2 = logs.getJsonObject(9).getJsonNumber("date").longValue();
        Assertions.assertTrue(date1 >= date2);

        // Check the logs (page 2)
        json = target().path("/app/log")
                .queryParam("offset",  "10")
                .queryParam("level", "DEBUG")
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class);
        logs = json.getJsonArray("logs");
        Assertions.assertTrue(logs.size() > 0);
        Long date3 = logs.getJsonObject(0).getJsonNumber("date").longValue();
        Long date4 = logs.getJsonObject(9).getJsonNumber("date").longValue();
        Assertions.assertTrue(date3 >= date4);
    }

    /**
     * Test the guest login.
     */
    @Test
    public void testGuestLogin() {
        // Login admin
        String adminToken = adminToken();

        // Try to login as guest
        Response response = target().path("/user/login").request()
                .post(Entity.form(new Form()
                        .param("username", "guest")));
        Assertions.assertEquals(Status.FORBIDDEN.getStatusCode(), response.getStatus());

        // Enable guest login
        target().path("/app/guest_login").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form()
                        .param("enabled", "true")), JsonObject.class);

        // Login as guest
        String guestToken = clientUtil.login("guest", "", false);

        // Guest cannot delete himself
        response = target().path("/user").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, guestToken)
                .delete();
        Assertions.assertEquals(Status.BAD_REQUEST.getStatusCode(), response.getStatus());

        // Guest cannot see opened sessions
        JsonObject json = target().path("/user/session").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, guestToken)
                .get(JsonObject.class);
        Assertions.assertEquals(0, json.getJsonArray("sessions").size());

        // Guest cannot delete opened sessions
        response = target().path("/user/session").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, guestToken)
                .delete();
        Assertions.assertEquals(Status.FORBIDDEN.getStatusCode(), response.getStatus());

        // Guest cannot enable TOTP
        response = target().path("/user/enable_totp").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, guestToken)
                .post(Entity.form(new Form()));
        Assertions.assertEquals(Status.FORBIDDEN.getStatusCode(), response.getStatus());

        // Guest cannot disable TOTP
        response = target().path("/user/disable_totp").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, guestToken)
                .post(Entity.form(new Form()));
        Assertions.assertEquals(Status.FORBIDDEN.getStatusCode(), response.getStatus());

        // Guest cannot update itself
        response = target().path("/user").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, guestToken)
                .post(Entity.form(new Form()));
        Assertions.assertEquals(Status.FORBIDDEN.getStatusCode(), response.getStatus());

        // Guest can see its documents
        target().path("/document/list").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, guestToken)
                .get(JsonObject.class);

        // Disable guest login (clean up state)
        target().path("/app/guest_login").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form()
                        .param("enabled", "false")), JsonObject.class);
    }

    /**
     * Test the ocr setting
     */
    @Test
    public void testOcrSetting() {
        // Login admin
        String adminToken = adminToken();

        // Check initial OCR state via /app (default is true)
        JsonObject json = target().path("/app").request()
                .get(JsonObject.class);
        Assertions.assertTrue(json.getBoolean("ocr_enabled"));

        // Disable OCR
        target().path("/app/ocr").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form()
                        .param("enabled", "false")
                ), JsonObject.class);

        // Verify disabled via /app
        json = target().path("/app").request()
                .get(JsonObject.class);
        Assertions.assertFalse(json.getBoolean("ocr_enabled"));

        // Re-enable OCR
        target().path("/app/ocr").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form()
                        .param("enabled", "true")
                ), JsonObject.class);

        // Verify re-enabled
        json = target().path("/app").request()
                .get(JsonObject.class);
        Assertions.assertTrue(json.getBoolean("ocr_enabled"));
    }

    /**
     * Test SMTP configuration changes.
     */
    @Test
    public void testSmtpConfiguration() {
        // Login admin
        String adminToken = adminToken();

        // Get SMTP configuration
        JsonObject json = target().path("/app/config_smtp").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class);
        if (!configSmtpChanged) {
                Assertions.assertTrue(json.isNull("hostname"));
                Assertions.assertTrue(json.isNull("port"));
                Assertions.assertTrue(json.isNull("username"));
                Assertions.assertTrue(json.isNull("password"));
                Assertions.assertTrue(json.isNull("from"));
        }

        // Change SMTP configuration
        target().path("/app/config_smtp").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form()
                        .param("hostname", "smtp.sismics.com")
                        .param("port", "1234")
                        .param("username", "sismics")
                        .param("from", "contact@sismics.com")
                ), JsonObject.class);
        configSmtpChanged = true;

        // Get SMTP configuration
        json = target().path("/app/config_smtp").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class);
        Assertions.assertEquals("smtp.sismics.com", json.getString("hostname"));
        Assertions.assertEquals(1234, json.getInt("port"));
        Assertions.assertEquals("sismics", json.getString("username"));
        Assertions.assertTrue(json.isNull("password"));
        Assertions.assertEquals("contact@sismics.com", json.getString("from"));
    }

    /**
     * Test inbox scanning.
     */
    @Test
    public void testInbox() {
        // Login admin
        String adminToken = adminToken();

        // Create a tag
        JsonObject json = target().path("/tag").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form()
                        .param("name", "Inbox")
                        .param("color", "#ff0000")), JsonObject.class);
        String tagInboxId = json.getString("id");

        // Get inbox configuration
        json = target().path("/app/config_inbox").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class);
        JsonObject lastSync = json.getJsonObject("last_sync");
        if (!configInboxChanged) {
                Assertions.assertFalse(json.getBoolean("enabled"));
                Assertions.assertEquals("", json.getString("hostname"));
                Assertions.assertEquals(993, json.getJsonNumber("port").intValue());
                Assertions.assertEquals("", json.getString("username"));
                Assertions.assertEquals("", json.getString("password"));
                Assertions.assertEquals("INBOX", json.getString("folder"));
                Assertions.assertEquals("", json.getString("tag"));
                Assertions.assertTrue(lastSync.isNull("date"));
                Assertions.assertTrue(lastSync.isNull("error"));
                Assertions.assertEquals(0, lastSync.getJsonNumber("count").intValue());
        }

        // Change inbox configuration
        target().path("/app/config_inbox").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form()
                        .param("enabled", "true")
                        .param("starttls", "false")
                        .param("autoTagsEnabled", "false")
                        .param("deleteImported", "false")
                        .param("hostname", "localhost")
                        .param("port", "9755")
                        .param("username", "test@sismics.com")
                        .param("password", "Test1234")
                        .param("folder", "INBOX")
                        .param("tag", tagInboxId)
                ), JsonObject.class);
        configInboxChanged = true;

        // Get inbox configuration
        json = target().path("/app/config_inbox").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class);
        Assertions.assertTrue(json.getBoolean("enabled"));
        Assertions.assertEquals("localhost", json.getString("hostname"));
        Assertions.assertEquals(9755, json.getInt("port"));
        Assertions.assertEquals("test@sismics.com", json.getString("username"));
        Assertions.assertEquals("Test1234", json.getString("password"));
        Assertions.assertEquals("INBOX", json.getString("folder"));
        Assertions.assertEquals(tagInboxId, json.getString("tag"));

        ServerSetup serverSetupSmtp = new ServerSetup(9754, null, ServerSetup.PROTOCOL_SMTP);
        ServerSetup serverSetupImap = new ServerSetup(9755, null, ServerSetup.PROTOCOL_IMAP);
        GreenMail greenMail = new GreenMail(new ServerSetup[] { serverSetupSmtp, serverSetupImap });
        greenMail.setUser("test@sismics.com", "Test1234");
        greenMail.start();

        // Test the inbox
        json = target().path("/app/test_inbox").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form()), JsonObject.class);
        Assertions.assertEquals(0, json.getJsonNumber("count").intValue());

        // Send an email
        GreenMailUtil.sendTextEmail("test@sismics.com", "test@sismicsdocs.com", "Test email 1", "Test content 1", serverSetupSmtp);

        // Trigger an inbox sync
        AppContext.getInstance().getInboxService().syncInbox();

        // Search for added documents
        json = target().path("/document/list")
                .queryParam("search", "tag:Inbox full:content")
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class);
        Assertions.assertEquals(1, json.getJsonArray("documents").size());

        // Get inbox configuration
        json = target().path("/app/config_inbox").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class);
        lastSync = json.getJsonObject("last_sync");
        Assertions.assertFalse(lastSync.isNull("date"));
        Assertions.assertTrue(lastSync.isNull("error"));
        Assertions.assertEquals(1, lastSync.getJsonNumber("count").intValue());

        // Trigger an inbox sync
        AppContext.getInstance().getInboxService().syncInbox();

        // Search for added documents
        json = target().path("/document/list")
                .queryParam("search", "tag:Inbox full:content")
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class);
        Assertions.assertEquals(1, json.getJsonArray("documents").size());

        // Get inbox configuration
        json = target().path("/app/config_inbox").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class);
        lastSync = json.getJsonObject("last_sync");
        Assertions.assertFalse(lastSync.isNull("date"));
        Assertions.assertTrue(lastSync.isNull("error"));
        Assertions.assertEquals(0, lastSync.getJsonNumber("count").intValue());

        greenMail.stop();
    }

    /**
     * Test the LDAP authentication.
     */
    @Test
    public void testLdapAuthentication() throws Exception {
        // Start LDAP server
        final DirectoryServiceFactory factory = new DefaultDirectoryServiceFactory();
        factory.init("Test");

        final DirectoryService directoryService = factory.getDirectoryService();
        directoryService.getChangeLog().setEnabled(false);
        directoryService.setShutdownHookEnabled(true);

        final Partition partition = new AvlPartition(directoryService.getSchemaManager());
        partition.setId("Test");
        partition.setSuffixDn(new Dn(directoryService.getSchemaManager(), "o=TEST"));
        partition.initialize();
        directoryService.addPartition(partition);

        final LdapServer ldapServer = new LdapServer();
        ldapServer.setTransports(new TcpTransport("localhost", 11389));
        ldapServer.setDirectoryService(directoryService);

        directoryService.startup();
        ldapServer.start();

        // Load test data in LDAP
        new LdifFileLoader(directoryService.getAdminSession(), new File(Resources.getResource("test.ldif").getFile()), null).execute();

        // Login admin
        String adminToken = adminToken();

        // Get the LDAP configuration
        JsonObject json = target().path("/app/config_ldap").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class);
        if (!configLdapChanged) {
                Assertions.assertFalse(json.getBoolean("enabled"));
        }

        // Change LDAP configuration
        target().path("/app/config_ldap").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form()
                        .param("enabled", "true")
                        .param("host", "localhost")
                        .param("port", "11389")
                        .param("usessl", "false")
                        .param("admin_dn", "uid=admin,ou=system")
                        .param("admin_password", "secret")
                        .param("base_dn", "o=TEST")
                        .param("filter", "(&(objectclass=inetOrgPerson)(uid=USERNAME))")
                        .param("default_email", "devnull@teedy.io")
                        .param("default_storage", "100000000")
                ), JsonObject.class);
        configLdapChanged = true;

        // Get the LDAP configuration
        json = target().path("/app/config_ldap").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class);
        Assertions.assertTrue(json.getBoolean("enabled"));
        Assertions.assertEquals("localhost", json.getString("host"));
        Assertions.assertEquals(11389, json.getJsonNumber("port").intValue());
        Assertions.assertEquals("uid=admin,ou=system", json.getString("admin_dn"));
        Assertions.assertEquals("secret", json.getString("admin_password"));
        Assertions.assertEquals("o=TEST", json.getString("base_dn"));
        Assertions.assertEquals("(&(objectclass=inetOrgPerson)(uid=USERNAME))", json.getString("filter"));
        Assertions.assertEquals("devnull@teedy.io", json.getString("default_email"));
        Assertions.assertEquals(100000000L, json.getJsonNumber("default_storage").longValue());

        // Login with a LDAP user
        String ldapTopen = clientUtil.login("ldap1", "secret", false);

        // Check user informations
        json = target().path("/user").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, ldapTopen)
                .get(JsonObject.class);
        Assertions.assertEquals("ldap1@teedy.io", json.getString("email"));

        // List all documents
        json = target().path("/document/list")
                .queryParam("sort_column", 3)
                .queryParam("asc", true)
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, ldapTopen)
                .get(JsonObject.class);
        JsonArray documents = json.getJsonArray("documents");
        Assertions.assertEquals(0, documents.size());

        // Stop LDAP server
        ldapServer.stop();
        directoryService.shutdown();
    }
}
