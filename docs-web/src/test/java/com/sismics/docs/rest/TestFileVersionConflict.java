package com.sismics.docs.rest;

import com.google.common.io.Resources;
import com.sismics.util.filter.TokenBasedSecurityFilter;
import org.glassfish.jersey.media.multipart.FormDataMultiPart;
import org.glassfish.jersey.media.multipart.MultiPart;
import org.glassfish.jersey.media.multipart.MultiPartFeature;
import org.glassfish.jersey.media.multipart.file.StreamDataBodyPart;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;

import java.io.InputStream;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Version-contract HTTP status tests for {@code PUT /file}: a stale base is a typed 409 Conflict, and an
 * unknown / cross-document {@code previousFileId} is a typed 400 Bad Request (never a 500). These pin the
 * single-writer, stale-base-safe file-version contract at the wire.
 */
public class TestFileVersionConflict extends BaseJerseyTest {

    /**
     * Replacing the SAME base twice must reject the second attempt with 409: once the first replacement
     * commits, the original file is no longer the current latest version, so the affected-row
     * compare-and-swap on it matches nothing (a stale base) and the second upload never creates a
     * duplicate latest row.
     */
    @Test
    public void staleBaseReplaceReturnsConflict() throws Exception {
        clientUtil.createUser("version_conflict_stale");
        String token = clientUtil.login("version_conflict_stale");
        String documentId = clientUtil.createDocument(token);

        String v0 = clientUtil.addFileToDocument(FILE_PIA_00452_JPG, token, documentId);

        // First replacement of v0 succeeds and demotes v0.
        Response first = replace(token, documentId, v0);
        Assertions.assertEquals(Status.OK, Status.fromStatusCode(first.getStatus()));

        // Second replacement from the SAME (now stale) base must be a 409 Conflict.
        Response second = replace(token, documentId, v0);
        Assertions.assertEquals(Status.CONFLICT, Status.fromStatusCode(second.getStatus()));

        // Exactly one latest, non-deleted row survives for the document.
        JsonObject list = target().path("/file/list")
                .queryParam("id", documentId)
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .get(JsonObject.class);
        Assertions.assertEquals(1, list.getJsonArray("files").size(),
                "a stale-base replacement must not create a second latest version");
    }

    /**
     * A {@code previousFileId} that belongs to another document must be a 400, not a 500.
     */
    @Test
    public void crossDocumentPreviousFileReturnsBadRequest() throws Exception {
        clientUtil.createUser("version_conflict_cross");
        String token = clientUtil.login("version_conflict_cross");
        String documentA = clientUtil.createDocument(token);
        String documentB = clientUtil.createDocument(token);

        String fileInA = clientUtil.addFileToDocument(FILE_PIA_00452_JPG, token, documentA);

        // Replace into document B using a previousFileId that lives in document A.
        Response response = replace(token, documentB, fileInA);
        Assertions.assertEquals(Status.BAD_REQUEST, Status.fromStatusCode(response.getStatus()));
    }

    /**
     * A {@code previousFileId} that is an orphan file (no associated document) must be a 400, not a 500.
     * The orphan's document id is null; validating it before dereferencing avoids the NullPointerException
     * that the old code turned into a 500.
     */
    @Test
    public void orphanPreviousFileReturnsBadRequest() throws Exception {
        clientUtil.createUser("version_conflict_orphan");
        String token = clientUtil.login("version_conflict_orphan");
        String documentId = clientUtil.createDocument(token);

        // Upload an orphan file (no document association).
        String orphanFileId = clientUtil.addFileToDocument(FILE_PIA_00452_JPG, token, null);

        Response response = replace(token, documentId, orphanFileId);
        Assertions.assertEquals(Status.BAD_REQUEST, Status.fromStatusCode(response.getStatus()));
    }

    /**
     * Raw {@code PUT /file} replacement returning the Response so the caller can assert the HTTP status
     * (the {@code ClientUtil} helper asserts success and cannot express an expected error).
     */
    private Response replace(String token, String documentId, String previousFileId) throws Exception {
        URL fileResource = Resources.getResource(FILE_PIA_00452_JPG);
        Path filePath = Paths.get(fileResource.toURI());
        String filename = filePath.getFileName().toString();
        try (InputStream is = fileResource.openStream()) {
            StreamDataBodyPart streamDataBodyPart = new StreamDataBodyPart("file", is, filename);
            try (FormDataMultiPart multiPart = new FormDataMultiPart()) {
                multiPart.field("id", documentId);
                if (previousFileId != null) {
                    multiPart.field("previousFileId", previousFileId);
                }
                MultiPart formContent = multiPart.bodyPart(streamDataBodyPart);
                return target()
                        .register(MultiPartFeature.class)
                        .path("/file").request()
                        .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                        .put(Entity.entity(formContent, MediaType.MULTIPART_FORM_DATA_TYPE));
            }
        }
    }
}
