package com.sismics.docs.rest;

import com.sismics.util.filter.TokenBasedSecurityFilter;
import org.glassfish.jersey.media.multipart.FormDataMultiPart;
import org.glassfish.jersey.media.multipart.MultiPartFeature;
import org.glassfish.jersey.media.multipart.file.StreamDataBodyPart;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Form;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Tests the custom tag-icon set (#287).
 *
 * <p>The uploads are what needs pinning here. An icon is an image the server writes to disk under
 * a name it chooses and later serves back with a Content-Type it chose at upload time, so the
 * three things that must hold are: only an administrator can put one there, only the two accepted
 * image kinds get in, and the decision about WHICH kind it is comes from the bytes rather than
 * from anything the client said about them.</p>
 *
 * @author fmaass
 */
public class TestTagIconResource extends BaseJerseyTest {
    /** Real PNG bytes, encoded here so the test carries no binary fixture. */
    private static byte[] pngBytes(int side) throws IOException {
        BufferedImage image = new BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < side; x++) {
            for (int y = 0; y < side; y++) {
                image.setRGB(x, y, 0xFF000000 | (x * 37 + y * 11));
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static byte[] jpegBytes() throws IOException {
        BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }

    private static final String SVG =
            "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 16 16\">"
                    + "<path d=\"M2 2h12v12H2z\"/></svg>";

    /** Uploads one icon and returns the whole response, so a test can assert on a refusal too. */
    private Response upload(String token, String name, byte[] content, String fileName)
            throws IOException {
        try (InputStream is = new ByteArrayInputStream(content);
             FormDataMultiPart multiPart = new FormDataMultiPart()) {
            StreamDataBodyPart part = new StreamDataBodyPart("image", is, fileName);
            return target()
                    .register(MultiPartFeature.class)
                    .path("/tag/icon").request()
                    .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                    .put(Entity.entity(multiPart.field("name", name).bodyPart(part),
                            MediaType.MULTIPART_FORM_DATA_TYPE));
        }
    }

    private String uploadOk(String token, String name, byte[] content, String fileName)
            throws IOException {
        Response response = upload(token, name, content, fileName);
        Assertions.assertEquals(Status.OK, Status.fromStatusCode(response.getStatus()),
                "the upload must succeed");
        return response.readEntity(JsonObject.class).getString("id");
    }

    private JsonArray listIcons(String token) {
        return target().path("/tag/icon").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .get(JsonObject.class)
                .getJsonArray("icons");
    }

    /**
     * There is exactly ONE icon set per instance, shared by every user and outliving every
     * request — so it also outlives each test in this class. Assertions are therefore scoped to
     * the icon a test is actually about rather than to the size of the list.
     */
    private JsonObject findIcon(String token, String name) {
        for (JsonValue value : listIcons(token)) {
            JsonObject icon = value.asJsonObject();
            if (name.equals(icon.getString("name"))) {
                return icon;
            }
        }
        return null;
    }

    /** The whole happy path: an admin uploads, everybody lists, everybody fetches the bytes. */
    @Test
    public void testUploadListAndServeAnIcon() throws Exception {
        String adminToken = adminToken();
        clientUtil.createUser("tagicon_user");
        String userToken = clientUtil.login("tagicon_user");

        byte[] png = pngBytes(16);
        String iconId = uploadOk(adminToken, "Warning", png, "warning.png");
        Assertions.assertNotNull(iconId);

        // Any authenticated user sees the set — icons are reference data, not the admin's private
        // property: every user picks from the same set for their own tags.
        JsonObject icon = findIcon(userToken, "Warning");
        Assertions.assertNotNull(icon, "the uploaded icon is in the set");
        Assertions.assertEquals(iconId, icon.getString("id"));
        Assertions.assertEquals("image/png", icon.getString("mimetype"));

        // The bytes come back verbatim, typed, and cacheable.
        Response data = target().path("/tag/icon/" + iconId + "/data").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, userToken)
                .get();
        Assertions.assertEquals(Status.OK, Status.fromStatusCode(data.getStatus()));
        Assertions.assertEquals("image/png", data.getHeaderString(HttpHeaders.CONTENT_TYPE),
                "the icon is served as the type its BYTES were sniffed to be");
        Assertions.assertNotNull(data.getHeaderString(HttpHeaders.EXPIRES),
                "an icon never changes for a given id, so it is served with a far-future expiry");
        Assertions.assertTrue(data.getHeaderString(HttpHeaders.CACHE_CONTROL).contains("public"),
                "an icon is cacheable");
        Assertions.assertEquals("nosniff", data.getHeaderString("X-Content-Type-Options"),
                "the browser must not be allowed to re-interpret an uploaded file's type");
        Assertions.assertArrayEquals(png, data.readEntity(byte[].class),
                "the stored bytes come back unchanged");
    }

    /** An SVG is accepted and served as SVG — the format the reporter pasted into the issue. */
    @Test
    public void testAnSvgIconIsAcceptedAndTypedAsSvg() throws Exception {
        String adminToken = adminToken();
        String iconId = uploadOk(adminToken, "Flag", SVG.getBytes(StandardCharsets.UTF_8), "flag.svg");

        Response data = target().path("/tag/icon/" + iconId + "/data").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get();
        Assertions.assertEquals("image/svg+xml", data.getHeaderString(HttpHeaders.CONTENT_TYPE));
        // An SVG is a document, and a same-origin one can script. It is served under a policy that
        // forbids it everything, so opening the URL directly cannot execute anything.
        Assertions.assertNotNull(data.getHeaderString("Content-Security-Policy"),
                "an uploaded SVG is served under a content security policy");
    }

    /** Only an administrator may add to the set. */
    @Test
    public void testANonAdminCannotUploadAnIcon() throws Exception {
        clientUtil.createUser("tagicon_nonadmin");
        String userToken = clientUtil.login("tagicon_nonadmin");

        Response response = upload(userToken, "Sneaky", pngBytes(16), "sneaky.png");
        Assertions.assertEquals(Status.FORBIDDEN, Status.fromStatusCode(response.getStatus()));
        Assertions.assertNull(findIcon(userToken, "Sneaky"),
                "the refused upload left nothing behind");
    }

    /** Only an administrator may remove from it. */
    @Test
    public void testANonAdminCannotDeleteAnIcon() throws Exception {
        String adminToken = adminToken();
        clientUtil.createUser("tagicon_nodelete");
        String userToken = clientUtil.login("tagicon_nodelete");
        String iconId = uploadOk(adminToken, "Keep", pngBytes(16), "keep.png");

        Response response = target().path("/tag/icon/" + iconId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, userToken)
                .delete();
        Assertions.assertEquals(Status.FORBIDDEN, Status.fromStatusCode(response.getStatus()));
        Assertions.assertNotNull(findIcon(adminToken, "Keep"), "the icon is still there");
        Assertions.assertNotNull(iconId);
    }

    /** The set is not anonymous reference data — it is behind the session like every other list. */
    @Test
    public void testTheIconSetIsNotReadableAnonymously() throws Exception {
        String adminToken = adminToken();
        String iconId = uploadOk(adminToken, "Private", pngBytes(16), "private.png");

        Assertions.assertEquals(Status.FORBIDDEN, Status.fromStatusCode(
                target().path("/tag/icon").request().get().getStatus()));
        Assertions.assertEquals(Status.FORBIDDEN, Status.fromStatusCode(
                target().path("/tag/icon/" + iconId + "/data").request().get().getStatus()));
    }

    /**
     * A type outside the two accepted ones is refused. The client declares `image/jpeg` here and
     * the bytes really are a JPEG — the point is that JPEG is not on the list, not that the client
     * lied.
     */
    @Test
    public void testAJpegIsRefused() throws Exception {
        String adminToken = adminToken();
        Response response = upload(adminToken, "Photo", jpegBytes(), "photo.jpg");
        Assertions.assertEquals(Status.BAD_REQUEST, Status.fromStatusCode(response.getStatus()));
        Assertions.assertEquals("InvalidImageType",
                response.readEntity(JsonObject.class).getString("type"));
    }

    /**
     * The type is decided by the BYTES. A file named `.png` whose content is not a PNG is refused —
     * otherwise the server would store arbitrary content and later hand it back labelled
     * `image/png`, which is exactly the shape of a stored-content attack.
     */
    @Test
    public void testTheDeclaredFileNameDoesNotDecideTheType() throws Exception {
        String adminToken = adminToken();
        byte[] notAnImage = "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8);

        Response response = upload(adminToken, "Liar", notAnImage, "innocent.png");
        Assertions.assertEquals(Status.BAD_REQUEST, Status.fromStatusCode(response.getStatus()));
        Assertions.assertEquals("InvalidImageType",
                response.readEntity(JsonObject.class).getString("type"));
        Assertions.assertNull(findIcon(adminToken, "Liar"), "nothing was stored");
    }

    /**
     * A NAMESPACE-PREFIXED script element is refused.
     *
     * <p>This is the payload that defeats a substring check: {@code <svg:script>} never contains
     * the text {@code <script}, so a scan for that literal waves it through while the browser
     * still executes it. The check is XML-aware and matches on the element's LOCAL name, which is
     * {@code script} either way.</p>
     */
    @Test
    public void testANamespacePrefixedScriptIsRefused() throws Exception {
        String adminToken = adminToken();
        String hostile = "<svg xmlns=\"http://www.w3.org/2000/svg\" "
                + "xmlns:s=\"http://www.w3.org/2000/svg\"><s:script>alert(1)</s:script></svg>";

        Response response = upload(adminToken, "PrefixedScript",
                hostile.getBytes(StandardCharsets.UTF_8), "prefixed.svg");
        Assertions.assertEquals(Status.BAD_REQUEST, Status.fromStatusCode(response.getStatus()));
        Assertions.assertEquals("InvalidImageContent",
                response.readEntity(JsonObject.class).getString("type"));
        Assertions.assertNull(findIcon(adminToken, "PrefixedScript"), "nothing was stored");
    }

    /**
     * An EXTERNAL reference is refused. An icon that fetches from another host on render would
     * report every viewer of every tagged document to that host.
     */
    @Test
    public void testAnExternalImageReferenceIsRefused() throws Exception {
        String adminToken = adminToken();
        String hostile = "<svg xmlns=\"http://www.w3.org/2000/svg\">"
                + "<image href=\"https://example.invalid/pixel.png\"/></svg>";

        Response response = upload(adminToken, "ExternalImage",
                hostile.getBytes(StandardCharsets.UTF_8), "external.svg");
        Assertions.assertEquals(Status.BAD_REQUEST, Status.fromStatusCode(response.getStatus()));
        Assertions.assertEquals("InvalidImageContent",
                response.readEntity(JsonObject.class).getString("type"));
        Assertions.assertNull(findIcon(adminToken, "ExternalImage"), "nothing was stored");
    }

    /** The same, through the legacy xlink spelling. */
    @Test
    public void testAnExternalXlinkReferenceIsRefused() throws Exception {
        String adminToken = adminToken();
        String hostile = "<svg xmlns=\"http://www.w3.org/2000/svg\" "
                + "xmlns:xlink=\"http://www.w3.org/1999/xlink\">"
                + "<use xlink:href=\"https://example.invalid/sprite.svg#icon\"/></svg>";

        Response response = upload(adminToken, "ExternalXlink",
                hostile.getBytes(StandardCharsets.UTF_8), "xlink.svg");
        Assertions.assertEquals(Status.BAD_REQUEST, Status.fromStatusCode(response.getStatus()));
        Assertions.assertEquals("InvalidImageContent",
                response.readEntity(JsonObject.class).getString("type"));
    }

    /** An event-handler attribute is refused, whatever its case. */
    @Test
    public void testAnEventHandlerAttributeIsRefused() throws Exception {
        String adminToken = adminToken();
        String hostile = "<svg xmlns=\"http://www.w3.org/2000/svg\" OnLoad=\"alert(1)\">"
                + "<path d=\"M2 2h12v12H2z\"/></svg>";

        Response response = upload(adminToken, "Handler",
                hostile.getBytes(StandardCharsets.UTF_8), "handler.svg");
        Assertions.assertEquals(Status.BAD_REQUEST, Status.fromStatusCode(response.getStatus()));
        Assertions.assertEquals("InvalidImageContent",
                response.readEntity(JsonObject.class).getString("type"));
    }

    /** A DOCTYPE is refused — it is how an SVG reads files off the server. */
    @Test
    public void testADoctypeIsRefused() throws Exception {
        String adminToken = adminToken();
        String hostile = "<?xml version=\"1.0\"?><!DOCTYPE svg [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>"
                + "<svg xmlns=\"http://www.w3.org/2000/svg\"><text>&xxe;</text></svg>";

        Response response = upload(adminToken, "Doctype",
                hostile.getBytes(StandardCharsets.UTF_8), "doctype.svg");
        Assertions.assertEquals(Status.BAD_REQUEST, Status.fromStatusCode(response.getStatus()));
        Assertions.assertNull(findIcon(adminToken, "Doctype"), "nothing was stored");
    }

    /** A style that fetches something is refused. */
    @Test
    public void testAStyleWithAnExternalUrlIsRefused() throws Exception {
        String adminToken = adminToken();
        String hostile = "<svg xmlns=\"http://www.w3.org/2000/svg\">"
                + "<rect style=\"fill:url(https://example.invalid/x)\"/></svg>";

        Response response = upload(adminToken, "StyleUrl",
                hostile.getBytes(StandardCharsets.UTF_8), "style.svg");
        Assertions.assertEquals(Status.BAD_REQUEST, Status.fromStatusCode(response.getStatus()));
        Assertions.assertEquals("InvalidImageContent",
                response.readEntity(JsonObject.class).getString("type"));
    }

    /** Content that is not well-formed XML is not an SVG. */
    @Test
    public void testMalformedXmlIsRefused() throws Exception {
        String adminToken = adminToken();
        Response response = upload(adminToken, "Malformed",
                "<svg xmlns=\"http://www.w3.org/2000/svg\"><path>".getBytes(StandardCharsets.UTF_8),
                "malformed.svg");
        Assertions.assertEquals(Status.BAD_REQUEST, Status.fromStatusCode(response.getStatus()));
        Assertions.assertEquals("InvalidImageType",
                response.readEntity(JsonObject.class).getString("type"));
    }

    /**
     * The other half of the rule: an ordinary drawing, including a SAME-DOCUMENT reference, is
     * accepted. A guard that refused these would make the feature useless.
     */
    @Test
    public void testAPlainSvgWithASameDocumentReferenceIsAccepted() throws Exception {
        String adminToken = adminToken();
        String benign = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 16 16\">"
                + "<defs><linearGradient id=\"g\"><stop offset=\"0\"/></linearGradient></defs>"
                + "<rect style=\"fill:#336699\" width=\"16\" height=\"16\"/>"
                + "<use href=\"#g\"/><path d=\"M2 2h12v12H2z\"/></svg>";

        String iconId = uploadOk(adminToken, "Benign", benign.getBytes(StandardCharsets.UTF_8),
                "benign.svg");
        Response data = target().path("/tag/icon/" + iconId + "/data").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get();
        Assertions.assertEquals("image/svg+xml", data.getHeaderString(HttpHeaders.CONTENT_TYPE));
    }

    /**
     * Deleting the same icon twice answers 404 the second time rather than 500.
     *
     * <p>Sequentially the pre-check already caught this; the 500 was the RACE, where two deletes
     * both passed that check and the second reached a row that had gone. The delete is now
     * idempotent in one step, so both orderings give the client the same answer.</p>
     */
    @Test
    public void testDeletingAnIconTwiceIsNotFound() throws Exception {
        String adminToken = adminToken();
        String iconId = uploadOk(adminToken, "DeleteTwice", pngBytes(16), "twice.png");

        Response first = target().path("/tag/icon/" + iconId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete();
        Assertions.assertEquals(Status.OK, Status.fromStatusCode(first.getStatus()));

        Response second = target().path("/tag/icon/" + iconId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete();
        Assertions.assertEquals(Status.NOT_FOUND, Status.fromStatusCode(second.getStatus()),
                "a second delete is Not Found, never a server error");
    }

    /** An SVG carrying a script is refused outright rather than stored and neutralised on the way out. */
    @Test
    public void testAnSvgContainingAScriptIsRefused() throws Exception {
        String adminToken = adminToken();
        String hostile = "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>";

        Response response = upload(adminToken, "Hostile", hostile.getBytes(StandardCharsets.UTF_8),
                "hostile.svg");
        Assertions.assertEquals(Status.BAD_REQUEST, Status.fromStatusCode(response.getStatus()));
        Assertions.assertEquals("InvalidImageContent",
                response.readEntity(JsonObject.class).getString("type"));
    }

    /** Above the size cap the upload is refused, and nothing is written. */
    @Test
    public void testAnOversizeIconIsRefused() throws Exception {
        String adminToken = adminToken();
        // A genuine PNG well past the 32 KiB cap: 256x256 of per-pixel noise does not compress.
        byte[] big = pngBytes(256);
        Assertions.assertTrue(big.length > 32 * 1024,
                "the fixture has to actually exceed the cap, or this test proves nothing");

        Response response = upload(adminToken, "Huge", big, "huge.png");
        Assertions.assertEquals(Status.BAD_REQUEST, Status.fromStatusCode(response.getStatus()));
        Assertions.assertEquals("PayloadTooLarge",
                response.readEntity(JsonObject.class).getString("type"));
        Assertions.assertNull(findIcon(adminToken, "Huge"), "nothing was stored");
    }

    /** An icon needs a name, and the name is length-checked like every other name in the API. */
    @Test
    public void testAnIconNeedsAName() throws Exception {
        String adminToken = adminToken();
        Response response = upload(adminToken, "", pngBytes(16), "nameless.png");
        Assertions.assertEquals(Status.BAD_REQUEST, Status.fromStatusCode(response.getStatus()));
        Assertions.assertEquals("ValidationError",
                response.readEntity(JsonObject.class).getString("type"));
    }

    /** Fetching an icon that does not exist is a 404, not a broken stream. */
    @Test
    public void testAnUnknownIconIsNotFound() {
        String adminToken = adminToken();
        Response response = target().path("/tag/icon/8b1e4f22-0000-4000-8000-0000000000ff/data")
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get();
        Assertions.assertEquals(Status.NOT_FOUND, Status.fromStatusCode(response.getStatus()));
    }

    /**
     * Deleting an icon takes it off the tags that used it. This is the contract the chip depends
     * on: a tag never points at an icon that is gone, so nothing ever renders a broken image.
     */
    @Test
    public void testDeletingAnIconLeavesTheTagsThatUsedItWithNoIcon() throws Exception {
        String adminToken = adminToken();
        clientUtil.createUser("tagicon_owner");
        String ownerToken = clientUtil.login("tagicon_owner");

        String iconId = uploadOk(adminToken, "Doomed", pngBytes(16), "doomed.png");

        // Any user may USE any icon on their own tags — only the SET is admin-managed.
        JsonObject json = target().path("/tag").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, ownerToken)
                .put(Entity.form(new Form()
                        .param("name", "IconTag")
                        .param("color", "#ff0000")
                        .param("icon", "set:" + iconId)), JsonObject.class);
        String tagId = json.getString("id");
        Assertions.assertEquals("set:" + iconId,
                target().path("/tag/" + tagId).request()
                        .cookie(TokenBasedSecurityFilter.COOKIE_NAME, ownerToken)
                        .get(JsonObject.class).getString("icon"));

        Response deleted = target().path("/tag/icon/" + iconId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete();
        Assertions.assertEquals(Status.OK, Status.fromStatusCode(deleted.getStatus()));

        JsonObject tag = target().path("/tag/" + tagId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, ownerToken)
                .get(JsonObject.class);
        Assertions.assertFalse(tag.containsKey("icon"),
                "the tag reports no icon at all once the icon it used was deleted");

        Assertions.assertEquals(Status.NOT_FOUND, Status.fromStatusCode(
                target().path("/tag/icon/" + iconId + "/data").request()
                        .cookie(TokenBasedSecurityFilter.COOKIE_NAME, ownerToken)
                        .get().getStatus()));
    }
}
