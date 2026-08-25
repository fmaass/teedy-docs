package com.sismics.docs.rest;

import com.sismics.docs.core.util.TransactionUtil;
import com.sismics.util.context.ThreadLocalContext;
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
 * Access counters end to end (#300): what the server RECORDS as an access, and who is allowed to see
 * which numbers.
 *
 * <p>The recording rule under test: a document access is one successful {@code GET /document/{id}}; a
 * file access is one successful {@code GET /file/{id}/data} serving the file's own bytes — {@code size}
 * absent (download / PDF preview) or {@code size=web} (preview rendition). The {@code thumb} raster the
 * list and gallery render, and the {@code content} text the app reads internally, are artifacts of one
 * logical open and are NOT accesses. Anonymous share reads are attributed to nobody and record nothing.</p>
 *
 * <p>The visibility rules under test: {@code GET /access/document/{id}} answers with the CALLER's own
 * numbers only and has no parameter that could ask for anyone else's; {@code GET /access/stats} is
 * administrator-only, server-side.</p>
 */
public class TestAccessResource extends BaseJerseyTest {
    private String createDocument(String token, String title) {
        JsonObject json = target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .put(Entity.form(new Form()
                        .param("title", title)
                        .param("language", "eng")
                        .param("create_date", Long.toString(new Date().getTime()))), JsonObject.class);
        return json.getString("id");
    }

    /** One document open, exactly as the SPA's document view issues it. */
    private void openDocument(String token, String documentId) {
        Response response = target().path("/document/" + documentId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .get();
        Assertions.assertEquals(200, response.getStatus());
        response.close();
    }

    /** One file fetch at the given size variation ({@code null} = the original bytes). */
    private void fetchFile(String token, String fileId, String size) {
        var request = size == null
                ? target().path("/file/" + fileId + "/data")
                : target().path("/file/" + fileId + "/data").queryParam("size", size);
        Response response = request.request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .get();
        Assertions.assertEquals(200, response.getStatus());
        response.close();
    }

    private JsonObject personalCounts(String token, String documentId) {
        return target().path("/access/document/" + documentId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .get(JsonObject.class);
    }

    private Response statsResponse(String token) {
        return target().path("/access/stats").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .get();
    }

    private long fileCount(JsonObject counts, String fileId) {
        JsonArray files = counts.getJsonArray("files");
        for (int i = 0; i < files.size(); i++) {
            JsonObject file = files.getJsonObject(i);
            if (fileId.equals(file.getString("id"))) {
                return file.getJsonNumber("count").longValue();
            }
        }
        return -1L;
    }

    @Test
    public void testDocumentOpensAreCountedForTheOpeningUser() {
        String adminToken = adminToken();
        String documentId = createDocument(adminToken, "Access counted doc");

        // Creating a document is not opening it.
        Assertions.assertEquals(0, personalCounts(adminToken, documentId).getJsonNumber("count").longValue(),
                "a freshly created, never-opened document has no recorded access");

        openDocument(adminToken, documentId);
        openDocument(adminToken, documentId);

        Assertions.assertEquals(2, personalCounts(adminToken, documentId).getJsonNumber("count").longValue(),
                "each successful GET /document/{id} is one recorded access");
    }

    @Test
    public void testListingDocumentsRecordsNothing() {
        String adminToken = adminToken();
        String documentId = createDocument(adminToken, "Access list-free doc");

        target().path("/document/list").queryParam("limit", 10).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class);

        Assertions.assertEquals(0, personalCounts(adminToken, documentId).getJsonNumber("count").longValue(),
                "browsing the document list must never count as opening the documents in it");
    }

    @Test
    public void testFileBytesCountButThumbnailAndContentDoNot() throws Exception {
        String adminToken = adminToken();
        String documentId = createDocument(adminToken, "Access file doc");
        String fileId = clientUtil.addFileToDocument(FILE_PIA_00452_JPG, adminToken, documentId);

        // The artifacts of ONE logical open: the list/gallery raster and the extracted text.
        fetchFile(adminToken, fileId, "thumb");
        fetchFile(adminToken, fileId, "content");
        Assertions.assertEquals(0, fileCount(personalCounts(adminToken, documentId), fileId),
                "a thumbnail raster and the extracted content text are not file accesses");

        // The file's own bytes: the preview rendition and the download.
        fetchFile(adminToken, fileId, "web");
        fetchFile(adminToken, fileId, null);
        Assertions.assertEquals(2, fileCount(personalCounts(adminToken, documentId), fileId),
                "viewing the preview and downloading the file are each one file access");
    }

    @Test
    public void testPersonalCountsNeverIncludeAnotherUsersAccesses() throws Exception {
        String adminToken = adminToken();
        clientUtil.createUser("acc300reader");
        String readerToken = clientUtil.login("acc300reader");

        String documentId = createDocument(adminToken, "Access private doc");
        String fileId = clientUtil.addFileToDocument(FILE_PIA_00452_JPG, adminToken, documentId);

        // Grant the reader READ so it can genuinely open the document.
        target().path("/acl").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form()
                        .param("source", documentId)
                        .param("perm", "READ")
                        .param("target", "acc300reader")
                        .param("type", "USER")), JsonObject.class);

        openDocument(adminToken, documentId);
        openDocument(readerToken, documentId);
        openDocument(readerToken, documentId);
        openDocument(readerToken, documentId);
        fetchFile(readerToken, fileId, null);

        JsonObject adminCounts = personalCounts(adminToken, documentId);
        Assertions.assertEquals(1, adminCounts.getJsonNumber("count").longValue(),
                "the admin's own count must stay at its own single open");
        Assertions.assertEquals(0, fileCount(adminCounts, fileId),
                "and must not inherit the reader's file download");

        JsonObject readerCounts = personalCounts(readerToken, documentId);
        Assertions.assertEquals(3, readerCounts.getJsonNumber("count").longValue(),
                "the reader sees exactly its own three opens");
        Assertions.assertEquals(1, fileCount(readerCounts, fileId));
    }

    @Test
    public void testPersonalCountsRequireReadOnTheDocument() throws Exception {
        String adminToken = adminToken();
        clientUtil.createUser("acc300outsider");
        String outsiderToken = clientUtil.login("acc300outsider");
        String documentId = createDocument(adminToken, "Access unreadable doc");

        Response response = target().path("/access/document/" + documentId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, outsiderToken)
                .get();
        Assertions.assertEquals(404, response.getStatus(),
                "counts for a document the caller cannot read are not-found, exactly like the document itself");

        // Positive control: the SAME caller, the SAME document, once READ is granted. Without this the
        // 404 above would also pass against a route that does not exist.
        target().path("/acl").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form()
                        .param("source", documentId)
                        .param("perm", "READ")
                        .param("target", "acc300outsider")
                        .param("type", "USER")), JsonObject.class);
        Assertions.assertEquals(200, target().path("/access/document/" + documentId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, outsiderToken)
                .get().getStatus());
    }

    @Test
    public void testAdminStatsAreRefusedToNonAdmins() throws Exception {
        clientUtil.createUser("acc300plain");
        String plainToken = clientUtil.login("acc300plain");

        Response response = statsResponse(plainToken);
        Assertions.assertEquals(403, response.getStatus(),
                "the aggregate access statistics are administrator-only, server-side");
        Assertions.assertEquals("ForbiddenError", response.readEntity(JsonObject.class).getString("type"));

        // Positive control: the same request as an administrator succeeds, so the 403 above is the guard
        // and not a broken route.
        Assertions.assertEquals(200, statsResponse(adminToken()).getStatus());
    }

    @Test
    public void testAdminStatsAggregateEveryUsersAccesses() throws Exception {
        String adminToken = adminToken();
        clientUtil.createUser("acc300colleague");
        String colleagueToken = clientUtil.login("acc300colleague");

        String documentId = createDocument(adminToken, "Access aggregated doc");
        target().path("/acl").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form()
                        .param("source", documentId)
                        .param("perm", "READ")
                        .param("target", "acc300colleague")
                        .param("type", "USER")), JsonObject.class);

        openDocument(adminToken, documentId);
        openDocument(colleagueToken, documentId);
        openDocument(colleagueToken, documentId);

        JsonObject stats = statsResponse(adminToken).readEntity(JsonObject.class);
        Assertions.assertTrue(stats.getJsonNumber("total_document_accesses").longValue() >= 3);

        JsonArray documents = stats.getJsonArray("documents");
        JsonObject ranked = null;
        for (int i = 0; i < documents.size(); i++) {
            if (documentId.equals(documents.getJsonObject(i).getString("id"))) {
                ranked = documents.getJsonObject(i);
            }
        }
        Assertions.assertNotNull(ranked, "the opened document must be ranked for an administrator");
        Assertions.assertEquals("Access aggregated doc", ranked.getString("title"));
        Assertions.assertEquals(3, ranked.getJsonNumber("total").longValue(),
                "the administrator total spans every user's opens");

        JsonArray users = ranked.getJsonArray("users");
        Assertions.assertEquals(2, users.size(), "both users appear in the per-user breakdown");
        Assertions.assertEquals("acc300colleague", users.getJsonObject(0).getString("username"),
                "the most active user is listed first");
        Assertions.assertEquals(2, users.getJsonObject(0).getJsonNumber("count").longValue());
        Assertions.assertEquals("admin", users.getJsonObject(1).getString("username"));
        Assertions.assertEquals(1, users.getJsonObject(1).getJsonNumber("count").longValue());
    }

    @Test
    public void testPersonalCountsAreRefusedToAnonymous() {
        String adminToken = adminToken();
        String documentId = createDocument(adminToken, "Access anon doc");

        Response response = target().path("/access/document/" + documentId).request().get();
        Assertions.assertEquals(403, response.getStatus(),
                "there is no anonymous personal count: an anonymous reader has no identity to count for");
    }

    /**
     * Runs {@code body} with {@code T_ACCESS_EVENT} renamed away, so every attempt to record an access
     * fails the way a missing table, a read-only database or a full disk would: not at the call, but
     * when the insert reaches the database. The table is always renamed back.
     */
    private void withAccessRecordingBroken(Runnable body) {
        renameTable("T_ACCESS_EVENT", "T_ACCESS_EVENT_BROKEN");
        try {
            body.run();
        } finally {
            renameTable("T_ACCESS_EVENT_BROKEN", "T_ACCESS_EVENT");
        }
    }

    private void renameTable(String from, String to) {
        TransactionUtil.handle(() -> ThreadLocalContext.get().getEntityManager()
                .createNativeQuery("alter table " + from + " rename to " + to)
                .executeUpdate());
    }

    @Test
    public void testAReadStillSucceedsWhenTheAccessRecordingFails() throws Exception {
        String adminToken = adminToken();
        String documentId = createDocument(adminToken, "Access resilient doc");
        String fileId = clientUtil.addFileToDocument(FILE_PIA_00452_JPG, adminToken, documentId);

        withAccessRecordingBroken(() -> {
            // Reading a document the caller is entitled to must not depend on the counter working. The
            // BODY is asserted, not only the status: a 200 carrying a broken payload would be no better.
            Response documentResponse = target().path("/document/" + documentId).request()
                    .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                    .get();
            Assertions.assertEquals(200, documentResponse.getStatus(),
                    "a failing access recorder must never turn an authorized document read into an error");
            Assertions.assertEquals("Access resilient doc",
                    documentResponse.readEntity(JsonObject.class).getString("title"));

            // Same for the file bytes.
            Response fileResponse = target().path("/file/" + fileId + "/data").request()
                    .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                    .get();
            Assertions.assertEquals(200, fileResponse.getStatus(),
                    "a failing access recorder must never turn an authorized file read into an error");
            Assertions.assertTrue(fileResponse.readEntity(byte[].class).length > 0,
                    "the file's bytes are still served in full");
        });

        // Control, and the non-vacuity proof: with recording working again the SAME read records, and
        // records exactly ONCE. Without this, a recorder that never ran at all would pass the block above.
        openDocument(adminToken, documentId);
        Assertions.assertEquals(1, personalCounts(adminToken, documentId).getJsonNumber("count").longValue(),
                "one read records exactly one access - the two reads taken while recording was broken are "
                        + "dropped, and the isolated write is not replayed");
    }

    @Test
    public void testAnonymousShareReadRecordsNothing() {
        String adminToken = adminToken();
        String documentId = createDocument(adminToken, "Access shared doc");

        String shareId = target().path("/share").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form().param("id", documentId)), JsonObject.class)
                .getString("id");

        // A logged-out visitor reading through the share link.
        Response response = target().path("/document/" + documentId).queryParam("share", shareId).request().get();
        Assertions.assertEquals(200, response.getStatus(), "control: the share link really does serve the document");
        response.close();

        Assertions.assertEquals(0, personalCounts(adminToken, documentId).getJsonNumber("count").longValue(),
                "an anonymous share read is attributed to nobody and is recorded against nobody");
    }
}
