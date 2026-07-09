package com.sismics.docs.rest;

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

import java.io.File;


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
        // Trash retention window (P7): /api/app must surface the configured purge
        // window as a number so the trash view can render an honest countdown.
        // The raw value is returned verbatim, so a present <= 0 value (auto-purge
        // disabled) is legal — do not assert a sign here.
        Assertions.assertTrue(json.containsKey("trash_retention_days"));
        Assertions.assertNotNull(json.getJsonNumber("trash_retention_days"));

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
                // The password is write-only and never present in the GET (BL-028 sibling).
                Assertions.assertFalse(json.containsKey("password"));
                Assertions.assertTrue(json.isNull("from"));
        }

        // Change SMTP configuration, including a password.
        target().path("/app/config_smtp").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form()
                        .param("hostname", "smtp.sismics.com")
                        .param("port", "1234")
                        .param("username", "sismics")
                        .param("password", "smtp-secret")
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
        // BL-028 sibling: the stored SMTP password must NEVER be echoed back — the GET is
        // write-only for the secret. The key must be ABSENT (not present-but-null), so the
        // client shows a "leave blank to keep" affordance rather than a value.
        Assertions.assertFalse(json.containsKey("password"),
                "SMTP GET must not return the stored password");
        Assertions.assertEquals("contact@sismics.com", json.getString("from"));

        // Keep-on-empty: re-POST without a password must NOT wipe the stored one. The
        // username change proves the POST ran; the password must still be present internally
        // (still never echoed by the GET, so we assert via a follow-up that changes another
        // field and confirms the endpoint still succeeds).
        target().path("/app/config_smtp").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form()
                        .param("username", "sismics2")
                ), JsonObject.class);
        json = target().path("/app/config_smtp").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class);
        Assertions.assertEquals("sismics2", json.getString("username"));
        Assertions.assertFalse(json.containsKey("password"),
                "SMTP GET must not return the stored password after a partial update");
    }

    /**
     * Test inbox scanning.
     */
    @Test
    public void testInbox() throws Exception {
        // Reserve OS-assigned ports for the embedded GreenMail SMTP + IMAP servers (no fixed
        // 9754/9755, so parallel test runs on one host don't collide with BindException).
        int smtpPort;
        int imapPort;
        try (java.net.ServerSocket smtpSocket = new java.net.ServerSocket(0);
             java.net.ServerSocket imapSocket = new java.net.ServerSocket(0)) {
            smtpPort = smtpSocket.getLocalPort();
            imapPort = imapSocket.getLocalPort();
        }

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
                // The IMAP password is write-only and never present in the GET (BL-028 sibling).
                Assertions.assertFalse(json.containsKey("password"),
                        "inbox GET must not return the IMAP password");
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
                        .param("port", Integer.toString(imapPort))
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
        Assertions.assertEquals(imapPort, json.getInt("port"));
        Assertions.assertEquals("test@sismics.com", json.getString("username"));
        // BL-028 sibling: the stored IMAP password must NEVER be echoed back — the GET is
        // write-only for the secret. The key must be ABSENT (not present-but-null).
        Assertions.assertFalse(json.containsKey("password"),
                "inbox GET must not return the stored IMAP password");
        Assertions.assertEquals("INBOX", json.getString("folder"));
        Assertions.assertEquals(tagInboxId, json.getString("tag"));

        // Keep-on-empty: re-POST the config WITHOUT a password param. The POST must keep the
        // stored "Test1234" — proven functionally below, where test_inbox and syncInbox
        // authenticate against GreenMail with exactly that stored password.
        target().path("/app/config_inbox").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form()
                        .param("enabled", "true")
                        .param("starttls", "false")
                        .param("autoTagsEnabled", "false")
                        .param("deleteImported", "false")
                ), JsonObject.class);

        ServerSetup serverSetupSmtp = new ServerSetup(smtpPort, null, ServerSetup.PROTOCOL_SMTP);
        ServerSetup serverSetupImap = new ServerSetup(imapPort, null, ServerSetup.PROTOCOL_IMAP);
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
        // Reserve an OS-assigned port for the embedded LDAP server (no fixed port,
        // so parallel test runs on one host don't collide with BindException).
        int ldapPort;
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            ldapPort = socket.getLocalPort();
        }

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
        ldapServer.setTransports(new TcpTransport("localhost", ldapPort));
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
                        .param("port", Integer.toString(ldapPort))
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
        Assertions.assertEquals(ldapPort, json.getJsonNumber("port").intValue());
        Assertions.assertEquals("uid=admin,ou=system", json.getString("admin_dn"));
        // BL-028: the LDAP admin bind password must NEVER be echoed back — the GET is
        // write-only for the secret. The key must be ABSENT so the client shows a
        // "leave blank to keep" affordance rather than the plaintext bind secret.
        Assertions.assertFalse(json.containsKey("admin_password"),
                "LDAP GET must not return the admin bind password");
        // The GET exposes only a boolean "is a password stored?" flag for the UI affordance.
        Assertions.assertTrue(json.getBoolean("admin_password_set"),
                "LDAP GET must report that an admin bind password is stored");
        Assertions.assertEquals("o=TEST", json.getString("base_dn"));
        Assertions.assertEquals("(&(objectclass=inetOrgPerson)(uid=USERNAME))", json.getString("filter"));
        Assertions.assertEquals("devnull@teedy.io", json.getString("default_email"));
        Assertions.assertEquals(100000000L, json.getJsonNumber("default_storage").longValue());

        // BL-028 keep-on-empty: re-save the LDAP config with an EMPTY admin_password (the
        // client sends blank to keep the stored bind secret). This must succeed WITHOUT a
        // validation error and must NOT wipe the stored password — so an LDAP login still
        // works afterward (the admin bind uses the preserved "secret").
        Response keepPasswordResponse = target().path("/app/config_ldap").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form()
                        .param("enabled", "true")
                        .param("host", "localhost")
                        .param("port", Integer.toString(ldapPort))
                        .param("usessl", "false")
                        .param("admin_dn", "uid=admin,ou=system")
                        .param("admin_password", "")
                        .param("base_dn", "o=TEST")
                        .param("filter", "(&(objectclass=inetOrgPerson)(uid=USERNAME))")
                        .param("default_email", "devnull@teedy.io")
                        .param("default_storage", "100000000")
                ));
        Assertions.assertEquals(Status.OK.getStatusCode(), keepPasswordResponse.getStatus());

        // Login with a LDAP user (proves the preserved admin bind password still works)
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

        // Security: an internal account must authenticate by its OWN password first.
        // "admin" exists both internally (password "admin") and in LDAP (uid=admin,
        // password "ldappass", mail admin-ldap@teedy.io). Logging in with the INTERNAL
        // password must succeed and return the INTERNAL admin (not the LDAP-provisioned
        // one). RED against the old LDAP-first @Priority(50) ordering, which would have
        // bound admin against LDAP and hijacked the local account.
        String adminInternalToken = clientUtil.login("admin", "admin", false);
        json = target().path("/user").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminInternalToken)
                .get(JsonObject.class);
        Assertions.assertEquals("admin", json.getString("username"));
        // The LDAP-provisioned "admin" (mail admin-ldap@teedy.io) must NOT be who logged in.
        Assertions.assertNotEquals("admin-ldap@teedy.io", json.getString("email"));
        Assertions.assertTrue(json.getJsonArray("base_functions").toString().contains("ADMIN"));

        // The LDAP password for "admin" must NOT authenticate the local admin account.
        Response hijackResponse = target().path("/user/login").request()
                .post(Entity.form(new Form()
                        .param("username", "admin")
                        .param("password", "ldappass")
                        .param("remember", "false")));
        Assertions.assertEquals(Status.FORBIDDEN.getStatusCode(), hijackResponse.getStatus());

        // Security: LDAP filter injection. A username of filter metacharacters
        // ("*)(uid=*") must be escaped per RFC 4515 so it cannot broaden the search
        // filter to match an arbitrary entry (e.g. ldap1). RED against the raw
        // .replace("USERNAME", username), which would have matched and attempted a bind.
        Response injectionResponse = target().path("/user/login").request()
                .post(Entity.form(new Form()
                        .param("username", "*)(uid=*")
                        .param("password", "secret")
                        .param("remember", "false")));
        Assertions.assertEquals(Status.FORBIDDEN.getStatusCode(), injectionResponse.getStatus());

        // Security: RFC 4513 unauthenticated/anonymous simple bind. A valid username with an
        // EMPTY password must be rejected before any LDAP bind, otherwise a permissive
        // directory that accepts an anonymous bind (valid DN + empty password) would let an
        // attacker authenticate/provision as any LDAP user with no password. RED against a
        // handler that passes the empty password straight to ldapConnection.bind(dn, password).
        Response emptyPasswordLogin = target().path("/user/login").request()
                .post(Entity.form(new Form()
                        .param("username", "ldap1")
                        .param("password", "")
                        .param("remember", "false")));
        Assertions.assertEquals(Status.FORBIDDEN.getStatusCode(), emptyPasswordLogin.getStatus());

        // Security: origin partition — an LDAP-provisioned user must NEVER authenticate via a
        // local password through the internal handler; they must always go through LDAP (so
        // LDAP disable/revocation/password rules are enforced). Provision ldap2 via LDAP, then
        // give it a valid LOCAL password as admin. A login with that LOCAL password must be
        // rejected: the internal handler refuses isLdap() users, and LDAP rejects it because
        // the real LDAP password is "secret2", not the local one. RED against internal auth
        // accepting the local password (which would bypass LDAP entirely).
        String ldap2Token = clientUtil.login("ldap2", "secret2", false);
        json = target().path("/user").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, ldap2Token)
                .get(JsonObject.class);
        Assertions.assertEquals("ldap2@teedy.io", json.getString("email"));

        Response setLocalPassword = target().path("/user/ldap2").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminInternalToken)
                .post(Entity.form(new Form().param("password", "LocalPass1")));
        Assertions.assertEquals(Status.OK.getStatusCode(), setLocalPassword.getStatus());

        Response localPasswordLogin = target().path("/user/login").request()
                .post(Entity.form(new Form()
                        .param("username", "ldap2")
                        .param("password", "LocalPass1")
                        .param("remember", "false")));
        Assertions.assertEquals(Status.FORBIDDEN.getStatusCode(), localPasswordLogin.getStatus());

        // Security: a disabled LDAP-provisioned user must NOT be able to log in via LDAP,
        // mirroring internal auth (which rejects disabled users). ldap1 was provisioned
        // above; disable it as admin, then an LDAP login for ldap1 must be rejected.
        // RED against a handler that returns the existing LDAP user without a disable check.
        Response disableResponse = target().path("/user/ldap1").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminInternalToken)
                .post(Entity.form(new Form().param("disabled", "true")));
        Assertions.assertEquals(Status.OK.getStatusCode(), disableResponse.getStatus());

        Response disabledLdapLogin = target().path("/user/login").request()
                .post(Entity.form(new Form()
                        .param("username", "ldap1")
                        .param("password", "secret")
                        .param("remember", "false")));
        Assertions.assertEquals(Status.FORBIDDEN.getStatusCode(), disabledLdapLogin.getStatus());

        // Security (partition trade-off): with LDAP globally disabled, an LDAP-origin user is
        // locked out entirely — the internal handler refuses isLdap() users and the LDAP
        // handler is off. This is correct for an LDAP-authoritative model (same as OIDC users
        // when OIDC is disabled). ldap2 has a valid local password but must still be rejected.
        target().path("/app/config_ldap").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminInternalToken)
                .post(Entity.form(new Form().param("enabled", "false")), JsonObject.class);

        Response ldapDisabledLockout = target().path("/user/login").request()
                .post(Entity.form(new Form()
                        .param("username", "ldap2")
                        .param("password", "LocalPass1")
                        .param("remember", "false")));
        Assertions.assertEquals(Status.FORBIDDEN.getStatusCode(), ldapDisabledLockout.getStatus());

        // Stop LDAP server
        ldapServer.stop();
        directoryService.shutdown();
    }

}
