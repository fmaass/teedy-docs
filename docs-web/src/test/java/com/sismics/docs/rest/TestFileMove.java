package com.sismics.docs.rest;

import com.sismics.docs.core.dao.FileDao;
import com.sismics.docs.core.util.ContentMacUtil;
import com.sismics.docs.core.util.TransactionUtil;
import com.sismics.util.filter.TokenBasedSecurityFilter;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Form;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

/**
 * REST tests for moving a file to another document ({@code POST /file/{id}/move}, #175): the WRITE-on-both
 * permission matrix, 404s, the same-document error, an ordinary single-file move, a two-version chain move
 * (versions intact, delete-promote at the target), trashed-target rejection, unchanged owner/quota, source
 * cover fallback + empty-target thumbnail, content-MAC re-key (overwrite) and clear, the post-commit search
 * re-point, and audit on both documents.
 */
public class TestFileMove extends BaseJerseyTest {

    @AfterEach
    public void disableDedup() {
        ContentMacUtil.resetForTest();
    }

    private Response moveRequest(String fileId, String targetDocumentId, String token) {
        return target().path("/file/" + fileId + "/move").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .post(Entity.form(new Form().param("targetDocumentId", targetDocumentId)));
    }

    private void moveOk(String fileId, String targetDocumentId, String token) {
        Response r = moveRequest(fileId, targetDocumentId, token);
        Assertions.assertEquals(Status.OK, Status.fromStatusCode(r.getStatus()),
                "move should succeed: " + r.readEntity(String.class));
    }

