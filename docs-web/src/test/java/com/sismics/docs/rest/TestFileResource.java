package com.sismics.docs.rest;

import com.google.common.io.ByteStreams;
import com.google.common.io.Resources;
import com.sismics.docs.core.util.DirectoryUtil;
import com.sismics.util.filter.TokenBasedSecurityFilter;
import com.sismics.util.mime.MimeType;
import com.sismics.util.mime.MimeTypeUtil;
import org.glassfish.jersey.media.multipart.FormDataMultiPart;
import org.glassfish.jersey.media.multipart.MultiPartFeature;
import org.glassfish.jersey.media.multipart.file.StreamDataBodyPart;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Form;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
import java.util.zip.ZipInputStream;

/**
 * Exhaustive test of the file resource.
 * 
 * @author bgamard
 */
public class TestFileResource extends BaseJerseyTest {
    /**
     * Test the file resource.
     * 
     * @throws Exception e
     */
    @Test
    public void testFileResource() throws Exception {
        // Login file_resources
        clientUtil.createUser("file_resources");
        String file1Token = clientUtil.login("file_resources");
        
        // Create a document
        String document1Id = clientUtil.createDocument(file1Token);
        
        // Add a file
        String file1Id = clientUtil.addFileToDocument(FILE_PIA_00452_JPG, file1Token, document1Id);
        
        // Add a file
        String file2Id = clientUtil.addFileToDocument(FILE_PIA_00452_JPG, file1Token, document1Id);
        
        // Get the file data
        Response response = target().path("/file/" + file1Id + "/data").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, file1Token)
                .get();
        InputStream is = (InputStream) response.getEntity();
        byte[] fileBytes = ByteStreams.toByteArray(is);
        Assertions.assertTrue(fileBytes.length > 0);
        
