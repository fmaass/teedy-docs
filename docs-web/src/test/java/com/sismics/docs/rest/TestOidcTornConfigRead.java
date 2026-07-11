package com.sismics.docs.rest;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.sismics.docs.core.constant.ConfigType;
import com.sismics.docs.core.dao.ConfigDao;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Torn-config-read guard (#44, HIGH-effort auth-surface blocker): a config save landing in the
 * MIDDLE of an OIDC callback must NOT produce a request that acts on a mix of old and new values
 * (e.g. sends the OLD client_id to the token endpoint but verifies the ID token against the NEW
 * client_id as audience). The callback resolves the whole config into ONE immutable snapshot at
 * the start, so every value the request uses is internally consistent.
 *
 * <p>The probe is deterministic and cannot pass under a torn read: the mock IdP's {@code /token}
 * handler BLOCKS until a concurrent save has flipped the DB {@code client_id} to a DIFFERENT
 * value, then releases. The ID token is signed with {@code aud} = the PRE-save client_id. If the
 * callback used one consistent snapshot, the token exchange sent the pre-save client_id AND the
 * ID-token verification used the pre-save client_id as audience → login succeeds. If any read
 * bypassed the snapshot, the audience check would read the flipped (post-save) client_id → the
 * signature/audience verification fails and the login is rejected. So a SUCCESSFUL, cookie-setting
 * login while a save lands mid-flow proves the request saw a consistent config.
 */
public class TestOidcTornConfigRead extends BaseJerseyTest {

    private static final String PRE_SAVE_CLIENT_ID = "client-before-save";
    private static final String POST_SAVE_CLIENT_ID = "client-after-save";
    private static final String ISSUER = "https://iss.torn.example";

    private HttpServer idp;
    private String idpBase;
    private RSAPublicKey publicKey;
    private RSAPrivateKey privateKey;
    private Client noRedirectClient;
    private WebTarget noRedirectTarget;

    /** Released by the concurrent saver once it has flipped client_id in the DB. */
    private final CountDownLatch saveLanded = new CountDownLatch(1);
    /** Set when the /token handler is entered, so the saver knows the callback is mid-flight. */
    private final CountDownLatch tokenRequested = new CountDownLatch(1);
    private final AtomicReference<String> clientIdSentToToken = new AtomicReference<>();

    @Override
    @BeforeEach
    public void setUp() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();
        publicKey = (RSAPublicKey) kp.getPublic();
        privateKey = (RSAPrivateKey) kp.getPrivate();

        idp = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        idp.createContext("/jwks", exchange -> respondJson(exchange, jwksJson()));
        idp.start();
        idpBase = "http://127.0.0.1:" + idp.getAddress().getPort();

        super.setUp();
        noRedirectClient = ClientBuilder.newBuilder()
                .property("jersey.config.client.followRedirects", false).build();
        noRedirectTarget = noRedirectClient.target(
                UriBuilder.fromUri("http://localhost:" + getPort() + "/docs").build());

        // Configure OIDC entirely via the DB (no JVM properties), with the pre-save client_id.
        inTx(() -> {
            ConfigDao dao = new ConfigDao();
            dao.update(ConfigType.OIDC_ENABLED, "true");
            dao.update(ConfigType.OIDC_ISSUER, ISSUER);
            dao.update(ConfigType.OIDC_CLIENT_ID, PRE_SAVE_CLIENT_ID);
            dao.update(ConfigType.OIDC_CLIENT_SECRET, "the-secret");
            dao.update(ConfigType.OIDC_REDIRECT_URI, "https://app.example/api/oidc/callback");
            dao.update(ConfigType.OIDC_JWKS_URI, idpBase + "/jwks");
            dao.update(ConfigType.OIDC_TOKEN_ENDPOINT, idpBase + "/token");
            return null;
        });
        resetOidcCaches();
    }

    @Override
    @AfterEach
    public void tearDown() throws Exception {
        inTx(() -> {
            EntityManager em = ThreadLocalContext.get().getEntityManager();
            for (ConfigType t : new ConfigType[]{ConfigType.OIDC_ENABLED, ConfigType.OIDC_ISSUER,
                    ConfigType.OIDC_CLIENT_ID, ConfigType.OIDC_CLIENT_SECRET, ConfigType.OIDC_REDIRECT_URI,
                    ConfigType.OIDC_JWKS_URI, ConfigType.OIDC_TOKEN_ENDPOINT}) {
                em.createNativeQuery("delete from T_CONFIG where CFG_ID_C = :id")
                        .setParameter("id", t.name()).executeUpdate();
            }
            return null;
        });
        resetOidcCaches();
        if (noRedirectClient != null) {
            noRedirectClient.close();
        }
        if (idp != null) {
            idp.stop(0);
        }
        super.tearDown();
    }

    @Test
    public void callbackWithConcurrentSaveUsesOneConsistentConfig() throws Exception {
        String subject = "sub-" + UUID.randomUUID();
        String nonce = UUID.randomUUID().toString();
        String state = seedState(nonce);

        // The ID token is signed with aud = the PRE-save client_id. A consistent snapshot verifies
        // against this same value; a torn read (verify sees the flipped client_id) rejects it.
        String idToken = JWT.create()
                .withIssuer(ISSUER)
                .withAudience(PRE_SAVE_CLIENT_ID)
                .withSubject(subject)
                .withClaim("nonce", nonce)
                .withClaim("preferred_username", "tornuser")
                .withClaim("email", "torn@example.com")
                .withExpiresAt(new Date(System.currentTimeMillis() + 300_000))
                .sign(rsa());

        // The /token handler blocks until a concurrent save has flipped client_id, so the save
        // is guaranteed to land AFTER the callback built its snapshot and BEFORE it finishes.
        String tokenBody = "{\"id_token\":\"" + idToken + "\",\"access_token\":\"at\",\"token_type\":\"Bearer\"}";
        idp.createContext("/token", exchange -> {
            // Capture the client_id the exchange actually sent (proves which snapshot was used).
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            clientIdSentToToken.set(extractParam(body, "client_id"));
            tokenRequested.countDown();
            try {
                // Wait for the concurrent save to land (bounded so a bug can't hang the suite).
                saveLanded.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            respondJson(exchange, tokenBody);
        });

        // Concurrent saver: once the callback is inside /token, flip client_id in the DB, then
        // release the token handler.
        Thread saver = new Thread(() -> {
            try {
                tokenRequested.await(10, TimeUnit.SECONDS);
                inTx(() -> {
                    new ConfigDao().update(ConfigType.OIDC_CLIENT_ID, POST_SAVE_CLIENT_ID);
                    return null;
                });
            } catch (Exception e) {
                // fall through; the assertion below will surface a failure
            } finally {
                saveLanded.countDown();
            }
        });
        saver.start();

        Response resp = doCallback("code-torn", state);
        saver.join(15_000);

        // The token exchange must have sent the PRE-save client_id (the snapshot value), never the
        // flipped one.
        Assertions.assertEquals(PRE_SAVE_CLIENT_ID, clientIdSentToToken.get(),
                "the token exchange must use the snapshot's client_id, not a mid-flight save");

        // A consistent snapshot verifies aud against the SAME pre-save client_id -> login succeeds
        // with a session cookie. A torn read would have verified against the flipped client_id and
        // rejected the login.
        Assertions.assertEquals(Response.Status.TEMPORARY_REDIRECT.getStatusCode(), resp.getStatus());
        Assertions.assertFalse(resp.getHeaderString("Location").endsWith("/#/login?error=oidc"),
                "a consistent config must let the login succeed, got: " + resp.getHeaderString("Location"));
        Assertions.assertTrue(resp.getCookies().containsKey("auth_token"),
                "a consistent snapshot must mint a session cookie despite the concurrent save");

        // The DB flip did land (the config really changed mid-flow), proving the save was concurrent.
        Assertions.assertEquals(POST_SAVE_CLIENT_ID,
                inTx(() -> new ConfigDao().getById(ConfigType.OIDC_CLIENT_ID).getValue()),
                "the concurrent save must have flipped the DB client_id mid-flow");

        User u = lookupBySub(ISSUER, subject);
        if (u != null) {
            deleteUser(u.getUsername());
        }
    }

    // --- mock IdP plumbing -----------------------------------------------------------------

    private Algorithm rsa() {
        return Algorithm.RSA256(publicKey, privateKey);
    }

    private static String extractParam(String formBody, String name) {
        for (String pair : formBody.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) {
                return java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
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
        String n = Base64.getUrlEncoder().withoutPadding().encodeToString(toUnsignedBytes(publicKey.getModulus()));
        String e = Base64.getUrlEncoder().withoutPadding().encodeToString(toUnsignedBytes(publicKey.getPublicExponent()));
        return "{\"keys\":[{\"kty\":\"RSA\",\"use\":\"sig\",\"alg\":\"RS256\",\"n\":\"" + n + "\",\"e\":\"" + e + "\"}]}";
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

    // --- DB helpers ------------------------------------------------------------------------

    private String seedState(String nonce) {
        return inTx(() -> {
            String id = UUID.randomUUID().toString();
            // Pin the PRE-save provider fingerprint (issuer + client_id): the callback builds its
            // snapshot BEFORE the concurrent client_id flip lands, so the fingerprint compares
            // against the same pre-save client_id and the fail-closed binding check passes — exactly
            // as a real same-provider login→callback would, leaving this test's torn-read invariant
            // the sole thing under test.
            OidcState st = new OidcState().setId(id).setNonce(nonce).setCodeVerifier("verifier")
                    .setReturnUrl(null).setIssuer(ISSUER).setClientId(PRE_SAVE_CLIENT_ID);
            new OidcStateDao().create(st);
            return id;
        });
    }

    private User lookupBySub(String issuer, String subject) {
        return inTx(() -> new UserDao().getByOidcSubject(issuer, subject));
    }

    private void deleteUser(String username) {
        inTx(() -> {
            EntityManager em = ThreadLocalContext.get().getEntityManager();
            em.createNativeQuery("delete from T_AUTHENTICATION_TOKEN where AUT_IDUSER_C in "
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
