package com.sismics.docs.rest;

import com.google.common.io.Resources;
import com.sismics.util.filter.TokenBasedSecurityFilter;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Form;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.glassfish.jersey.media.multipart.FormDataMultiPart;
import org.glassfish.jersey.media.multipart.MultiPartFeature;
import org.glassfish.jersey.media.multipart.file.StreamDataBodyPart;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

/**
 * Regression tests for the {@code ?share=} ACL bypass (CVE-2026-50885 / CVE-2025-11853): an untrusted
 * {@code ?share=} request parameter was added verbatim to the ACL target list, so {@code ?share=admin}
 * injected the reserved ACL name that {@link com.sismics.docs.core.util.SecurityUtil#skipAclCheck} honours
 * and bypassed the ACL check entirely — returning protected content to an anonymous caller. The fix only
 * trusts a {@code ?share=} value that resolves to a genuine active share (a server-generated UUID), so a
 * forged reserved name grants nothing while a real share still works.
 */
public class TestShareAclBypass extends BaseJerseyTest {

    /**
     * A forged share must never yield the protected content: any clean 4xx denial (403 forbidden / 404
     * not-found / 400 bad-request) is acceptable; a 2xx (leak) or a 5xx (crash) is not.
     */
    private static boolean isDenied(int status) {
        return status == 400 || status == 403 || status == 404;
    }

    @Test
    public void forgedShareTokenIsRejectedAcrossSinks() throws Exception {
        // A private document owned by bypass_owner, with one file.
        clientUtil.createUser("bypass_owner");
        String ownerToken = clientUtil.login("bypass_owner");
        JsonObject doc = target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, ownerToken)
                .put(Entity.form(new Form().param("title", "secret").param("language", "eng")), JsonObject.class);
        String documentId = doc.getString("id");

        // Add a file so the file-data and zip sinks are exercisable.
        String fileId;
        try (InputStream is = Resources.getResource("file/PIA00452.jpg").openStream()) {
            StreamDataBodyPart filePart = new StreamDataBodyPart("file", is, "PIA00452.jpg");
            try (FormDataMultiPart multiPart = new FormDataMultiPart()) {
                JsonObject fileJson = target().register(MultiPartFeature.class).path("/file").request()
                        .cookie(TokenBasedSecurityFilter.COOKIE_NAME, ownerToken)
                        .put(Entity.entity(multiPart.field("id", documentId).bodyPart(filePart),
                                MediaType.MULTIPART_FORM_DATA_TYPE), JsonObject.class);
                fileId = fileJson.getString("id");
            }
        }

        // ATTACK: an anonymous caller forging the reserved admin token must NOT read the document. The
        // same forged value is denied across EVERY ?share= sink, since they all route through the one
        // getTargetIdList guard — asserting each independently keeps a future per-sink refactor honest.
        for (String forged : new String[]{"admin", "administrators", documentId}) {
            Response r = target().path("/document/" + documentId).queryParam("share", forged).request().get();
            Assertions.assertTrue(isDenied(r.getStatus()),
                    "?share=" + forged + " must be denied on the document read, got " + r.getStatus());
        }

        record Sink(String label, jakarta.ws.rs.client.Invocation.Builder req) {}
        Sink[] sinks = new Sink[]{
                new Sink("document pdf", target().path("/document/" + documentId + "/pdf")
                        .queryParam("margin", "0").queryParam("share", "admin").request()),
                new Sink("comment list", target().path("/comment/" + documentId)
                        .queryParam("share", "admin").request()),
                new Sink("file list", target().path("/file/list").queryParam("id", documentId)
                        .queryParam("share", "admin").request()),
                new Sink("file data", target().path("/file/" + fileId + "/data")
                        .queryParam("share", "admin").request()),
                new Sink("file zip", target().path("/file/zip").queryParam("id", documentId)
                        .queryParam("share", "admin").request()),
        };
        for (Sink sink : sinks) {
            Response r = sink.req().get();
            Assertions.assertTrue(isDenied(r.getStatus()),
                    "?share=admin must be denied on the " + sink.label() + " sink, got " + r.getStatus());
        }
    }

    @Test
    public void genuineShareStillGrantsAnonymousAccess() {
        // Owner creates a document and a real share of it.
        clientUtil.createUser("share_ok_owner");
        String ownerToken = clientUtil.login("share_ok_owner");
        JsonObject doc = target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, ownerToken)
                .put(Entity.form(new Form().param("title", "shared").param("language", "eng")), JsonObject.class);
        String documentId = doc.getString("id");

        JsonObject share = target().path("/share").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, ownerToken)
                .put(Entity.form(new Form().param("id", documentId).param("name", "public link")), JsonObject.class);
        String shareId = share.getString("id");

        // The genuine share token still grants anonymous read (the fix must not break real shares).
        JsonObject viaShare = target().path("/document/" + documentId).queryParam("share", shareId)
                .request().get(JsonObject.class);
        Assertions.assertEquals(documentId, viaShare.getString("id"),
                "a genuine share token must still grant anonymous read access");
    }
}
