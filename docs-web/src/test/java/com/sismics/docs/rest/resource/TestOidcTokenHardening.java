package com.sismics.docs.rest.resource;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Date;

/**
 * R-023 ID-token hardening and R-024 logout_url composition — unit-level coverage
 * of the parts reachable without a live IdP (no network in the test env).
 */
public class TestOidcTokenHardening {

    @AfterEach
    public void tearDown() throws Exception {
        setDiscoveryCache(null);
        System.clearProperty("docs.oidc_enabled");
    }

    /**
     * R-023: an ID token with no exp claim must be rejected. The exp guard runs
     * before any JWKS fetch, so no network is required.
     */
    @Test
    public void rejectsIdTokenWithoutExp() throws Exception {
        // A syntactically valid JWT with NO exp claim.
        String tokenNoExp = JWT.create()
                .withIssuer("https://auth.example.com")
                .withSubject("user-1")
                .sign(Algorithm.HMAC256("irrelevant-for-decode"));

        Exception thrown = Assertions.assertThrows(InvocationTargetException.class,
                () -> invokeVerifyIdToken(tokenNoExp));
        Throwable cause = thrown.getCause();
        Assertions.assertNotNull(cause);
        Assertions.assertTrue(cause.getMessage() != null && cause.getMessage().contains("exp"),
                "expected the missing-exp guard to fire, got: " + cause.getMessage());
    }

    /**
     * R-024: getEndSessionEndpoint composes from the cached discovery document.
     * Returns the value when OIDC is enabled and discovery advertises it, null otherwise.
     */
    @Test
    public void endSessionEndpointReadFromDiscovery() throws Exception {
        System.setProperty("docs.oidc_enabled", "true");
        JsonObject discovery = Json.createObjectBuilder()
                .add("issuer", "https://auth.example.com")
                .add("end_session_endpoint", "https://auth.example.com/logout")
                .build();
        setDiscoveryCache(discovery);

        Assertions.assertEquals("https://auth.example.com/logout", OidcResource.getEndSessionEndpoint());
    }

    @Test
    public void endSessionEndpointNullWhenDisabled() throws Exception {
        System.setProperty("docs.oidc_enabled", "false");
        JsonObject discovery = Json.createObjectBuilder()
                .add("issuer", "https://auth.example.com")
                .add("end_session_endpoint", "https://auth.example.com/logout")
                .build();
        setDiscoveryCache(discovery);

        Assertions.assertNull(OidcResource.getEndSessionEndpoint(),
                "no logout_url should be composed when OIDC is disabled");
    }

    @Test
    public void endSessionEndpointNullWhenDiscoveryLacksIt() throws Exception {
        System.setProperty("docs.oidc_enabled", "true");
        JsonObject discovery = Json.createObjectBuilder()
                .add("issuer", "https://auth.example.com")
                .build();
        setDiscoveryCache(discovery);

        Assertions.assertNull(OidcResource.getEndSessionEndpoint(),
                "no logout_url when the provider does not advertise end_session_endpoint");
    }

    private static void invokeVerifyIdToken(String idToken) throws Exception {
        System.setProperty("docs.oidc_issuer", "https://auth.example.com");
        System.setProperty("docs.oidc_client_id", "test");
        OidcResource resource = new OidcResource();
        Method m = OidcResource.class.getDeclaredMethod("verifyIdToken", String.class);
        m.setAccessible(true);
        m.invoke(resource, idToken);
    }

    private static void setDiscoveryCache(JsonObject value) throws Exception {
        Field f = OidcResource.class.getDeclaredField("discoveryCache");
        f.setAccessible(true);
        f.set(null, value);
    }
}
