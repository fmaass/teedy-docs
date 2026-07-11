package com.sismics.docs.rest;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.sismics.docs.core.dao.OidcStateDao;
import com.sismics.docs.core.dao.UserDao;
import com.sismics.docs.core.model.jpa.OidcState;
import com.sismics.docs.core.model.jpa.User;
import com.sismics.util.context.ThreadLocalContext;
import com.sismics.util.jpa.EMF;
import com.sun.net.httpserver.HttpServer;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityTransaction;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * End-to-end OIDC callback coverage (#21) against a mock IdP (ephemeral
 * {@link HttpServer} serving token / JWKS / UserInfo). Exercises the REAL callback code path
 * — token exchange, JWKS verification, nonce check — around the new behaviors:
 *
 * <ul>
 *   <li>a token with no {@code sub} is rejected without provisioning a user (fail closed);</li>
 *   <li>configured claims are honored, and a claim absent from the ID token is fetched from
 *       the UserInfo endpoint (Authelia &gt;=4.38 minimal-ID-token case);</li>
 *   <li>a repeat login with the same issuer+subject creates no second user.</li>
 * </ul>
 */
public class TestOidcCallbackFlow extends BaseJerseyTest {

    private HttpServer idp;
    private String idpBase;
    private RSAPublicKey publicKey;
    private RSAPrivateKey privateKey;
    private final AtomicReference<String> userInfoBody = new AtomicReference<>();
    private final AtomicReference<Boolean> userInfoCalled = new AtomicReference<>(false);
    private final AtomicReference<Boolean> tokenCalled = new AtomicReference<>(false);
    private Client noRedirectClient;
    private WebTarget noRedirectTarget;

    @Override
    @BeforeEach
    public void setUp() throws Exception {
        // Generate the IdP signing key and stand up token/JWKS/UserInfo endpoints.
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();
        publicKey = (RSAPublicKey) kp.getPublic();
        privateKey = (RSAPrivateKey) kp.getPrivate();

        idp = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        idp.createContext("/jwks", exchange -> respondJson(exchange, jwksJson()));
        idp.createContext("/userinfo", exchange -> {
            userInfoCalled.set(true);
            String body = userInfoBody.get() != null ? userInfoBody.get() : "{}";
            respondJson(exchange, body);
        });
        idp.start();
        idpBase = "http://127.0.0.1:" + idp.getAddress().getPort();

        // OIDC config: issuer is arbitrary; endpoints are overridden to the mock IdP.
        System.setProperty("docs.oidc_enabled", "true");
        System.setProperty("docs.oidc_issuer", "https://iss.mock.example");
        System.setProperty("docs.oidc_client_id", "test-client");
        System.setProperty("docs.oidc_client_secret", "secret");
        System.setProperty("docs.oidc_redirect_uri", "https://app.example.com/api/oidc/callback");
        System.setProperty("docs.oidc_jwks_uri", idpBase + "/jwks");
        System.setProperty("docs.oidc_userinfo_endpoint", idpBase + "/userinfo");
        resetOidcCaches();

        super.setUp();
        noRedirectClient = ClientBuilder.newBuilder()
                .property("jersey.config.client.followRedirects", false).build();
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
        if (idp != null) {
            idp.stop(0);
        }
        for (String p : new String[]{"docs.oidc_enabled", "docs.oidc_issuer", "docs.oidc_client_id",
                "docs.oidc_client_secret", "docs.oidc_redirect_uri", "docs.oidc_jwks_uri",
                "docs.oidc_userinfo_endpoint", "docs.oidc_token_endpoint", "docs.oidc_username_claim",
                "docs.oidc_email_claim"}) {
            System.clearProperty(p);
        }
        resetOidcCaches();
    }

