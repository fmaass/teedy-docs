package com.sismics.docs.rest.resource;

import com.sun.net.httpserver.HttpServer;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Regression coverage for #156 (reported in #155): RP-initiated logout must still reach the IdP's
 * {@code end_session_endpoint} when an operator configures the four OIDC endpoints explicitly.
 *
 * <p>With all endpoints set explicitly, no login/callback path ever fetches the discovery document,
 * so the value-keyed discovery cache stays empty. The old {@code getEndSessionEndpoint} only READ
 * that cache, so an empty cache silently disabled RP-initiated logout. The fix lets
 * {@code getEndSessionEndpoint} populate the discovery cache ON DEMAND (bounded by the shared
 * client's call timeout, failing open to local logout on any error), and adds a
 * system-property-only {@code docs.oidc_end_session_endpoint} override that short-circuits
 * discovery entirely.
 *
 * <p>These tests drive the real resolution path against an ephemeral local {@link HttpServer}
 * standing in for the IdP's discovery endpoint (no live IdP), mirroring the HttpServer pattern used
 * by {@code TestOidcClaims}. Effective config is supplied via {@code docs.oidc_*} system properties
 * (the property tier of the accessor), matching {@code TestOidcTokenHardening}.
 */
public class TestOidcEndSessionDiscovery {

    private static final String CLIENT_ID = "teedy-client";

    @AfterEach
    public void tearDown() {
        for (String key : new String[]{
                "docs.oidc_enabled", "docs.oidc_issuer", "docs.oidc_client_id",
                "docs.oidc_redirect_uri", "docs.oidc_authorization_endpoint",
                "docs.oidc_token_endpoint", "docs.oidc_jwks_uri", "docs.oidc_userinfo_endpoint",
                "docs.oidc_end_session_endpoint"}) {
            System.clearProperty(key);
        }
        OidcResource.resetConfigCacheForTest();
    }

    /**
     * Test 1 (fails on the pre-fix code): all four endpoints are set explicitly and the discovery
     * cache is empty, but the provider's discovery document advertises an {@code end_session_endpoint}.
     * Logout must fetch discovery on demand and compose a {@code logout_url}. On the pre-fix code
     * {@code getEndSessionEndpoint} only read the empty cache, so {@code resolveLogoutUrl} returned
     * null and RP-initiated logout was silently disabled.
     */
    @Test
    public void explicitEndpointsWithEmptyCacheStillResolvesLogoutUrl() throws Exception {
        AtomicInteger discoveryHits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            int port = server.getAddress().getPort();
            String issuer = "http://127.0.0.1:" + port;
            String endSession = issuer + "/logout";
            JsonObject discovery = Json.createObjectBuilder()
                    .add("issuer", issuer)
                    .add("end_session_endpoint", endSession)
                    .build();
            serveDiscovery(server, issuer, discovery, discoveryHits);
            server.start();

            configureExplicitEndpoints(issuer);
            OidcResource.resetConfigCacheForTest(); // discovery cache starts EMPTY

            String idToken = unsignedIdToken(issuer, CLIENT_ID);
            String url = OidcResource.resolveLogoutUrl(idToken);

            Assertions.assertNotNull(url,
                    "explicit endpoints + empty discovery cache must still resolve a logout URL "
                            + "(on-demand discovery fetch); got null (pre-fix behavior)");
            Assertions.assertTrue(url.startsWith(endSession),
                    "the composed logout URL must start with the discovered end_session_endpoint: " + url);
            Assertions.assertTrue(discoveryHits.get() >= 1,
                    "the discovery document must have been fetched on demand");
        } finally {
            server.stop(0);
        }
    }

    /**
     * Test 2: a discovery fetch that fails (issuer host unreachable) must fail open to local logout —
     * {@code resolveLogoutUrl} returns null, throws no exception, and returns promptly (never blocks
     * logout indefinitely). Nothing listens on 127.0.0.1:1, so the connection is refused at once.
     */
    @Test
    public void discoveryFetchFailureFailsOpenToLocalLogout() {
        String issuer = "http://127.0.0.1:1"; // connection refused, no listener
        configureExplicitEndpoints(issuer);
        OidcResource.resetConfigCacheForTest();

        String idToken = unsignedIdToken(issuer, CLIENT_ID);

        long startNanos = System.nanoTime();
        String url = OidcResource.resolveLogoutUrl(idToken);
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

        Assertions.assertNull(url,
                "an unreachable discovery endpoint must fail open to local logout (null URL)");
        Assertions.assertTrue(elapsedMs < OidcResource.HTTP_CALL_TIMEOUT_SECONDS * 1000,
                "logout must return promptly on a discovery failure, never block; took " + elapsedMs + "ms");
    }

    /**
     * Test 3: the system-property-only {@code docs.oidc_end_session_endpoint} override is honored
     * WITHOUT any discovery fetch. The issuer points at a live discovery server advertising a
     * DIFFERENT end_session_endpoint; the override must win and the discovery endpoint must receive
     * zero requests.
     */
    @Test
    public void systemPropertyOverrideHonoredWithoutDiscoveryFetch() throws Exception {
        AtomicInteger discoveryHits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            int port = server.getAddress().getPort();
            String issuer = "http://127.0.0.1:" + port;
            JsonObject discovery = Json.createObjectBuilder()
                    .add("issuer", issuer)
                    .add("end_session_endpoint", issuer + "/discovered-logout")
                    .build();
            serveDiscovery(server, issuer, discovery, discoveryHits);
            server.start();

            configureExplicitEndpoints(issuer);
            String override = "https://override.example/end-session";
            System.setProperty("docs.oidc_end_session_endpoint", override);
            OidcResource.resetConfigCacheForTest();

            String resolved = OidcResource.getEndSessionEndpoint(OidcResource.snapshot());

            Assertions.assertEquals(override, resolved,
                    "the system-property override must win over the discovered end_session_endpoint");
            Assertions.assertEquals(0, discoveryHits.get(),
                    "the override must short-circuit discovery: no discovery fetch may occur");
        } finally {
            server.stop(0);
        }
    }

    // --- helpers ---------------------------------------------------------------------------

    /** Sets every OIDC endpoint explicitly (so no login/callback path fetches discovery). */
    private static void configureExplicitEndpoints(String issuer) {
        System.setProperty("docs.oidc_enabled", "true");
        System.setProperty("docs.oidc_issuer", issuer);
        System.setProperty("docs.oidc_client_id", CLIENT_ID);
        System.setProperty("docs.oidc_redirect_uri", issuer + "/api/oidc/callback");
        System.setProperty("docs.oidc_authorization_endpoint", issuer + "/authorize");
        System.setProperty("docs.oidc_token_endpoint", issuer + "/token");
        System.setProperty("docs.oidc_jwks_uri", issuer + "/jwks");
        System.setProperty("docs.oidc_userinfo_endpoint", issuer + "/userinfo");
    }

    /** Serves {@code discovery} at the issuer's well-known path, counting each hit. */
    private static void serveDiscovery(HttpServer server, String issuer, JsonObject discovery,
                                       AtomicInteger hits) {
        server.createContext("/.well-known/openid-configuration", exchange -> {
            hits.incrementAndGet();
            byte[] bytes = discovery.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
    }

    /**
     * Builds an UNSIGNED id_token carrying the given {@code iss} and {@code aud}. resolveLogoutUrl
     * reads iss/aud without verifying the signature (our own previously-verified stored token), so a
     * placeholder signature segment suffices — matching {@code TestOidcLogoutTornRead}.
     */
    private static String unsignedIdToken(String issuer, String audience) {
        String header = b64url("{\"alg\":\"RS256\",\"typ\":\"JWT\"}");
        String payload = b64url("{\"sub\":\"user-1\",\"iss\":\"" + issuer + "\",\"aud\":\""
                + audience + "\"}");
        return header + "." + payload + ".c2ln";
    }

    private static String b64url(String json) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}
