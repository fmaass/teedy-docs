package com.sismics.docs.rest;

import com.google.common.io.Resources;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetup;
import com.sismics.docs.core.model.context.AppContext;
import com.sismics.util.context.ThreadLocalContext;
import com.sismics.util.filter.TokenBasedSecurityFilter;
import com.sismics.util.jpa.EMF;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityTransaction;
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
     * A soft-deleted user who still owns a saved filter must not wedge the storage purge.
     *
     * T_SAVED_FILTER.FK_SFL_IDUSER_C is ON DELETE RESTRICT, so unless clean_storage first
     * removes those filters the user hard-delete throws a constraint violation and aborts the
     * whole transaction. Reproduce the ownership, delete the user, purge, and assert both the
     * user row and the filter are gone.
     */
    @Test
    public void testCleanStoragePurgesSavedFiltersOfDeletedUsers() {
        String adminToken = adminToken();

        // Create a regular user and log in as them
        clientUtil.createUser("clean_storage_filter_user");
        String userToken = clientUtil.login("clean_storage_filter_user");

        // The user owns a saved filter
        JsonObject filterJson = target().path("/savedfilter").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, userToken)
                .put(Entity.form(new Form()
                        .param("name", "clean_storage_filter")
                        .param("query", "search=example")), JsonObject.class);
        String filterId = filterJson.getString("id");
        Assertions.assertNotNull(filterId);
        Assertions.assertEquals(1L, countSavedFilters(filterId));

        // Admin soft-deletes the user (row stays until the storage purge)
        Response response = target().path("/user/clean_storage_filter_user").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete();
        Assertions.assertEquals(Status.OK, Status.fromStatusCode(response.getStatus()));

        // Purge storage: this hard-deletes soft-deleted users and must not abort on the filter FK
        response = target().path("/app/batch/clean_storage").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form()));
        Assertions.assertEquals(Status.OK, Status.fromStatusCode(response.getStatus()));

        // The user row is gone and its saved filter was removed
        Assertions.assertEquals(0L, countUsersByName("clean_storage_filter_user"));
        Assertions.assertEquals(0L, countSavedFilters(filterId));
    }

    private long countSavedFilters(String filterId) {
        return readCount("select count(*) from T_SAVED_FILTER where SFL_ID_C = :id", "id", filterId);
    }

    private long countUsersByName(String username) {
        return readCount("select count(*) from T_USER where USE_USERNAME_C = :id", "id", username);
    }

    private long readCount(String sql, String paramName, String paramValue) {
        EntityManager prev = ThreadLocalContext.get().getEntityManager();
        EntityManager em = EMF.get().createEntityManager();
        EntityTransaction tx = em.getTransaction();
        try {
            ThreadLocalContext.get().setEntityManager(em);
            tx.begin();
            Object n = em.createNativeQuery(sql).setParameter(paramName, paramValue).getSingleResult();
            tx.commit();
            return ((Number) n).longValue();
        } finally {
            if (tx.isActive()) {
                tx.rollback();
            }
            em.close();
            ThreadLocalContext.get().setEntityManager(prev);
        }
    }

    /**
     * Runs an UPDATE/INSERT/DELETE native statement in an isolated, committed transaction (the
     * {@link #readCount} pattern for writes). Used to place fixture rows into exact states — precise
     * boundary timestamps, storage values, soft-delete marks — that the REST API cannot set directly.
     */
    private void executeSql(String sql, java.util.Map<String, Object> params) {
        EntityManager prev = ThreadLocalContext.get().getEntityManager();
        EntityManager em = EMF.get().createEntityManager();
        EntityTransaction tx = em.getTransaction();
        try {
            ThreadLocalContext.get().setEntityManager(em);
            tx.begin();
            jakarta.persistence.Query q = em.createNativeQuery(sql);
            for (java.util.Map.Entry<String, Object> e : params.entrySet()) {
                q.setParameter(e.getKey(), e.getValue());
            }
            q.executeUpdate();
            tx.commit();
        } finally {
            if (tx.isActive()) {
                tx.rollback();
            }
            em.close();
            ThreadLocalContext.get().setEntityManager(prev);
        }
    }

    /**
     * Inserts one T_AUDIT_LOG row for the given entity class / type at an EXACT UTC instant
     * (millis-since-epoch), bypassing AuditLogDao (which stamps the server clock). This lets a test
     * place activity rows on precise window boundaries and for precise entity classes, so the
     * class-set filter and the {@code >= start}/{@code < end} native predicates are directly tested.
     */
    private void insertAuditRow(String entityClass, String type, long createMs) {
        executeSql("insert into T_AUDIT_LOG (LOG_ID_C, LOG_IDENTITY_C, LOG_CLASSENTITY_C, LOG_TYPE_C, LOG_IDUSER_C, LOG_CREATEDATE_D)"
                + " values (:id, :identity, :cls, :type, :user, :date)",
                java.util.Map.of(
                        "id", java.util.UUID.randomUUID().toString(),
                        "identity", java.util.UUID.randomUUID().toString(),
                        "cls", entityClass,
                        "type", type,
                        "user", "admin",
                        "date", new java.util.Date(createMs)));
    }

    /**
     * Asserts a JSON object's key set is EXACTLY the expected keys — no missing keys and no extras.
     * Locks the response contract so an added/renamed field is caught, not silently tolerated.
     */
    private static void assertExactKeys(JsonObject object, String path, String... expected) {
        java.util.Set<String> expectedSet = new java.util.HashSet<>(java.util.Arrays.asList(expected));
        Assertions.assertEquals(expectedSet, object.keySet(),
                path + " must have exactly the pinned keys " + expectedSet + " (actual: " + object.keySet() + ")");
    }

    /**
     * Test the configurable footer links: admin write + validation + anonymous read.
     */
    @Test
    public void testFooterLinks() {
        String adminToken = adminToken();

        // Anonymous GET /app carries NO footer_links key when none configured (unchanged chrome).
        JsonObject json = target().path("/app").request().get(JsonObject.class);
        Assertions.assertFalse(json.containsKey("footer_links"), "footer_links must be absent when unset");

        // Configure two valid links as admin.
        String links = "[{\"label\":\"Imprint\",\"url\":\"https://example.com/imprint\"},"
                + "{\"label\":\"Privacy\",\"url\":\"http://example.com/privacy\"}]";
        Response response = target().path("/app/footer_links").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form().param("links", links)));
        Assertions.assertEquals(Status.OK, Status.fromStatusCode(response.getStatus()));

        // Anonymous GET /app now returns them (public chrome, no auth cookie).
        json = target().path("/app").request().get(JsonObject.class);
        JsonArray footerLinks = json.getJsonArray("footer_links");
        Assertions.assertEquals(2, footerLinks.size());
        Assertions.assertEquals("Imprint", footerLinks.getJsonObject(0).getString("label"));
        Assertions.assertEquals("https://example.com/imprint", footerLinks.getJsonObject(0).getString("url"));
        Assertions.assertEquals("Privacy", footerLinks.getJsonObject(1).getString("label"));

        // --- Scheme-evasion rejection (open-redirect / XSS guard) ---
        // Every non-http(s) scheme and every whitespace/case-obfuscated variant must be
        // rejected by validateHttpUrl. If any of these were ACCEPTED it would be a real
        // open-redirect/XSS defect; these cases pin the fail-closed behaviour.
        String[] evasionUrls = {
                "javascript:alert(1)",                       // bare javascript:
                "  javascript:alert(1)",                     // whitespace-prefixed (strip must not un-hide it)
                "JavaScript:alert(1)",                       // mixed-case scheme
                "data:text/html,<script>alert(1)</script>",  // data: URI
                "//evil.com",                                // protocol-relative
                "  //evil.com",                              // protocol-relative, whitespace-prefixed
                "ftp://example.com",                         // non-http(s) scheme
        };
        for (String evil : evasionUrls) {
            Response r = target().path("/app/footer_links").request()
                    .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                    .post(Entity.form(new Form().param("links",
                            "[{\"label\":\"Evil\",\"url\":\"" + jsonEscape(evil) + "\"}]")));
            Assertions.assertEquals(Status.BAD_REQUEST, Status.fromStatusCode(r.getStatus()),
                    "a non-http(s) / obfuscated URL must be rejected: " + evil);
        }

        // Reject an entry missing its label.
        response = target().path("/app/footer_links").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form().param("links",
                        "[{\"url\":\"https://example.com\"}]")));
        Assertions.assertEquals(Status.BAD_REQUEST, Status.fromStatusCode(response.getStatus()),
                "a link without a label must be rejected");

        // Reject a blank / whitespace-only label (validateStringNotBlank).
        response = target().path("/app/footer_links").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form().param("links",
                        "[{\"label\":\"   \",\"url\":\"https://example.com\"}]")));
        Assertions.assertEquals(Status.BAD_REQUEST, Status.fromStatusCode(response.getStatus()),
                "a whitespace-only label must be rejected");

        // Reject a non-string label (JSON number where a string is required).
        response = target().path("/app/footer_links").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form().param("links",
                        "[{\"label\":42,\"url\":\"https://example.com\"}]")));
        Assertions.assertEquals(Status.BAD_REQUEST, Status.fromStatusCode(response.getStatus()),
                "a non-string label must be rejected");

        // Reject a non-string url (JSON boolean where a string is required).
        response = target().path("/app/footer_links").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form().param("links",
                        "[{\"label\":\"Ok\",\"url\":true}]")));
        Assertions.assertEquals(Status.BAD_REQUEST, Status.fromStatusCode(response.getStatus()),
                "a non-string url must be rejected");

        // Reject a label longer than 40 characters.
        response = target().path("/app/footer_links").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form().param("links",
                        "[{\"label\":\"" + "a".repeat(41) + "\",\"url\":\"https://example.com\"}]")));
        Assertions.assertEquals(Status.BAD_REQUEST, Status.fromStatusCode(response.getStatus()),
                "a label longer than 40 characters must be rejected");

        // Reject a url longer than 500 characters.
        String longUrl = "https://example.com/" + "a".repeat(500);
        response = target().path("/app/footer_links").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form().param("links",
                        "[{\"label\":\"Ok\",\"url\":\"" + longUrl + "\"}]")));
        Assertions.assertEquals(Status.BAD_REQUEST, Status.fromStatusCode(response.getStatus()),
                "a url longer than 500 characters must be rejected");

        // Reject more than 5 entries, and the error names the limit.
        StringBuilder tooMany = new StringBuilder("[");
        for (int i = 0; i < 6; i++) {
            if (i > 0) tooMany.append(',');
            tooMany.append("{\"label\":\"L").append(i).append("\",\"url\":\"https://example.com/").append(i).append("\"}");
        }
        tooMany.append(']');
        response = target().path("/app/footer_links").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form().param("links", tooMany.toString())));
        Assertions.assertEquals(Status.BAD_REQUEST, Status.fromStatusCode(response.getStatus()),
                "more than 5 footer links must be rejected");
        Assertions.assertTrue(response.readEntity(String.class).contains("5"),
                "the cap-exceeded error must name the 5-link limit");

        // An anonymous caller must not be able to write footer links.
        response = target().path("/app/footer_links").request()
                .post(Entity.form(new Form().param("links", "[]")));
        Assertions.assertEquals(Status.FORBIDDEN, Status.fromStatusCode(response.getStatus()),
                "an anonymous caller must not write footer links");

        // An AUTHENTICATED non-admin caller must not be able to write footer links either
        // (checkBaseFunction(ADMIN) — a valid session without the admin function is 403).
        clientUtil.createUser("footer_links_user");
        String userToken = clientUtil.login("footer_links_user");
        response = target().path("/app/footer_links").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, userToken)
                .post(Entity.form(new Form().param("links",
                        "[{\"label\":\"X\",\"url\":\"https://example.com\"}]")));
        Assertions.assertEquals(Status.FORBIDDEN, Status.fromStatusCode(response.getStatus()),
                "a non-admin authenticated caller must not write footer links");

        // An empty array clears the config: footer_links is absent again on GET /app.
        response = target().path("/app/footer_links").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form().param("links", "[]")));
        Assertions.assertEquals(Status.OK, Status.fromStatusCode(response.getStatus()));
        json = target().path("/app").request().get(JsonObject.class);
        Assertions.assertFalse(json.containsKey("footer_links"), "an empty array must clear footer_links");
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
        // Start the embedded GreenMail SMTP + IMAP servers on OS-assigned dynamic ports and read
        // the real bound ports back only after start — no reserve-then-release window in which
        // another process could steal a port (BindException race, issue #33).
        GreenMail greenMail = new GreenMail(new ServerSetup[] {
                ServerSetup.SMTP.dynamicPort(), ServerSetup.IMAP.dynamicPort() });
        greenMail.setUser("test@sismics.com", "Test1234");
        greenMail.start();
        try {
        int smtpPort = greenMail.getSmtp().getPort();
        int imapPort = greenMail.getImap().getPort();

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

        // Client-side setup pointing at the port GreenMail actually bound.
        ServerSetup serverSetupSmtp = new ServerSetup(smtpPort, null, ServerSetup.PROTOCOL_SMTP);

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
        } finally {
            // Stop in a finally so an assertion failure above cannot leak the embedded
            // SMTP/IMAP server (and its bound ports) into the rest of the suite.
            greenMail.stop();
        }
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

        // Bind to port 0 and read the REAL bound port back from MINA's acceptor after start —
        // no ServerSocket(0) reserve-then-release window in which another process could steal
        // the port (BindException race, issue #33). Transport.getPort()/LdapServer.getPort()
        // still report the configured 0, so we must read the acceptor's local address instead.
        final TcpTransport transport = new TcpTransport("localhost", 0);
        final LdapServer ldapServer = new LdapServer();
        ldapServer.setTransports(transport);
        ldapServer.setDirectoryService(directoryService);

        directoryService.startup();
        ldapServer.start();
        int ldapPort = ((java.net.InetSocketAddress) transport.getAcceptor().getLocalAddress()).getPort();

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

    /**
     * The admin stats dashboard endpoint: response shape, seeded totals, per-user storage
     * ordering, the documents-created series bucketing, and the activity series.
     *
     * <p>The test DB is shared across the suite, so absolute totals are not asserted; instead a
     * KNOWN increment is created (a fresh document with a controlled create_date, a fresh tag, a
     * fresh favorite) and the corresponding total/bucket is asserted to grow by exactly that
     * increment (before/after deltas).
     */
    @Test
    public void testStats() {
        String adminToken = adminToken();

        // Baseline snapshot.
        JsonObject before = target().path("/app/stats").queryParam("window", 7).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class);

        // --- Response shape: every pinned field is present with the right JSON type. ---
        Assertions.assertEquals(7, before.getInt("window"));
        JsonObject totals = before.getJsonObject("totals");
        for (String key : new String[] { "documents", "files", "users", "tags", "favorites" }) {
            Assertions.assertNotNull(before.getJsonObject("totals").getJsonNumber(key), "totals." + key);
        }
        JsonObject storage = before.getJsonObject("storage");
        Assertions.assertNotNull(storage.getJsonNumber("global"), "storage.global");
        JsonArray perUser = storage.getJsonArray("per_user");
        Assertions.assertNotNull(perUser, "storage.per_user");
        // per_user rows carry exactly the pinned fields.
        if (!perUser.isEmpty()) {
            JsonObject row = perUser.getJsonObject(0);
            Assertions.assertNotNull(row.getString("username"));
            Assertions.assertNotNull(row.getJsonNumber("storage_current"));
            Assertions.assertNotNull(row.getJsonNumber("storage_quota"));
        }
        JsonObject series = before.getJsonObject("series");
        JsonArray documentsCreated = series.getJsonArray("documents_created");
        JsonArray activity = series.getJsonArray("activity");
        // A 7-day window is exactly 7 zero-filled buckets, each {date, count}.
        Assertions.assertEquals(7, documentsCreated.size(), "documents_created is 7 daily buckets");
        Assertions.assertEquals(7, activity.size(), "activity is 7 daily buckets");
        for (int i = 0; i < 7; i++) {
            JsonObject bucket = documentsCreated.getJsonObject(i);
            Assertions.assertNotNull(bucket.getString("date"));
            Assertions.assertNotNull(bucket.getJsonNumber("count"));
        }
        // The buckets are ascending, contiguous UTC days ending today.
        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneOffset.UTC);
        Assertions.assertEquals(today.toString(),
                documentsCreated.getJsonObject(6).getString("date"), "last bucket is today (UTC)");
        Assertions.assertEquals(today.minusDays(6).toString(),
                documentsCreated.getJsonObject(0).getString("date"), "first bucket is six UTC days ago");

        // --- ADVISORY: the response shape has EXACTLY the pinned keys at each level (no extras). ---
        assertExactKeys(totals, "totals", "documents", "files", "users", "tags", "favorites");
        assertExactKeys(storage, "storage", "global", "per_user");
        assertExactKeys(series, "series", "documents_created", "activity");
        assertExactKeys(before, "response", "window", "totals", "storage", "series");
        if (!perUser.isEmpty()) {
            assertExactKeys(perUser.getJsonObject(0), "per_user[0]", "username", "storage_current", "storage_quota");
        }
        for (int i = 0; i < 7; i++) {
            assertExactKeys(documentsCreated.getJsonObject(i), "documents_created[" + i + "]", "date", "count");
            assertExactKeys(activity.getJsonObject(i), "activity[" + i + "]", "date", "count");
        }

        long docsBefore = totals.getJsonNumber("documents").longValue();
        long tagsBefore = totals.getJsonNumber("tags").longValue();
        long favsBefore = totals.getJsonNumber("favorites").longValue();
        long filesBefore = totals.getJsonNumber("files").longValue();
        long usersBefore = totals.getJsonNumber("users").longValue();
        long todayDocsBefore = documentsCreated.getJsonObject(6).getJsonNumber("count").longValue();
        long todayActivityBefore = activity.getJsonObject(6).getJsonNumber("count").longValue();

        // ============================================================================
        // B1 — the pinned COUNT SEMANTICS are locked by seeded fixtures below. Each of the
        // following would break a specific mutation of the StatsDao aggregate SQL:
        //   files INCLUDE historical versions ; users INCLUDE disabled ; deleted EXCLUDED ;
        //   activity class set is EXACTLY {Document,File,Comment,Route,Tag} ; global storage sum.
        // ============================================================================

        // A document with an explicit create_date of "now" (UTC-today bucket).
        JsonObject docJson = target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form()
                        .param("title", "Stats seed document")
                        .param("language", "eng")
                        .param("create_date", Long.toString(System.currentTimeMillis()))), JsonObject.class);
        String docId = docJson.getString("id");

        // A tag.
        JsonObject tagJson = target().path("/tag").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form().param("name", "StatsSeedTag").param("color", "#00ff00")), JsonObject.class);
        String tagId = tagJson.getString("id");
        Assertions.assertNotNull(tagId);

        // A favorite on the seeded document.
        Response favResponse = target().path("/favorite/" + docId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form()));
        Assertions.assertEquals(Status.OK, Status.fromStatusCode(favResponse.getStatus()));

        // FILES INCLUDE HISTORICAL VERSIONS: upload a file, then REPLACE it (previousFileId) so
        // the original becomes a non-latest version. Two non-deleted T_FILE rows now exist for one
        // logical file — the files total must count BOTH. Assert exactly +2. A mutation that adds
        // `FIL_LATESTVERSION_B = true` to the count query would see only +1 and fail here.
        String firstFileId;
        try {
            firstFileId = clientUtil.addFileToDocument("file/PIA00452.jpg", adminToken, docId);
            clientUtil.addFileToDocumentReplacing("file/PIA00452.jpg", adminToken, docId, firstFileId);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        long nonLatestVersions = readCount(
                "select count(*) from T_FILE where FIL_IDDOC_C = :id and FIL_LATESTVERSION_B = false and FIL_DELETEDATE_D is null",
                "id", docId);
        Assertions.assertEquals(1L, nonLatestVersions, "the replaced upload must leave one non-latest version row");

        // USERS INCLUDE DISABLED: create a user (with quota) and DISABLE it. The user is disabled
        // but NOT deleted, so the users total must still count it. Excluding disabled users would
        // undercount by one and fail the +1 assertion below.
        String disabledUser = "stats_disabled_user";
        clientUtil.createUser(disabledUser);
        Response disableResponse = target().path("/user/" + disabledUser).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form().param("disabled", "true").param("storage_quota", "50000000")));
        Assertions.assertEquals(Status.OK, Status.fromStatusCode(disableResponse.getStatus()));
        Assertions.assertTrue(readCount(
                "select count(*) from T_USER where USE_USERNAME_C = :u and USE_DISABLEDATE_D is not null and USE_DELETEDATE_D is null",
                "u", disabledUser) == 1L, "the seeded user must be disabled-but-not-deleted");

        // GLOBAL STORAGE: give the disabled user a known non-zero USE_STORAGECURRENT_N via direct
        // SQL and assert the global sum grows by EXACTLY that amount — locking the sum semantics (a
        // disabled user still contributes storage). Measured in its OWN isolated delta window that
        // brackets ONLY the storage UPDATE, so concurrent file-upload storage changes elsewhere in
        // the test cannot perturb it.
        long seededStorage = 123456L;
        long globalStorageJustBefore = target().path("/app/stats").queryParam("window", 7).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class).getJsonObject("storage").getJsonNumber("global").longValue();
        executeSql("update T_USER set USE_STORAGECURRENT_N = :s where USE_USERNAME_C = :u",
                java.util.Map.of("s", seededStorage, "u", disabledUser));
        long globalStorageJustAfter = target().path("/app/stats").queryParam("window", 7).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class).getJsonObject("storage").getJsonNumber("global").longValue();
        Assertions.assertEquals(globalStorageJustBefore + seededStorage, globalStorageJustAfter,
                "global storage sum grows by the disabled user's seeded storage (disabled users contribute)");

        // DELETED ROWS EXCLUDED: soft-delete an extra document, file, and tag via direct SQL and
        // assert they are NOT counted. Removing the `*_DELETEDATE_D is null` predicate from a count
        // query would include these and break the exact-delta assertions.
        // Deleted via the REAL DELETE endpoints (soft-delete), which leave clean_storage-safe state
        // (the document delete cascades to its file and clears DOC_IDFILE_C) — a direct-SQL
        // DELETEDATE update would strand the DOC_IDFILE_C FK and 500 a later clean_storage run.
        String deletedDocId = target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form().param("title", "Stats deleted doc").param("language", "eng")
                        .param("create_date", Long.toString(System.currentTimeMillis()))), JsonObject.class)
                .getString("id");
        String deletedTagId = target().path("/tag").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form().param("name", "StatsDeletedTag").param("color", "#111111")), JsonObject.class)
                .getString("id");
        try {
            clientUtil.addFileToDocument("file/PIA00452.jpg", adminToken, deletedDocId);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        // Soft-delete the document (cascades to its file) and the tag through the API.
        Assertions.assertEquals(Status.OK, Status.fromStatusCode(
                target().path("/document/" + deletedDocId).request()
                        .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken).delete().getStatus()));
        Assertions.assertEquals(Status.OK, Status.fromStatusCode(
                target().path("/tag/" + deletedTagId).request()
                        .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken).delete().getStatus()));
        // Clear the soft-deleted document's main-file pointer so a later clean_storage run (in
        // sibling tests sharing this DB) can hard-delete the soft-deleted file: it purges files
        // BEFORE documents, and a lingering DOC_IDFILE_C FK would otherwise abort that purge.
        executeSql("update T_DOCUMENT set DOC_IDFILE_C = null where DOC_ID_C = :id",
                java.util.Map.of("id", deletedDocId));
        // Confirm the fixture state: the document, its file, and the tag are all soft-deleted.
        Assertions.assertEquals(1L, readCount(
                "select count(*) from T_DOCUMENT where DOC_ID_C = :id and DOC_DELETEDATE_D is not null", "id", deletedDocId),
                "the deleted document must be soft-deleted");
        Assertions.assertTrue(readCount(
                "select count(*) from T_FILE where FIL_IDDOC_C = :id and FIL_DELETEDATE_D is not null", "id", deletedDocId) >= 1L,
                "the deleted document's file must be soft-deleted too");
        Assertions.assertEquals(1L, readCount(
                "select count(*) from T_TAG where TAG_ID_C = :id and TAG_DELETEDATE_D is not null", "id", deletedTagId),
                "the deleted tag must be soft-deleted");

        // ACTIVITY CLASS SET IS EXACT: seed two audit rows dated NOW — one for an IN-set class
        // (Comment) and one for an OUT-of-set class (Group, which is never in the activity set).
        // The activity bucket must grow by EXACTLY 1 (the in-set row), never 2. Broadening the
        // class list to include Group would make this +2 and fail.
        long nowMs = System.currentTimeMillis();
        insertAuditRow("Comment", "CREATE", nowMs);
        insertAuditRow("Group", "CREATE", nowMs);

        // --- After snapshot: each pinned semantic shows the exact expected delta. ---
        JsonObject after = target().path("/app/stats").queryParam("window", 7).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class);
        JsonObject afterTotals = after.getJsonObject("totals");
        // documents: +1 (the seeded active doc; the deleted doc must NOT count).
        Assertions.assertEquals(docsBefore + 1, afterTotals.getJsonNumber("documents").longValue(),
                "documents total grows by exactly one active document (deleted excluded)");
        // tags: +1 (the seeded active tag; the deleted tag must NOT count).
        Assertions.assertEquals(tagsBefore + 1, afterTotals.getJsonNumber("tags").longValue(),
                "tags total grows by exactly one active tag (deleted excluded)");
        // favorites: +1.
        Assertions.assertEquals(favsBefore + 1, afterTotals.getJsonNumber("favorites").longValue(),
                "favorites total grows by the one seeded favorite");
        // files: +2 (latest + one non-latest version on the active doc; the deleted file excluded).
        Assertions.assertEquals(filesBefore + 2, afterTotals.getJsonNumber("files").longValue(),
                "files total counts historical versions and excludes the deleted file");
        // users: +1 (the disabled-but-not-deleted user counts).
        Assertions.assertEquals(usersBefore + 1, afterTotals.getJsonNumber("users").longValue(),
                "users total counts the disabled (non-deleted) user");
        // (global storage was asserted exactly above in its own isolated delta window.)

        // documents_created: today's UTC bucket grew by the one ACTIVE seeded document (the
        // deleted doc, also created today, must NOT appear — the series filters deleteDate null).
        JsonArray afterDocsCreated = after.getJsonObject("series").getJsonArray("documents_created");
        Assertions.assertEquals(todayDocsBefore + 1,
                afterDocsCreated.getJsonObject(6).getJsonNumber("count").longValue(),
                "documents_created today's bucket grows by exactly the active seeded document");

        // activity: the real create/upload actions above emitted many in-set audit rows, PLUS the
        // one seeded in-set Comment row. The seeded OUT-of-set Group row must NOT count. So the
        // delta is strictly positive AND the exact in-set count from the seeded rows holds: we
        // assert the bucket rose by at least the seeded in-set row while the out-of-set row was
        // ignored, by comparing against a count of ONLY the two seeded rows' contribution.
        JsonArray afterActivity = after.getJsonObject("series").getJsonArray("activity");
        long activityDelta = afterActivity.getJsonObject(6).getJsonNumber("count").longValue() - todayActivityBefore;
        Assertions.assertTrue(activityDelta > 0, "activity today's bucket increased after in-set actions");
        // Directly assert the class-set exactness against the DB: today's activity count equals the
        // number of retained audit rows in {Document,File,Comment,Route,Tag} dated today — and that
        // this EXCLUDES the seeded Group row (which exists but must not be counted).
        long expectedTodayActivity = countTodayActivityInSet();
        Assertions.assertEquals(expectedTodayActivity,
                afterActivity.getJsonObject(6).getJsonNumber("count").longValue(),
                "activity today's bucket counts exactly the in-set classes and excludes out-of-set (Group)");
        Assertions.assertTrue(readCount(
                "select count(*) from T_AUDIT_LOG where LOG_CLASSENTITY_C = :c", "c", "Group") >= 1L,
                "the out-of-set Group audit row exists but was not counted above");
    }

    /**
     * Counts the audit-log rows dated within today's UTC day whose entity class is in the pinned
     * activity set {Document, File, Comment, Route, Tag} — the exact set the endpoint must use.
     * Computed independently here (not via StatsDao) so it is a real oracle for the class filter.
     */
    private long countTodayActivityInSet() {
        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneOffset.UTC);
        java.util.Date start = java.util.Date.from(today.atStartOfDay(java.time.ZoneOffset.UTC).toInstant());
        java.util.Date end = java.util.Date.from(today.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant());
        EntityManager prev = ThreadLocalContext.get().getEntityManager();
        EntityManager em = EMF.get().createEntityManager();
        EntityTransaction tx = em.getTransaction();
        try {
            ThreadLocalContext.get().setEntityManager(em);
            tx.begin();
            Object n = em.createNativeQuery("select count(*) from T_AUDIT_LOG where LOG_CLASSENTITY_C in ('Document','File','Comment','Route','Tag') and LOG_CREATEDATE_D >= :start and LOG_CREATEDATE_D < :end")
                    .setParameter("start", start)
                    .setParameter("end", end)
                    .getSingleResult();
            tx.commit();
            return ((Number) n).longValue();
        } finally {
            if (tx.isActive()) {
                tx.rollback();
            }
            em.close();
            ThreadLocalContext.get().setEntityManager(prev);
        }
    }

    /**
     * B2 — DB-level boundary seeding for BOTH series. Rows are placed at precise UTC instants:
     * exactly at the window start (00:00:00.000 UTC of the first day — INCLUDED), the last
     * representable instant before the window end (INCLUDED), and exactly at the window end
     * (00:00:00.000 UTC of today+1 — EXCLUDED), for documents_created (DOC_CREATEDATE_D) and
     * activity (LOG_CREATEDATE_D). The instants are computed via java.time with ZoneOffset.UTC so
     * the assertions hold regardless of the JVM's ambient timezone (also covering the non-UTC
     * concern durably). A mutation of the native predicates {@code >=}→{@code >} or {@code <}→{@code <=}
     * in either StatsDao query must break this test.
     */
    @Test
    public void testStatsBoundarySeeding() {
        String adminToken = adminToken();
        int window = 7;

        // Window boundaries as EXACT UTC instants (mirrors StatsBucketUtil.windowStart/windowEnd).
        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneOffset.UTC);
        java.time.Instant startInstant = today.minusDays(window - 1L).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        java.time.Instant endInstant = today.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        long startMs = startInstant.toEpochMilli();
        long lastBeforeEndMs = endInstant.toEpochMilli() - 1L; // last representable instant in today's UTC bucket
        long endMs = endInstant.toEpochMilli();                // exactly tomorrow 00:00 UTC — must be excluded

        String firstBucketDate = today.minusDays(window - 1L).toString();
        String lastBucketDate = today.toString();

        // ---- documents_created boundaries (DOC_CREATEDATE_D). Measure this series in its OWN
        // before/after window. Creating a document also emits a Document audit row at server-now, so
        // the activity series is measured SEPARATELY below (bracketing only insertAuditRow calls) to
        // keep its deltas exact. ----
        JsonObject beforeDocs = target().path("/app/stats").queryParam("window", window).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class);
        long docFirstBefore = bucketCount(beforeDocs, "documents_created", firstBucketDate);
        long docLastBefore = bucketCount(beforeDocs, "documents_created", lastBucketDate);
        long docTotalBefore = totalSeriesCount(beforeDocs, "documents_created");

        // Three docs at start, last-before-end, and exactly end. The API accepts a millis create_date;
        // we then pin DOC_CREATEDATE_D to the EXACT instant via direct SQL so the boundary is exact.
        String docAtStart = createDatedDocument(adminToken, "bnd-doc-start", startMs);
        String docAtLastBeforeEnd = createDatedDocument(adminToken, "bnd-doc-lastbeforeend", lastBeforeEndMs);
        String docAtEnd = createDatedDocument(adminToken, "bnd-doc-end", endMs);

        JsonObject afterDocs = target().path("/app/stats").queryParam("window", window).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class);
        // Start-instant doc → FIRST bucket (INCLUDED, boundary inclusive); last-before-end → LAST
        // (today) bucket (INCLUDED); end-instant doc → EXCLUDED entirely (>= end). When start and last
        // buckets differ (window > 1) these are +1 each; the total-in-window delta is exactly 2.
        Assertions.assertEquals(docFirstBefore + 1, bucketCount(afterDocs, "documents_created", firstBucketDate),
                "a document at the exact window start (00:00 UTC) is INCLUDED in the first bucket");
        Assertions.assertEquals(docLastBefore + 1, bucketCount(afterDocs, "documents_created", lastBucketDate),
                "a document at the last instant before end is INCLUDED in the last (today) bucket");
        long docTotalDelta = totalSeriesCount(afterDocs, "documents_created") - docTotalBefore;
        Assertions.assertEquals(2L, docTotalDelta,
                "exactly two of the three boundary documents fall in the window (the end-instant one is excluded)");

        // ---- activity boundaries (LOG_CREATEDATE_D). Measured in a FRESH before/after that brackets
        // ONLY the three insertAuditRow calls, so no document-create audit noise leaks in. ----
        JsonObject beforeAct = target().path("/app/stats").queryParam("window", window).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class);
        long actFirstBefore = bucketCount(beforeAct, "activity", firstBucketDate);
        long actLastBefore = bucketCount(beforeAct, "activity", lastBucketDate);
        long actTotalBefore = totalSeriesCount(beforeAct, "activity");

        // Three in-set (Document) audit rows at the same three instants.
        insertAuditRow("Document", "CREATE", startMs);
        insertAuditRow("Document", "CREATE", lastBeforeEndMs);
        insertAuditRow("Document", "CREATE", endMs);

        JsonObject afterAct = target().path("/app/stats").queryParam("window", window).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class);
        Assertions.assertEquals(actFirstBefore + 1, bucketCount(afterAct, "activity", firstBucketDate),
                "an audit row at the exact window start (00:00 UTC) is INCLUDED in the first bucket");
        Assertions.assertEquals(actLastBefore + 1, bucketCount(afterAct, "activity", lastBucketDate),
                "an audit row at the last instant before end is INCLUDED in the last (today) bucket");
        long actTotalDelta = totalSeriesCount(afterAct, "activity") - actTotalBefore;
        Assertions.assertEquals(2L, actTotalDelta,
                "exactly two of the three boundary audit rows fall in the window (the end-instant one is excluded)");

        // Clean up the seeded documents (best-effort; leftover soft-deletes don't affect assertions).
        for (String id : new String[] { docAtStart, docAtLastBeforeEnd, docAtEnd }) {
            target().path("/document/" + id).request()
                    .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken).delete();
        }
    }

    /** Creates a document then pins its DOC_CREATEDATE_D to an EXACT millis instant via direct SQL. */
    private String createDatedDocument(String adminToken, String title, long createMs) {
        String id = target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form().param("title", title).param("language", "eng")
                        .param("create_date", Long.toString(createMs))), JsonObject.class)
                .getString("id");
        executeSql("update T_DOCUMENT set DOC_CREATEDATE_D = :d where DOC_ID_C = :id",
                java.util.Map.of("d", new java.util.Date(createMs), "id", id));
        return id;
    }

    /** Returns the count in the named series' bucket for a given yyyy-MM-dd UTC date. */
    private static long bucketCount(JsonObject stats, String series, String date) {
        JsonArray arr = stats.getJsonObject("series").getJsonArray(series);
        for (int i = 0; i < arr.size(); i++) {
            if (arr.getJsonObject(i).getString("date").equals(date)) {
                return arr.getJsonObject(i).getJsonNumber("count").longValue();
            }
        }
        throw new AssertionError("no bucket for " + date + " in series " + series);
    }

    /** Sums every bucket in the named series. */
    private static long totalSeriesCount(JsonObject stats, String series) {
        JsonArray arr = stats.getJsonObject("series").getJsonArray(series);
        long total = 0;
        for (int i = 0; i < arr.size(); i++) {
            total += arr.getJsonObject(i).getJsonNumber("count").longValue();
        }
        return total;
    }

    /**
     * window validation: 7/30/90 are accepted; anything else (missing, 0, 14, negative, huge) is
     * a 400. This is the boundary of the pinned windows contract.
     */
    @Test
    public void testStatsWindowValidation() {
        String adminToken = adminToken();

        for (int good : new int[] { 7, 30, 90 }) {
            Response r = target().path("/app/stats").queryParam("window", good).request()
                    .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                    .get();
            Assertions.assertEquals(Status.OK, Status.fromStatusCode(r.getStatus()), "window=" + good + " is accepted");
            // The accepted window is echoed and drives the bucket count.
            JsonObject json = r.readEntity(JsonObject.class);
            Assertions.assertEquals(good, json.getInt("window"));
            Assertions.assertEquals(good, json.getJsonObject("series").getJsonArray("documents_created").size());
        }

        // A missing window is a 400.
        Response missing = target().path("/app/stats").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get();
        Assertions.assertEquals(Status.BAD_REQUEST, Status.fromStatusCode(missing.getStatus()), "missing window is 400");

        for (int bad : new int[] { 0, 1, 14, 60, 91, -7, 365 }) {
            Response r = target().path("/app/stats").queryParam("window", bad).request()
                    .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                    .get();
            Assertions.assertEquals(Status.BAD_REQUEST, Status.fromStatusCode(r.getStatus()),
                    "window=" + bad + " must be rejected as 400");
        }
    }

    /**
     * The stats endpoint is admin-only: an anonymous caller is 403, and an authenticated
     * NON-admin caller is 403 too (checkBaseFunction(ADMIN)). No data leaks to either.
     */
    @Test
    public void testStatsAdminOnly() {
        // Anonymous (no cookie) → 403.
        Response anon = target().path("/app/stats").queryParam("window", 7).request().get();
        Assertions.assertEquals(Status.FORBIDDEN, Status.fromStatusCode(anon.getStatus()),
                "an anonymous caller must not read stats");

        // Authenticated non-admin → 403 (before window validation runs).
        clientUtil.createUser("stats_nonadmin");
        String userToken = clientUtil.login("stats_nonadmin");
        Response nonAdmin = target().path("/app/stats").queryParam("window", 7).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, userToken)
                .get();
        Assertions.assertEquals(Status.FORBIDDEN, Status.fromStatusCode(nonAdmin.getStatus()),
                "a non-admin authenticated caller must not read stats");
        // A non-admin hitting it with an INVALID window is still 403, not 400 — the auth gate is
        // evaluated before window validation, so no information about validation leaks.
        Response nonAdminBadWindow = target().path("/app/stats").queryParam("window", 14).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, userToken)
                .get();
        Assertions.assertEquals(Status.FORBIDDEN, Status.fromStatusCode(nonAdminBadWindow.getStatus()),
                "the admin gate precedes window validation");
    }

    /**
     * B3 — per-user top-10 ordering is deterministic: storage_current DESC, then username ASC as
     * the tie-breaker. Two users are given EQUAL, VERY HIGH storage that guarantees they land in the
     * top 10 (so the tie-break assertion is never vacuous), and are asserted to appear ADJACENT with
     * the ascending-username user FIRST.
     *
     * <p>The realness trap the reviewer flagged: without an explicit tie-break the DB returns equal
     * rows in an arbitrary (usually insertion/rowid) order, which can COINCIDE with username-ASC and
     * make the assertion vacuous. To force the missing-tie-break to be observable, the user whose
     * username sorts FIRST (the "aaa" one) is CREATED LAST — so the natural insertion order is the
     * REVERSE of the required username order. With the {@code username ASC} clause present the query
     * still returns aaa-before-bbb; remove it and the query returns bbb-before-aaa (insertion order),
     * failing the assertion. A fixed alphabetical relationship (aaa < bbb) with reversed creation
     * order is what makes this mutation reliably caught rather than luck-dependent.
     */
    @Test
    public void testStatsTopUserStorageOrdering() {
        String adminToken = adminToken();

        // Fixed alphabetical relationship (aaa < bbb) with a per-run suffix so re-runs don't collide.
        String suffix = Long.toString(System.nanoTime());
        String tieFirstAlpha = "stats_tie_aaa_" + suffix;  // sorts FIRST by username
        String tieSecondAlpha = "stats_tie_bbb_" + suffix;  // sorts SECOND by username
        // Create in REVERSE-alphabetical order (bbb first, aaa second) so the DB's natural row order
        // is [bbb, aaa]; only the explicit username-ASC tie-break flips it back to [aaa, bbb].
        clientUtil.createUser(tieSecondAlpha);
        clientUtil.createUser(tieFirstAlpha);
        long tieStorage = 9_000_000_000_000L; // dwarfs any real per-user usage in the test DB
        executeSql("update T_USER set USE_STORAGECURRENT_N = :s where USE_USERNAME_C in (:a, :b)",
                java.util.Map.of("s", tieStorage, "a", tieFirstAlpha, "b", tieSecondAlpha));

        JsonObject json = target().path("/app/stats").queryParam("window", 7).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class);
        JsonArray perUser = json.getJsonObject("storage").getJsonArray("per_user");

        // At most 10 rows.
        Assertions.assertTrue(perUser.size() <= 10, "per_user is capped at the top 10");

        // The list is globally sorted by storage_current DESC (non-increasing) — the primary key.
        long prevStorage = Long.MAX_VALUE;
        int idxFirstAlpha = -1;
        int idxSecondAlpha = -1;
        for (int i = 0; i < perUser.size(); i++) {
            JsonObject row = perUser.getJsonObject(i);
            long current = row.getJsonNumber("storage_current").longValue();
            Assertions.assertTrue(current <= prevStorage, "storage_current must be non-increasing");
            prevStorage = current;
            String username = row.getString("username");
            if (username.equals(tieFirstAlpha)) idxFirstAlpha = i;
            if (username.equals(tieSecondAlpha)) idxSecondAlpha = i;
        }

        // BOTH tie users are present (their high storage guarantees a top-10 slot).
        Assertions.assertTrue(idxFirstAlpha >= 0, "the ascending-username tie user must appear in the top 10");
        Assertions.assertTrue(idxSecondAlpha >= 0, "the descending-username tie user must appear in the top 10");
        // They carry the SAME storage (a genuine tie), so ONLY the username tie-break decides order.
        Assertions.assertEquals(tieStorage, perUser.getJsonObject(idxFirstAlpha).getJsonNumber("storage_current").longValue());
        Assertions.assertEquals(tieStorage, perUser.getJsonObject(idxSecondAlpha).getJsonNumber("storage_current").longValue());
        // Fixture sanity: aaa sorts before bbb, but aaa was created LAST (reverse insertion order).
        Assertions.assertTrue(tieFirstAlpha.compareTo(tieSecondAlpha) < 0, "fixture sanity: aaa sorts before bbb");
        // The ascending-username user must come IMMEDIATELY BEFORE the other. Absent the username-ASC
        // tie-break the DB returns them in insertion order [bbb, aaa] and idxFirstAlpha > idxSecondAlpha.
        Assertions.assertEquals(1, idxSecondAlpha - idxFirstAlpha,
                "equal-storage tie users must be adjacent with the ascending username FIRST (username ASC tie-break)");
    }

    /**
     * Minimal JSON string-content escaping for embedding a raw URL inside a JSON
     * literal in the footer-links test payloads (backslash and double-quote only —
     * the evasion URLs contain neither, but this keeps the payloads well-formed).
     */
    private static String jsonEscape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

}
