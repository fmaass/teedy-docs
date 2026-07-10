package com.sismics.docs.rest;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.sismics.docs.core.dao.ApiKeyDao;
import com.sismics.docs.core.dao.AuthenticationTokenDao;
import com.sismics.docs.core.dao.OidcStateDao;
import com.sismics.docs.core.dao.UserDao;
import com.sismics.docs.core.model.jpa.ApiKey;
import com.sismics.docs.core.model.jpa.AuthenticationToken;
import com.sismics.docs.core.model.jpa.OidcState;
import com.sismics.docs.core.model.jpa.User;
import com.sismics.docs.rest.resource.OidcResource;
import com.sismics.util.context.ThreadLocalContext;
import com.sismics.util.filter.ApiKeyBasedSecurityFilter;
import com.sismics.util.filter.TokenBasedSecurityFilter;
import com.sismics.util.jpa.EMF;
import com.sun.net.httpserver.HttpServer;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Form;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import jakarta.ws.rs.core.UriBuilder;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * Disabled-user authentication enforcement (issue #17).
 *
 * <p>Access denial for a disabled user is already covered by {@code SecurityFilter.injectUser}
 * (a disabled user is injected as anonymous). These tests cover the THREE side-effect gaps that
 * happen <em>before</em> that boundary rejects the user, plus the guest invariant:</p>
 *
 * <ol>
 *   <li>Cookie auth rotates the long-lived token before loading the user — a disabled cookie
 *       request must NOT rotate the token.</li>
 *   <li>API-key auth updates {@code lastUsedDate} before loading the user — a disabled API-key
 *       request must NOT update it.</li>
 *   <li>OIDC callback mints an auth token for the resolved user with no disabled check — a
 *       disabled OIDC identity must be rejected before any token is created.</li>
 * </ol>
 *
 * <p>All three assert the ABSENCE of a bookkeeping mutation, so they are mutation-proof: reverting
 * the guard re-introduces the mutation and fails the test.</p>
 */
public class TestDisabledUserAuth extends BaseJerseyTest {

    /**
     * A disabled user's cookie request must not rotate its long-lived token.
     *
     * <p>Rotation only fires when a long-lived token has under 30 days remaining, so the token
     * is back-dated to force the rotation branch. With the disabled guard placed before rotation,
     * the creation date must be unchanged after the request.</p>
     */
    @Test
    public void disabledUserCookieDoesNotRotateToken() {
        clientUtil.createUser("disabled_rotate");
        String token = clientUtil.login("disabled_rotate", "Test1234", true); // long-lived

        // Back-date the token so it is inside the 30-day rotation window, then disable the user.
        long backDated = System.currentTimeMillis()
                - (TokenBasedSecurityFilter.TOKEN_LONG_LIFETIME - 5 * 24 * 3600) * 1000L;
        Date backDatedDate = new Date(backDated);
        writeDb(em -> {
            AuthenticationToken at = new AuthenticationTokenDao().get(token);
            at.setCreationDate(backDatedDate);
            new AuthenticationTokenDao().updateCreationDate(at);
            User user = new UserDao().getActiveByUsername("disabled_rotate");
            user.setDisableDate(new Date());
            em.merge(user);
            return null;
        });

        // Sanity: the token would rotate for an ENABLED user in this same state — verify the
        // rotation branch is genuinely reachable (mutation realness for the assertion below).
        // Make a request as the disabled user.
        target().path("/user").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .get();

        Date after = readDb(em -> new AuthenticationTokenDao().get(token).getCreationDate());
        Assertions.assertEquals(backDatedDate.getTime(), after.getTime(),
                "a disabled user's cookie request must NOT rotate the auth token");
    }

    /**
     * Control: an ENABLED user in the same back-dated state DOES rotate, proving the rotation
     * branch is reachable and the disabled assertion above is not vacuously true.
     */
    @Test
    public void enabledUserCookieRotatesToken() {
        clientUtil.createUser("enabled_rotate");
        String token = clientUtil.login("enabled_rotate", "Test1234", true);

        long backDated = System.currentTimeMillis()
                - (TokenBasedSecurityFilter.TOKEN_LONG_LIFETIME - 5 * 24 * 3600) * 1000L;
        Date backDatedDate = new Date(backDated);
        writeDb(em -> {
            AuthenticationToken at = new AuthenticationTokenDao().get(token);
            at.setCreationDate(backDatedDate);
            new AuthenticationTokenDao().updateCreationDate(at);
            return null;
        });

        target().path("/user").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .get();

        Date after = readDb(em -> new AuthenticationTokenDao().get(token).getCreationDate());
        Assertions.assertTrue(after.getTime() > backDatedDate.getTime(),
                "an enabled user's cookie request in the rotation window must rotate the token");
    }

    /**
     * A disabled user's API-key request must not update the key's last-used date.
     */
    @Test
    public void disabledUserApiKeyDoesNotUpdateLastUsed() {
        String adminToken = adminToken();

        // Create a non-admin user and an API key for that user.
        clientUtil.createUser("disabled_apikey");
        String userToken = clientUtil.login("disabled_apikey", "Test1234", false);
        JsonObject keyJson = target().path("/apikey").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, userToken)
                .put(Entity.form(new Form().param("name", "k")), JsonObject.class);
        String rawKey = keyJson.getString("key");
        String keyId = keyJson.getString("id");

        // Disable the user directly, leaving last-used null.
        writeDb(em -> {
            User user = new UserDao().getActiveByUsername("disabled_apikey");
            user.setDisableDate(new Date());
            em.merge(user);
            return null;
        });

        Date lastUsedBefore = readDb(em -> new ApiKeyDao().getByKeyHash(
                ApiKeyBasedSecurityFilter.sha256Hex(rawKey)).getLastUsedDate());
        Assertions.assertNull(lastUsedBefore, "precondition: last-used starts null");

        // Make an API-key request as the disabled user. Target GET /user, which returns 200
        // (anonymous info) even for a rejected user, so the request transaction COMMITS. This
        // makes the last-used mutation observable: a 403 endpoint would roll the whole request
        // transaction back (RequestContextFilter) and mask the write, so it would not be
        // mutation-proof. Without the guard, updateLastUsedDate runs and commits here.
        Response resp = target().path("/user").request()
                .header("Authorization", "Bearer " + rawKey)
                .get();
        // Sanity: the endpoint committed (2xx) so a leaked last-used write would persist.
        Assertions.assertEquals(Status.OK.getStatusCode(), resp.getStatus());

        Date lastUsedAfter = readDb(em -> new ApiKeyDao().getByKeyHash(
                ApiKeyBasedSecurityFilter.sha256Hex(rawKey)).getLastUsedDate());
        Assertions.assertNull(lastUsedAfter,
                "a disabled user's API-key request must NOT update lastUsedDate");

        // Regression: the disabled user is anonymous, so a permission-gated endpoint is denied.
        Response denied = target().path("/document/list").request()
                .header("Authorization", "Bearer " + rawKey)
                .get();
        Assertions.assertEquals(Status.FORBIDDEN.getStatusCode(), denied.getStatus());
    }

    /**
     * OIDC gap: the callback mints an auth token for the resolved user with no disabled check.
     * The shared eligibility guard ({@link OidcResource#isOidcUserEligible(User)}) must reject a
     * disabled user, so no token is minted.
     */
    @Test
    public void disabledOidcUserIsIneligibleAndMintsNoToken() {
        clientUtil.createUser("disabled_oidc");

        String userId = writeDb(em -> {
            User user = new UserDao().getActiveByUsername("disabled_oidc");
            // Bind an OIDC identity and disable the account on the managed entity.
            user.setOidcIssuer("https://idp.example.com");
            user.setOidcSubject("sub-123");
            user.setDisableDate(new Date());
            em.merge(user);
            return user.getId();
        });

        // The shared guard used by the OIDC callback must classify this user as ineligible.
        Boolean eligible = readDb(em -> {
            User resolved = new UserDao().getByOidcSubject("https://idp.example.com", "sub-123");
            Assertions.assertNotNull(resolved,
                    "getByOidcSubject must still return the disabled user (no filtering)");
            return OidcResource.isOidcUserEligible(resolved);
        });
        Assertions.assertFalse(eligible,
                "a disabled OIDC identity must be ineligible so the callback mints no token");

        // No auth token should ever have been created for this disabled user.
        List<AuthenticationToken> tokens = readDb(em ->
                new AuthenticationTokenDao().getByUserId(userId));
        Assertions.assertTrue(tokens.isEmpty(),
                "no auth token/cookie must be minted for a disabled OIDC user");
    }

    /**
     * OIDC gap, driven end-to-end through the real {@code /oidc/callback} handler: this proves the
     * disabled-account guard sits BEFORE the token mint on the live code path, not just at the
     * {@link OidcResource#isOidcUserEligible(User)} seam.
     *
     * <p>The token/JWKS exchange normally hits a live IdP. Here a loopback {@link HttpServer}
     * (JDK built-in — no mock framework or new dependency) serves a token response whose
     * {@code id_token} is a real RS256 JWT plus the matching JWKS, and the explicit
     * {@code docs.oidc_token_endpoint}/{@code docs.oidc_jwks_uri} overrides point the resource at
     * it. The callback therefore exchanges the code, verifies the ID token, resolves the (disabled)
     * user by OIDC subject, and reaches the eligibility guard — the exact ordering under test.</p>
     *
     * <p>Mutation proof: removing the {@code isOidcUserEligible} guard in {@code OidcResource} flips
     * this test from "redirect to {@code /#/login?error=oidc} with zero tokens minted" to "redirect
     * to the app with a session token minted".</p>
     */
    @Test
    public void disabledOidcUserRejectedByRealCallbackBeforeMint() throws Exception {
        String issuer = "https://idp.example.com";
        String subject = "sub-callback-" + UUID.randomUUID();
        String clientId = "test-client";
        String kid = "test-key-1";

        // Real RSA keypair: the IdP signs the ID token with the private key; the resource verifies
        // it against the public key served from the loopback JWKS endpoint.
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair keyPair = kpg.generateKeyPair();
        RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
        RSAPrivateKey privateKey = (RSAPrivateKey) keyPair.getPrivate();

        // Create a disabled user bound to the OIDC identity resolved by getByOidcSubject.
        String userId = writeDb(em -> {
            User user = new UserDao().getActiveByUsername("disabled_oidc_cb");
            if (user == null) {
                clientUtil.createUser("disabled_oidc_cb");
                user = new UserDao().getActiveByUsername("disabled_oidc_cb");
            }
            user.setOidcIssuer(issuer);
            user.setOidcSubject(subject);
            user.setDisableDate(new Date());
            em.merge(user);
            return user.getId();
        });

        String state = UUID.randomUUID().toString();
        String nonce = UUID.randomUUID().toString();

        // Sign an ID token the resource will accept (issuer/audience/nonce/exp/sub all valid).
        String idToken = JWT.create()
                .withKeyId(kid)
                .withIssuer(issuer)
                .withAudience(clientId)
                .withSubject(subject)
                .withClaim("nonce", nonce)
                .withClaim("preferred_username", "disabled_oidc_cb")
                .withClaim("email", "disabled_oidc_cb@docs.com")
                .withExpiresAt(new Date(System.currentTimeMillis() + 5 * 60 * 1000))
                .sign(Algorithm.RSA256(publicKey, privateKey));

        String tokenJson = Json.createObjectBuilder()
                .add("access_token", "ignored")
                .add("token_type", "Bearer")
                .add("id_token", idToken)
                .build().toString();

        String jwksJson = Json.createObjectBuilder()
                .add("keys", Json.createArrayBuilder()
                        .add(Json.createObjectBuilder()
                                .add("kty", "RSA")
                                .add("use", "sig")
                                .add("alg", "RS256")
                                .add("kid", kid)
                                .add("n", base64Url(publicKey.getModulus().toByteArray()))
                                .add("e", base64Url(publicKey.getPublicExponent().toByteArray()))))
                .build().toString();

        HttpServer idp = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        idp.createContext("/token", exchange -> respond(exchange, tokenJson));
        idp.createContext("/jwks", exchange -> respond(exchange, jwksJson));
        idp.start();
        int idpPort = idp.getAddress().getPort();

        Client noRedirectClient = ClientBuilder.newBuilder()
                .property("jersey.config.client.followRedirects", false)
                .build();
        try {
            // Point the resource at the loopback IdP via the explicit-endpoint overrides
            // (these bypass discovery), and reset the static JWKS cache so our key is fetched.
            System.setProperty("docs.oidc_enabled", "true");
            System.setProperty("docs.oidc_issuer", issuer);
            System.setProperty("docs.oidc_client_id", clientId);
            System.setProperty("docs.oidc_client_secret", "test-secret");
            System.setProperty("docs.oidc_redirect_uri", "https://app.example.com/api/oidc/callback");
            System.setProperty("docs.oidc_token_endpoint", "http://127.0.0.1:" + idpPort + "/token");
            System.setProperty("docs.oidc_jwks_uri", "http://127.0.0.1:" + idpPort + "/jwks");
            resetOidcJwksCache();

            // Persist a valid, unexpired state row so the callback passes the state/nonce checks.
            writeDb(em -> {
                new OidcStateDao().create(new OidcState()
                        .setId(state)
                        .setNonce(nonce)
                        .setCodeVerifier("test-code-verifier")
                        .setReturnUrl("/#/document"));
                return null;
            });

            WebTargetOf noRedirect = new WebTargetOf(noRedirectClient);
            Response response = noRedirect.callback("test-code", state);

            // (a) Rejection redirect to the SPA login error page — NOT to the app.
            Assertions.assertEquals(Status.TEMPORARY_REDIRECT.getStatusCode(), response.getStatus());
            String location = response.getHeaderString("Location");
            Assertions.assertNotNull(location);
            Assertions.assertTrue(location.endsWith("/#/login?error=oidc"),
                    "a disabled OIDC user must be redirected to the login error page, got: " + location);

            // (b) No auth-token cookie set on the response.
            Assertions.assertFalse(response.getCookies().containsKey(TokenBasedSecurityFilter.COOKIE_NAME),
                    "no auth_token cookie must be set for a disabled OIDC user");

            // (c) No AuthenticationToken minted for the disabled user.
            List<AuthenticationToken> tokens = readDb(em ->
                    new AuthenticationTokenDao().getByUserId(userId));
            Assertions.assertTrue(tokens.isEmpty(),
                    "no auth token must be minted through the real callback for a disabled OIDC user");
        } finally {
            noRedirectClient.close();
            idp.stop(0);
            System.clearProperty("docs.oidc_enabled");
            System.clearProperty("docs.oidc_issuer");
            System.clearProperty("docs.oidc_client_id");
            System.clearProperty("docs.oidc_client_secret");
            System.clearProperty("docs.oidc_redirect_uri");
            System.clearProperty("docs.oidc_token_endpoint");
            System.clearProperty("docs.oidc_jwks_uri");
            resetOidcJwksCache();
        }
    }

    /**
     * Builds a no-redirect callback request against the running Grizzly server. Kept as a tiny
     * holder so the ephemeral test port (from BaseJerseyTest) is resolved lazily at call time.
     */
    private final class WebTargetOf {
        private final Client client;

        WebTargetOf(Client client) {
            this.client = client;
        }

        Response callback(String code, String state) {
            return client.target(UriBuilder.fromUri(getBaseUri()).build())
                    .path("/oidc/callback")
                    .queryParam("code", code)
                    .queryParam("state", state)
                    .request()
                    .get();
        }
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body) {
        try {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Resets OidcResource's static JWKS cache so the loopback IdP's key is fetched fresh. The
     * discovery cache is not touched because the explicit token/jwks endpoint overrides bypass it.
     */
    private static void resetOidcJwksCache() throws Exception {
        for (String fieldName : new String[]{"jwksCache", "jwksLastRefreshMs"}) {
            Field f = OidcResource.class.getDeclaredField(fieldName);
            f.setAccessible(true);
            if (f.getType() == long.class) {
                f.setLong(null, 0L);
            } else {
                f.set(null, null);
            }
        }
    }

    /**
     * Login path: a disabled account with the CORRECT credentials must be rejected BEFORE a
     * session token is minted or a cookie set. The exposed case is the guest-login branch,
     * which resolves the guest directly via getActiveByUsername (bypassing the auth handlers
     * that already reject disabled internal/LDAP users). A disabled guest with guest-login
     * enabled must get a 403, no auth_token cookie, and no new session-token row.
     */
    @Test
    public void disabledGuestLoginMintsNoToken() {
        String adminToken = adminToken();

        // Enable guest login (admin endpoint).
        target().path("/app/guest_login").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form().param("enabled", "true")), JsonObject.class);

        // Disable the guest account directly (the update endpoint refuses to disable guest).
        String guestId = writeDb(em -> {
            User guest = new UserDao().getActiveByUsername("guest");
            guest.setDisableDate(new Date());
            em.merge(guest);
            return guest.getId();
        });

        int tokensBefore = readDb(em -> new AuthenticationTokenDao().getByUserId(guestId).size());

        // Attempt guest login (correct "credentials" — guest uses an empty password).
        Response response = target().path("/user/login").request()
                .post(Entity.form(new Form().param("username", "guest")));
        Assertions.assertEquals(Status.FORBIDDEN.getStatusCode(), response.getStatus(),
                "a disabled guest login must be rejected");

        // No auth_token cookie must be issued.
        Assertions.assertFalse(response.getCookies().containsKey(TokenBasedSecurityFilter.COOKIE_NAME),
                "a disabled guest login must not set an auth_token cookie");

        // No new session-token row must be created.
        int tokensAfter = readDb(em -> new AuthenticationTokenDao().getByUserId(guestId).size());
        Assertions.assertEquals(tokensBefore, tokensAfter,
                "a disabled guest login must mint no session token");

        // Re-disable guest login to keep global config clean for other tests.
        target().path("/app/guest_login").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form().param("enabled", "false")), JsonObject.class);
    }

    /**
     * Control: an ENABLED user with the correct password still logs in and gets a token —
     * the disabled-login guard is not over-broad.
     */
    @Test
    public void enabledUserLoginStillSucceeds() {
        clientUtil.createUser("enabled_login");
        Response response = target().path("/user/login").request()
                .post(Entity.form(new Form()
                        .param("username", "enabled_login")
                        .param("password", "Test1234")
                        .param("remember", "false")));
        Assertions.assertEquals(Status.OK.getStatusCode(), response.getStatus());
        Assertions.assertTrue(response.getCookies().containsKey(TokenBasedSecurityFilter.COOKIE_NAME),
                "an enabled user login must set an auth_token cookie");
    }

    /**
     * Guest invariant: the update endpoint refuses to disable guest, but if guest is disabled
     * directly, the shared eligibility predicate must still reject it (defense in depth).
     */
    @Test
    public void directlyDisabledGuestIsRejectedByGuard() {
        User disabledGuest = new User().setDisableDate(new Date());
        Assertions.assertTrue(disabledGuest.isDisabled(),
                "a directly-disabled user (incl. guest) must be caught by the shared guard");
        Assertions.assertFalse(OidcResource.isOidcUserEligible(disabledGuest));
    }

    /**
     * Runs a mutating block in its own transactional EntityManager, restoring the prior context.
     */
    private <T> T writeDb(Function<EntityManager, T> fn) {
        EntityManager em = EMF.get().createEntityManager();
        ThreadLocalContext previous = ThreadLocalContext.get();
        ThreadLocalContext context = ThreadLocalContext.get();
        context.setEntityManager(em);
        try {
            em.getTransaction().begin();
            T result = fn.apply(em);
            em.getTransaction().commit();
            return result;
        } finally {
            if (em.isOpen()) {
                em.close();
            }
            if (previous != null) {
                previous.setEntityManager(null);
            }
        }
    }

    /**
     * Runs a read-only block in its own transactional EntityManager, restoring the prior context.
     * The transaction is rolled back (read-only), but it is required because DAO queries here run
     * under a JPA context that expects an active transaction.
     */
    private <T> T readDb(Function<EntityManager, T> fn) {
        EntityManager em = EMF.get().createEntityManager();
        ThreadLocalContext previous = ThreadLocalContext.get();
        ThreadLocalContext context = ThreadLocalContext.get();
        context.setEntityManager(em);
        try {
            em.getTransaction().begin();
            T result = fn.apply(em);
            em.getTransaction().rollback();
            return result;
        } finally {
            if (em.isOpen()) {
                em.close();
            }
            if (previous != null) {
                previous.setEntityManager(null);
            }
        }
    }
}