    /**
     * A token carrying no {@code sub} must be rejected (redirect to the login error page) and
     * MUST NOT provision any user.
     */
    @Test
    public void callbackWithoutSubIsRejectedAndProvisionsNothing() throws Exception {
        long before = countUsers();
        String nonce = UUID.randomUUID().toString();
        String state = seedState(nonce);
        String idToken = JWT.create()
                .withIssuer("https://iss.mock.example")
                .withAudience("test-client")
                .withClaim("nonce", nonce)
                .withClaim("preferred_username", "no-sub-user")
                .withClaim("email", "nosub@example.com")
                .withExpiresAt(new Date(System.currentTimeMillis() + 300_000))
                // deliberately NO subject
                .sign(rsa());
        startTokenEndpoint(idToken, "access-token");

        Response resp = doCallback("code-1", state);
        Assertions.assertEquals(Response.Status.TEMPORARY_REDIRECT.getStatusCode(), resp.getStatus());
        Assertions.assertTrue(resp.getHeaderString("Location").endsWith("/#/login?error=oidc"),
                "a token with no sub must redirect to the error page");
        Assertions.assertEquals(before, countUsers(), "no user may be provisioned for a sub-less token");
    }

    /**
     * A configured email claim absent from the ID token is fetched from the UserInfo
     * endpoint (sub-verified), and the user is provisioned with that email.
     */
    @Test
    public void callbackFetchesEmailFromUserInfoWhenMissingFromIdToken() throws Exception {
        String subject = "sub-" + UUID.randomUUID();
        String nonce = UUID.randomUUID().toString();
        String state = seedState(nonce);
        // Minimal ID token: sub + preferred_username, but NO email.
        String idToken = JWT.create()
                .withIssuer("https://iss.mock.example")
                .withAudience("test-client")
                .withSubject(subject)
                .withClaim("nonce", nonce)
                .withClaim("preferred_username", "grace")
                .withExpiresAt(new Date(System.currentTimeMillis() + 300_000))
                .sign(rsa());
        startTokenEndpoint(idToken, "access-token-xyz");
        userInfoBody.set("{\"sub\":\"" + subject + "\",\"email\":\"grace@userinfo.example\"}");

        Response resp = doCallback("code-2", state);
        Assertions.assertEquals(Response.Status.TEMPORARY_REDIRECT.getStatusCode(), resp.getStatus());
        Assertions.assertFalse(resp.getHeaderString("Location").endsWith("/#/login?error=oidc"),
                "login should succeed: " + resp.getHeaderString("Location"));
        Assertions.assertTrue(userInfoCalled.get(), "UserInfo must be consulted for the missing email");
        Assertions.assertTrue(resp.getCookies().containsKey("auth_token"),
                "a successful login must set the auth_token session cookie");

        User u = lookupBySub("https://iss.mock.example", subject);
        Assertions.assertNotNull(u, "user must be provisioned");
        Assertions.assertEquals("grace@userinfo.example", u.getEmail(),
                "email must come from the sub-verified UserInfo response");
        deleteUser(u.getUsername());
    }

    /**
     * Fail-closed contract: a UserInfo fetch that was ATTEMPTED and returned a mismatching
     * {@code sub} must REJECT the login at the callback level — error redirect, no session
     * cookie, nothing provisioned. (Converting the failed fetch to null and proceeding to a
     * session is the vulnerability under test.)
     */
    @Test
    public void callbackRejectsLoginOnUserInfoSubMismatch() throws Exception {
        String subject = "sub-" + UUID.randomUUID();
        String nonce = UUID.randomUUID().toString();
        String state = seedState(nonce);
        // Minimal ID token: sub + preferred_username, NO email -> forces the UserInfo fetch.
        String idToken = JWT.create()
                .withIssuer("https://iss.mock.example")
                .withAudience("test-client")
                .withSubject(subject)
                .withClaim("nonce", nonce)
                .withClaim("preferred_username", "mallorytarget")
                .withExpiresAt(new Date(System.currentTimeMillis() + 300_000))
                .sign(rsa());
        startTokenEndpoint(idToken, "access-token-mm");
        userInfoBody.set("{\"sub\":\"SOMEONE-ELSE\",\"email\":\"attacker@evil.example\"}");

        Response resp = doCallback("code-mm", state);
        Assertions.assertEquals(Response.Status.TEMPORARY_REDIRECT.getStatusCode(), resp.getStatus());
        Assertions.assertTrue(resp.getHeaderString("Location").endsWith("/#/login?error=oidc"),
                "a mismatched UserInfo sub must reject the login, got: " + resp.getHeaderString("Location"));
        Assertions.assertFalse(resp.getCookies().containsKey("auth_token"),
                "no session cookie may be set on a rejected login");
        Assertions.assertEquals(0, countBySub("https://iss.mock.example", subject),
                "nothing may be provisioned when the UserInfo fetch fails closed");
    }

