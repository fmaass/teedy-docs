package com.sismics.docs.rest;

import com.google.common.io.Resources;
import com.sismics.docs.core.util.ContentMacUtil;
import com.sismics.util.filter.TokenBasedSecurityFilter;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Form;
import jakarta.ws.rs.core.MediaType;
import org.glassfish.jersey.media.multipart.FormDataMultiPart;
import org.glassfish.jersey.media.multipart.MultiPartFeature;
import org.glassfish.jersey.media.multipart.file.StreamDataBodyPart;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

/**
 * End-to-end REST contract for content-hash duplicate detection (#119): PUT /file surfaces the advisory
 * {@code duplicateKind}/{@code duplicateOfId} hint for a renamed-identical upload, collapses an identical
 * new-version upload to a no-op that returns the existing id (no new version), and emits neither field when
 * the feature is off. The MAC is computed by FileResource streaming the multipart body as the temp is written.
 */
public class TestFileDedup extends BaseJerseyTest {

    @AfterEach
    public void disableDedup() {
        ContentMacUtil.resetForTest();
    }

    /** Upload the SAME text resource (identical plaintext) so two uploads share a document MAC. */
    private JsonObject upload(String token, String documentId, String previousFileId, String name) throws Exception {
        try (InputStream is = Resources.getResource(FILE_DOCUMENT_TXT).openStream()) {
            StreamDataBodyPart body = new StreamDataBodyPart("file", is, name);
            try (FormDataMultiPart multiPart = new FormDataMultiPart()) {
                FormDataMultiPart form = (FormDataMultiPart) multiPart.field("id", documentId);
                if (previousFileId != null) {
                    form = (FormDataMultiPart) form.field("previousFileId", previousFileId);
                }
                return target()
                        .register(MultiPartFeature.class)
                        .path("/file").request()
                        .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                        .put(Entity.entity(form.bodyPart(body), MediaType.MULTIPART_FORM_DATA_TYPE), JsonObject.class);
            }
        }
    }

    private int versionCount(String token, String fileId) {
        return target().path("/file/" + fileId + "/versions").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .get(JsonObject.class).getJsonArray("files").size();
    }

    /**
     * (#150/#160) A file uploaded standalone (orphan, MAC null) and attached to a document AFTER the
     * one-shot startup backfill has drained must get its content MAC computed SYNCHRONOUSLY on the attach
     * path — WITHOUT waiting for a restart or the periodic backfill. Proven end-to-end: the attached
     * orphan participates in duplicate detection immediately, so a subsequent identical upload to the same
     * document carries the advisory content-duplicate hint pointing at it. The backfill service is not
     * driven in this test, so the ONLY way the hint can fire is a MAC computed on attach.
     */
    @Test
    public void orphanAttachedPostBackfill_getsMacSynchronously() throws Exception {
        clientUtil.createUser("dedup_attach");
        String token = clientUtil.login("dedup_attach");

        // Feature ON before the orphan is even uploaded.
        ContentMacUtil.setMasterKeyForTest("rest-master-secret");

        // Upload an ORPHAN (no document): newSink(null) is null, so its stored MAC is null.
        String orphanFileId = clientUtil.addFileToDocument(FILE_DOCUMENT_TXT, token, null);

        // Attach it to a document AFTER creation — the path that previously left the MAC null forever.
        String documentId = clientUtil.createDocument(token);
        target().path("/file/" + orphanFileId + "/attach").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .post(Entity.form(new Form().param("id", documentId)), JsonObject.class);

        // Upload an identical (renamed) file to the SAME document: the advisory content-duplicate hint
        // fires ONLY if the attached orphan already carries a matching per-document MAC — i.e. it was
        // MAC'd on attach, without a restart.
        JsonObject twin = upload(token, documentId, null, "twin.txt");
        Assertions.assertEquals("content", twin.getString("duplicateKind"),
                "the orphan attached post-creation must be MAC'd synchronously so it participates in dedup");
        Assertions.assertEquals(orphanFileId, twin.getString("duplicateOfId"),
                "the hint must point at the orphan that was attached and MAC'd on the attach path");
    }

    @Test
    public void renamedDuplicate_carriesHint_noopCollapses_offEmitsNothing() throws Exception {
        clientUtil.createUser("dedup_rest");
        String token = clientUtil.login("dedup_rest");

        // Feature OFF: an upload carries NO hint field.
        String offDoc = clientUtil.createDocument(token);
        JsonObject off = upload(token, offDoc, null, "off.txt");
        Assertions.assertFalse(off.containsKey("duplicateKind"), "with the feature off no hint field is emitted");

        // Enable the feature. Work in a FRESH document so the twin is a file uploaded WHILE ENABLED (hence
        // carrying a MAC the hint lookup can match).
        ContentMacUtil.setMasterKeyForTest("rest-master-secret");
        String documentId = clientUtil.createDocument(token);
        JsonObject first = upload(token, documentId, null, "a.txt");
        String firstFileId = first.getString("id");
        Assertions.assertFalse(first.containsKey("duplicateKind"), "the first enabled upload has no twin yet");

        // Same plaintext as a fresh (renamed) file: still creates, but the response now carries an advisory
        // content-duplicate hint pointing at the existing identical file.
        JsonObject hinted = upload(token, documentId, null, "renamed.txt");
        Assertions.assertEquals("content", hinted.getString("duplicateKind"));
        Assertions.assertEquals(firstFileId, hinted.getString("duplicateOfId"),
                "the hint points at the existing identical file");
        Assertions.assertNotEquals(firstFileId, hinted.getString("id"), "a renamed duplicate still CREATES a new file");

        // Upload identical content as a NEW VERSION of the first file -> NO-OP: returns the existing id and
        // creates no new version.
        int before = versionCount(token, firstFileId);
        JsonObject noop = upload(token, documentId, firstFileId, "again.txt");
        Assertions.assertEquals(firstFileId, noop.getString("id"), "an identical new version collapses to the existing file");
        Assertions.assertEquals(before, versionCount(token, firstFileId), "a no-op must not create a new version");
    }
}
