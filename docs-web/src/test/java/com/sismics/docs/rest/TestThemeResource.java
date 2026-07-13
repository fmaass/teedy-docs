package com.sismics.docs.rest;

import com.google.common.io.Resources;
import com.sismics.util.filter.TokenBasedSecurityFilter;
import org.glassfish.jersey.media.multipart.FormDataMultiPart;
import org.glassfish.jersey.media.multipart.MultiPartFeature;
import org.glassfish.jersey.media.multipart.file.StreamDataBodyPart;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Form;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.InputStream;

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
}