    /**
     * A persistence failure while refreshing the email on repeat login must fail the
     * callback (error redirect), not be swallowed into a session with a silently-stale
     * profile. Vector: an email claim exceeding the USE_EMAIL_C varchar(100) column.
     */
    @Test
    public void callbackErrorsWhenEmailUpdateFails() throws Exception {
        String subject = "sub-" + UUID.randomUUID();

        // First login provisions normally.
        String nonce1 = UUID.randomUUID().toString();
        String state1 = seedState(nonce1);
        startTokenEndpoint(signIdToken(subject, nonce1, "ivan", "ivan@example.com"), "at-e1");
        Response r1 = doCallback("code-e1", state1);
        Assertions.assertEquals(Response.Status.TEMPORARY_REDIRECT.getStatusCode(), r1.getStatus());
        User first = lookupBySub("https://iss.mock.example", subject);
        Assertions.assertNotNull(first);

        // Repeat login carries an email claim that cannot be persisted (>100 chars).
        String oversized = "x".repeat(120) + "@example.com";
        String nonce2 = UUID.randomUUID().toString();
        String state2 = seedState(nonce2);
        startTokenEndpoint(signIdToken(subject, nonce2, "ivan", oversized), "at-e2");
        Response r2 = doCallback("code-e2", state2);
        Assertions.assertEquals(Response.Status.TEMPORARY_REDIRECT.getStatusCode(), r2.getStatus());
        Assertions.assertTrue(r2.getHeaderString("Location").endsWith("/#/login?error=oidc"),
                "a failed email update must fail the login, got: " + r2.getHeaderString("Location"));
        Assertions.assertFalse(r2.getCookies().containsKey("auth_token"),
                "no session cookie may be set when the email update failed");
        Assertions.assertEquals("ivan@example.com",
                lookupBySub("https://iss.mock.example", subject).getEmail(),
                "the stored email must be unchanged after the failed update");
        deleteUser(first.getUsername());
    }

    /**
     * A second login with the same issuer+subject must not create a second user.
     */
    @Test
    public void repeatLoginCreatesNoSecondUser() throws Exception {
        String subject = "sub-" + UUID.randomUUID();

        String nonce1 = UUID.randomUUID().toString();
        String state1 = seedState(nonce1);
        startTokenEndpoint(signIdToken(subject, nonce1, "henry", "henry@example.com"), "at-1");
        Response r1 = doCallback("code-a", state1);
        Assertions.assertEquals(Response.Status.TEMPORARY_REDIRECT.getStatusCode(), r1.getStatus());
        User first = lookupBySub("https://iss.mock.example", subject);
        Assertions.assertNotNull(first);

        String nonce2 = UUID.randomUUID().toString();
        String state2 = seedState(nonce2);
        startTokenEndpoint(signIdToken(subject, nonce2, "henry", "henry@example.com"), "at-2");
        Response r2 = doCallback("code-b", state2);
        Assertions.assertEquals(Response.Status.TEMPORARY_REDIRECT.getStatusCode(), r2.getStatus());

        Assertions.assertEquals(1, countBySub("https://iss.mock.example", subject),
                "repeat login with same issuer+subject must not create a second user");
        deleteUser(first.getUsername());
    }

    /**
     * A disabled identity's login attempt must be rejected WITHOUT mutating stored profile
     * data: the eligibility check must run before the repeat-login email refresh. (The error
     * redirect is a 3xx, so the request transaction COMMITS — an email update executed
     * before the rejection would silently persist.)
     */
    @Test
    public void disabledAccountLoginRejectedWithoutEmailMutation() throws Exception {
        String subject = "sub-" + UUID.randomUUID();

        // Provision normally, then disable the account.
        String nonce1 = UUID.randomUUID().toString();
        String state1 = seedState(nonce1);
        startTokenEndpoint(signIdToken(subject, nonce1, "karla", "karla@example.com"), "at-d1");
        Response r1 = doCallback("code-d1", state1);
        Assertions.assertEquals(Response.Status.TEMPORARY_REDIRECT.getStatusCode(), r1.getStatus());
        User user = lookupBySub("https://iss.mock.example", subject);
        Assertions.assertNotNull(user);
        disableUser(user.getUsername());

        // Repeat login with a CHANGED email claim: rejected, and the change must NOT stick.
        String nonce2 = UUID.randomUUID().toString();
        String state2 = seedState(nonce2);
        startTokenEndpoint(signIdToken(subject, nonce2, "karla", "karla-new@example.com"), "at-d2");
        Response r2 = doCallback("code-d2", state2);
        Assertions.assertEquals(Response.Status.TEMPORARY_REDIRECT.getStatusCode(), r2.getStatus());
        Assertions.assertTrue(r2.getHeaderString("Location").endsWith("/#/login?error=oidc"),
                "a disabled account must be rejected, got: " + r2.getHeaderString("Location"));
        Assertions.assertFalse(r2.getCookies().containsKey("auth_token"),
                "no session cookie may be set for a disabled account");
        Assertions.assertEquals("karla@example.com",
                lookupBySub("https://iss.mock.example", subject).getEmail(),
                "a disabled identity's login attempt must not mutate the stored email");
        deleteUser(user.getUsername());
    }