        // Get the thumbnail data
        response = target().path("/file/" + file1Id + "/data")
                .queryParam("size", "thumb")
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, file1Token)
                .get();
        Assertions.assertEquals(Status.OK, Status.fromStatusCode(response.getStatus()));
        is = (InputStream) response.getEntity();
        fileBytes = ByteStreams.toByteArray(is);
        Assertions.assertTrue(fileBytes.length > 0);
        
        // Get the content data
        response = target().path("/file/" + file1Id + "/data")
                .queryParam("size", "content")
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, file1Token)
                .get();
        Assertions.assertEquals(Status.OK, Status.fromStatusCode(response.getStatus()));

        // Get the web data
        response = target().path("/file/" + file1Id + "/data")
                .queryParam("size", "web")
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, file1Token)
                .get();
        Assertions.assertEquals(Status.OK, Status.fromStatusCode(response.getStatus()));
        is = (InputStream) response.getEntity();
        fileBytes = ByteStreams.toByteArray(is);
        Assertions.assertTrue(fileBytes.length > 0);
        
        // Check that the files are not readable directly from FS
        Path storedFile = DirectoryUtil.getStorageDirectory().resolve(file1Id);
        Assertions.assertEquals(MimeType.DEFAULT, MimeTypeUtil.guessMimeType(storedFile, null));

        // Get all files from a document
        JsonObject json = target().path("/file/list")
                .queryParam("id", document1Id)
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, file1Token)
                .get(JsonObject.class);
        JsonArray files = json.getJsonArray("files");
        Assertions.assertEquals(2, files.size());
        Assertions.assertEquals(file1Id, files.getJsonObject(0).getString("id"));
        Assertions.assertEquals("PIA00452.jpg", files.getJsonObject(0).getString("name"));
        Assertions.assertEquals("image/jpeg", files.getJsonObject(0).getString("mimetype"));
        Assertions.assertEquals(0, files.getJsonObject(0).getInt("version"));
        Assertions.assertEquals(FILE_PIA_00452_JPG_SIZE, files.getJsonObject(0).getJsonNumber("size").longValue());
        // The current-version uploader (creator) and its create date are exposed additively.
        Assertions.assertEquals("file_resources", files.getJsonObject(0).getString("creator"));
        Assertions.assertTrue(files.getJsonObject(0).getJsonNumber("create_date").longValue() > 0);
        Assertions.assertEquals(file2Id, files.getJsonObject(1).getString("id"));
        Assertions.assertEquals("PIA00452.jpg", files.getJsonObject(1).getString("name"));
        Assertions.assertEquals(0, files.getJsonObject(1).getInt("version"));

        // Rename a file
        target().path("file/" + file1Id)
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, file1Token)
                .post(Entity.form(new Form()
                        .param("name", "Pale Blue Dot")), JsonObject.class);

        // Get all files from a document
        json = target().path("/file/list")
                .queryParam("id", document1Id)
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, file1Token)
                .get(JsonObject.class);
        files = json.getJsonArray("files");
        Assertions.assertEquals(2, files.size());
        Assertions.assertEquals(file1Id, files.getJsonObject(0).getString("id"));
        Assertions.assertEquals("Pale Blue Dot", files.getJsonObject(0).getString("name"));

        // Reorder files
        target().path("/file/reorder").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, file1Token)
                .post(Entity.form(new Form()
                        .param("id", document1Id)
                        .param("order", file2Id)
                        .param("order", file1Id)), JsonObject.class);

        // Get all files from a document
        json = target().path("/file/list")
                .queryParam("id", document1Id)
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, file1Token)
                .get(JsonObject.class);
        files = json.getJsonArray("files");
        Assertions.assertEquals(2, files.size());
        Assertions.assertEquals(file2Id, files.getJsonObject(0).getString("id"));
        Assertions.assertEquals(file1Id, files.getJsonObject(1).getString("id"));
        
        // Get a ZIP from all files
        response = target().path("/file/zip")
                .queryParam("id", document1Id)
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, file1Token)
                .get();
        Assertions.assertEquals(Status.OK, Status.fromStatusCode(response.getStatus()));

        // Deletes a file
        json = target().path("/file/" + file1Id).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, file1Token)
                .delete(JsonObject.class);
        Assertions.assertEquals("ok", json.getString("status"));
        
        // Get the file data (not found)
        response = target().path("/file/" + file1Id + "/data").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, file1Token)
                .get();
        Assertions.assertEquals(Status.NOT_FOUND, Status.fromStatusCode(response.getStatus()));
        
        // Check that files are deleted from FS
        storedFile = DirectoryUtil.getStorageDirectory().resolve(file1Id);
        Path webFile = DirectoryUtil.getStorageDirectory().resolve(file1Id + "_web");
        Path thumbnailFile = DirectoryUtil.getStorageDirectory().resolve(file1Id + "_thumb");
        Assertions.assertFalse(Files.exists(storedFile));
        Assertions.assertFalse(Files.exists(webFile));
        Assertions.assertFalse(Files.exists(thumbnailFile));
        
        // Get all files from a document
        json = target().path("/file/list")
                .queryParam("id", document1Id)
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, file1Token)
                .get(JsonObject.class);
        files = json.getJsonArray("files");
        Assertions.assertEquals(1, files.size());

        // Process a file
        target().path("/file/" + file2Id + "/process").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, file1Token)
                .post(Entity.form(new Form()), JsonObject.class);

        // Get all versions from a file
        json = target().path("/file/" + file2Id + "/versions")
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, file1Token)
                .get(JsonObject.class);
        files = json.getJsonArray("files");
        Assertions.assertEquals(1, files.size());
        JsonObject file = files.getJsonObject(0);
        Assertions.assertEquals(file2Id, file.getString("id"));
        Assertions.assertEquals("PIA00452.jpg", file.getString("name"));
        Assertions.assertEquals("image/jpeg", file.getString("mimetype"));
        Assertions.assertEquals(0, file.getInt("version"));

        // Add a new version to a file
        String file3Id;
        try (InputStream is0 = Resources.getResource("file/document.txt").openStream()) {
            StreamDataBodyPart streamDataBodyPart = new StreamDataBodyPart("file", is0, "document.txt");
            try (FormDataMultiPart multiPart = new FormDataMultiPart()) {
                json = target()
                        .register(MultiPartFeature.class)
                        .path("/file").request()
                        .cookie(TokenBasedSecurityFilter.COOKIE_NAME, file1Token)
                        .put(Entity.entity(
                                multiPart
                                        .field("id", document1Id)
                                        .field("previousFileId", file2Id)
                                        .bodyPart(streamDataBodyPart),
                                MediaType.MULTIPART_FORM_DATA_TYPE), JsonObject.class);
                file3Id = json.getString("id");
                Assertions.assertNotNull(file2Id);
            }
        }

        // Get all versions from a file
        json = target().path("/file/" + file2Id + "/versions")
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, file1Token)
                .get(JsonObject.class);
        files = json.getJsonArray("files");
        Assertions.assertEquals(2, files.size());
        file = files.getJsonObject(1);
        Assertions.assertEquals(file3Id, file.getString("id"));
        Assertions.assertEquals("document.txt", file.getString("name"));
        Assertions.assertEquals("text/plain", file.getString("mimetype"));
        Assertions.assertEquals(1, file.getInt("version"));

        // Delete the previous version
        json = target().path("/file/" + file2Id).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, file1Token)
                .delete(JsonObject.class);
        Assertions.assertEquals("ok", json.getString("status"));

        // Check the newly created version
        json = target().path("/file/list")
                .queryParam("id", document1Id)
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, file1Token)
                .get(JsonObject.class);
        files = json.getJsonArray("files");
        Assertions.assertEquals(1, files.size());
        Assertions.assertEquals(file3Id, files.getJsonObject(0).getString("id"));
        Assertions.assertEquals("document.txt", files.getJsonObject(0).getString("name"));
        Assertions.assertEquals(1, files.getJsonObject(0).getInt("version"));
    }
    
    @Test
    public void testFileResourceZip() throws Exception {
        // Login file_resources
        clientUtil.createUser("file_resources_zip");
        String file1Token = clientUtil.login("file_resources_zip");

        // Create a document
        String document1Id = clientUtil.createDocument(file1Token);

        // Add a file
        String file1Id = clientUtil.addFileToDocument(FILE_PIA_00452_JPG, file1Token, document1Id);

        // Get a ZIP from all files of the document
        Response response = target().path("/file/zip")
                .queryParam("id", document1Id)
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, file1Token)
                .get();
        Assertions.assertEquals(Status.OK, Status.fromStatusCode(response.getStatus()));
        InputStream is = (InputStream) response.getEntity();
        ZipInputStream zipInputStream = new ZipInputStream(is);
        Assertions.assertEquals("PIA00452.jpg", zipInputStream.getNextEntry().getName());
        Assertions.assertNull(zipInputStream.getNextEntry());

        // Fail if we don't have access to the document
        response = target().path("/file/zip")
                .queryParam("id", document1Id)
                .request()
                .get();
        Assertions.assertEquals(Status.NOT_FOUND, Status.fromStatusCode(response.getStatus()));

        // Create a document
        String document2Id = clientUtil.createDocument(file1Token);

        // Add a file
        String file2Id = clientUtil.addFileToDocument(FILE_EINSTEIN_ROOSEVELT_LETTER_PNG, file1Token, document2Id);

        // Get a ZIP from both files
        response = target().path("/file/zip")
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, file1Token)
                .post(Entity.form(new Form()
                        .param("files", file1Id)
                        .param("files", file2Id)));
        Assertions.assertEquals(Status.OK, Status.fromStatusCode(response.getStatus()));
        is = (InputStream) response.getEntity();
        zipInputStream = new ZipInputStream(is);
        Assertions.assertNotNull(zipInputStream.getNextEntry().getName());
        Assertions.assertNotNull(zipInputStream.getNextEntry().getName());
        Assertions.assertNull(zipInputStream.getNextEntry());
        
        // Fail if we don't have access to the files
        response = target().path("/file/zip")
                .request()
                .post(Entity.form(new Form()
                        .param("files", file1Id)
                        .param("files", file2Id)));
        Assertions.assertEquals(Status.FORBIDDEN, Status.fromStatusCode(response.getStatus()));
    }

    /**
     * Test using a ZIP file.
     *
     * @throws Exception e
     */
    @Test
    public void testZipFileUpload() throws Exception {
        // Login file_zip
        clientUtil.createUser("file_zip");
        String fileZipToken = clientUtil.login("file_zip");

        // Create a document
        String document1Id = clientUtil.createDocument(fileZipToken);

        // Add a file
        clientUtil.addFileToDocument(FILE_WIKIPEDIA_ZIP, fileZipToken, document1Id);
    }

    /**
     * Test orphan files (without linked document).
     * 
     * @throws Exception e
     */
    @Test
    public void testOrphanFile() throws Exception {
        // Login file_orphan
        clientUtil.createUser("file_orphan");
        String fileOrphanToken = clientUtil.login("file_orphan");
        
        // Add a file
        String file1Id = clientUtil.addFileToDocument(FILE_PIA_00452_JPG, fileOrphanToken, null);

        // Get all orphan files
        JsonObject json = target().path("/file/list").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, fileOrphanToken)
                .get(JsonObject.class);
        JsonArray files = json.getJsonArray("files");
        Assertions.assertEquals(1, files.size());

        // Get the thumbnail data
        Response response = target().path("/file/" + file1Id + "/data")
                .queryParam("size", "thumb")
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, fileOrphanToken)
                .get();
        Assertions.assertEquals(Status.OK, Status.fromStatusCode(response.getStatus()));
        InputStream is = (InputStream) response.getEntity();
        byte[] fileBytes = ByteStreams.toByteArray(is);
        Assertions.assertTrue(fileBytes.length > 0);

        // Get the file data
        response = target().path("/file/" + file1Id + "/data").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, fileOrphanToken)
                .get();
        is = (InputStream) response.getEntity();
        fileBytes = ByteStreams.toByteArray(is);
        Assertions.assertEquals(FILE_PIA_00452_JPG_SIZE, fileBytes.length);
        
        // Create another document
        String document2Id = clientUtil.createDocument(fileOrphanToken);
        
        // Attach a file to a document
        target().path("/file/" + file1Id + "/attach").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, fileOrphanToken)
                .post(Entity.form(new Form()
                        .param("id", document2Id)), JsonObject.class);
        
        // Get all files from a document
        json = target().path("/file/list")
                .queryParam("id", document2Id)
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, fileOrphanToken)
                .get(JsonObject.class);
        files = json.getJsonArray("files");
        Assertions.assertEquals(1, files.size());
        
        // Add a file
        String file2Id = clientUtil.addFileToDocument(FILE_PIA_00452_JPG, fileOrphanToken, null);
        
        // Deletes a file
        json = target().path("/file/" + file2Id).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, fileOrphanToken)
                .delete(JsonObject.class);
        Assertions.assertEquals("ok", json.getString("status"));
    }
    
    /**
     * Test user quota.
     * 
     * @throws Exception e
     */
    @Test
    public void testQuota() throws Exception {
        // Login file_quota
        clientUtil.createUser("file_quota");
        String fileQuotaToken = clientUtil.login("file_quota");
        
        // Add a file (292641 bytes large)
        String file1Id = clientUtil.addFileToDocument(FILE_EINSTEIN_ROOSEVELT_LETTER_PNG, fileQuotaToken, null);

        // Check current quota
        Assertions.assertEquals(FILE_EINSTEIN_ROOSEVELT_LETTER_PNG_SIZE, getUserQuota(fileQuotaToken));
        
        // Add a file (292641 bytes large)
        clientUtil.addFileToDocument(FILE_EINSTEIN_ROOSEVELT_LETTER_PNG, fileQuotaToken, null);
        
        // Check current quota
        Assertions.assertEquals(FILE_EINSTEIN_ROOSEVELT_LETTER_PNG_SIZE * 2, getUserQuota(fileQuotaToken));
        
        // Add a file (292641 bytes large)
        clientUtil.addFileToDocument(FILE_EINSTEIN_ROOSEVELT_LETTER_PNG, fileQuotaToken, null);
        
        // Check current quota
        Assertions.assertEquals(FILE_EINSTEIN_ROOSEVELT_LETTER_PNG_SIZE * 3, getUserQuota(fileQuotaToken));
        
        // Add a file (292641 bytes large)
        try {
            clientUtil.addFileToDocument(FILE_EINSTEIN_ROOSEVELT_LETTER_PNG, fileQuotaToken, null);
            Assertions.fail();
        } catch (jakarta.ws.rs.BadRequestException ignored) {
        }
        
        // Deletes a file
        JsonObject json = target().path("/file/" + file1Id).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, fileQuotaToken)
                .delete(JsonObject.class);
        Assertions.assertEquals("ok", json.getString("status"));
        
        // Check current quota
        Assertions.assertEquals(FILE_EINSTEIN_ROOSEVELT_LETTER_PNG_SIZE * 2, getUserQuota(fileQuotaToken));

        // Create a document
        long create1Date = new Date().getTime();
        json = target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, fileQuotaToken)
                .put(Entity.form(new Form()
                        .param("title", "File test document 1")
                        .param("language", "eng")
                        .param("create_date", Long.toString(create1Date))), JsonObject.class);
        String document1Id = json.getString("id");
        Assertions.assertNotNull(document1Id);

        // Add a file to this document (163510 bytes large)
        clientUtil.addFileToDocument(FILE_PIA_00452_JPG, fileQuotaToken, document1Id);

        // Check current quota
        Assertions.assertEquals(FILE_EINSTEIN_ROOSEVELT_LETTER_PNG_SIZE * 2 + FILE_PIA_00452_JPG_SIZE, getUserQuota(fileQuotaToken));

        // Trashes the document (soft-delete, files preserved)
        json = target().path("/document/" + document1Id).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, fileQuotaToken)
                .delete(JsonObject.class);
        Assertions.assertEquals("ok", json.getString("status"));

        // Quota still includes trashed files
        Assertions.assertEquals(FILE_EINSTEIN_ROOSEVELT_LETTER_PNG_SIZE * 2 + FILE_PIA_00452_JPG_SIZE, getUserQuota(fileQuotaToken));

        // Permanently delete the trashed document
        json = target().path("/document/" + document1Id + "/permanent").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, fileQuotaToken)
                .delete(JsonObject.class);
        Assertions.assertEquals("ok", json.getString("status"));

        // Quota now reflects the permanent deletion
        Assertions.assertEquals(FILE_EINSTEIN_ROOSEVELT_LETTER_PNG_SIZE * 2, getUserQuota(fileQuotaToken));
    }

    /**
     * Permanently deleting a document with multiple files reclaims the sum of their sizes exactly
     * once (Option B: synchronous per-producer decrement, atomic with the delete transaction).
     */
    @Test
    public void testQuotaMultiFileDocument() throws Exception {
        clientUtil.createUser("file_quota_multi");
        String token = clientUtil.login("file_quota_multi");

        // Create a document and attach two files to it.
        JsonObject json = target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .put(Entity.form(new Form()
                        .param("title", "Multi-file quota doc")
                        .param("language", "eng")
                        .param("create_date", Long.toString(new Date().getTime()))), JsonObject.class);
        String documentId = json.getString("id");

        clientUtil.addFileToDocument(FILE_PIA_00452_JPG, token, documentId);
        clientUtil.addFileToDocument(FILE_EINSTEIN_ROOSEVELT_LETTER_PNG, token, documentId);

        long expected = FILE_PIA_00452_JPG_SIZE + FILE_EINSTEIN_ROOSEVELT_LETTER_PNG_SIZE;
        Assertions.assertEquals(expected, getUserQuota(token));

        // Trash then permanently delete the document.
        target().path("/document/" + documentId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .delete(JsonObject.class);
        // Trashing preserves the files, so quota is unchanged.
        Assertions.assertEquals(expected, getUserQuota(token));

        target().path("/document/" + documentId + "/permanent").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .delete(JsonObject.class);

        // Both files' sizes reclaimed exactly once -> back to zero.
        Assertions.assertEquals(0L, getUserQuota(token));
    }

    /**
     * A document with files uploaded by TWO different owners (a WRITE collaborator uploads to the
     * document owner's document): permanently deleting the document must reclaim each owner's bytes
     * from THEIR OWN quota, exactly once — never charge the document owner for the collaborator's file.
     */
    @Test
    public void testQuotaMultiOwnerDocument() throws Exception {
        clientUtil.createUser("quota_owner");
        clientUtil.createUser("quota_collab");
        String ownerToken = clientUtil.login("quota_owner");
        String collabToken = clientUtil.login("quota_collab");

        // Owner creates a document and uploads one file to it.
        JsonObject json = target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, ownerToken)
                .put(Entity.form(new Form()
                        .param("title", "Two-owner quota doc")
                        .param("language", "eng")
                        .param("create_date", Long.toString(new Date().getTime()))), JsonObject.class);
        String documentId = json.getString("id");
        clientUtil.addFileToDocument(FILE_PIA_00452_JPG, ownerToken, documentId);

        // Grant the collaborator WRITE on the document, then the collaborator uploads a file to it.
        target().path("/acl").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, ownerToken)
                .put(Entity.form(new Form()
                        .param("source", documentId)
                        .param("perm", "WRITE")
                        .param("target", "quota_collab")
                        .param("type", "USER")), JsonObject.class);
        clientUtil.addFileToDocument(FILE_EINSTEIN_ROOSEVELT_LETTER_PNG, collabToken, documentId);

        // Each file is charged to ITS uploader.
        Assertions.assertEquals(FILE_PIA_00452_JPG_SIZE, getUserQuota(ownerToken));
        Assertions.assertEquals(FILE_EINSTEIN_ROOSEVELT_LETTER_PNG_SIZE, getUserQuota(collabToken));

        // Trash then permanently delete the document (as the owner).
        target().path("/document/" + documentId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, ownerToken)
                .delete(JsonObject.class);
        target().path("/document/" + documentId + "/permanent").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, ownerToken)
                .delete(JsonObject.class);

        // Each owner's quota reclaimed by their own file's size, exactly once.
        Assertions.assertEquals(0L, getUserQuota(ownerToken),
                "the document owner must be reclaimed only their own file's bytes");
        Assertions.assertEquals(0L, getUserQuota(collabToken),
                "the collaborator's uploaded file must be reclaimed from the collaborator, not the doc owner");
    }

    /**
     * Individually deleting a file reclaims it immediately; permanently deleting its document later
     * must NOT reclaim that already-deleted file again (no double count), while still reclaiming a
     * sibling file that was only ever cascade-trashed with the document.
     */
    @Test
    public void testNoDoubleReclaimAfterIndividualFileDelete() throws Exception {
        clientUtil.createUser("quota_nodouble");
        String token = clientUtil.login("quota_nodouble");

        // A separate, untouched document with a file: it provides a non-zero baseline so a
        // double-reclaim produces a WRONG value instead of being hidden by the floor-to-zero clamp.
        String keepDocId = clientUtil.createDocument(token);
        clientUtil.addFileToDocument(FILE_EINSTEIN_ROOSEVELT_LETTER_PNG, token, keepDocId);

        JsonObject json = target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .put(Entity.form(new Form()
                        .param("title", "No-double-reclaim doc")
                        .param("language", "eng")
                        .param("create_date", Long.toString(new Date().getTime()))), JsonObject.class);
        String documentId = json.getString("id");

        // Two files on the document under test.
        String file1Id = clientUtil.addFileToDocument(FILE_PIA_00452_JPG, token, documentId);
        clientUtil.addFileToDocument(FILE_PIA_00452_JPG, token, documentId);
        Assertions.assertEquals(FILE_EINSTEIN_ROOSEVELT_LETTER_PNG_SIZE + FILE_PIA_00452_JPG_SIZE * 2,
                getUserQuota(token));

        // Individually delete file1 -> reclaimed now.
        target().path("/file/" + file1Id).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .delete(JsonObject.class);
        Assertions.assertEquals(FILE_EINSTEIN_ROOSEVELT_LETTER_PNG_SIZE + FILE_PIA_00452_JPG_SIZE,
                getUserQuota(token));

        // Trash then permanently delete the document. file1 must NOT be reclaimed again; only the
        // still-linked, never-individually-deleted second file is reclaimed now. A double-reclaim of
        // file1 would drop the quota below the untouched keep-document baseline.
        target().path("/document/" + documentId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .delete(JsonObject.class);
        target().path("/document/" + documentId + "/permanent").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .delete(JsonObject.class);

        // Only the untouched keep-document's file remains counted.
        Assertions.assertEquals(FILE_EINSTEIN_ROOSEVELT_LETTER_PNG_SIZE, getUserQuota(token),
                "the already-deleted file must not be reclaimed a second time");
    }

    private long getUserQuota(String userToken) {
        return target().path("/user").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, userToken)
                .get(JsonObject.class).getJsonNumber("storage_current").longValue();
    }

    /**
     * SEC A3-03: when a file upload fails in FileUtil.createFile BEFORE the async event
     * that owns the plaintext temp is queued (here: a quota breach), the unencrypted
     * temp file must not be left behind. No async event is queued on this failure path,
     * so there is no async-deletion race to make the count flaky.
     */
    @Test
    public void testUploadFailureDeletesTempFile() throws Exception {
        clientUtil.createUser("file_leak"); // 1MB quota
        String token = clientUtil.login("file_leak");

        // Fill the quota with three 292KB uploads (~878KB)
        clientUtil.addFileToDocument(FILE_EINSTEIN_ROOSEVELT_LETTER_PNG, token, null);
        clientUtil.addFileToDocument(FILE_EINSTEIN_ROOSEVELT_LETTER_PNG, token, null);
        clientUtil.addFileToDocument(FILE_EINSTEIN_ROOSEVELT_LETTER_PNG, token, null);

        java.util.Set<Path> before = snapshotSismicsTempFiles();

        // The fourth upload breaches the 1MB quota: createFile throws QuotaReached after
        // writing the temp, before queuing the async event.
        try {
            clientUtil.addFileToDocument(FILE_EINSTEIN_ROOSEVELT_LETTER_PNG, token, null);
            Assertions.fail("Expected quota breach");
        } catch (jakarta.ws.rs.BadRequestException ignored) {
        }

        java.util.Set<Path> leaked = leakedSismicsTempFiles(before);
        Assertions.assertTrue(leaked.isEmpty(),
                "Plaintext temp file leaked on failed upload: " + leaked);
    }

    /**
     * SEC A3-03b: when the server-side multipart copy aborts BEFORE createFile (here: the
     * oversize limit is exceeded mid-copy, after the plaintext temp already exists on
     * disk), the temp created at the top of the upload must still be deleted. This
     * exercises the copy phase — the cleanup scope must cover creation + copy, not only
     * createFile.
     */
    @Test
    public void testUploadCopyFailureDeletesTempFile() throws Exception {
        clientUtil.createUser("file_copy_err");
        String token = clientUtil.login("file_copy_err");

        java.util.Set<Path> before = snapshotSismicsTempFiles();

        // Force the oversize path to trip during the server-side copy loop: the real file
        // (292KB) exceeds this 1KB cap, so the resource writes the temp, then throws
        // PayloadTooLarge before ever reaching createFile.
        System.setProperty("docs.max_upload_size", "1024");
        try (InputStream is = Resources.getResource(FILE_EINSTEIN_ROOSEVELT_LETTER_PNG).openStream()) {
            StreamDataBodyPart part = new StreamDataBodyPart("file", is, "large.png");
            try (FormDataMultiPart multiPart = new FormDataMultiPart()) {
                Response response = target().register(MultiPartFeature.class)
                        .path("/file").request()
                        .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                        .put(Entity.entity(multiPart.bodyPart(part), MediaType.MULTIPART_FORM_DATA_TYPE));
                Assertions.assertNotEquals(Status.OK.getStatusCode(), response.getStatus(),
                        "Oversize upload must be rejected");
            }
        } finally {
            System.clearProperty("docs.max_upload_size");
        }

        java.util.Set<Path> leaked = leakedSismicsTempFiles(before);
        Assertions.assertTrue(leaked.isEmpty(),
                "Plaintext temp file leaked on oversize copy failure: " + leaked);
    }

    /**
     * Snapshots the {@code sismics_docs*} temp files currently present in the shared system
     * tmpdir. The leak assertions work on the DELTA between two snapshots ({@link
     * #leakedSismicsTempFiles(java.util.Set)}), never on a raw count: a raw count races with
     * concurrent JVMs (parallel worktree suites) whose long-lived temps inflate the number.
     * Every file that pre-exists this test — including every other JVM's — appears in BOTH
     * snapshots and cancels out. The guarantee is asymmetric: a FALSE PASS is impossible (a
     * file this test leaks always lands in the delta), but a concurrent JVM CAN still inject
     * a matching file between the snapshots, producing a spurious FAILURE — a loud,
     * investigable outcome, never a silently missed leak.
     */
    private java.util.Set<Path> snapshotSismicsTempFiles() throws Exception {
        Path tmpDir = com.sismics.docs.core.service.FileService.getTemporaryDirectory();
        try (java.util.stream.Stream<Path> files = Files.list(tmpDir)) {
            return files.filter(p -> p.getFileName().toString().startsWith("sismics_docs"))
                    .collect(java.util.stream.Collectors.toSet());
        }
    }

    /**
     * The {@code sismics_docs*} temp files that appeared AFTER {@code before} and REMAIN
     * present. Transient temps (a concurrent suite's in-flight upload, straggler async
     * processing) are deleted by their owner shortly after; the check polls briefly until
     * the delta drains. A GENUINE leak has no deleter and survives the full window, failing
     * loudly. Polling narrows the spurious-failure window without weakening detection.
     */
    private java.util.Set<Path> leakedSismicsTempFiles(java.util.Set<Path> before) throws Exception {
        java.util.Set<Path> leaked;
        long deadline = System.currentTimeMillis() + 15000;
        do {
            leaked = new java.util.HashSet<>(snapshotSismicsTempFiles());
            leaked.removeAll(before);
            if (leaked.isEmpty()) {
                return leaked;
            }
            Thread.sleep(250);
        } while (System.currentTimeMillis() < deadline);
        return leaked;
    }

    /**
     * SEC-02: renaming and deleting a file must require WRITE on the parent
     * document, not merely READ. A user with only a READ ACL on a shared
     * document must not be able to rename or delete its files.
     */
    @Test
    public void testFileMutationRequiresWrite() throws Exception {
        // Owner creates a document with a file
        clientUtil.createUser("filewrite1");
        String t1 = clientUtil.login("filewrite1");
        JsonObject json = target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, t1)
                .put(Entity.form(new Form()
                        .param("title", "File write perm doc")
                        .param("language", "eng")), JsonObject.class);
        String documentId = json.getString("id");
        String fileId = clientUtil.addFileToDocument(FILE_EINSTEIN_ROOSEVELT_LETTER_PNG, t1, documentId);

        // Share the document READ-only with a second user
        clientUtil.createUser("filewrite2");
        String t2 = clientUtil.login("filewrite2");
        target().path("/acl").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, t1)
                .put(Entity.form(new Form()
                        .param("source", documentId)
                        .param("perm", "READ")
                        .param("target", "filewrite2")
                        .param("type", "USER")), JsonObject.class);

        // READ-only user may read the file data ...
        Response response = target().path("/file/" + fileId + "/data").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, t2)
                .get();
        Assertions.assertEquals(Status.OK, Status.fromStatusCode(response.getStatus()));

        // ... may list its versions (read path stays READ) ...
        response = target().path("/file/" + fileId + "/versions").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, t2)
                .get();
        Assertions.assertEquals(Status.OK, Status.fromStatusCode(response.getStatus()));

        // ... may download it via the zip-by-files endpoint (read path stays READ) ...
        response = target().path("/file/zip").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, t2)
                .post(Entity.form(new Form().param("files", fileId)));
        Assertions.assertEquals(Status.OK, Status.fromStatusCode(response.getStatus()));

        // ... but must NOT rename it
        response = target().path("/file/" + fileId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, t2)
                .post(Entity.form(new Form().param("name", "hijacked-name")));
        Assertions.assertEquals(Status.FORBIDDEN, Status.fromStatusCode(response.getStatus()));

        // ... and must NOT delete it
        response = target().path("/file/" + fileId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, t2)
                .delete();
        Assertions.assertEquals(Status.FORBIDDEN, Status.fromStatusCode(response.getStatus()));

        // A non-owner granted WRITE on the document CAN rename and delete its files
        // (proves the gate keys on the WRITE permission, not on document ownership)
        clientUtil.createUser("filewrite3");
        String t3 = clientUtil.login("filewrite3");
        target().path("/acl").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, t1)
                .put(Entity.form(new Form()
                        .param("source", documentId)
                        .param("perm", "WRITE")
                        .param("target", "filewrite3")
                        .param("type", "USER")), JsonObject.class);
        String file2Id = clientUtil.addFileToDocument(FILE_EINSTEIN_ROOSEVELT_LETTER_PNG, t1, documentId);
        json = target().path("/file/" + file2Id).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, t3)
                .post(Entity.form(new Form().param("name", "write-user-renamed")), JsonObject.class);
        Assertions.assertEquals("ok", json.getString("status"));
        json = target().path("/file/" + file2Id).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, t3)
                .delete(JsonObject.class);
        Assertions.assertEquals("ok", json.getString("status"));

        // The owner (WRITE) can still rename and delete
        json = target().path("/file/" + fileId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, t1)
                .post(Entity.form(new Form().param("name", "owner-renamed")), JsonObject.class);
        Assertions.assertEquals("ok", json.getString("status"));
        json = target().path("/file/" + fileId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, t1)
                .delete(JsonObject.class);
        Assertions.assertEquals("ok", json.getString("status"));
    }

    /**
     * Regression test for stored XSS: an uploaded text/html original file must be
     * served as an attachment (never inline), so it cannot execute same-origin.
     *
     * @throws Exception e
     */
    @Test
    public void testOriginalHtmlServedAsAttachment() throws Exception {
        // Login
        clientUtil.createUser("file_xss");
        String token = clientUtil.login("file_xss");

        // Create a document and upload an HTML file
        String documentId = clientUtil.createDocument(token);
        String fileId = clientUtil.addFileToDocument(FILE_XSS_HTML, token, documentId);

        // Download the original file (no size parameter)
        Response response = target().path("/file/" + fileId + "/data").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .get();
        Assertions.assertEquals(Status.OK, Status.fromStatusCode(response.getStatus()));

        // The original file must be forced to download, not rendered inline
        String contentDisposition = response.getHeaderString("Content-Disposition");
        Assertions.assertNotNull(contentDisposition);
        Assertions.assertTrue(contentDisposition.toLowerCase().startsWith("attachment"),
                "Original file download must use Content-Disposition: attachment, got: " + contentDisposition);
        Assertions.assertFalse(contentDisposition.toLowerCase().startsWith("inline"),
                "Original file download must not be served inline, got: " + contentDisposition);
    }

    /**
     * Regression test for #136: a multipart {@code file} part carrying NO filename attribute must be
     * accepted with a null (absent) stored name — the whole stack tolerates a null name — instead of
     * throwing {@code URLDecoder.decode(null, ...)} and returning HTTP 500.
     *
     * @throws Exception e
     */
    @Test
    public void testUploadFileWithoutFilename() throws Exception {
        // Login and create a document to attach the file to
        clientUtil.createUser("file_noname");
        String token = clientUtil.login("file_noname");
        String documentId = clientUtil.createDocument(token);

        // A "file" part whose Content-Disposition carries NO filename= parameter, so the server sees
        // getContentDisposition().getFileName() == null. StreamDataBodyPart cannot express this — it
        // defaults the filename to the part name — so build the content disposition (name only) directly.
        org.glassfish.jersey.media.multipart.FormDataContentDisposition noFilename =
                org.glassfish.jersey.media.multipart.FormDataContentDisposition.name("file").build();
        org.glassfish.jersey.media.multipart.FormDataBodyPart filePart =
                new org.glassfish.jersey.media.multipart.FormDataBodyPart(noFilename,
                        "content with no filename".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        MediaType.APPLICATION_OCTET_STREAM_TYPE);

        String fileId;
        try (org.glassfish.jersey.media.multipart.FormDataMultiPart multiPart =
                     new org.glassfish.jersey.media.multipart.FormDataMultiPart()) {
            org.glassfish.jersey.media.multipart.FormDataMultiPart form =
                    (org.glassfish.jersey.media.multipart.FormDataMultiPart) multiPart.field("id", documentId);
            Response response = target()
                    .register(org.glassfish.jersey.media.multipart.MultiPartFeature.class)
                    .path("/file").request()
                    .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                    .put(Entity.entity(form.bodyPart(filePart), MediaType.MULTIPART_FORM_DATA_TYPE));
            // Pre-fix this was HTTP 500 (URLDecoder.decode(null) NPE).
            Assertions.assertEquals(Status.OK, Status.fromStatusCode(response.getStatus()),
                    "A filename-less file part must be accepted, not 500");
            JsonObject json = response.readEntity(JsonObject.class);
            Assertions.assertEquals("ok", json.getString("status"));
            fileId = json.getString("id");
            Assertions.assertNotNull(fileId);
        }

        // The stored file carries a null name, serialized as JSON null (rc.8 Phase G nullable-wire).
        JsonObject list = target().path("/file/list")
                .queryParam("id", documentId)
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .get(JsonObject.class);
        JsonArray files = list.getJsonArray("files");
        Assertions.assertEquals(1, files.size());
        JsonObject file = files.getJsonObject(0);
        Assertions.assertEquals(fileId, file.getString("id"));
        Assertions.assertTrue(file.isNull("name"),
                "An absent filename must store a null name, got: " + file.get("name"));
    }

    /**
     * Uploads a file with the given multipart filename through the real Jersey multipart boundary and
     * returns the name the server persisted (read back via the file-list API).
     *
     * @param token Auth token
     * @param documentId Document to attach to
     * @param sentFileName Filename to send in the multipart part
     * @return The persisted file name
     * @throws Exception e
     */
    private String uploadAndGetName(String token, String documentId, String sentFileName) throws Exception {
        String fileId;
        try (InputStream is = Resources.getResource("file/document.txt").openStream()) {
            StreamDataBodyPart part = new StreamDataBodyPart("file", is, sentFileName);
            try (FormDataMultiPart multiPart = new FormDataMultiPart()) {
                JsonObject json = target()
                        .register(MultiPartFeature.class)
                        .path("/file").request()
                        .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                        .put(Entity.entity(multiPart.field("id", documentId).bodyPart(part),
                                MediaType.MULTIPART_FORM_DATA_TYPE), JsonObject.class);
                fileId = json.getString("id");
            }
        }
        JsonObject list = target().path("/file/list")
                .queryParam("id", documentId)
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .get(JsonObject.class);
        JsonArray files = list.getJsonArray("files");
        for (int i = 0; i < files.size(); i++) {
            JsonObject file = files.getJsonObject(i);
            if (fileId.equals(file.getString("id"))) {
                return file.isNull("name") ? null : file.getString("name");
            }
        }
        throw new AssertionError("Uploaded file " + fileId + " not found in listing");
    }

    /**
     * Regression test for #143: multipart upload filenames are decoded as UTF-8. Browsers transmit the
     * Content-Disposition filename as UTF-8 bytes, but Jersey decodes the header as ISO-8859-1, so a name
     * like {@code Körper.pdf} was persisted as the mojibake {@code KÃ¶rper.pdf}. The removed
     * {@code URLDecoder.decode} also rewrote {@code +} to a space and returned HTTP 500 on a literal
     * {@code %}. These upload through the real Jersey multipart boundary and assert the persisted name.
     *
     * @throws Exception e
     */
    @Test
    public void testUploadFilenameEncoding() throws Exception {
        clientUtil.createUser("file_encoding");
        String token = clientUtil.login("file_encoding");
        String documentId = clientUtil.createDocument(token);

        // (a) A UTF-8 umlaut name a browser sends is repaired instead of stored as mojibake.
        Assertions.assertEquals("Körperschaftsteuererklärung.pdf",
                uploadAndGetName(token, documentId, "Körperschaftsteuererklärung.pdf"));
        // (b) A non-Latin (CJK) name survives the round-trip; a naive getBytes(ISO_8859_1) would corrupt it.
        Assertions.assertEquals("文档.pdf", uploadAndGetName(token, documentId, "文档.pdf"));
        // (c) Plain ASCII is untouched.
        Assertions.assertEquals("report.pdf", uploadAndGetName(token, documentId, "report.pdf"));
        // (d) A literal "+" must survive (proves the URLDecoder that rewrote it to a space is gone).
        Assertions.assertEquals("a+b.pdf", uploadAndGetName(token, documentId, "a+b.pdf"));
        // (e) A literal "%" must not throw HTTP 500 (URLDecoder rejected the invalid escape).
        Assertions.assertEquals("50%.pdf", uploadAndGetName(token, documentId, "50%.pdf"));
    }

    /**
     * Unit coverage for {@link com.sismics.docs.rest.resource.FileResource#repairMultipartFilename} on the
     * two branches the multipart client cannot exercise: Jersey's client always UTF-8-encodes the filename
     * and the server decodes it as ISO-8859-1, so the server never observes a code point above U+00FF, nor a
     * sub-U+0100 string that is not valid UTF-8. Both are reachable in the wild (RFC 5987 {@code filename*};
     * legacy Latin-1 clients) and must be pinned directly.
     */
    @Test
    public void testRepairMultipartFilenameBranches() {
        // Absent filename stays null (#136 no-filename regression contract).
        Assertions.assertNull(com.sismics.docs.rest.resource.FileResource.repairMultipartFilename(null));

        // A name already carrying a code point > U+00FF was decoded correctly and passes through untouched.
        Assertions.assertEquals("文档.pdf",
                com.sismics.docs.rest.resource.FileResource.repairMultipartFilename("文档.pdf"));

        // Single-byte mojibake is repaired; this also pins the documented normalization of a literal
        // ISO-8859-1 name whose bytes happen to be valid UTF-8 (the accepted #143 trade-off).
        Assertions.assertEquals("Körper.pdf",
                com.sismics.docs.rest.resource.FileResource.repairMultipartFilename("KÃ¶rper.pdf"));

        // Multi-byte mojibake (what the server observes for a CJK name) is repaired.
        String cjkMojibake = new String("文档.pdf".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                java.nio.charset.StandardCharsets.ISO_8859_1);
        Assertions.assertEquals("文档.pdf",
                com.sismics.docs.rest.resource.FileResource.repairMultipartFilename(cjkMojibake));

        // A genuine Latin-1 name whose bytes are not valid UTF-8 is kept as-is (strict decoder rejects it).
        Assertions.assertEquals("café.pdf",
                com.sismics.docs.rest.resource.FileResource.repairMultipartFilename("café.pdf"));

        // ASCII names carrying "+", "%" or a quote are left byte-for-byte untouched (URLDecoder is gone).
        Assertions.assertEquals("a+b.pdf",
                com.sismics.docs.rest.resource.FileResource.repairMultipartFilename("a+b.pdf"));
        Assertions.assertEquals("50%.pdf",
                com.sismics.docs.rest.resource.FileResource.repairMultipartFilename("50%.pdf"));
        Assertions.assertEquals("a\"b.pdf",
                com.sismics.docs.rest.resource.FileResource.repairMultipartFilename("a\"b.pdf"));
    }

    /**
     * Regression test for #148: Jersey already decodes an RFC 5987 {@code filename*} parameter to Unicode
     * and, per RFC 6266, prefers it over a plain {@code filename}. The #143 mojibake repair must therefore
     * never be applied to a {@code filename*}-sourced name — re-decoding a name whose own characters happen
     * to look like UTF-8 mojibake would corrupt it. These construct a real
     * {@link org.glassfish.jersey.media.multipart.FormDataContentDisposition} from the raw header and assert
     * the name the upload path resolves.
     *
     * @throws Exception e
     */
    @Test
    public void testResolveUploadFilenameFilenameStar() throws Exception {
        // (a) A filename*-sourced name is kept verbatim, never routed through the repair. The real name here
        //     is "KÃ¶rper.pdf" (its own bytes look like UTF-8 mojibake); an unconditional repair would
        //     mis-decode it to "Körper.pdf". The resolved name must round-trip identical to getFileName().
        org.glassfish.jersey.media.multipart.FormDataContentDisposition star =
                new org.glassfish.jersey.media.multipart.FormDataContentDisposition(
                        "form-data; name=\"file\"; filename*=UTF-8''K%C3%83%C2%B6rper.pdf");
        Assertions.assertNotNull(star.getParameters().get("filename*"),
                "Test premise: Jersey must expose the raw filename* parameter as the source discriminator");
        Assertions.assertEquals(star.getFileName(),
                com.sismics.docs.rest.resource.FileResource.resolveUploadFilename(star),
                "A filename*-sourced name must round-trip verbatim, not be repaired");

        // (b) The mojibake specimen survives resolution unchanged (not corrupted to "Körper.pdf").
        Assertions.assertEquals("KÃ¶rper.pdf",
                com.sismics.docs.rest.resource.FileResource.resolveUploadFilename(star));

        // (c) A second filename* name the repair would corrupt ("cafÃ©.pdf" -> "café.pdf") is kept as sent.
        org.glassfish.jersey.media.multipart.FormDataContentDisposition starCafe =
                new org.glassfish.jersey.media.multipart.FormDataContentDisposition(
                        "form-data; name=\"file\"; filename*=UTF-8''caf%C3%83%C2%A9.pdf");
        Assertions.assertEquals("cafÃ©.pdf",
                com.sismics.docs.rest.resource.FileResource.resolveUploadFilename(starCafe));

        // (d) RFC 6266 precedence: with both parameters present, filename* wins and is kept verbatim.
        org.glassfish.jersey.media.multipart.FormDataContentDisposition both =
                new org.glassfish.jersey.media.multipart.FormDataContentDisposition(
                        "form-data; name=\"file\"; filename=\"fallback.pdf\"; filename*=UTF-8''K%C3%83%C2%B6rper.pdf");
        Assertions.assertEquals("KÃ¶rper.pdf",
                com.sismics.docs.rest.resource.FileResource.resolveUploadFilename(both));

        // (e) A plain filename= name is still repaired for header charset mojibake (#143 behavior preserved).
        org.glassfish.jersey.media.multipart.FormDataContentDisposition plain =
                new org.glassfish.jersey.media.multipart.FormDataContentDisposition(
                        "form-data; name=\"file\"; filename=\"KÃ¶rper.pdf\"");
        Assertions.assertNull(plain.getParameters().get("filename*"));
        Assertions.assertEquals("Körper.pdf",
                com.sismics.docs.rest.resource.FileResource.resolveUploadFilename(plain));

        // (f) An absent content disposition resolves to a null name (#136 no-filename contract).
        Assertions.assertNull(com.sismics.docs.rest.resource.FileResource.resolveUploadFilename(null));
    }
}
