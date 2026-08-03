package com.sismics.docs.rest;

import com.google.common.io.Resources;
import com.sismics.docs.core.util.DirectoryUtil;
import com.sismics.util.filter.TokenBasedSecurityFilter;
import org.glassfish.jersey.media.multipart.FormDataMultiPart;
import org.glassfish.jersey.media.multipart.MultiPartFeature;
import org.glassfish.jersey.media.multipart.file.StreamDataBodyPart;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Form;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Locale;

/**
 * Test the theme resource.
 * 
 * @author bgamard
 */
public class TestThemeResource extends BaseJerseyTest {
    /**
     * Test the theme resource.
     */
    @Test
    public void testThemeResource() throws Exception {
        // Login admin
        String adminToken = adminToken();

        // Get the stylesheet anonymously
        String stylesheet = target().path("/theme/stylesheet").request()
                .get(String.class);
        Assertions.assertTrue(stylesheet.contains("background-color: #ffffff;"));

        // Get the theme configuration anonymously
        JsonObject json = target().path("/theme").request()
                .get(JsonObject.class);
        Assertions.assertEquals("Teedy", json.getString("name"));
        Assertions.assertEquals("#ffffff", json.getString("color"));
        Assertions.assertEquals("", json.getString("css"));

        // Update the main color as admin
        target().path("/theme").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form()
                        .param("color", "#ff0000")
                .param("name", "My App")
                .param("css", ".body { content: 'Custom CSS'; }")), JsonObject.class);

        // Get the stylesheet anonymously
        stylesheet = target().path("/theme/stylesheet").request()
                .get(String.class);
        Assertions.assertTrue(stylesheet.contains("background-color: #ff0000;"));
        Assertions.assertTrue(stylesheet.contains("Custom CSS"));

        // Get the theme configuration anonymously
        json = target().path("/theme").request()
                .get(JsonObject.class);
        Assertions.assertEquals("My App", json.getString("name"));
        Assertions.assertEquals("#ff0000", json.getString("color"));
        Assertions.assertEquals(".body { content: 'Custom CSS'; }", json.getString("css"));
        // The favicon cache-bust token is present (0 when no custom favicon has been
        // uploaded into this data dir; a prior run may have left one, so we assert it
        // is non-negative here and prove it CHANGES on upload below — the real contract).
        long faviconVersionBefore = json.getJsonNumber("favicon_version").longValue();
        Assertions.assertTrue(faviconVersionBefore >= 0L);

        // Get the logo
        Response response = target().path("/theme/image/logo").request().get();
        Assertions.assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());

        // Get the background
        response = target().path("/theme/image/background").request().get();
        Assertions.assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());

        // Change the logo
        try (InputStream is = Resources.getResource("file/PIA00452.jpg").openStream()) {
            StreamDataBodyPart streamDataBodyPart = new StreamDataBodyPart("image", is, "PIA00452.jpg");
            try (FormDataMultiPart multiPart = new FormDataMultiPart()) {
                target()
                        .register(MultiPartFeature.class)
                        .path("/theme/image/logo").request()
                        .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                        .put(Entity.entity(multiPart.bodyPart(streamDataBodyPart),
                                MediaType.MULTIPART_FORM_DATA_TYPE), JsonObject.class);
            }
        }

        // Change the background
        try (InputStream is = Resources.getResource("file/Einstein-Roosevelt-letter.png").openStream()) {
            StreamDataBodyPart streamDataBodyPart = new StreamDataBodyPart("image", is, "Einstein-Roosevelt-letter.png");
            try (FormDataMultiPart multiPart = new FormDataMultiPart()) {
                target()
                        .register(MultiPartFeature.class)
                        .path("/theme/image/background").request()
                        .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                        .put(Entity.entity(multiPart.bodyPart(streamDataBodyPart),
                                MediaType.MULTIPART_FORM_DATA_TYPE), JsonObject.class);
            }
        }

        // Get the logo
        response = target().path("/theme/image/logo").request().get();
        Assertions.assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());

        // Get the background
        response = target().path("/theme/image/background").request().get();
        Assertions.assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());

        // Get the favicon anonymously (bundled default, before any upload)
        response = target().path("/theme/image/favicon").request().get();
        Assertions.assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());

        // Change the favicon as admin (mirrors the logo/background upload path)
        try (InputStream is = Resources.getResource("file/PIA00452.jpg").openStream()) {
            StreamDataBodyPart streamDataBodyPart = new StreamDataBodyPart("image", is, "PIA00452.jpg");
            try (FormDataMultiPart multiPart = new FormDataMultiPart()) {
                target()
                        .register(MultiPartFeature.class)
                        .path("/theme/image/favicon").request()
                        .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                        .put(Entity.entity(multiPart.bodyPart(streamDataBodyPart),
                                MediaType.MULTIPART_FORM_DATA_TYPE), JsonObject.class);
            }
        }

        // Get the uploaded favicon
        response = target().path("/theme/image/favicon").request().get();
        Assertions.assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());

        // The favicon cache-bust token is now non-zero (the uploaded file's
        // last-modified stamp) and CHANGED from before the upload, so the SPA
        // re-fetches past the 15-day image cache when the favicon is replaced.
        json = target().path("/theme").request()
                .get(JsonObject.class);
        long faviconVersionAfter = json.getJsonNumber("favicon_version").longValue();
        Assertions.assertTrue(faviconVersionAfter > 0L);
        Assertions.assertNotEquals(faviconVersionBefore, faviconVersionAfter);

        // Reset the main color as admin
        target().path("/theme").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form()
                        .param("color", "#ffffff")
                .param("name", "Teedy")
                .param("css", "")), JsonObject.class);
    }

    /**
     * The theme config is stored as ONE JSON blob, so a partial POST used to overwrite every
     * field the caller happened to omit with null. The settings UI posts subsets (and an older
     * client never posts main_color at all), so the contract is: an ABSENT form parameter
     * PRESERVES the stored value, an EMPTY one CLEARS it.
     */
    @Test
    public void testThemePartialUpdateSemantics() throws Exception {
        String adminToken = adminToken();
        resetTheme(adminToken);
        try {
            // Seed every field in one call.
            postTheme(adminToken, new Form()
                    .param("color", "#010203")
                    .param("name", "Seeded")
                    .param("main_color", "#0a0b0c")
                    .param("css", ".seeded { color: red; }"));

            // Post ONLY the name: every other field must survive.
            postTheme(adminToken, new Form().param("name", "Renamed"));
            JsonObject json = getTheme();
            Assertions.assertEquals("Renamed", json.getString("name"));
            Assertions.assertEquals("#010203", json.getString("color"));
            Assertions.assertEquals("#0a0b0c", json.getString("main_color"));
            Assertions.assertEquals(".seeded { color: red; }", json.getString("css"));

            // An old client that posts name+color+css but has never heard of main_color must
            // not silently disable the branded palette.
            postTheme(adminToken, new Form()
                    .param("color", "#040506")
                    .param("name", "Legacy client")
                    .param("css", ".legacy { color: blue; }"));
            json = getTheme();
            Assertions.assertEquals("#0a0b0c", json.getString("main_color"));
            Assertions.assertEquals("#040506", json.getString("color"));

            // An EMPTY value clears: name falls back to the product default, color to #ffffff,
            // main_color to null (feature off), css to the empty string.
            postTheme(adminToken, new Form()
                    .param("name", "")
                    .param("color", "")
                    .param("main_color", "")
                    .param("css", ""));
            json = getTheme();
            Assertions.assertEquals("Teedy", json.getString("name"));
            Assertions.assertEquals("#ffffff", json.getString("color"));
            Assertions.assertTrue(json.isNull("main_color"));
            Assertions.assertEquals("", json.getString("css"));
        } finally {
            resetTheme(adminToken);
        }
    }

    /**
     * main_color is the #241 brand color the SPA derives its PrimeVue primary palette from.
     * Null means "feature off" (the look is unchanged); a value must be a valid hex color.
     */
    @Test
    public void testThemeMainColor() throws Exception {
        String adminToken = adminToken();
        resetTheme(adminToken);
        try {
            // Absent by default.
            Assertions.assertTrue(getTheme().isNull("main_color"));

            postTheme(adminToken, new Form().param("main_color", "#336699"));
            Assertions.assertEquals("#336699", getTheme().getString("main_color"));

            // Not a hex color -> validation error, and the stored value is untouched.
            Response response = target().path("/theme").request()
                    .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                    .post(Entity.form(new Form().param("main_color", "purple")));
            Assertions.assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
            Assertions.assertEquals("#336699", getTheme().getString("main_color"));

            // Only an admin may set it.
            clientUtil.createUser("theme_main_color_user");
            String userToken = clientUtil.login("theme_main_color_user");
            response = target().path("/theme").request()
                    .cookie(TokenBasedSecurityFilter.COOKIE_NAME, userToken)
                    .post(Entity.form(new Form().param("main_color", "#000000")));
            Assertions.assertEquals(Response.Status.FORBIDDEN.getStatusCode(), response.getStatus());
            Assertions.assertEquals("#336699", getTheme().getString("main_color"));
        } finally {
            resetTheme(adminToken);
        }
    }

    /**
     * Custom CSS lives in the theme directory (theme/custom.css), not in the 4000-char config
     * blob. The compiled stylesheet is: generated navbar rule, then the LEGACY blob css, then
     * the file — and both the modern text endpoints and the legacy form field stay coherent.
     */
    @Test
    public void testThemeStylesheetEndpoints() throws Exception {
        String adminToken = adminToken();
        resetTheme(adminToken);
        try {
            String stylesheetVersionEmpty = getTheme().getString("stylesheet_version");

            // A legacy client's form css still compiles into the stylesheet.
            postTheme(adminToken, new Form().param("css", ".legacy-blob { color: red; }"));
            String stylesheet = target().path("/theme/stylesheet").request().get(String.class);
            Assertions.assertTrue(stylesheet.contains(".legacy-blob"));
            Assertions.assertEquals(".legacy-blob { color: red; }", getTheme().getString("css"));

            // A modern PUT REPLACES the effective CSS: the file is written and the legacy blob
            // cleared, so a reset cannot leave the old rules active.
            Response response = target().path("/theme/stylesheet").request()
                    .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                    .put(Entity.entity(".modern-file { color: green; }", MediaType.TEXT_PLAIN_TYPE));
            Assertions.assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());

            stylesheet = target().path("/theme/stylesheet").request().get(String.class);
            Assertions.assertFalse(stylesheet.contains(".legacy-blob"));
            Assertions.assertTrue(stylesheet.contains(".modern-file"));
            // The generated navbar rule still comes FIRST so custom CSS can override it.
            Assertions.assertTrue(stylesheet.indexOf("background-color") < stylesheet.indexOf(".modern-file"));
            // GET /theme keeps reporting the EFFECTIVE custom CSS for old clients.
            Assertions.assertEquals(".modern-file { color: green; }", getTheme().getString("css"));

            // The cache-bust token tracks the effective body.
            String stylesheetVersionModern = getTheme().getString("stylesheet_version");
            Assertions.assertNotEquals(stylesheetVersionEmpty, stylesheetVersionModern);

            // The generated color rule is part of the hashed body: changing only the navbar
            // color must still bust the stylesheet cache.
            postTheme(adminToken, new Form().param("color", "#123456"));
            Assertions.assertNotEquals(stylesheetVersionModern, getTheme().getString("stylesheet_version"));

            // Only an admin may write it.
            clientUtil.createUser("theme_stylesheet_user");
            String userToken = clientUtil.login("theme_stylesheet_user");
            response = target().path("/theme/stylesheet").request()
                    .cookie(TokenBasedSecurityFilter.COOKIE_NAME, userToken)
                    .put(Entity.entity(".nope {}", MediaType.TEXT_PLAIN_TYPE));
            Assertions.assertEquals(Response.Status.FORBIDDEN.getStatusCode(), response.getStatus());

            // DELETE clears BOTH sources.
            postTheme(adminToken, new Form().param("css", ".legacy-again { color: red; }"));
            response = target().path("/theme/stylesheet").request()
                    .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                    .delete();
            Assertions.assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
            stylesheet = target().path("/theme/stylesheet").request().get(String.class);
            Assertions.assertFalse(stylesheet.contains(".legacy-again"));
            Assertions.assertFalse(stylesheet.contains(".modern-file"));
            Assertions.assertEquals("", getTheme().getString("css"));
        } finally {
            resetTheme(adminToken);
        }
    }

    /**
     * The custom script is served as an EXTERNAL same-origin JavaScript file (never inlined),
     * with a JavaScript MIME type, an explicit UTF-8 charset and X-Content-Type-Options:
     * nosniff. It is publicly readable — the login shell and ordinary users load it.
     */
    @Test
    public void testThemeScriptEndpoints() throws Exception {
        String adminToken = adminToken();
        resetTheme(adminToken);
        try {
            // Empty by default: a 200 with an empty body, not a 404.
            Response response = target().path("/theme/script").request().get();
            Assertions.assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
            Assertions.assertEquals("", response.readEntity(String.class));
            String contentType = response.getHeaderString(HttpHeaders.CONTENT_TYPE).toLowerCase(Locale.ROOT);
            Assertions.assertTrue(contentType.contains("javascript"), "content type was " + contentType);
            Assertions.assertTrue(contentType.contains("charset=utf-8"), "content type was " + contentType);
            Assertions.assertEquals("nosniff", response.getHeaderString("X-Content-Type-Options"));

            String scriptVersionEmpty = getTheme().getString("script_version");

            // Write it as admin.
            String script = "window.__teedyThemeProbe = 'ok';";
            response = target().path("/theme/script").request()
                    .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                    .put(Entity.entity(script, MediaType.TEXT_PLAIN_TYPE));
            Assertions.assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());

            Assertions.assertEquals(script, target().path("/theme/script").request().get(String.class));
            Assertions.assertNotEquals(scriptVersionEmpty, getTheme().getString("script_version"));

            // Only an admin may write it.
            clientUtil.createUser("theme_script_user");
            String userToken = clientUtil.login("theme_script_user");
            response = target().path("/theme/script").request()
                    .cookie(TokenBasedSecurityFilter.COOKIE_NAME, userToken)
                    .put(Entity.entity("window.__nope = 1;", MediaType.TEXT_PLAIN_TYPE));
            Assertions.assertEquals(Response.Status.FORBIDDEN.getStatusCode(), response.getStatus());
            Assertions.assertEquals(script, target().path("/theme/script").request().get(String.class));

            // DELETE empties it again.
            response = target().path("/theme/script").request()
                    .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                    .delete();
            Assertions.assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
            Assertions.assertEquals("", target().path("/theme/script").request().get(String.class));
            Assertions.assertEquals(scriptVersionEmpty, getTheme().getString("script_version"));
        } finally {
            resetTheme(adminToken);
        }
    }

    /**
     * A CSS/JS asset is capped at 256 KiB of UTF-8 so a runaway paste cannot fill the theme
     * directory or the config blob. Every write path — the two text endpoints and the legacy
     * form field — rejects with 413.
     */
    @Test
    public void testThemeAssetSizeLimit() throws Exception {
        String adminToken = adminToken();
        resetTheme(adminToken);
        try {
            String oversized = oversizedAsset();

            Response response = target().path("/theme/stylesheet").request()
                    .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                    .put(Entity.entity(oversized, MediaType.TEXT_PLAIN_TYPE));
            Assertions.assertEquals(413, response.getStatus());

            response = target().path("/theme/script").request()
                    .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                    .put(Entity.entity(oversized, MediaType.TEXT_PLAIN_TYPE));
            Assertions.assertEquals(413, response.getStatus());

            response = target().path("/theme").request()
                    .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                    .post(Entity.form(new Form().param("css", oversized)));
            Assertions.assertEquals(413, response.getStatus());

            // Nothing was written by any of the three rejections.
            Assertions.assertEquals("", getTheme().getString("css"));
            Assertions.assertEquals("", target().path("/theme/script").request().get(String.class));
        } finally {
            resetTheme(adminToken);
        }
    }

    /**
     * An admin can RESET a theme image back to the bundled default from the UI — without a
     * reset there is no way to undo a bad logo upload short of shell access to the data dir.
     */
    @Test
    public void testThemeImageDelete() throws Exception {
        String adminToken = adminToken();
        resetTheme(adminToken);
        try {
            // Upload a custom favicon.
            try (InputStream is = Resources.getResource("file/PIA00452.jpg").openStream()) {
                StreamDataBodyPart streamDataBodyPart = new StreamDataBodyPart("image", is, "PIA00452.jpg");
                try (FormDataMultiPart multiPart = new FormDataMultiPart()) {
                    target()
                            .register(MultiPartFeature.class)
                            .path("/theme/image/favicon").request()
                            .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                            .put(Entity.entity(multiPart.bodyPart(streamDataBodyPart),
                                    MediaType.MULTIPART_FORM_DATA_TYPE), JsonObject.class);
                }
            }
            Assertions.assertTrue(getTheme().getJsonNumber("favicon_version").longValue() > 0L);

            // A non-admin cannot reset it.
            clientUtil.createUser("theme_image_user");
            String userToken = clientUtil.login("theme_image_user");
            Response response = target().path("/theme/image/favicon").request()
                    .cookie(TokenBasedSecurityFilter.COOKIE_NAME, userToken)
                    .delete();
            Assertions.assertEquals(Response.Status.FORBIDDEN.getStatusCode(), response.getStatus());
            Assertions.assertTrue(getTheme().getJsonNumber("favicon_version").longValue() > 0L);

            // The admin reset drops the upload; the endpoint keeps serving the bundled default.
            response = target().path("/theme/image/favicon").request()
                    .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                    .delete();
            Assertions.assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
            Assertions.assertEquals(0L, getTheme().getJsonNumber("favicon_version").longValue());
            response = target().path("/theme/image/favicon").request().get();
            Assertions.assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());

            // Resetting an image that was never uploaded is a no-op, not an error.
            response = target().path("/theme/image/logo").request()
                    .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                    .delete();
            Assertions.assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
            response = target().path("/theme/image/logo").request()
                    .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                    .delete();
            Assertions.assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        } finally {
            resetTheme(adminToken);
        }
    }

    /**
     * The 256 KiB cap has to hold on the bytes that actually reach the disk, not on the bytes the
     * client sent. Lone UTF-8 continuation bytes are the gap: each one decodes to U+FFFD, which
     * re-encodes to THREE bytes, so a request sitting exactly on the limit would triple on write.
     */
    @Test
    public void testThemeAssetsRejectMalformedUtf8() throws Exception {
        String adminToken = adminToken();
        resetTheme(adminToken);
        try {
            // Seed known-good assets: a rejected write must also leave these untouched.
            String script = "window.__seeded = 1;";
            String css = ".seeded { color: red; }";
            putText(adminToken, "/theme/script", script);
            putText(adminToken, "/theme/stylesheet", css);

            // Exactly the raw-byte limit, entirely of lone continuation bytes (0x80 never starts a
            // valid UTF-8 sequence), so the raw-length bound alone lets it through.
            byte[] malformed = new byte[256 * 1024];
            java.util.Arrays.fill(malformed, (byte) 0x80);

            for (String path : new String[] { "/theme/script", "/theme/stylesheet" }) {
                Response response = target().path(path).request()
                        .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                        .put(Entity.entity(malformed, MediaType.TEXT_PLAIN_TYPE));
                int status = response.getStatus();
                Assertions.assertTrue(status >= 400 && status < 500,
                        path + " accepted malformed UTF-8 with status " + status);
            }

            // The authoritative check is the stored file, not the status code: assert on disk that
            // nothing over the cap was ever written.
            for (String fileName : new String[] { "custom.js", "custom.css" }) {
                java.nio.file.Path assetPath = DirectoryUtil.getThemeDirectory().resolve(fileName);
                if (Files.exists(assetPath)) {
                    Assertions.assertTrue(Files.size(assetPath) <= 256 * 1024,
                            fileName + " grew to " + Files.size(assetPath) + " bytes, over the 256 KiB cap");
                }
            }

            // …and the previously stored assets survived the rejected writes.
            Assertions.assertEquals(script, target().path("/theme/script").request().get(String.class));
            Assertions.assertEquals(css, getTheme().getString("css"));
        } finally {
            resetTheme(adminToken);
        }
    }

    /**
     * A colour parameter must be a real hexadecimal colour. Validating only its LENGTH accepted
     * "#gggggg", persisted it, and left the SPA quietly rendering the stock palette against a
     * configuration that claims otherwise.
     */
    @Test
    public void testThemeColorValidation() throws Exception {
        String adminToken = adminToken();
        resetTheme(adminToken);
        try {
            postTheme(adminToken, new Form().param("color", "#123456").param("main_color", "#abcdef"));

            // Right length, not hexadecimal.
            for (String field : new String[] { "color", "main_color" }) {
                Response response = target().path("/theme").request()
                        .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                        .post(Entity.form(new Form().param(field, "#gggggg")));
                Assertions.assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus(),
                        field + " accepted a non-hexadecimal value");
            }

            // Right length once trimmed, but the stored value would keep the padding.
            Response response = target().path("/theme").request()
                    .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                    .post(Entity.form(new Form().param("color", " #ff0000 ")));
            Assertions.assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());

            // Nothing was persisted by any of the rejections.
            JsonObject json = getTheme();
            Assertions.assertEquals("#123456", json.getString("color"));
            Assertions.assertEquals("#abcdef", json.getString("main_color"));

            // Green guard: a valid colour still round-trips, in either case.
            postTheme(adminToken, new Form().param("color", "#AABBCC").param("main_color", "#0f0f0f"));
            json = getTheme();
            Assertions.assertEquals("#AABBCC", json.getString("color"));
            Assertions.assertEquals("#0f0f0f", json.getString("main_color"));
        } finally {
            resetTheme(adminToken);
        }
    }

    /**
     * Writes a text asset and asserts it was accepted.
     */
    private void putText(String adminToken, String path, String body) {
        Response response = target().path(path).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.entity(body, MediaType.TEXT_PLAIN_TYPE));
        Assertions.assertEquals(Response.Status.OK.getStatusCode(), response.getStatus(),
                "PUT " + path + " rejected: " + response.readEntity(String.class));
    }

    /**
     * Returns the public theme configuration.
     */
    private JsonObject getTheme() {
        return target().path("/theme").request().get(JsonObject.class);
    }

    /**
     * Posts a theme form as the given admin and asserts it was accepted.
     */
    private void postTheme(String adminToken, Form form) {
        Response response = target().path("/theme").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(form));
        Assertions.assertEquals(Response.Status.OK.getStatusCode(), response.getStatus(),
                "POST /theme rejected: " + response.readEntity(String.class));
    }

    /**
     * Restores the pristine theme state. These tests share one data directory and one config
     * row with every other test in the run, so each of them both starts and ends here rather
     * than assuming an untouched instance.
     */
    private void resetTheme(String adminToken) {
        postTheme(adminToken, new Form()
                .param("color", "")
                .param("name", "")
                .param("main_color", "")
                .param("css", ""));
        target().path("/theme/script").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken).delete().close();
        for (String type : new String[] { "logo", "background", "favicon" }) {
            target().path("/theme/image/" + type).request()
                    .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken).delete().close();
        }
    }

    /**
     * A payload one byte over the 256 KiB CSS/JS asset limit.
     */
    private static String oversizedAsset() {
        int limit = 256 * 1024;
        StringBuilder sb = new StringBuilder(limit + 1);
        while (sb.length() <= limit) {
            sb.append('a');
        }
        return sb.toString();
    }
}