    /**
     * Provider-binding guard (#44, same class as the logout binding): an admin
     * may switch the OIDC provider LIVE between the login request (which mints the state, PKCE, and
     * nonce under provider A) and the callback (a FRESH snapshot). If the provider changed, provider
     * A's authorization CODE must NOT be exchanged with provider B's token endpoint. The state pins
     * the login-time provider fingerprint (issuer + client_id); the callback rejects (fail closed,
     * error redirect) when the CURRENT provider no longer matches — BEFORE any token exchange.
     */
    @Test
    public void callbackRejectsWhenProviderChangedSinceLogin() throws Exception {
        String subject = "sub-" + UUID.randomUUID();
        String nonce = UUID.randomUUID().toString();
        // State pinned to a DIFFERENT provider (A) than the current config (B = iss.mock.example /
        // test-client): this models an admin switching the provider after login() ran.
        String state = seedStateWithFingerprint(nonce,
                "https://provider-a.example", "client-a");

        // A well-formed, correctly-signed token for the CURRENT provider would otherwise succeed —
        // proving the rejection is due to the provider-binding check, not a malformed token.
        startTokenEndpoint(signIdToken(subject, nonce, "eve", "eve@example.com"), "at-x");

        Response resp = doCallback("code-switch", state);
        Assertions.assertEquals(Response.Status.TEMPORARY_REDIRECT.getStatusCode(), resp.getStatus());
        Assertions.assertTrue(resp.getHeaderString("Location").endsWith("/#/login?error=oidc"),
                "a provider switch between login and callback must reject, got: "
                        + resp.getHeaderString("Location"));
        Assertions.assertFalse(resp.getCookies().containsKey("auth_token"),
                "no session cookie may be set when the provider changed since login");
        Assertions.assertFalse(tokenCalled.get(),
                "the authorization code must NOT be exchanged with the switched-to provider");
        Assertions.assertEquals(0, countBySub("https://iss.mock.example", subject),
                "nothing may be provisioned when the provider changed since login");
    }

    /**
     * Fail-closed on ambiguity (cross-model re-review): a state with NO pinned provider fingerprint
     * (a legacy state created before {@code dbupdate-046}) must be REJECTED, not accepted. Treating
     * null as a match would be fail-open — a pre-migration state plus a live A→B provider switch
     * within the state TTL would exchange provider A's code with provider B. After the deploy every
     * login writes a fingerprint, so the only cost is a one-time retry for a login in-flight across
     * the deploy moment.
     */
    @Test
    public void callbackRejectsStateWithoutProviderFingerprint() throws Exception {
        String subject = "sub-" + UUID.randomUUID();
        String nonce = UUID.randomUUID().toString();
        // A legacy state: no issuer/client_id pinned. The current config is a valid single provider.
        String state = seedStateWithoutFingerprint(nonce);
        startTokenEndpoint(signIdToken(subject, nonce, "leo", "leo@example.com"), "at-legacy");

        Response resp = doCallback("code-legacy", state);
        Assertions.assertEquals(Response.Status.TEMPORARY_REDIRECT.getStatusCode(), resp.getStatus());
        Assertions.assertTrue(resp.getHeaderString("Location").endsWith("/#/login?error=oidc"),
                "an unpinned (pre-migration) state must be rejected fail-closed, got: "
                        + resp.getHeaderString("Location"));
        Assertions.assertFalse(resp.getCookies().containsKey("auth_token"),
                "no session cookie may be set for an unpinned state");
        Assertions.assertFalse(tokenCalled.get(),
                "an unpinned state must be rejected BEFORE the token exchange");
        Assertions.assertEquals(0, countBySub("https://iss.mock.example", subject),
                "nothing may be provisioned for an unpinned state");
    }

