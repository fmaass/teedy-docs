package com.sismics.docs.rest;

import com.google.common.io.ByteStreams;
import com.sismics.util.filter.TokenBasedSecurityFilter;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Form;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;

import java.io.InputStream;

/**
 * End-to-end HTTP tests for the PDF page-operations endpoint (POST /file/:id/pages): it saves a new
 * version, preserves the original in history, rejects a stale-base replay with 409, rejects an empty
 * result and a non-PDF and an over-ceiling source with a typed 400, and requires WRITE on the parent
 * document. The manifests are built against the fixture's real page count (read back over the wire) so
 * the assertions do not depend on a hard-coded page total.
 */
public class TestFilePageOperations extends BaseJerseyTest {

    private Response applyPages(String token, String fileId, String manifest) {
        return target().path("/file/" + fileId + "/pages").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .post(Entity.form(new Form().param("manifest", manifest)));
    }

    /**
     * Download the (decrypted) original bytes of a file version and return its PDF page count.
     */
    private int downloadedPageCount(String token, String fileId) throws Exception {
        Response response = target().path("/file/" + fileId + "/data").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .get();
        Assertions.assertEquals(Status.OK, Status.fromStatusCode(response.getStatus()));
        byte[] bytes;
        try (InputStream is = (InputStream) response.getEntity()) {
            bytes = ByteStreams.toByteArray(is);
        }
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            return doc.getNumberOfPages();
        }
    }

    private String keepAllReversedManifest(int pageCount, int baseVersion) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"version\":1,\"baseVersion\":").append(baseVersion).append(",\"pages\":[");
        for (int i = pageCount - 1; i >= 0; i--) {
            sb.append("{\"source\":").append(i).append("}");
            if (i > 0) {
                sb.append(",");
            }
        }
        sb.append("]}");
        return sb.toString();
    }

    private String dropLastManifest(int pageCount, int baseVersion) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"version\":1,\"baseVersion\":").append(baseVersion).append(",\"pages\":[");
        for (int i = 0; i < pageCount - 1; i++) {
            sb.append("{\"source\":").append(i).append("}");
            if (i < pageCount - 2) {
                sb.append(",");
            }
        }
        sb.append("]}");
        return sb.toString();
    }

    @Test
    public void deleteCreatesNewVersionAndPreservesOriginal() throws Exception {
        clientUtil.createUser("pageops_owner", 100_000_000);
        String token = clientUtil.login("pageops_owner");
        String documentId = clientUtil.createDocument(token);
        String fileId = clientUtil.addFileToDocument(FILE_WIKIPEDIA_PDF, token, documentId);

        int sourcePages = downloadedPageCount(token, fileId);
        Assertions.assertTrue(sourcePages >= 2, "the PDF fixture must have at least two pages");

        // Drop the last page: the result is a new latest version with one fewer page.
        Response response = applyPages(token, fileId, dropLastManifest(sourcePages, 0));
        Assertions.assertEquals(Status.OK, Status.fromStatusCode(response.getStatus()));
        JsonObject json = response.readEntity(JsonObject.class);
        Assertions.assertEquals("ok", json.getString("status"));
        String newFileId = json.getString("id");
        Assertions.assertNotEquals(fileId, newFileId, "a new version row is created");

        // The new latest version has the expected page tree size.
        Assertions.assertEquals(sourcePages - 1, downloadedPageCount(token, newFileId),
                "the new version must have one fewer page");

        // The original stays in history, byte-intact (still its original page count, still downloadable).
        Assertions.assertEquals(sourcePages, downloadedPageCount(token, fileId),
                "the original version must be preserved unchanged");

        // The version chain lists exactly two versions.
        JsonObject versions = target().path("/file/" + newFileId + "/versions").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .get(JsonObject.class);
        Assertions.assertEquals(2, versions.getJsonArray("files").size(),
                "the original and the new version must both be in history");

        // The document's file list shows exactly one latest file for the document.
        JsonObject list = target().path("/file/list").queryParam("id", documentId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .get(JsonObject.class);
        Assertions.assertEquals(1, list.getJsonArray("files").size(),
                "a page operation must not create a second latest file");
    }

    @Test
    public void staleBaseReplayReturnsConflict() throws Exception {
        clientUtil.createUser("pageops_stale", 100_000_000);
        String token = clientUtil.login("pageops_stale");
        String documentId = clientUtil.createDocument(token);
        String fileId = clientUtil.addFileToDocument(FILE_WIKIPEDIA_PDF, token, documentId);
        int sourcePages = downloadedPageCount(token, fileId);

        String manifest = keepAllReversedManifest(sourcePages, 0);

        // First application succeeds and demotes the operated base.
        Response first = applyPages(token, fileId, manifest);
        Assertions.assertEquals(Status.OK, Status.fromStatusCode(first.getStatus()));
        String newFileId = first.readEntity(JsonObject.class).getString("id");

        // Replaying the SAME manifest against the now-stale base is a 409 Conflict (affected-row CAS),
        // not a duplicate version.
        Response replay = applyPages(token, fileId, manifest);
        Assertions.assertEquals(Status.CONFLICT, Status.fromStatusCode(replay.getStatus()));

        JsonObject list = target().path("/file/list").queryParam("id", documentId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .get(JsonObject.class);
        Assertions.assertEquals(1, list.getJsonArray("files").size(),
                "a stale-base replay must not create a second latest version");

        // History is EXACTLY the two versions (original + the single successful op) — the failed replay
        // added no hidden extra row.
        JsonObject versions = target().path("/file/" + newFileId + "/versions").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .get(JsonObject.class);
        Assertions.assertEquals(2, versions.getJsonArray("files").size(),
                "the failed replay must not add a hidden version");
    }

    @Test
    public void emptyResultRejected() throws Exception {
        clientUtil.createUser("pageops_empty", 100_000_000);
        String token = clientUtil.login("pageops_empty");
        String documentId = clientUtil.createDocument(token);
        String fileId = clientUtil.addFileToDocument(FILE_WIKIPEDIA_PDF, token, documentId);

        Response response = applyPages(token, fileId, "{\"version\":1,\"baseVersion\":0,\"pages\":[]}");
        Assertions.assertEquals(Status.BAD_REQUEST, Status.fromStatusCode(response.getStatus()));
        Assertions.assertEquals("EmptyResult", response.readEntity(JsonObject.class).getString("type"));
    }

    @Test
    public void nonPdfRejected() throws Exception {
        clientUtil.createUser("pageops_nonpdf", 100_000_000);
        String token = clientUtil.login("pageops_nonpdf");
        String documentId = clientUtil.createDocument(token);
        String fileId = clientUtil.addFileToDocument(FILE_EINSTEIN_ROOSEVELT_LETTER_PNG, token, documentId);

        Response response = applyPages(token, fileId, "{\"version\":1,\"baseVersion\":0,\"pages\":[{\"source\":0}]}");
        Assertions.assertEquals(Status.BAD_REQUEST, Status.fromStatusCode(response.getStatus()));
        Assertions.assertEquals("NotPdf", response.readEntity(JsonObject.class).getString("type"));
    }

    @Test
    public void overOutputPageCeilingRejected() throws Exception {
        clientUtil.createUser("pageops_ceiling", 100_000_000);
        String token = clientUtil.login("pageops_ceiling");
        String documentId = clientUtil.createDocument(token);
        String fileId = clientUtil.addFileToDocument(FILE_WIKIPEDIA_PDF, token, documentId);
        int sourcePages = downloadedPageCount(token, fileId);
        Assertions.assertTrue(sourcePages > 1, "the fixture must exceed the one-page ceiling under test");

        // Tighten the configurable OUTPUT-page ceiling to 1: a manifest keeping ALL pages exceeds it.
        System.setProperty("docs.pdf_page_ops_max_pages", "1");
        try {
            Response response = applyPages(token, fileId, keepAllReversedManifest(sourcePages, 0));
            Assertions.assertEquals(Status.BAD_REQUEST, Status.fromStatusCode(response.getStatus()));
            Assertions.assertEquals("TooManyPages",
                    response.readEntity(JsonObject.class).getString("type"));
        } finally {
            System.clearProperty("docs.pdf_page_ops_max_pages");
        }

        // The rejected attempt created no new version.
        JsonObject list = target().path("/file/list").queryParam("id", documentId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .get(JsonObject.class);
        Assertions.assertEquals(1, list.getJsonArray("files").size());
    }

    @Test
    public void largeSourceReducedWithinCeilingIsAllowed() throws Exception {
        clientUtil.createUser("pageops_reduce", 100_000_000);
        String token = clientUtil.login("pageops_reduce");
        String documentId = clientUtil.createDocument(token);
        String fileId = clientUtil.addFileToDocument(FILE_WIKIPEDIA_PDF, token, documentId);
        int sourcePages = downloadedPageCount(token, fileId);
        Assertions.assertTrue(sourcePages > 1, "the fixture must have more pages than the output ceiling");

        // Even with the OUTPUT ceiling at 1, reducing a many-page source to a SINGLE output page is legal —
        // the ceiling bounds the output, not the source.
        System.setProperty("docs.pdf_page_ops_max_pages", "1");
        try {
            Response response = applyPages(token, fileId, "{\"version\":1,\"baseVersion\":0,\"pages\":[{\"source\":0}]}");
            Assertions.assertEquals(Status.OK, Status.fromStatusCode(response.getStatus()));
            String newFileId = response.readEntity(JsonObject.class).getString("id");
            Assertions.assertEquals(1, downloadedPageCount(token, newFileId),
                    "the reduced output must have exactly one page");
        } finally {
            System.clearProperty("docs.pdf_page_ops_max_pages");
        }
    }

    @Test
    public void orphanFileRejected() throws Exception {
        clientUtil.createUser("pageops_orphan", 100_000_000);
        String token = clientUtil.login("pageops_orphan");
        // An owned orphan PDF (no parent document): page-ops would fork an unrelated file, so it must be a
        // typed 400, not a silent fork.
        String orphanFileId = clientUtil.addFileToDocument(FILE_WIKIPEDIA_PDF, token, null);

        Response response = applyPages(token, orphanFileId, "{\"version\":1,\"baseVersion\":0,\"pages\":[{\"source\":0}]}");
        Assertions.assertEquals(Status.BAD_REQUEST, Status.fromStatusCode(response.getStatus()));
        Assertions.assertEquals("PreviousVersionMismatch",
                response.readEntity(JsonObject.class).getString("type"));

        // The original orphan is untouched (still downloadable, still its original page count).
        Assertions.assertTrue(downloadedPageCount(token, orphanFileId) > 1,
                "the original orphan file must be preserved unchanged");
    }

    @Test
    public void readOnlyPrincipalIsForbidden() throws Exception {
        clientUtil.createUser("pageops_owner2", 100_000_000);
        clientUtil.createUser("pageops_reader");
        String ownerToken = clientUtil.login("pageops_owner2");
        String readerToken = clientUtil.login("pageops_reader");
        String documentId = clientUtil.createDocument(ownerToken);
        String fileId = clientUtil.addFileToDocument(FILE_WIKIPEDIA_PDF, ownerToken, documentId);

        // Grant the reader READ-only on the parent document.
        target().path("/acl").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, ownerToken)
                .put(Entity.form(new Form()
                        .param("source", documentId)
                        .param("perm", "READ")
                        .param("target", "pageops_reader")
                        .param("type", "USER")), JsonObject.class);

        Response response = applyPages(readerToken, fileId, "{\"version\":1,\"baseVersion\":0,\"pages\":[{\"source\":0}]}");
        Assertions.assertEquals(Status.FORBIDDEN, Status.fromStatusCode(response.getStatus()),
                "a READ-only principal must get 403 from the page-operations endpoint");
    }
}
