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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Torn logout-URL guard (#44, HIGH-effort auth-surface blocker): the RP-initiated logout URL must
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
        // Baseline: with provider A configured, the composed URL is fully-A.
        String url = inTx(() -> OidcResource.resolveLogoutUrl("the-id-token"));
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
                    String u = inTx(() -> OidcResource.resolveLogoutUrl("tok"));
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

    // --- helpers ---------------------------------------------------------------------------

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
