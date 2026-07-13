package com.sismics.docs.rest.resource;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import jakarta.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Configurable OIDC claim names (#21), username sanitization + deterministic collision
 * policy, and the sub-verified UserInfo fallback. The UserInfo path is exercised against an
 * ephemeral local {@link HttpServer} (no live IdP), asserting the request is a GET carrying
 * the Bearer access token, that the response is parsed, and that a {@code sub} mismatch is
 * rejected fail-closed (its claims discarded).
 */
public class TestOidcClaims {

    @AfterEach
    public void tearDown() throws Exception {
        System.clearProperty("docs.oidc_username_claim");
        System.clearProperty("docs.oidc_email_claim");
        System.clearProperty("docs.oidc_userinfo_endpoint");
        System.clearProperty("docs.oidc_issuer");
    }

    // --- Username sanitization -------------------------------------------------------------

    @Test
    public void sanitizeCollapsesNonCharsetRuns() throws Exception {
        // '@' '/' whitespace -> collapsed to single '_', trimmed at ends.
        Assertions.assertEquals("alice_example.com".replaceAll("[^A-Za-z0-9_]+", "_"),
                "alice_example_com", "sanity on the collapse target");
        Assertions.assertEquals("alice_example_com", invokeSanitize("alice@example.com"));
        Assertions.assertEquals("john_doe", invokeSanitize("  john/doe  "));
        Assertions.assertEquals("a_b_c", invokeSanitize("a  b\tc"));
    }

    @Test
    public void sanitizeReturnsNullWhenNothingSurvives() throws Exception {
        Assertions.assertNull(invokeSanitize("@@@"));
        Assertions.assertNull(invokeSanitize("   "));
        Assertions.assertNull(invokeSanitize(null));
    }

    // --- Deterministic username derivation ------------------------------------------------

    @Test
    public void deriveUsernameIsDeterministicPerIdentity() throws Exception {
        String u1 = invokeDerive("alice", "https://iss.example", "sub-1", 12);
        String u2 = invokeDerive("alice", "https://iss.example", "sub-1", 12);
        Assertions.assertEquals(u1, u2, "same identity must derive the same username");
        Assertions.assertTrue(u1.matches("^[a-zA-Z0-9_]{3,50}$"), "must match Teedy username charset: " + u1);
        Assertions.assertTrue(u1.startsWith("alice_"), "stem preserved: " + u1);
    }

    @Test
    public void deriveUsernameDiffersForDifferentSubjectsSameStem() throws Exception {
        // Two different subjects that sanitize to the same stem must NOT contend for one name.
        String u1 = invokeDerive("alice", "https://iss.example", "sub-1", 12);
        String u2 = invokeDerive("alice", "https://iss.example", "sub-2", 12);
        Assertions.assertNotEquals(u1, u2,
                "distinct subjects with the same stem must derive distinct usernames");
    }

    @Test
    public void deriveUsernameFitsPatternForLongStem() throws Exception {
        String longStem = "x".repeat(200);
        String u = invokeDerive(longStem, "https://iss.example", "sub-1", 12);
        Assertions.assertNotNull(u);
        Assertions.assertTrue(u.length() <= 50, "username must not exceed 50 chars: " + u.length());
        Assertions.assertTrue(u.matches("^[a-zA-Z0-9_]{3,50}$"), u);
    }

    // --- #59 verbatim username derivation (opt-in) -----------------------------------------

    /**
     * CHARACTERIZATION of the DEFAULT (hash-suffix) derivation: for a fixed (stem, issuer,
     * subject) the derived username is byte-identical and carries the deterministic hash suffix.
     * This pins the flag-OFF contract that must remain unchanged (Codex B4).
     */
    @Test
    public void hashSuffixDerivationIsByteIdenticalForIdentity() throws Exception {
        String u1 = invokeDerive("alice", "https://iss.example", "sub-verb-1", 12);
        String u2 = invokeDerive("alice", "https://iss.example", "sub-verb-1", 12);
        Assertions.assertEquals(u1, u2, "hash-suffix derivation must be deterministic");
        Assertions.assertTrue(u1.matches("^alice_[0-9a-f]{12}$"),
                "flag-OFF username must be the stem plus a 12-hex hash suffix: " + u1);
    }

    /**
     * Flag-ON: the sanitized preferred_username is used VERBATIM (no hash suffix) at the FULL
     * username-length budget — no {@code _<hash>} tail, and a long claim keeps far more of the
     * name than the hash-reserved truncation would (37-char cap vs 50-char cap).
     */
    @Test
    public void verbatimSanitizesAtFullLengthWithoutHashSuffix() throws Exception {
        Assertions.assertEquals("alice", invokeSanitizeVerbatim("alice"));
        Assertions.assertEquals("alice_example_com", invokeSanitizeVerbatim("alice@example.com"));
        // A 60-char claim: verbatim keeps 50 (full budget); the hash path would cap the stem at 37.
        String longClaim = "a".repeat(60);
        String verbatim = invokeSanitizeVerbatim(longClaim);
        Assertions.assertEquals(50, verbatim.length(), "verbatim uses the FULL 50-char budget");
        Assertions.assertFalse(verbatim.contains("_"), "verbatim carries no hash-suffix separator: " + verbatim);
        Assertions.assertTrue(verbatim.matches("^[a-zA-Z0-9_]{3,50}$"), verbatim);
    }

    /**
     * Verbatim sanitization returns null when nothing usable survives (all-symbol claim, or a
     * sanitized result shorter than the 3-char username minimum), so provisioning falls back to
     * the hash disambiguator rather than minting an invalid username.
     */
    @Test
    public void verbatimReturnsNullWhenNothingUsableSurvives() throws Exception {
        Assertions.assertNull(invokeSanitizeVerbatim("@@@"));
        Assertions.assertNull(invokeSanitizeVerbatim(null));
        Assertions.assertNull(invokeSanitizeVerbatim("ab"), "a <3-char stem cannot be a verbatim username");
    }

    // --- Claim name configuration ---------------------------------------------------------

    @Test
    public void claimNamesDefaultToPreferredUsernameAndEmail() throws Exception {
        Assertions.assertEquals("preferred_username", invokeStatic("getUsernameClaim"));
        Assertions.assertEquals("email", invokeStatic("getEmailClaim"));
    }

    @Test
    public void claimNamesHonorConfiguredOverrides() throws Exception {
        System.setProperty("docs.oidc_username_claim", "nickname");
        System.setProperty("docs.oidc_email_claim", "mail");
        Assertions.assertEquals("nickname", invokeStatic("getUsernameClaim"));
        Assertions.assertEquals("mail", invokeStatic("getEmailClaim"));
    }

    // --- UserInfo fallback via ephemeral HTTP server --------------------------------------

    @Test
    public void userInfoFetchSendsBearerGetAndParsesMatchingSub() throws Exception {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> auth = new AtomicReference<>();
        HttpServer server = startUserInfoServer(exchange -> {
            method.set(exchange.getRequestMethod());
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            return "{\"sub\":\"sub-abc\",\"preferred_username\":\"alice\",\"email\":\"alice@example.com\"}";
        });
        try {
            System.setProperty("docs.oidc_userinfo_endpoint",
                    "http://localhost:" + server.getAddress().getPort() + "/userinfo");
            JsonObject result = invokeFetchUserInfo("access-tok-123", "sub-abc");
            Assertions.assertNotNull(result, "matching-sub UserInfo must be returned");
            Assertions.assertEquals("GET", method.get(), "UserInfo must be fetched with GET");
            Assertions.assertEquals("Bearer access-tok-123", auth.get(),
                    "UserInfo must carry the access token as a Bearer credential");
            Assertions.assertEquals("alice", result.getString("preferred_username"));
            Assertions.assertEquals("alice@example.com", result.getString("email"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void userInfoFetchFailsClosedOnSubMismatch() throws Exception {
        HttpServer server = startUserInfoServer(exchange ->
                "{\"sub\":\"ATTACKER-sub\",\"email\":\"victim@example.com\"}");
        try {
            System.setProperty("docs.oidc_userinfo_endpoint",
                    "http://localhost:" + server.getAddress().getPort() + "/userinfo");
            Assertions.assertThrows(java.lang.reflect.InvocationTargetException.class,
                    () -> invokeFetchUserInfo("access-tok-123", "expected-sub"),
                    "a mismatching UserInfo sub must REJECT the login (throw), not be silently discarded");
        } finally {
            server.stop(0);
        }
    }

    /**
     * With a known endpoint but no access token, the required fetch cannot be attempted —
     * the login must be rejected (fail closed), not proceed on a WARN.
     */
    @Test
    public void userInfoFetchRejectsWithoutAccessTokenWhenEndpointKnown() throws Exception {
        System.setProperty("docs.oidc_userinfo_endpoint", "http://127.0.0.1:1/userinfo");
        Assertions.assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> invokeFetchUserInfo(null, "sub-abc"),
                "endpoint known but no access_token -> reject");
    }

    /**
     * When NO UserInfo endpoint is known (no property, discovery carries no
     * userinfo_endpoint), the WARN fallback proceeds: the fetch returns null.
     */
    @Test
    public void userInfoFetchFallsBackWhenNoEndpointKnown() throws Exception {
        System.clearProperty("docs.oidc_userinfo_endpoint");
        JsonObject discovery = jakarta.json.Json.createObjectBuilder()
                .add("issuer", "https://iss.example")
                .build();
        JsonObject result = invokeFetchUserInfoWithDiscovery(null, "sub-abc", discovery);
        Assertions.assertNull(result, "no endpoint known -> WARN fallback (null), login proceeds");
    }

    /** A UserInfo response with a non-JSON content type must be rejected, not parsed. */
    @Test
    public void userInfoFetchRejectsNonJsonContentType() throws Exception {
        HttpServer server = startUserInfoServer(exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/html");
            return "<html>session expired</html>";
        });
        try {
            System.setProperty("docs.oidc_userinfo_endpoint",
                    "http://localhost:" + server.getAddress().getPort() + "/userinfo");
            Assertions.assertThrows(java.lang.reflect.InvocationTargetException.class,
                    () -> invokeFetchUserInfo("access-tok-123", "sub-abc"),
                    "non-JSON UserInfo response must be rejected");
        } finally {
            server.stop(0);
        }
    }

    /** A UserInfo response exceeding the size cap must be rejected before parsing. */
    @Test
    public void userInfoFetchRejectsOversizedResponse() throws Exception {
        String huge = "{\"sub\":\"sub-abc\",\"pad\":\"" + "a".repeat(70 * 1024) + "\"}";
        HttpServer server = startUserInfoServer(exchange -> huge);
        try {
            System.setProperty("docs.oidc_userinfo_endpoint",
                    "http://localhost:" + server.getAddress().getPort() + "/userinfo");
            Assertions.assertThrows(java.lang.reflect.InvocationTargetException.class,
                    () -> invokeFetchUserInfo("access-tok-123", "sub-abc"),
                    "oversized UserInfo response must be rejected");
        } finally {
            server.stop(0);
        }
    }

    /**
     * The size cap is measured in RAW BYTES, not decoded characters. A body whose CHARACTER
     * count is under the 64 KiB cap but whose UTF-8 BYTE length exceeds it must still be
     * rejected. Padded with a 3-byte character (U+20AC EURO SIGN): ~32 K chars is well under
     * the 64 Ki-char boundary but ~96 KiB, over the byte cap. RED against the previous
     * {@code body.length()} char-count comparison, which would have let this multibyte body
     * through.
     */
    @Test
    public void userInfoFetchRejectsMultibyteBodyOverByteCapUnderCharCount() throws Exception {
        String pad = "€".repeat(32 * 1024); // ~32K chars, ~96 KiB in UTF-8
        String body = "{\"sub\":\"sub-abc\",\"pad\":\"" + pad + "\"}";
        Assertions.assertTrue(body.length() < 64 * 1024,
                "precondition: char count must be under the cap so char-count logic would pass");
        Assertions.assertTrue(body.getBytes(StandardCharsets.UTF_8).length > 64 * 1024,
                "precondition: UTF-8 byte length must exceed the cap");
        HttpServer server = startUserInfoServer(exchange -> body);
        try {
            System.setProperty("docs.oidc_userinfo_endpoint",
                    "http://localhost:" + server.getAddress().getPort() + "/userinfo");
            Assertions.assertThrows(java.lang.reflect.InvocationTargetException.class,
                    () -> invokeFetchUserInfo("access-tok-123", "sub-abc"),
                    "a body over the BYTE cap must be rejected even when its char count is under it");
        } finally {
            server.stop(0);
        }
    }

    /**
     * A structured {@code +json} suffix media type (RFC 6839), e.g. {@code application/hal+json},
     * is valid JSON and must be accepted — not rejected like a non-JSON type. RED against the
     * previous {@code contains("application/json")} check, which matched only the exact base type.
     */
    @Test
    public void userInfoFetchAcceptsHalJsonSuffixContentType() throws Exception {
        HttpServer server = startUserInfoServer(exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/hal+json; charset=utf-8");
            return "{\"sub\":\"sub-abc\",\"email\":\"alice@example.com\"}";
        });
        try {
            System.setProperty("docs.oidc_userinfo_endpoint",
                    "http://localhost:" + server.getAddress().getPort() + "/userinfo");
            JsonObject result = invokeFetchUserInfo("access-tok-123", "sub-abc");
            Assertions.assertNotNull(result, "an application/hal+json UserInfo response must be accepted");
            Assertions.assertEquals("alice@example.com", result.getString("email"));
        } finally {
            server.stop(0);
        }
    }

    // --- reflection helpers ----------------------------------------------------------------

    private static String invokeSanitize(String raw) throws Exception {
        Method m = OidcResource.class.getDeclaredMethod("sanitizeUsernameStem", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, raw);
    }

    private static String invokeDerive(String stem, String issuer, String subject, int hashLen) throws Exception {
        Method m = OidcResource.class.getDeclaredMethod(
                "deriveUsername", String.class, String.class, String.class, int.class);
        m.setAccessible(true);
        return (String) m.invoke(null, stem, issuer, subject, hashLen);
    }

    private static String invokeSanitizeVerbatim(String raw) throws Exception {
        Method m = OidcResource.class.getDeclaredMethod("sanitizeUsernameVerbatim", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, raw);
    }

    /**
     * The claim getters are request-scoped: they read from an {@link OidcResource.OidcConfigSnapshot}.
     * Build the snapshot from the CURRENT effective config (the test sets the relevant properties),
     * then invoke the getter with it.
     */
    private static String invokeStatic(String name) throws Exception {
        Method m = OidcResource.class.getDeclaredMethod(name, OidcResource.OidcConfigSnapshot.class);
        m.setAccessible(true);
        return (String) m.invoke(null, OidcResource.snapshot());
    }

    private static JsonObject invokeFetchUserInfo(String accessToken, String expectedSub) throws Exception {
        return invokeFetchUserInfoWithDiscovery(accessToken, expectedSub, null);
    }

    private static JsonObject invokeFetchUserInfoWithDiscovery(String accessToken, String expectedSub,
                                                               JsonObject discovery) throws Exception {
        // fetchUserInfo is an instance method taking the request snapshot; give the discovery-
        // derived path a config. The discovery document (when supplied) is seeded via the
        // value-keyed cache test seam, keyed off the effective issuer set here.
        System.setProperty("docs.oidc_issuer", "https://iss.example");
        OidcResource.setDiscoveryCacheForTest(discovery);
        try {
            OidcResource resource = new OidcResource();
            Method m = OidcResource.class.getDeclaredMethod("fetchUserInfo",
                    OidcResource.OidcConfigSnapshot.class, String.class, String.class);
            m.setAccessible(true);
            return (JsonObject) m.invoke(resource, OidcResource.snapshot(), accessToken, expectedSub);
        } finally {
            OidcResource.setDiscoveryCacheForTest(null);
        }
    }

    private interface Responder {
        String respond(HttpExchange exchange) throws Exception;
    }

    private static HttpServer startUserInfoServer(Responder responder) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/userinfo", exchange -> {
            String body;
            try (InputStream ignored = exchange.getRequestBody()) {
                body = responder.respond(exchange);
            } catch (Exception e) {
                body = "{}";
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            // Default to JSON, but honor a content type the responder set explicitly.
            if (!exchange.getResponseHeaders().containsKey("Content-Type")) {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
            }
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        return server;
    }
}
