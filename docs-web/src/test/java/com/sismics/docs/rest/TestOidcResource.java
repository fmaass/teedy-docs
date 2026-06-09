package com.sismics.docs.rest;

import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Test the OIDC resource error handling.
 */
public class TestOidcResource extends BaseJerseyTest {

    private Client noRedirectClient;
    private WebTarget noRedirectTarget;

    @Override
    @BeforeEach
    public void setUp() throws Exception {
        System.setProperty("docs.oidc_enabled", "true");
        System.setProperty("docs.oidc_issuer", "https://auth.example.com");
        System.setProperty("docs.oidc_client_id", "test");
        System.setProperty("docs.oidc_client_secret", "secret");
        System.setProperty("docs.oidc_redirect_uri", "https://app.example.com/api/oidc/callback");
        super.setUp();
        noRedirectClient = ClientBuilder.newBuilder()
                .property("jersey.config.client.followRedirects", false)
                .build();
        noRedirectTarget = noRedirectClient.target(
                UriBuilder.fromUri("http://localhost:" + getPort() + "/docs").build());
    }

    @Override
    @AfterEach
    public void tearDown() throws Exception {
        super.tearDown();
        if (noRedirectClient != null) {
            noRedirectClient.close();
        }
        System.clearProperty("docs.oidc_enabled");
        System.clearProperty("docs.oidc_issuer");
        System.clearProperty("docs.oidc_client_id");
        System.clearProperty("docs.oidc_client_secret");
        System.clearProperty("docs.oidc_redirect_uri");
    }

    @Test
    public void testCallbackProviderErrorRedirectsWithErrorParam() {
        Response response = noRedirectTarget.path("/oidc/callback")
                .queryParam("error", "access_denied")
                .request()
                .get();
        Assertions.assertEquals(Response.Status.TEMPORARY_REDIRECT.getStatusCode(), response.getStatus());
        String location = response.getHeaderString("Location");
        Assertions.assertNotNull(location);
        Assertions.assertTrue(location.endsWith("/#/login?error=oidc"),
                "Expected redirect to /#/login?error=oidc but got: " + location);
    }

    @Test
    public void testCallbackMissingCodeReturnsBadRequest() {
        Response response = target().path("/oidc/callback")
                .queryParam("state", "somestate")
                .request()
                .get();
        Assertions.assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
    }

    @Test
    public void testLoginDisabledReturns404() {
        System.setProperty("docs.oidc_enabled", "false");
        Response response = target().path("/oidc/login")
                .request()
                .get();
        Assertions.assertEquals(Response.Status.NOT_FOUND.getStatusCode(), response.getStatus());
    }

    @Test
    public void testCallbackDisabledReturns404() {
        System.setProperty("docs.oidc_enabled", "false");
        Response response = target().path("/oidc/callback")
                .queryParam("code", "test")
                .queryParam("state", "test")
                .request()
                .get();
        Assertions.assertEquals(Response.Status.NOT_FOUND.getStatusCode(), response.getStatus());
    }
}