    private void grantAcl(String documentId, String perm, String targetUser, String grantorToken) {
        target().path("/acl").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, grantorToken)
                .put(Entity.form(new Form()
                        .param("source", documentId)
                        .param("perm", perm)
                        .param("target", targetUser)
                        .param("type", "USER")), JsonObject.class);
    }

    private JsonObject getDocument(String documentId, String token) {
        return target().path("/document/" + documentId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .get(JsonObject.class);
    }

    private JsonArray filesInDocument(String documentId, String token) {
        return target().path("/file/list").queryParam("id", documentId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .get(JsonObject.class).getJsonArray("files");
    }

    private String contentMac(String fileId) {
        AtomicReference<String> ref = new AtomicReference<>();
        TransactionUtil.handle(() -> ref.set(new FileDao().getActiveById(fileId).getContentMac()));
        return ref.get();
    }

    // --- permission matrix ----------------------------------------------------------------------------

    @Test
    public void testMoveRequiresWriteOnBothDocuments() throws Exception {
        clientUtil.createUser("mv_owner");
        String owner = clientUtil.login("mv_owner");
        clientUtil.createUser("mv_collab");
        String collab = clientUtil.login("mv_collab");

        String docA = clientUtil.createDocument(owner);
        String docB = clientUtil.createDocument(owner);
        String fileId = clientUtil.addFileToDocument(FILE_DOCUMENT_TXT, owner, docA);

        // Collab can only READ both documents: no WRITE on the source -> 403.
        grantAcl(docA, "READ", "mv_collab", owner);
        grantAcl(docB, "READ", "mv_collab", owner);
        Assertions.assertEquals(Status.FORBIDDEN, Status.fromStatusCode(moveRequest(fileId, docB, collab).getStatus()),
                "a READ-only user must not move a file out of the source document");

        // WRITE on the source but only READ on the target -> 403.
        grantAcl(docA, "WRITE", "mv_collab", owner);
        Assertions.assertEquals(Status.FORBIDDEN, Status.fromStatusCode(moveRequest(fileId, docB, collab).getStatus()),
                "WRITE on the source alone must not authorize a move into a read-only target");

        // WRITE on both -> success.
        grantAcl(docB, "WRITE", "mv_collab", owner);
        moveOk(fileId, docB, collab);
    }

    @Test
    public void testNotFound() throws Exception {
        clientUtil.createUser("mv_nf");
        String token = clientUtil.login("mv_nf");
        String docA = clientUtil.createDocument(token);
        String docB = clientUtil.createDocument(token);
        String fileId = clientUtil.addFileToDocument(FILE_DOCUMENT_TXT, token, docA);

        // Unknown file -> 404.
        Assertions.assertEquals(Status.NOT_FOUND,
                Status.fromStatusCode(moveRequest("00000000-0000-0000-0000-000000000000", docB, token).getStatus()));

        // Unknown target document -> 404.
        Assertions.assertEquals(Status.NOT_FOUND,
                Status.fromStatusCode(moveRequest(fileId, "00000000-0000-0000-0000-000000000000", token).getStatus()));
    }

    @Test
    public void testSameDocumentIsAnError() throws Exception {
        clientUtil.createUser("mv_same");
        String token = clientUtil.login("mv_same");
        String docA = clientUtil.createDocument(token);
        String fileId = clientUtil.addFileToDocument(FILE_DOCUMENT_TXT, token, docA);

        Response r = moveRequest(fileId, docA, token);
        Assertions.assertEquals(Status.BAD_REQUEST, Status.fromStatusCode(r.getStatus()));
        Assertions.assertTrue(r.readEntity(String.class).contains("SameDocument"));
    }

    // --- ordinary and chain moves ---------------------------------------------------------------------

    @Test
    public void testOrdinaryMoveKeepsOwnerAndQuota() throws Exception {
        clientUtil.createUser("mv_ord");
        String token = clientUtil.login("mv_ord");
        String docA = clientUtil.createDocument(token);
        String docB = clientUtil.createDocument(token);
        String fileId = clientUtil.addFileToDocument(FILE_PIA_00452_JPG, token, docA);
        awaitAsyncQuiescence("file processing must settle before the move");

        long quotaBefore = target().path("/user").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token).get(JsonObject.class)
                .getJsonNumber("storage_current").longValue();

        moveOk(fileId, docB, token);

        Assertions.assertEquals(0, filesInDocument(docA, token).size(), "the source document loses the file");
        JsonArray targetFiles = filesInDocument(docB, token);
        Assertions.assertEquals(1, targetFiles.size(), "the target document gains exactly one file");
        Assertions.assertEquals(fileId, targetFiles.getJsonObject(0).getString("id"));
        Assertions.assertEquals("mv_ord", targetFiles.getJsonObject(0).getString("creator"), "the file owner is unchanged");

        long quotaAfter = target().path("/user").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token).get(JsonObject.class)
                .getJsonNumber("storage_current").longValue();
        Assertions.assertEquals(quotaBefore, quotaAfter, "a move must not change quota attribution");
    }

    @Test
    public void testChainMoveKeepsVersionsAndPromotesAtTarget() throws Exception {
        clientUtil.createUser("mv_chain");
        String token = clientUtil.login("mv_chain");
        String docA = clientUtil.createDocument(token);
        String docB = clientUtil.createDocument(token);
        String v0 = clientUtil.addFileToDocument(FILE_DOCUMENT_TXT, token, docA);
        String v1 = clientUtil.addFileToDocumentReplacing(FILE_PIA_00452_JPG, token, docA, v0);

        moveOk(v1, docB, token);

        Assertions.assertEquals(0, filesInDocument(docA, token).size(), "the whole chain leaves the source");
        JsonArray targetFiles = filesInDocument(docB, token);
        Assertions.assertEquals(1, targetFiles.size(), "only the latest version shows in the target file list");
        Assertions.assertEquals(v1, targetFiles.getJsonObject(0).getString("id"));

        JsonArray versions = target().path("/file/" + v1 + "/versions").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .get(JsonObject.class).getJsonArray("files");
        Assertions.assertEquals(2, versions.size(), "both versions remain reachable at the target");

        // Deleting the latest at the target promotes the prior version within the target.
        target().path("/file/" + v1).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token).delete();
        JsonArray afterDelete = filesInDocument(docB, token);
        Assertions.assertEquals(1, afterDelete.size());
        Assertions.assertEquals(v0, afterDelete.getJsonObject(0).getString("id"), "the prior version is promoted at the target");
    }

    @Test
    public void testTrashedTargetRejected() throws Exception {
        clientUtil.createUser("mv_trash");
        String token = clientUtil.login("mv_trash");
        String docA = clientUtil.createDocument(token);
        String docB = clientUtil.createDocument(token);
        String fileId = clientUtil.addFileToDocument(FILE_DOCUMENT_TXT, token, docA);

        // Trash the target, then attempt the move.
        target().path("/document/" + docB).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token).delete();
        Assertions.assertEquals(Status.NOT_FOUND, Status.fromStatusCode(moveRequest(fileId, docB, token).getStatus()),
                "a move into a trashed document must be rejected");
    }

    // --- serving cover reconciliation -----------------------------------------------------------------

    @Test
    public void testCoverFallbackOnSourceAndEmptyTargetThumbnail() throws Exception {
        clientUtil.createUser("mv_cover");
        String token = clientUtil.login("mv_cover");
        String docA = clientUtil.createDocument(token);
        String docB = clientUtil.createDocument(token);
        String file1 = clientUtil.addFileToDocument(FILE_PIA_00452_JPG, token, docA);
        String file2 = clientUtil.addFileToDocument(FILE_EINSTEIN_ROOSEVELT_LETTER_PNG, token, docA);
        awaitAsyncQuiescence("uploads must settle before setting the cover");

        // Pin the moved file as the source's explicit cover.
        target().path("/document/" + docA + "/cover").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .post(Entity.form(new Form().param("file", file1)), JsonObject.class);
        awaitAsyncQuiescence("cover selection must settle");
        Assertions.assertEquals(file1, getDocument(docA, token).getString("file_id"));

        moveOk(file1, docB, token);
        awaitAsyncQuiescence("the move must reconcile both served covers");

        JsonObject sourceDoc = getDocument(docA, token);
        Assertions.assertTrue(sourceDoc.isNull("file_id_cover"), "the source's dangling explicit cover is cleared");
        Assertions.assertEquals(file2, sourceDoc.getString("file_id"), "the source cover falls back to the remaining file");

        Assertions.assertEquals(file1, getDocument(docB, token).getString("file_id"),
                "a previously empty target gains the moved file as its thumbnail");
    }

    // --- content MAC re-key ---------------------------------------------------------------------------

    @Test
    public void testMacOverwrittenForTargetDocument() throws Exception {
        ContentMacUtil.setMasterKeyForTest("move-master-secret");
        clientUtil.createUser("mv_mac_ow");
        String token = clientUtil.login("mv_mac_ow");
        String docA = clientUtil.createDocument(token);
        String docB = clientUtil.createDocument(token);
        String fileId = clientUtil.addFileToDocument(FILE_DOCUMENT_TXT, token, docA);
        awaitAsyncQuiescence("upload must settle");

        String macBefore = contentMac(fileId);
        Assertions.assertNotNull(macBefore, "the source-keyed MAC is present with the feature on");

        moveOk(fileId, docB, token);

        String macAfter = contentMac(fileId);
        Assertions.assertNotNull(macAfter, "the moved row keeps a MAC with the feature on");
        Assertions.assertNotEquals(macBefore, macAfter, "the MAC is re-keyed to the target document");
    }

    @Test
    public void testMacClearedWhenFeatureOff() throws Exception {
        ContentMacUtil.setMasterKeyForTest("move-master-secret");
        clientUtil.createUser("mv_mac_clr");
        String token = clientUtil.login("mv_mac_clr");
        String docA = clientUtil.createDocument(token);
        String docB = clientUtil.createDocument(token);
        String fileId = clientUtil.addFileToDocument(FILE_DOCUMENT_TXT, token, docA);
        awaitAsyncQuiescence("upload must settle");
        Assertions.assertNotNull(contentMac(fileId), "the file starts with a source-keyed MAC");

        // Disable the feature, then move: a MAC keyed to the source must never survive.
        ContentMacUtil.resetForTest();
        moveOk(fileId, docB, token);
        Assertions.assertNull(contentMac(fileId), "with the feature off, the moved row's MAC is cleared");
    }

    // --- search re-point ------------------------------------------------------------------------------

    @Test
    public void testSearchFollowsFileToTarget() throws Exception {
        clientUtil.createUser("mv_search");
        String token = clientUtil.login("mv_search");
        String docA = clientUtil.createDocument(token);
        String docB = clientUtil.createDocument(token);
        String fileId = clientUtil.addFileToDocument(FILE_DOCUMENT_TXT, token, docA);
        awaitProcessingQuiescence();

        awaitCondition("the file content must be searchable under the source before the move",
                () -> searchReturns("full:love", docA, token) && !searchReturns("full:love", docB, token));

        moveOk(fileId, docB, token);

        awaitCondition("the search index must re-point the file to the target after the move",
                () -> searchReturns("full:love", docB, token) && !searchReturns("full:love", docA, token));
    }

    private boolean searchReturns(String search, String documentId, String token) {
        JsonArray documents = target().path("/document/list").queryParam("search", search).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .get(JsonObject.class).getJsonArray("documents");
        for (int i = 0; i < documents.size(); i++) {
            if (documentId.equals(documents.getJsonObject(i).getString("id"))) {
                return true;
            }
        }
        return false;
    }

    // --- audit ----------------------------------------------------------------------------------------

    @Test
    public void testAuditOnBothDocuments() throws Exception {
        clientUtil.createUser("mv_audit");
        String token = clientUtil.login("mv_audit");
        String docA = clientUtil.createDocument(token);
        String docB = clientUtil.createDocument(token);
        String fileId = clientUtil.addFileToDocument(FILE_DOCUMENT_TXT, token, docA);

        moveOk(fileId, docB, token);

        Assertions.assertTrue(hasDocumentUpdate(docA, token), "the source document has a move audit entry");
        Assertions.assertTrue(hasDocumentUpdate(docB, token), "the target document has a move audit entry");
    }

    private boolean hasDocumentUpdate(String documentId, String token) {
        JsonArray logs = target().path("/auditlog").queryParam("document", documentId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .get(JsonObject.class).getJsonArray("logs");
        for (int i = 0; i < logs.size(); i++) {
            JsonObject entry = logs.getJsonObject(i);
            if ("Document".equals(entry.getString("class")) && "UPDATE".equals(entry.getString("type"))
                    && documentId.equals(entry.getString("target"))) {
                return true;
            }
        }
        return false;
    }
}