    /**
     * Happy path THROUGH the provider-binding guard: a state carrying a NON-null fingerprint that
     * MATCHES the current effective provider (issuer + client_id) must let the callback proceed
     * exactly as before — code exchanged, session cookie minted, user provisioned. This proves
     * {@link OidcResource#providerFingerprintMatches} returns true on a real matching fingerprint
     * (not just the null-legacy backward-compat branch the other tests exercise).
     */
    @Test
    public void callbackSucceedsWhenPinnedFingerprintMatchesCurrentProvider() throws Exception {
        String subject = "sub-" + UUID.randomUUID();
        String nonce = UUID.randomUUID().toString();
        // Fingerprint pinned to the CURRENT config (set in setUp): issuer + client_id both match.
        String state = seedStateWithFingerprint(nonce,
                "https://iss.mock.example", "test-client");
        startTokenEndpoint(signIdToken(subject, nonce, "frank", "frank@example.com"), "at-fp");

        Response resp = doCallback("code-fp", state);
        Assertions.assertEquals(Response.Status.TEMPORARY_REDIRECT.getStatusCode(), resp.getStatus());
        Assertions.assertFalse(resp.getHeaderString("Location").endsWith("/#/login?error=oidc"),
                "a matching pinned fingerprint must let the login succeed, got: "
                        + resp.getHeaderString("Location"));
        Assertions.assertTrue(tokenCalled.get(),
                "a matching fingerprint must proceed to the token exchange");
        Assertions.assertTrue(resp.getCookies().containsKey("auth_token"),
                "a matching fingerprint must mint the session cookie");

        User u = lookupBySub("https://iss.mock.example", subject);
        Assertions.assertNotNull(u, "a matching fingerprint must provision the user");
        deleteUser(u.getUsername());
    }

    // --- mock IdP plumbing -----------------------------------------------------------------

    private Algorithm rsa() {
        return Algorithm.RSA256(publicKey, privateKey);
    }

    private String signIdToken(String subject, String nonce, String username, String email) {
        return JWT.create()
                .withIssuer("https://iss.mock.example")
                .withAudience("test-client")
                .withSubject(subject)
                .withClaim("nonce", nonce)
                .withClaim("preferred_username", username)
                .withClaim("email", email)
                .withExpiresAt(new Date(System.currentTimeMillis() + 300_000))
                .sign(rsa());
    }

    private void startTokenEndpoint(String idToken, String accessToken) {
        String body = "{\"id_token\":\"" + idToken + "\",\"access_token\":\"" + accessToken
                + "\",\"token_type\":\"Bearer\"}";
        // Register a fresh /token context each call (remove any prior one first).
        try {
            idp.removeContext("/token");
        } catch (IllegalArgumentException ignored) {
            // no prior context
        }
        idp.createContext("/token", exchange -> {
            tokenCalled.set(true);
            respondJson(exchange, body);
        });
        System.setProperty("docs.oidc_token_endpoint", idpBase + "/token");
    }

    private static void respondJson(com.sun.net.httpserver.HttpExchange exchange, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private String jwksJson() {
        String n = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(toUnsignedBytes(publicKey.getModulus()));
        String e = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(toUnsignedBytes(publicKey.getPublicExponent()));
        return "{\"keys\":[{\"kty\":\"RSA\",\"use\":\"sig\",\"alg\":\"RS256\",\"n\":\""
                + n + "\",\"e\":\"" + e + "\"}]}";
    }

    private static byte[] toUnsignedBytes(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            byte[] trimmed = new byte[bytes.length - 1];
            System.arraycopy(bytes, 1, trimmed, 0, trimmed.length);
            return trimmed;
        }
        return bytes;
    }

    private Response doCallback(String code, String state) {
        return noRedirectTarget.path("/oidc/callback")
                .queryParam("code", code)
                .queryParam("state", state)
                .request().get();
    }

