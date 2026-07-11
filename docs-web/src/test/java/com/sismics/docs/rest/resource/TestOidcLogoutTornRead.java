package com.sismics.docs.rest.resource;

import com.sismics.docs.core.constant.ConfigType;
import com.sismics.docs.core.dao.ConfigDao;
import com.sismics.docs.rest.BaseJerseyTest;
import com.sismics.util.context.ThreadLocalContext;
import com.sismics.util.jpa.EMF;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityTransaction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Torn logout-URL guard (#44): the RP-initiated logout URL must
 * be composed from ONE config snapshot, so a provider change landing mid-logout can never mix one
 * provider's {@code end_session_endpoint} with another provider's {@code post_logout_redirect_uri}
 * — which would send the stored {@code id_token_hint} to the WRONG provider's endpoint (a token
 * exposure). {@link OidcResource#resolveLogoutUrl(String)} reads the enabled flag, the discovery
 * cache key, and the redirect URI all from the same snapshot.
 *
 * <p>The probe: two providers A and B are pre-seeded in the discovery cache, each advertising its
 * OWN end_session endpoint on its OWN host. A writer thread flips the DB config between the FULL A
 * config and the FULL B config (issuer + redirect together); a reader thread hammers
 * {@code resolveLogoutUrl}. Every non-null URL it produces MUST have its end_session host and its
 * post_logout_redirect host on the SAME side (both A or both B) — never mixed. Under a snapshot
 * this invariant always holds; a read that bypassed the snapshot would eventually emit a mixed URL.
 */
public class TestOidcLogoutTornRead extends BaseJerseyTest {

    private static final String ISSUER_A = "https://provider-a.example";
    private static final String ISSUER_B = "https://provider-b.example";
    private static final String END_SESSION_A = "https://provider-a.example/logout";
    private static final String END_SESSION_B = "https://provider-b.example/logout";
    private static final String REDIRECT_A = "https://app-a.example/api/oidc/callback";
    private static final String REDIRECT_B = "https://app-b.example/api/oidc/callback";
    private static final String CLIENT_ID = "teedy-client";

    @Override
    @BeforeEach
    public void setUp() throws Exception {
        super.setUp();
        clearState();

        // Pre-seed BOTH providers' discovery docs into the value-keyed cache: each keyed by its own
        // issuer's discovery URL (set the issuer, seed, repeat). Then start on provider A.
        seedDiscovery(ISSUER_A, END_SESSION_A);
        seedDiscovery(ISSUER_B, END_SESSION_B);
        applyConfig(ISSUER_A, REDIRECT_A);
    }

    @Override
    @AfterEach
    public void tearDown() throws Exception {
        clearState();
        super.tearDown();
    }

    @Test
    public void logoutUrlIsComposedFromOneConsistentSnapshot() throws Exception {
        // The stored id_token is bound to its issuer: use an A-issued token, matching the starting
        // provider A. When the config is flipped to B mid-test, the A-token no longer matches the
        // current provider and resolveLogoutUrl returns null (skipped below) — so every non-null
        // result is provider A and must be internally consistent (endpoint + redirect both A).
        final String tokenA = unsignedIdToken(ISSUER_A);

        // Baseline: with provider A configured, the composed URL is fully-A.
        String url = inTx(() -> OidcResource.resolveLogoutUrl(tokenA));
        Assertions.assertNotNull(url, "a logout URL must be composed when OIDC + end_session are set");
        Assertions.assertTrue(url.startsWith(END_SESSION_A), url);
        Assertions.assertTrue(url.contains("post_logout_redirect_uri="
                + enc("https://app-a.example")), url);

        // Concurrency: flip A<->B in the DB while hammering resolveLogoutUrl. Every non-null result
        // must be internally consistent (endpoint and redirect on the same provider).
        int iterations = 200;
        final CountDownLatch start = new CountDownLatch(1);
        final AtomicReference<String> mixed = new AtomicReference<>();

        Thread reader = new Thread(() -> {
            try {
                start.await();
                for (int i = 0; i < iterations && mixed.get() == null; i++) {
                    // resolveLogoutUrl reads config via ConfigDao, which needs a tx context.
                    String u = inTx(() -> OidcResource.resolveLogoutUrl(tokenA));
                    if (u == null) {
                        continue;
                    }
                    boolean endpointA = u.startsWith(END_SESSION_A);
                    boolean endpointB = u.startsWith(END_SESSION_B);
                    boolean redirectA = u.contains(enc("https://app-a.example"));
                    boolean redirectB = u.contains(enc("https://app-b.example"));
                    // Consistent = (endpoint A with redirect A) XOR (endpoint B with redirect B).
                    boolean allA = endpointA && redirectA && !endpointB && !redirectB;
                    boolean allB = endpointB && redirectB && !endpointA && !redirectA;
                    if (!allA && !allB) {
                        mixed.set(u);
                    }
                }
            } catch (Exception e) {
                mixed.set("reader failed: " + e.getMessage());
            }
        });

        Thread writer = new Thread(() -> {
            try {
                start.await();
                for (int i = 0; i < iterations && mixed.get() == null; i++) {
                    if (i % 2 == 0) {
                        applyConfig(ISSUER_B, REDIRECT_B);
                    } else {
                        applyConfig(ISSUER_A, REDIRECT_A);
                    }
                }
            } catch (Exception e) {
                mixed.set("writer failed: " + e.getMessage());
            }
        });

        reader.start();
        writer.start();
        start.countDown();
        reader.join();
        writer.join();

        Assertions.assertNull(mixed.get(),
                "a concurrent provider change mid-logout must never yield a torn logout URL "
                        + "(endpoint of one provider + redirect of another): " + mixed.get());
    }

    /**
     * Provider-binding guard (#44): the stored {@code id_token} is minted by
     * the provider that was configured when the user logged in. If an admin switches the OIDC
     * provider LIVE (no restart) after that login, a logout must NOT disclose provider A's ID token
     * to provider B's {@code end_session_endpoint}. {@link OidcResource#resolveLogoutUrl(String)}
     * binds the token to its issuer: when the token's {@code iss} does not equal the CURRENT
     * provider's effective issuer, it returns null (no RP-initiated redirect — the caller just
     * clears the local session).
     */
    @Test
    public void logoutBindsIdTokenToItsIssuer() {
        // A token whose iss = provider A, while the CURRENT config is provider B: the token must
        // NOT be sent to provider B's end_session_endpoint. resolveLogoutUrl must return null.
        String tokenIssuedByA = unsignedIdToken(ISSUER_A);
        applyConfig(ISSUER_B, REDIRECT_B);
        OidcResource.resetConfigCacheForTest();
        // Re-seed both discovery docs (resetConfigCacheForTest cleared the discovery cache).
        seedDiscovery(ISSUER_A, END_SESSION_A);
        seedDiscovery(ISSUER_B, END_SESSION_B);
        applyConfig(ISSUER_B, REDIRECT_B);

        String mismatched = inTx(() -> OidcResource.resolveLogoutUrl(tokenIssuedByA));
        Assertions.assertNull(mismatched,
                "provider A's id_token must NOT be sent to provider B's end_session_endpoint; "
                        + "resolveLogoutUrl must return null on an issuer mismatch, got: " + mismatched);

        // A token whose iss = the CURRENT provider (B): the RP-logout URL IS built with B's endpoint.
        String tokenIssuedByB = unsignedIdToken(ISSUER_B);
        String matched = inTx(() -> OidcResource.resolveLogoutUrl(tokenIssuedByB));
        Assertions.assertNotNull(matched,
                "a token whose iss matches the current provider must build the RP-logout URL");
        Assertions.assertTrue(matched.startsWith(END_SESSION_B), matched);
        Assertions.assertTrue(matched.contains("id_token_hint=" + enc(tokenIssuedByB)), matched);
    }

    /**
     * Provider-binding guard, audience half: the callback validates BOTH iss and aud, so logout
     * must too. A token whose iss matches the current provider but whose {@code aud} is no longer
     * the current {@code client_id} must NOT be disclosed to the provider's end_session_endpoint —
     * resolveLogoutUrl falls back to local-only logout (returns null). An {@code aud} array that
     * still CONTAINS the current client_id remains valid.
     */
    @Test
    public void logoutBindsIdTokenToClientIdAudience() {
        // iss matches provider A (the current provider) but aud is a stale/foreign client_id.
        String staleAudToken = unsignedIdToken(ISSUER_A, "\"some-other-client\"");
        String mismatched = inTx(() -> OidcResource.resolveLogoutUrl(staleAudToken));
        Assertions.assertNull(mismatched,
                "a token whose aud is not the current client_id must fall back to local logout "
                        + "(no id_token_hint); got: " + mismatched);

        // aud as an ARRAY that still contains the current client_id is a valid match.
        String arrayAudToken = unsignedIdToken(ISSUER_A, "[\"another-client\",\"" + CLIENT_ID + "\"]");
        String matched = inTx(() -> OidcResource.resolveLogoutUrl(arrayAudToken));
        Assertions.assertNotNull(matched,
                "an aud array containing the current client_id must build the RP-logout URL");
        Assertions.assertTrue(matched.startsWith(END_SESSION_A), matched);
        Assertions.assertTrue(matched.contains("id_token_hint=" + enc(arrayAudToken)), matched);

        // A token with no aud claim at all must likewise fall back to local logout.
        String noAudToken = unsignedIdToken(ISSUER_A, null);
        String noAud = inTx(() -> OidcResource.resolveLogoutUrl(noAudToken));
        Assertions.assertNull(noAud, "a token with no aud claim must fall back to local logout");
    }

    /**
     * Fail-safe: a stored token that carries no {@code iss} claim (or cannot be parsed) must not be
     * disclosed to any provider — resolveLogoutUrl returns null (skip the external redirect).
     */
    @Test
    public void logoutSkipsExternalRedirectWhenTokenHasNoIssuer() {
        String noIssToken = unsignedIdToken(null);
        String url = inTx(() -> OidcResource.resolveLogoutUrl(noIssToken));
        Assertions.assertNull(url, "a token with no iss claim must not be sent to any provider");

        String garbage = inTx(() -> OidcResource.resolveLogoutUrl("not-a-jwt"));
        Assertions.assertNull(garbage, "an unparseable token must not be sent to any provider");
    }

    // --- helpers ---------------------------------------------------------------------------

    /**
     * Builds an UNSIGNED (header.payload.sig-placeholder) id_token carrying the given issuer as its
     * {@code iss} claim (or no iss when {@code issuer} is null). resolveLogoutUrl reads iss WITHOUT
     * verifying the signature (it is our own previously-verified stored token), so a placeholder
     * signature segment is sufficient for this probe.
     */
    private static String unsignedIdToken(String issuer) {
        return unsignedIdToken(issuer, "\"" + CLIENT_ID + "\"");
    }

    /**
     * As {@link #unsignedIdToken(String)} but with an explicit {@code aud} JSON fragment (a quoted
     * string like {@code "client"} or an array like {@code ["a","client"]}), or null to omit aud.
     */
    private static String unsignedIdToken(String issuer, String audJson) {
        String header = b64url("{\"alg\":\"RS256\",\"typ\":\"JWT\"}");
        StringBuilder claims = new StringBuilder("{\"sub\":\"user-1\"");
        if (issuer != null) {
            claims.append(",\"iss\":\"").append(issuer).append("\"");
        }
        if (audJson != null) {
            claims.append(",\"aud\":").append(audJson);
        }
        claims.append("}");
        String payload = b64url(claims.toString());
        return header + "." + payload + ".c2ln";
    }

    private static String b64url(String json) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private static String enc(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }

    private void seedDiscovery(String issuer, String endSession) {
        // Set the issuer so the seam keys the discovery under this issuer's discovery URL, then seed
        // it. The seam reads the effective issuer via a config snapshot, so it must run inside a tx
        // context (the caller applies the final starting config afterwards).
        applyConfig(issuer, REDIRECT_A);
        JsonObject discovery = Json.createObjectBuilder()
                .add("issuer", issuer)
                .add("end_session_endpoint", endSession)
                .build();
        inTx(() -> {
            OidcResource.setDiscoveryCacheForTest(discovery);
            return null;
        });
    }

    private void applyConfig(String issuer, String redirect) {
        inTx(() -> {
            ConfigDao dao = new ConfigDao();
            dao.update(ConfigType.OIDC_ENABLED, "true");
            dao.update(ConfigType.OIDC_ISSUER, issuer);
            dao.update(ConfigType.OIDC_REDIRECT_URI, redirect);
            dao.update(ConfigType.OIDC_CLIENT_ID, CLIENT_ID);
            return null;
        });
    }

    private void clearState() {
        inTx(() -> {
            EntityManager em = ThreadLocalContext.get().getEntityManager();
            for (OidcResource.OidcKey key : OidcResource.OidcKey.values()) {
                em.createNativeQuery("delete from T_CONFIG where CFG_ID_C = :id")
                        .setParameter("id", key.configType().name()).executeUpdate();
            }
            return null;
        });
        for (OidcResource.OidcKey key : OidcResource.OidcKey.values()) {
            System.clearProperty(key.propertyName());
        }
        OidcResource.resetConfigCacheForTest();
    }

    private <T> T inTx(Supplier<T> work) {
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
}
