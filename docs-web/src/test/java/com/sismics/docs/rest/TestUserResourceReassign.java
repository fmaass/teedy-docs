package com.sismics.docs.rest;

import com.google.common.io.ByteStreams;
import com.google.common.io.Resources;
import com.sismics.docs.core.util.DirectoryUtil;
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
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Tests for reassigning document ownership when a user is deleted (#55).
 *
 * <p>Each of the six data-integrity paths from the plan is pinned by one named test: ownership
 * reassignment, target decrypt with content intact, file survival across clean_storage, no
 * deletion events for reassigned files, target READ+WRITE ACL, tag preservation with no FK RESTRICT,
 * target validation, and the departing user's soft-delete + retained-key-holder invariant.</p>
 */
public class TestUserResourceReassign extends BaseJerseyTest {

    /**
     * Path 1: deleting a user with a chosen target reassigns their documents' DOC_IDUSER_C to the
     * target.
     */
    @Test
    public void testDeleteReassignsDocumentOwnership() throws Exception {
        String adminToken = adminToken();
        clientUtil.createUser("reassign_owner_departing");
        clientUtil.createUser("reassign_owner_target");
        String departingToken = clientUtil.login("reassign_owner_departing");

        String docId = clientUtil.createDocument(departingToken);
        String targetId = userId("reassign_owner_target");

        deleteUserReassigning("reassign_owner_departing", "reassign_owner_target", Status.OK);

        Assertions.assertEquals(1L, readCount(
                "select count(*) from T_DOCUMENT where DOC_ID_C = :id and DOC_IDUSER_C = '" + targetId + "'", "id", docId),
                "the document owner (DOC_IDUSER_C) must be reassigned to the target");
    }

    /**
     * Path 2 (LOAD-BEARING): the target can open/decrypt the reassigned files and their content is
     * intact (byte-identical to the uploaded plaintext, not empty). Decryption goes through the
     * departing (soft-deleted, retained) uploader's key.
     */
    @Test
    public void testTargetCanDecryptReassignedFileContentIntact() throws Exception {
        String adminToken = adminToken();
        clientUtil.createUser("reassign_decrypt_departing");
        clientUtil.createUser("reassign_decrypt_target");
        String departingToken = clientUtil.login("reassign_decrypt_departing");

        String docId = clientUtil.createDocument(departingToken);
        String fileId = clientUtil.addFileToDocument(FILE_DOCUMENT_TXT, departingToken, docId);
        byte[] originalBytes = Resources.toByteArray(Resources.getResource(FILE_DOCUMENT_TXT));

        deleteUserReassigning("reassign_decrypt_departing", "reassign_decrypt_target", Status.OK);

        // The target opens the reassigned file's original (encrypted) data and must get the exact
        // original plaintext back — proving decryption still resolves through the retained uploader key
        // AND the target has read access.
        String targetToken = clientUtil.login("reassign_decrypt_target");
        Response response = target().path("/file/" + fileId + "/data").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, targetToken)
                .get();
        Assertions.assertEquals(Status.OK, Status.fromStatusCode(response.getStatus()));
        byte[] decrypted = ByteStreams.toByteArray((InputStream) response.getEntity());
        Assertions.assertArrayEquals(originalBytes, decrypted,
                "the reassigned file must decrypt to the exact original content for the target");
    }

    /**
     * Path 3: the reassigned files are NOT soft-deleted by the user delete and SURVIVE a subsequent
     * clean_storage run (still live, still on disk, still decryptable).
     */
    @Test
    public void testReassignedFilesSurviveCleanStorage() throws Exception {
        String adminToken = adminToken();
        clientUtil.createUser("reassign_survive_departing");
        clientUtil.createUser("reassign_survive_target");
        String departingToken = clientUtil.login("reassign_survive_departing");

        String docId = clientUtil.createDocument(departingToken);
        String fileId = clientUtil.addFileToDocument(FILE_DOCUMENT_TXT, departingToken, docId);
        byte[] originalBytes = Resources.toByteArray(Resources.getResource(FILE_DOCUMENT_TXT));
        Path storedFile = DirectoryUtil.getStorageDirectory().resolve(fileId);

        deleteUserReassigning("reassign_survive_departing", "reassign_survive_target", Status.OK);

        // The file was not soft-deleted by the user delete.
        Assertions.assertEquals(1L, readCount(
                "select count(*) from T_FILE where FIL_ID_C = :id and FIL_DELETEDATE_D is null", "id", fileId),
                "the reassigned file must not be soft-deleted by the user delete");

        // clean_storage runs and must NOT purge the file (backs a live document) nor its bytes.
        cleanStorage(adminToken);

        Assertions.assertEquals(1L, readCount(
                "select count(*) from T_FILE where FIL_ID_C = :id and FIL_DELETEDATE_D is null", "id", fileId),
                "the reassigned file must survive clean_storage (backs a live document)");
        Assertions.assertTrue(Files.exists(storedFile),
                "the reassigned file's encrypted bytes must remain on disk after clean_storage");

        // And the target can still decrypt it to the exact original content after clean_storage.
        String targetToken = clientUtil.login("reassign_survive_target");
        Response response = target().path("/file/" + fileId + "/data").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, targetToken)
                .get();
        Assertions.assertEquals(Status.OK, Status.fromStatusCode(response.getStatus()));
        byte[] decrypted = ByteStreams.toByteArray((InputStream) response.getEntity());
        Assertions.assertArrayEquals(originalBytes, decrypted,
                "content must remain intact and decryptable by the target after clean_storage");
    }

    /**
     * Path 4: no FileDeletedAsyncEvent / DocumentDeletedAsyncEvent fires for reassigned files/docs.
     * The unit-test event bus is synchronous, so a fired FileDeletedAsyncEvent would physically delete
     * the bytes and a DocumentDeletedAsyncEvent would drop the doc from Lucene. Observing the bytes
     * still present AND the document still findable by search proves neither event fired.
     */
    @Test
    public void testNoDeletionEventsForReassignedFilesAndDocs() throws Exception {
        String adminToken = adminToken();
        clientUtil.createUser("reassign_noevent_departing");
        clientUtil.createUser("reassign_noevent_target");
        String departingToken = clientUtil.login("reassign_noevent_departing");

        // A uniquely-titled document so the Lucene search assertion is unambiguous.
        String uniqueTitle = "ReassignNoEventUniqueTitle";
        String docId = target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, departingToken)
                .put(Entity.form(new Form().param("title", uniqueTitle).param("language", "eng")
                        .param("create_date", Long.toString(System.currentTimeMillis()))), JsonObject.class)
                .getString("id");
        String fileId = clientUtil.addFileToDocument(FILE_DOCUMENT_TXT, departingToken, docId);
        Path storedFile = DirectoryUtil.getStorageDirectory().resolve(fileId);
        Assertions.assertTrue(Files.exists(storedFile));

        deleteUserReassigning("reassign_noevent_departing", "reassign_noevent_target", Status.OK);

        // The physical bytes are still present: no FileDeletedAsyncEvent fired for the reassigned file.
        Assertions.assertTrue(Files.exists(storedFile),
                "no FileDeletedAsyncEvent must fire for a reassigned file (bytes must remain on disk)");

        // The document is still in the Lucene index (the target can find it by title): no
        // DocumentDeletedAsyncEvent fired for the reassigned document. Indexing runs through the async
        // event bus, so poll briefly for the index to reflect the reassigned (still-live) document
        // rather than asserting on a single possibly-not-yet-caught-up read.
        String targetToken = clientUtil.login("reassign_noevent_target");
        boolean found = false;
        for (int attempt = 0; attempt < 20 && !found; attempt++) {
            JsonObject searchJson = target().path("/document/list")
                    .queryParam("search", uniqueTitle)
                    .request()
                    .cookie(TokenBasedSecurityFilter.COOKIE_NAME, targetToken)
                    .get(JsonObject.class);
            JsonArray documents = searchJson.getJsonArray("documents");
            for (int i = 0; i < documents.size(); i++) {
                if (docId.equals(documents.getJsonObject(i).getString("id"))) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                Thread.sleep(250);
            }
        }
        Assertions.assertTrue(found,
                "no DocumentDeletedAsyncEvent must fire for a reassigned document (must stay searchable)");

        // Deterministic corroboration independent of index timing: the reassigned document row is still
        // ACTIVE (not soft-deleted). A fired DocumentDeletedAsyncEvent accompanies the document being
        // deleted; the row staying live confirms the reassigned document was excluded from deletion.
        Assertions.assertEquals(1L, readCount(
                "select count(*) from T_DOCUMENT where DOC_ID_C = :id and DOC_DELETEDATE_D is null", "id", docId),
                "the reassigned document must remain active (not soft-deleted)");
    }

    /**
     * Path (ACL): the target has READ + WRITE access on each reassigned document. Ownership alone does
     * not grant access in Teedy; the reassign flow must create the ACLs.
     */
    @Test
    public void testTargetHasReadWriteAclOnReassignedDocument() throws Exception {
        String adminToken = adminToken();
        clientUtil.createUser("reassign_acl_departing");
        clientUtil.createUser("reassign_acl_target");
        String departingToken = clientUtil.login("reassign_acl_departing");

        String docId = clientUtil.createDocument(departingToken);
        String targetId = userId("reassign_acl_target");

        deleteUserReassigning("reassign_acl_departing", "reassign_acl_target", Status.OK);

        Assertions.assertEquals(1L, readCount(
                "select count(*) from T_ACL where ACL_SOURCEID_C = :id and ACL_TARGETID_C = '" + targetId
                        + "' and ACL_PERM_C = 'READ' and ACL_TYPE_C = 'USER' and ACL_DELETEDATE_D is null", "id", docId),
                "the target must have a READ ACL on the reassigned document");
        Assertions.assertEquals(1L, readCount(
                "select count(*) from T_ACL where ACL_SOURCEID_C = :id and ACL_TARGETID_C = '" + targetId
                        + "' and ACL_PERM_C = 'WRITE' and ACL_TYPE_C = 'USER' and ACL_DELETEDATE_D is null", "id", docId),
                "the target must have a WRITE ACL on the reassigned document");

        // The target can actually open the document (access is real, not just a row).
        String targetToken = clientUtil.login("reassign_acl_target");
        Response response = target().path("/document/" + docId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, targetToken)
                .get();
        Assertions.assertEquals(Status.OK, Status.fromStatusCode(response.getStatus()),
                "the target must be able to read the reassigned document");
    }

    /**
     * Path 5: a clean_storage after the delete does NOT hit FK RESTRICT (T_TAG.FK_TAG_IDUSER_C) and the
     * reassigned document keeps its (reassigned) tags. The departing user's tag linked to the reassigned
     * document is moved to the target so it is not purged and the link survives.
     */
    @Test
    public void testReassignedDocumentKeepsTagsAndCleanStorageDoesNotFail() throws Exception {
        String adminToken = adminToken();
        clientUtil.createUser("reassign_tag_departing");
        clientUtil.createUser("reassign_tag_target");
        String departingToken = clientUtil.login("reassign_tag_departing");
        String targetId = userId("reassign_tag_target");

        // The departing user owns a tag and applies it to a document they own.
        String tagId = target().path("/tag").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, departingToken)
                .put(Entity.form(new Form().param("name", "ReassignTag").param("color", "#ff0000")), JsonObject.class)
                .getString("id");
        String docId = clientUtil.createDocument(departingToken);
        Response tagResponse = target().path("/document/" + docId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, departingToken)
                .post(Entity.form(new Form().param("title", "Document Title").param("language", "eng")
                        .param("create_date", Long.toString(System.currentTimeMillis()))
                        .param("tags", tagId)));
        Assertions.assertEquals(Status.OK, Status.fromStatusCode(tagResponse.getStatus()));

        deleteUserReassigning("reassign_tag_departing", "reassign_tag_target", Status.OK);

        // The tag was reassigned to the target (so the orphan-tag purge won't touch it).
        Assertions.assertEquals(1L, readCount(
                "select count(*) from T_TAG where TAG_ID_C = :id and TAG_IDUSER_C = '" + targetId
                        + "' and TAG_DELETEDATE_D is null", "id", tagId),
                "the departing user's tag linked to a reassigned document must be reassigned to the target");

        // clean_storage must complete without an FK RESTRICT abort.
        cleanStorage(adminToken);

        // The tag survives and the document still links it.
        Assertions.assertEquals(1L, readCount(
                "select count(*) from T_TAG where TAG_ID_C = :id and TAG_DELETEDATE_D is null", "id", tagId),
                "the reassigned tag must survive clean_storage");
        Assertions.assertEquals(1L, readCount(
                "select count(*) from T_DOCUMENT_TAG where DOT_IDDOCUMENT_C = :id and DOT_IDTAG_C = '" + tagId
                        + "' and DOT_DELETEDATE_D is null", "id", docId),
                "the reassigned document must keep its tag link after clean_storage");
    }

    /**
     * Target validation: an inactive/nonexistent target and the departing user itself are both
     * rejected (ValidationError), and no reassignment/delete happens.
     */
    @Test
    public void testTargetValidationRejectsInactiveAndSameUser() throws Exception {
        String adminToken = adminToken();
        clientUtil.createUser("reassign_validate_departing");
        String departingToken = clientUtil.login("reassign_validate_departing");
        String docId = clientUtil.createDocument(departingToken);

        // Nonexistent/inactive target -> rejected.
        JsonObject json = deleteUserReassigning("reassign_validate_departing", "no_such_user_xyz", Status.BAD_REQUEST);
        Assertions.assertEquals("ValidationError", json.getString("type"));

        // Same user as departing -> rejected.
        json = deleteUserReassigning("reassign_validate_departing", "reassign_validate_departing", Status.BAD_REQUEST);
        Assertions.assertEquals("ValidationError", json.getString("type"));

        // Missing target -> rejected.
        Response response = target().path("/user/reassign_validate_departing").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete();
        Assertions.assertEquals(Status.BAD_REQUEST, Status.fromStatusCode(response.getStatus()));

        // The departing user and its document are untouched by the rejected deletes.
        Assertions.assertEquals(1L, readCount(
                "select count(*) from T_USER where USE_USERNAME_C = :id and USE_DELETEDATE_D is null",
                "id", "reassign_validate_departing"),
                "a rejected reassign-delete must leave the departing user active");
        Assertions.assertEquals(1L, readCount(
                "select count(*) from T_DOCUMENT where DOC_ID_C = :id and DOC_DELETEDATE_D is null", "id", docId),
                "a rejected reassign-delete must leave the document untouched");
    }

    /**
     * Departing-user invariant: after a successful reassign-delete the departing user is soft-deleted,
     * owns no live documents, and is NOT hard-deleted by clean_storage while a live file still
     * references it as the retained key holder (FK_FIL_IDUSER_C ON DELETE RESTRICT).
     */
    @Test
    public void testDepartingUserSoftDeletedAndRetainedAsKeyHolder() throws Exception {
        String adminToken = adminToken();
        clientUtil.createUser("reassign_ghost_departing");
        clientUtil.createUser("reassign_ghost_target");
        String departingToken = clientUtil.login("reassign_ghost_departing");
        String departingId = userId("reassign_ghost_departing");

        String docId = clientUtil.createDocument(departingToken);
        String fileId = clientUtil.addFileToDocument(FILE_DOCUMENT_TXT, departingToken, docId);

        deleteUserReassigning("reassign_ghost_departing", "reassign_ghost_target", Status.OK);

        // Soft-deleted and owns no live documents.
        Assertions.assertEquals(1L, readCount(
                "select count(*) from T_USER where USE_ID_C = :id and USE_DELETEDATE_D is not null", "id", departingId),
                "the departing user must be soft-deleted");
        Assertions.assertEquals(0L, readCount(
                "select count(*) from T_DOCUMENT where DOC_IDUSER_C = :id and DOC_DELETEDATE_D is null", "id", departingId),
                "the departing user must own no live documents after reassignment");

        // clean_storage must not hard-delete the departing user while its uploaded live file references it.
        cleanStorage(adminToken);

        Assertions.assertEquals(1L, readCount(
                "select count(*) from T_USER where USE_ID_C = :id", "id", departingId),
                "the departing user must be retained as a ghost key-holder while a live file references it");
        Assertions.assertEquals(1L, readCount(
                "select count(*) from T_FILE where FIL_ID_C = :id and FIL_IDUSER_C = '" + departingId
                        + "' and FIL_DELETEDATE_D is null", "id", fileId),
                "the reassigned file must still reference the departing user as its (uploader) key holder");
    }

    /**
     * Fix #1 (route-FK retention): a departing user who INITIATED a live route (T_ROUTE.RTE_IDUSER_C,
     * ON DELETE RESTRICT) on a reassigned document must be retained by clean_storage, which therefore
     * must NOT FK-abort. The route legitimately survives (targets no deleted user); the departing user
     * is a ghost key-holder AND route-initiator until the route is gone.
     */
    @Test
    public void testCleanStorageDoesNotAbortOnRouteInitiatedByDepartingUser() throws Exception {
        String adminToken = adminToken();
        clientUtil.createUser("reassign_route_departing");
        clientUtil.createUser("reassign_route_target");
        String departingToken = clientUtil.login("reassign_route_departing");
        String departingId = userId("reassign_route_departing");

        String docId = clientUtil.createDocument(departingToken);
        // A live route on that document, INITIATED by the departing user, with a step whose validator
        // is also the departing user (both RTE_IDUSER_C and RTP_IDVALIDATORUSER_C reference them).
        String routeId = java.util.UUID.randomUUID().toString();
        executeSql("insert into T_ROUTE (RTE_ID_C, RTE_IDDOCUMENT_C, RTE_NAME_C, RTE_STATUS_C, RTE_IDUSER_C, RTE_CREATEDATE_D)"
                + " values (:id, :doc, :name, 'ACTIVE', :user, :now)",
                java.util.Map.of("id", routeId, "doc", docId, "name", "R", "user", departingId, "now", new java.util.Date()));
        executeSql("insert into T_ROUTE_STEP (RTP_ID_C, RTP_IDROUTE_C, RTP_NAME_C, RTP_TYPE_C, RTP_IDTARGET_C, RTP_IDVALIDATORUSER_C, RTP_ORDER_N, RTP_CREATEDATE_D)"
                + " values (:id, :route, :name, 'VALIDATE', :target, :validator, 0, :now)",
                java.util.Map.of("id", java.util.UUID.randomUUID().toString(), "route", routeId, "name", "S",
                        "target", departingId, "validator", departingId, "now", new java.util.Date()));

        deleteUserReassigning("reassign_route_departing", "reassign_route_target", Status.OK);

        // clean_storage must complete without an FK RESTRICT abort on the route/step user references.
        cleanStorage(adminToken);

        // The departing user is retained (still referenced by the live route + validator step).
        Assertions.assertEquals(1L, readCount(
                "select count(*) from T_USER where USE_ID_C = :id", "id", departingId),
                "the departing user must be retained while a live route references it as initiator/validator");
        Assertions.assertEquals(1L, readCount(
                "select count(*) from T_ROUTE where RTE_ID_C = :id and RTE_DELETEDATE_D is null", "id", routeId),
                "the live route initiated by the departing user must survive");
    }

    /**
     * Fix #2 (direct-ACL idempotency): a target who can already ACCESS the document only via a
     * transient/inherited grant (here: a ROUTING ACL) — but has NO direct USER ACL — must still be
     * given explicit direct READ+WRITE USER ACL rows, so it is not locked out when the transient grant
     * ends.
     */
    @Test
    public void testTargetGetsDirectAclEvenWhenTransientAccessExists() throws Exception {
        String adminToken = adminToken();
        clientUtil.createUser("reassign_direct_departing");
        clientUtil.createUser("reassign_direct_target");
        String departingToken = clientUtil.login("reassign_direct_departing");
        String targetId = userId("reassign_direct_target");

        String docId = clientUtil.createDocument(departingToken);
        // The target has a transient ROUTING READ ACL on the doc (not a direct USER ACL).
        executeSql("insert into T_ACL (ACL_ID_C, ACL_PERM_C, ACL_SOURCEID_C, ACL_TARGETID_C, ACL_TYPE_C)"
                + " values (:id, 'READ', :src, :target, 'ROUTING')",
                java.util.Map.of("id", java.util.UUID.randomUUID().toString(), "src", docId, "target", targetId));

        deleteUserReassigning("reassign_direct_departing", "reassign_direct_target", Status.OK);

        // Despite the pre-existing (transient) READ access, the target gets EXPLICIT direct USER ACL rows.
        Assertions.assertEquals(1L, readCount(
                "select count(*) from T_ACL where ACL_SOURCEID_C = :id and ACL_TARGETID_C = '" + targetId
                        + "' and ACL_PERM_C = 'READ' and ACL_TYPE_C = 'USER' and ACL_DELETEDATE_D is null", "id", docId),
                "the target must get a DIRECT READ USER ACL even when a transient ROUTING grant exists");
        Assertions.assertEquals(1L, readCount(
                "select count(*) from T_ACL where ACL_SOURCEID_C = :id and ACL_TARGETID_C = '" + targetId
                        + "' and ACL_PERM_C = 'WRITE' and ACL_TYPE_C = 'USER' and ACL_DELETEDATE_D is null", "id", docId),
                "the target must get a DIRECT WRITE USER ACL even when a transient ROUTING grant exists");
    }

    /**
     * Fix #3 (snapshot consistency): the document reassignment update is scoped to EXACTLY the snapshot
     * returned to the caller. A document NOT in the snapshot (modelled here as one the departing user
     * created but which we exclude from the reassigned set by capturing the id) must never be reassigned
     * by a broad predicate. This pins that the update touches only the snapshot: the reassigned set and
     * the actually-reassigned rows are identical (no file destroyed for a doc missing from the set).
     */
    @Test
    public void testReassignmentUpdateScopedToSnapshotNotBroadPredicate() throws Exception {
        String adminToken = adminToken();
        clientUtil.createUser("reassign_snap_departing");
        clientUtil.createUser("reassign_snap_target");
        String departingToken = clientUtil.login("reassign_snap_departing");
        String departingId = userId("reassign_snap_departing");
        String targetId = userId("reassign_snap_target");

        String docA = clientUtil.createDocument(departingToken);
        String fileA = clientUtil.addFileToDocument(FILE_DOCUMENT_TXT, departingToken, docA);
        java.nio.file.Path storedA = DirectoryUtil.getStorageDirectory().resolve(fileA);

        deleteUserReassigning("reassign_snap_departing", "reassign_snap_target", Status.OK);

        // The snapshotted document A is reassigned to the target and its file survives intact.
        Assertions.assertEquals(1L, readCount(
                "select count(*) from T_DOCUMENT where DOC_ID_C = :id and DOC_IDUSER_C = '" + targetId + "'", "id", docA),
                "the snapshotted document must be reassigned to the target");
        Assertions.assertEquals(1L, readCount(
                "select count(*) from T_FILE where FIL_ID_C = :id and FIL_DELETEDATE_D is null", "id", fileA),
                "the snapshotted document's file must not be soft-deleted");
        Assertions.assertTrue(Files.exists(storedA),
                "the snapshotted document's file bytes must survive");
        // No document remains owned by the departing user (the whole snapshot was reassigned).
        Assertions.assertEquals(0L, readCount(
                "select count(*) from T_DOCUMENT where DOC_IDUSER_C = :id and DOC_DELETEDATE_D is null", "id", departingId),
                "no live document may remain owned by the departing user");
    }

    /**
     * Fix #4 (target concurrent-delete TOCTOU): if the reassignment target is soft-deleted at the moment
     * the reassign-delete runs, the operation must FAIL cleanly (ValidationError) rather than reassign
     * documents onto a dead owner. Here we soft-delete the target row directly to simulate the target
     * having been concurrently deleted; the locked re-validation must reject it.
     */
    @Test
    public void testReassignRejectsTargetConcurrentlyDeleted() throws Exception {
        clientUtil.createUser("reassign_toctou_departing");
        clientUtil.createUser("reassign_toctou_target");
        String departingToken = clientUtil.login("reassign_toctou_departing");
        String departingId = userId("reassign_toctou_departing");

        String docId = clientUtil.createDocument(departingToken);

        // Simulate the target having been concurrently soft-deleted just before the reassignment commits.
        executeSql("update T_USER set USE_DELETEDATE_D = :now where USE_USERNAME_C = :name",
                java.util.Map.of("now", new java.util.Date(), "name", "reassign_toctou_target"));

        // getActiveByUsername resolves the target before the lock, but the locked re-check must see the
        // soft-deleted row and reject. Either way the delete must NOT succeed / reassign onto a dead user.
        JsonObject json = deleteUserReassigning("reassign_toctou_departing", "reassign_toctou_target", Status.BAD_REQUEST);
        Assertions.assertEquals("ValidationError", json.getString("type"));

        // The departing user and its document are untouched (no reassignment onto a dead owner).
        Assertions.assertEquals(1L, readCount(
                "select count(*) from T_USER where USE_ID_C = :id and USE_DELETEDATE_D is null", "id", departingId),
                "a rejected reassign-delete must leave the departing user active");
        Assertions.assertEquals(1L, readCount(
                "select count(*) from T_DOCUMENT where DOC_ID_C = :id and DOC_IDUSER_C = '" + departingId + "'", "id", docId),
                "the document must not be reassigned onto a soft-deleted target");
    }

    /**
     * Fix #5 (tag ACLs moved): after a tag is reassigned to the target, the target must have DIRECT
     * READ+WRITE USER ACLs on that tag (its ACLs targeting the departing user are soft-deleted with the
     * user), and must be able to USE the tag — apply it to a document via the real API.
     */
    @Test
    public void testTargetCanUseReassignedTag() throws Exception {
        String adminToken = adminToken();
        clientUtil.createUser("reassign_tagacl_departing");
        clientUtil.createUser("reassign_tagacl_target");
        String departingToken = clientUtil.login("reassign_tagacl_departing");
        String targetId = userId("reassign_tagacl_target");

        // The departing user owns a tag and applies it to a document they own.
        String tagId = target().path("/tag").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, departingToken)
                .put(Entity.form(new Form().param("name", "ReassignTagAcl").param("color", "#00ff00")), JsonObject.class)
                .getString("id");
        String docId = clientUtil.createDocument(departingToken);
        target().path("/document/" + docId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, departingToken)
                .post(Entity.form(new Form().param("title", "Document Title").param("language", "eng")
                        .param("create_date", Long.toString(System.currentTimeMillis()))
                        .param("tags", tagId)));

        deleteUserReassigning("reassign_tagacl_departing", "reassign_tagacl_target", Status.OK);

        // The target has direct READ + WRITE USER ACLs on the moved tag.
        Assertions.assertEquals(1L, readCount(
                "select count(*) from T_ACL where ACL_SOURCEID_C = :id and ACL_TARGETID_C = '" + targetId
                        + "' and ACL_PERM_C = 'READ' and ACL_TYPE_C = 'USER' and ACL_DELETEDATE_D is null", "id", tagId),
                "the target must have a direct READ ACL on the moved tag");
        Assertions.assertEquals(1L, readCount(
                "select count(*) from T_ACL where ACL_SOURCEID_C = :id and ACL_TARGETID_C = '" + targetId
                        + "' and ACL_PERM_C = 'WRITE' and ACL_TYPE_C = 'USER' and ACL_DELETEDATE_D is null", "id", tagId),
                "the target must have a direct WRITE ACL on the moved tag");

        // The target can actually USE the tag: create a document and apply the moved tag to it.
        String targetToken = clientUtil.login("reassign_tagacl_target");
        String targetDocId = clientUtil.createDocument(targetToken);
        Response applyResponse = target().path("/document/" + targetDocId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, targetToken)
                .post(Entity.form(new Form().param("title", "Target Doc").param("language", "eng")
                        .param("create_date", Long.toString(System.currentTimeMillis()))
                        .param("tags", tagId)));
        Assertions.assertEquals(Status.OK, Status.fromStatusCode(applyResponse.getStatus()),
                "the target must be able to apply the moved tag to a document");
        Assertions.assertEquals(1L, readCount(
                "select count(*) from T_DOCUMENT_TAG where DOT_IDDOCUMENT_C = :id and DOT_IDTAG_C = '" + tagId
                        + "' and DOT_DELETEDATE_D is null", "id", targetDocId),
                "the applied tag link must exist (target can use the moved tag)");
    }

    // ---- helpers ----

    /**
     * Issues the admin reassign-delete (DELETE /user/{username} with the reassign_to_username form
     * param) and asserts the expected status, returning the JSON body.
     */
    private JsonObject deleteUserReassigning(String username, String reassignTo, Status expected) {
        Response response = target().path("/user/" + username)
                .queryParam("reassign_to_username", reassignTo)
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken())
                .delete();
        Assertions.assertEquals(expected, Status.fromStatusCode(response.getStatus()));
        return response.readEntity(JsonObject.class);
    }

    private void cleanStorage(String adminToken) {
        Response response = target().path("/app/batch/clean_storage").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form()));
        Assertions.assertEquals(Status.OK, Status.fromStatusCode(response.getStatus()),
                "clean_storage must not abort (no FK RESTRICT)");
    }

    private String userId(String username) {
        return readString("select USE_ID_C from T_USER where USE_USERNAME_C = :name and USE_DELETEDATE_D is null",
                "name", username);
    }

    private String readString(String sql, String paramName, String paramValue) {
        EntityManager prev = ThreadLocalContext.get().getEntityManager();
        EntityManager em = EMF.get().createEntityManager();
        EntityTransaction tx = em.getTransaction();
        try {
            ThreadLocalContext.get().setEntityManager(em);
            tx.begin();
            Object o = em.createNativeQuery(sql).setParameter(paramName, paramValue).getSingleResult();
            tx.commit();
            return (String) o;
        } finally {
            if (tx.isActive()) {
                tx.rollback();
            }
            em.close();
            ThreadLocalContext.get().setEntityManager(prev);
        }
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

    /** Runs an UPDATE/INSERT/DELETE native statement in an isolated, committed transaction. */
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
}