    // --- DB helpers (own transaction; independent of the callback's request tx) ------------

    /**
     * Seeds a state pinning the CURRENT config's provider fingerprint (issuer + client_id) so the
     * callback's fail-closed provider-binding check passes and the flow reaches the code path each
     * test exercises. Every test in this class runs under the single provider configured in setUp
     * ({@code https://iss.mock.example} / {@code test-client}); a real login() writes exactly this
     * fingerprint, so seeding it here mirrors a genuine same-provider login→callback.
     */
    private String seedState(String nonce) {
        return seedStateWithFingerprint(nonce, "https://iss.mock.example", "test-client");
    }

    /** Seeds a state with NO provider fingerprint (a legacy pre-migration state). */
    private String seedStateWithoutFingerprint(String nonce) {
        return inTx(() -> {
            String id = UUID.randomUUID().toString();
            OidcState st = new OidcState().setId(id).setNonce(nonce).setCodeVerifier("verifier").setReturnUrl(null);
            new OidcStateDao().create(st);
            return id;
        });
    }

    /** Seeds a state pinning a specific login-time provider fingerprint (issuer + client_id). */
    private String seedStateWithFingerprint(String nonce, String issuer, String clientId) {
        return inTx(() -> {
            String id = UUID.randomUUID().toString();
            OidcState st = new OidcState().setId(id).setNonce(nonce).setCodeVerifier("verifier")
                    .setReturnUrl(null).setIssuer(issuer).setClientId(clientId);
            new OidcStateDao().create(st);
            return id;
        });
    }

    private User lookupBySub(String issuer, String subject) {
        return inTx(() -> new UserDao().getByOidcSubject(issuer, subject));
    }

    private long countUsers() {
        return inTx(() -> {
            EntityManager em = ThreadLocalContext.get().getEntityManager();
            Object n = em.createNativeQuery("select count(*) from T_USER where USE_DELETEDATE_D is null").getSingleResult();
            return ((Number) n).longValue();
        });
    }

    private long countBySub(String issuer, String subject) {
        return inTx(() -> {
            EntityManager em = ThreadLocalContext.get().getEntityManager();
            Object n = em.createNativeQuery(
                            "select count(*) from T_USER where USE_OIDC_ISSUER_C = :i and USE_OIDC_SUBJECT_C = :s and USE_DELETEDATE_D is null")
                    .setParameter("i", issuer).setParameter("s", subject).getSingleResult();
            return ((Number) n).longValue();
        });
    }

    /** Administratively disables a user (sets USE_DISABLEDATE_D) on a committed transaction. */
    private void disableUser(String username) {
        inTx(() -> {
            ThreadLocalContext.get().getEntityManager()
                    .createNativeQuery("update T_USER set USE_DISABLEDATE_D = CURRENT_TIMESTAMP where USE_USERNAME_C = :u")
                    .setParameter("u", username).executeUpdate();
            return null;
        });
    }

    private void deleteUser(String username) {
        inTx(() -> {
            EntityManager em = ThreadLocalContext.get().getEntityManager();
            em.createNativeQuery(
                            "delete from T_AUTHENTICATION_TOKEN where AUT_IDUSER_C in "
                                    + "(select USE_ID_C from T_USER where USE_USERNAME_C = :u)")
                    .setParameter("u", username).executeUpdate();
            em.createNativeQuery("delete from T_USER where USE_USERNAME_C = :u")
                    .setParameter("u", username).executeUpdate();
            return null;
        });
    }

    private <T> T inTx(java.util.function.Supplier<T> work) {
        EntityManager prev = ThreadLocalContext.get().getEntityManager();
        EntityManager em = EMF.get().createEntityManager();
        EntityTransaction tx = em.getTransaction();
        try {
            ThreadLocalContext.get().setEntityManager(em);
            tx.begin();
            T result = work.get();
            tx.commit();
            return result;
        } finally {
            if (tx.isActive()) {
                tx.rollback();
            }
            em.close();
            ThreadLocalContext.get().setEntityManager(prev);
        }
    }

    private static void resetOidcCaches() throws Exception {
        java.lang.reflect.Method m = Class.forName("com.sismics.docs.rest.resource.OidcResource")
                .getDeclaredMethod("resetConfigCacheForTest");
        m.setAccessible(true);
        m.invoke(null);
    }
}
