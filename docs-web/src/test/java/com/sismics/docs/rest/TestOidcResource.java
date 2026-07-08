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

    /**
     * R-021: OIDC enabled but misconfigured (missing client_id) must NOT return a
     * raw 500 (which auto-loops the browser). It must redirect to the SPA login
     * error page, which offers a route back to the local login form.
     */
    @Test
    public void testLoginMisconfiguredRedirectsToErrorPage() throws Exception {
        // Force re-validation with a broken config (client_id blank).
        resetOidcConfigCache();
        System.clearProperty("docs.oidc_client_id");
        try {
            Response response = noRedirectTarget.path("/oidc/login")
                    .request()
                    .get();
            Assertions.assertEquals(Response.Status.TEMPORARY_REDIRECT.getStatusCode(), response.getStatus(),
                    "misconfigured OIDC login must redirect, not 500");
            String location = response.getHeaderString("Location");
            Assertions.assertNotNull(location);
            Assertions.assertTrue(location.endsWith("/#/login?error=oidc"),
                    "Expected redirect to /#/login?error=oidc but got: " + location);
        } finally {
            System.setProperty("docs.oidc_client_id", "test");
            resetOidcConfigCache();
        }
    }

    /**
     * R-024: the state parameter is single-use and TTL-bound. An unknown/consumed
     * state on callback must be rejected as a bad request (fail closed).
     */
    @Test
    public void testCallbackUnknownStateRejected() {
        Response response = target().path("/oidc/callback")
                .queryParam("code", "somecode")
                .queryParam("state", "never-issued-state")
                .request()
                .get();
        Assertions.assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus(),
                "an unknown/consumed state must be rejected");
    }

    /**
     * R-024: login() with explicit endpoints issues a fresh state and redirects to
     * the authorization endpoint carrying that state + PKCE challenge. A returnUrl
     * outside the /#/ whitelist must not break the flow (it is dropped internally).
     */
    @Test
    public void testLoginRedirectsToAuthEndpointWithStateAndPkce() throws Exception {
        System.setProperty("docs.oidc_authorization_endpoint", "https://auth.example.com/authorize");
        try {
            resetOidcConfigCache();
            System.setProperty("docs.oidc_client_id", "test");
            Response response = noRedirectTarget.path("/oidc/login")
                    .queryParam("returnUrl", "https://evil.example.com/steal")
                    .request()
                    .get();
            Assertions.assertEquals(Response.Status.TEMPORARY_REDIRECT.getStatusCode(), response.getStatus());
            String location = response.getHeaderString("Location");
            Assertions.assertNotNull(location);
            Assertions.assertTrue(location.startsWith("https://auth.example.com/authorize?"),
                    "Expected redirect to the authorization endpoint but got: " + location);
            Assertions.assertTrue(location.contains("state="), "authorize URL must carry a state");
            Assertions.assertTrue(location.contains("code_challenge="), "authorize URL must carry a PKCE challenge");
            Assertions.assertTrue(location.contains("code_challenge_method=S256"), "PKCE method must be S256");
            Assertions.assertFalse(location.contains("evil.example.com"),
                    "a non-whitelisted returnUrl must not appear in the authorize redirect");
        } finally {
            System.clearProperty("docs.oidc_authorization_endpoint");
            resetOidcConfigCache();
        }
    }

    /**
     * Invokes the package-private test seam OidcResource.resetConfigCacheForTest()
     * via reflection (the test lives in a different package).
     */
    private static void resetOidcConfigCache() throws Exception {
        Class<?> clazz = Class.forName("com.sismics.docs.rest.resource.OidcResource");
        java.lang.reflect.Method m = clazz.getDeclaredMethod("resetConfigCacheForTest");
        m.setAccessible(true);
        m.invoke(null);
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
