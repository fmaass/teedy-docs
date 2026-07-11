package com.sismics.docs.rest.resource;

import com.sismics.docs.core.constant.ConfigType;
import com.sismics.docs.core.dao.ConfigDao;
import com.sismics.docs.rest.BaseJerseyTest;
import com.sismics.util.context.ThreadLocalContext;
import com.sismics.util.filter.TokenBasedSecurityFilter;
import com.sismics.util.jpa.EMF;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityTransaction;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Form;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * DB-first OIDC configuration (#44): the {@code /app/config_oidc} endpoints, the single
 * {@link OidcResource#oidcConfig} accessor with DB → property → default precedence, the
 * write-only client-secret contract, the value-keyed caches (config change with no restart), and
 * the ADR-0015-fenced surface. The login-redirect assertions prove the LOGIN path itself uses the
 * accessor (not only the callback), and the zero-config assertions prove a no-DB deployment
 * behaves exactly as the v3.3.0 property-only build.
 */
public class TestOidcConfig extends BaseJerseyTest {

    private Client noRedirectClient;
    private WebTarget noRedirectTarget;

    @Override
    @BeforeEach
    public void setUp() throws Exception {
        super.setUp();
        noRedirectClient = ClientBuilder.newBuilder()
                .property("jersey.config.client.followRedirects", false).build();
        noRedirectTarget = noRedirectClient.target(
                UriBuilder.fromUri("http://localhost:" + getPort() + "/docs").build());
        clearOidcState();
    }

    @Override
    @AfterEach
    public void tearDown() throws Exception {
        clearOidcState();
        if (noRedirectClient != null) {
            noRedirectClient.close();
        }
        super.tearDown();
    }

    // =========================================================================================
    // (a) Precedence accessor per tier + blank-DB falls through + POST reset clears the row
    // =========================================================================================

    @Test
    public void accessorPrecedenceDbOverridesPropertyOverridesDefault() {
        // Default tier: no DB row, no property -> the built-in default.
        Assertions.assertEquals("openid profile email", inTx(() -> OidcResource.oidcConfig(OidcResource.OidcKey.SCOPE)));
        Assertions.assertEquals("preferred_username",
                inTx(() -> OidcResource.oidcConfig(OidcResource.OidcKey.USERNAME_CLAIM)));

        // Property tier: a property set, no DB row -> the property value.
        System.setProperty("docs.oidc_scope", "openid email");
        try {
            Assertions.assertEquals("openid email", inTx(() -> OidcResource.oidcConfig(OidcResource.OidcKey.SCOPE)));
            Assertions.assertEquals("property", inTx(() -> OidcResource.oidcConfigSource(OidcResource.OidcKey.SCOPE)));

            // DB tier: a DB row wins over the property.
            inTx(() -> {
                new ConfigDao().update(ConfigType.OIDC_SCOPE, "openid profile");
                return null;
            });
            Assertions.assertEquals("openid profile", inTx(() -> OidcResource.oidcConfig(OidcResource.OidcKey.SCOPE)));
            Assertions.assertEquals("db", inTx(() -> OidcResource.oidcConfigSource(OidcResource.OidcKey.SCOPE)));

            // Blank DB value is UNSET: it must fall through to the property tier, not override
            // with emptiness.
            inTx(() -> {
                new ConfigDao().update(ConfigType.OIDC_SCOPE, "");
                return null;
            });
            Assertions.assertEquals("openid email", inTx(() -> OidcResource.oidcConfig(OidcResource.OidcKey.SCOPE)),
                    "a blank DB value must fall through to the property tier");
            Assertions.assertEquals("property", inTx(() -> OidcResource.oidcConfigSource(OidcResource.OidcKey.SCOPE)));
        } finally {
            System.clearProperty("docs.oidc_scope");
        }
    }

    // =========================================================================================
    // (b) Secret write-only round-trip: GET never contains it; POST empty preserves; reset clears
    // =========================================================================================

    @Test
    public void clientSecretIsWriteOnly() {
        String adminToken = adminToken();

        // Configure OIDC with a secret.
        postConfig(adminToken, enabledForm("client_secret", "s3cr3t"));

        JsonObject json = getConfig(adminToken);
        Assertions.assertTrue(json.getBoolean("enabled"));
        Assertions.assertFalse(json.containsKey("client_secret"),
                "the client secret must NEVER be echoed by the GET");
        Assertions.assertTrue(json.getBoolean("client_secret_set"),
                "the GET must report that a client secret is stored");

        // POST with an EMPTY client_secret preserves the stored value.
        postConfig(adminToken, enabledForm("client_secret", ""));
        Assertions.assertEquals("s3cr3t", inTx(() ->
                new ConfigDao().getById(ConfigType.OIDC_CLIENT_SECRET).getValue()),
                "an empty client_secret must preserve the stored secret");
        Assertions.assertTrue(getConfig(adminToken).getBoolean("client_secret_set"));

        // client_secret_reset=true clears the stored secret.
        postConfig(adminToken, enabledForm("client_secret", "", "client_secret_reset", "true"));
        Assertions.assertFalse(getConfig(adminToken).getBoolean("client_secret_set"),
                "client_secret_reset=true must clear the stored secret");
    }

    // =========================================================================================
    // (c) Config change picked up WITHOUT restart (value-keyed caches); concurrent login safe
    // =========================================================================================

    @Test
    public void configChangeIsPickedUpWithoutRestart() {
        String adminToken = adminToken();
        postConfig(adminToken, enabledForm("client_secret", "s3cr3t", "client_id", "client-A"));
        Assertions.assertEquals("client-A", inTx(() -> OidcResource.oidcConfig(OidcResource.OidcKey.CLIENT_ID)));

        // Save a new client id; the very next accessor read reflects it — no restart, no cache
        // invalidation hook. (The accessor reads the current row every call.)
        postConfig(adminToken, enabledForm("client_secret", "", "client_id", "client-B"));
        Assertions.assertEquals("client-B", inTx(() -> OidcResource.oidcConfig(OidcResource.OidcKey.CLIENT_ID)),
                "a config change must be visible to the next read with no restart");
    }

    @Test
    public void concurrentLoginDuringSaveCannotPinStaleConfig() throws Exception {
        String adminToken = adminToken();
        postConfig(adminToken, enabledForm("client_secret", "s3cr3t", "client_id", "before"));

        // A reader that runs its OWN transaction concurrently with a save must observe a single
        // consistent config — either fully pre-save or fully post-save — never a torn mix.
        // Each read is a fresh accessor call in its own transaction; the DB row is the single
        // source of truth, so there is no static state to pin a stale value across the save.
        int iterations = 40;
        final CountDownLatch start = new CountDownLatch(1);
        final AtomicReference<String> torn = new AtomicReference<>();

        Thread reader = new Thread(() -> {
            try {
                start.await();
                for (int i = 0; i < iterations && torn.get() == null; i++) {
                    String id = inTx(() -> OidcResource.oidcConfig(OidcResource.OidcKey.CLIENT_ID));
                    // Every observed value must be one of the two whole configs, never a blank
                    // or a half-written state.
                    if (!"before".equals(id) && !"after".equals(id)) {
                        torn.set("observed torn client_id: " + id);
                    }
                }
            } catch (Exception e) {
                torn.set("reader failed: " + e.getMessage());
            }
        });

        Thread writer = new Thread(() -> {
            try {
                start.await();
                for (int i = 0; i < iterations; i++) {
                    postConfig(adminToken, enabledForm("client_secret", "",
                            "client_id", i % 2 == 0 ? "after" : "before"));
                }
            } catch (Exception e) {
                torn.set("writer failed: " + e.getMessage());
            }
        });

        reader.start();
        writer.start();
        start.countDown();
        reader.join();
        writer.join();

        Assertions.assertNull(torn.get(), "a concurrent login during a save must never see torn config: " + torn.get());
    }

    // =========================================================================================
    // (d) DB-OVERRIDE REDIRECT: the /oidc/login redirect reflects DB values, proving the LOGIN
    //     path (not just the callback) reads the accessor.
    // =========================================================================================

    @Test
    public void loginRedirectReflectsDbOverridesOverDifferentProperties() {
        // Different JVM properties are present; the DB overrides must win in the redirect URL.
        System.setProperty("docs.oidc_enabled", "true");
        System.setProperty("docs.oidc_client_id", "prop-client");
        System.setProperty("docs.oidc_authorization_endpoint", "https://prop.example/authorize");
        try {
            inTx(() -> {
                ConfigDao dao = new ConfigDao();
                dao.update(ConfigType.OIDC_ENABLED, "true");
                dao.update(ConfigType.OIDC_ISSUER, "https://db.example");
                dao.update(ConfigType.OIDC_CLIENT_ID, "db-client");
                dao.update(ConfigType.OIDC_CLIENT_SECRET, "db-secret");
                dao.update(ConfigType.OIDC_REDIRECT_URI, "https://app.example/api/oidc/callback");
                dao.update(ConfigType.OIDC_AUTHORIZATION_ENDPOINT, "https://db.example/authorize");
                return null;
            });
            OidcResource.resetConfigCacheForTest();

            Response resp = noRedirectTarget.path("/oidc/login").request().get();
            Assertions.assertEquals(Status.TEMPORARY_REDIRECT.getStatusCode(), resp.getStatus());
            String location = resp.getHeaderString("Location");
            Assertions.assertTrue(location.startsWith("https://db.example/authorize"),
                    "the authorization endpoint must come from the DB override, got: " + location);
            Assertions.assertTrue(location.contains("client_id=db-client"),
                    "the client id must come from the DB override, got: " + location);
            Assertions.assertFalse(location.contains("prop-client"),
                    "the JVM property client id must NOT appear when a DB override is set");
        } finally {
            System.clearProperty("docs.oidc_enabled");
            System.clearProperty("docs.oidc_client_id");
            System.clearProperty("docs.oidc_authorization_endpoint");
            OidcResource.resetConfigCacheForTest();
        }
    }

    // =========================================================================================
    // (e) FOUR-PART zero-config parity: with NO DB rows the accessor returns exactly the property
    //     tier, the login redirect matches a property-configured v3.3.0 build, and GET /app
    //     oidc_enabled matches the property tier. (TestOidcCallbackFlow, unchanged, covers the
    //     callback-path parity part.)
    // =========================================================================================

    @Test
    public void zeroDbConfigMatchesPropertyTier() {
        // Set ALL 12 docs.oidc_* properties so the "all 12 keys" parity assertion is exact.
        Map<OidcResource.OidcKey, String> props = new java.util.LinkedHashMap<>();
        props.put(OidcResource.OidcKey.ENABLED, "true");
        props.put(OidcResource.OidcKey.ISSUER, "https://iss.example");
        props.put(OidcResource.OidcKey.CLIENT_ID, "prop-client");
        props.put(OidcResource.OidcKey.CLIENT_SECRET, "prop-secret");
        props.put(OidcResource.OidcKey.REDIRECT_URI, "https://app.example/api/oidc/callback");
        props.put(OidcResource.OidcKey.SCOPE, "openid profile email");
        props.put(OidcResource.OidcKey.AUTHORIZATION_ENDPOINT, "https://iss.example/authorize");
        props.put(OidcResource.OidcKey.TOKEN_ENDPOINT, "https://iss.example/token");
        props.put(OidcResource.OidcKey.JWKS_URI, "https://iss.example/jwks");
        props.put(OidcResource.OidcKey.USERINFO_ENDPOINT, "https://iss.example/userinfo");
        props.put(OidcResource.OidcKey.USERNAME_CLAIM, "preferred_username");
        props.put(OidcResource.OidcKey.EMAIL_CLAIM, "email");
        for (Map.Entry<OidcResource.OidcKey, String> e : props.entrySet()) {
            System.setProperty(e.getKey().propertyName(), e.getValue());
        }
        try {
            OidcResource.resetConfigCacheForTest();

            // Part 1: the accessor returns exactly the property-tier value for all 12 keys (no DB),
            // and each key reports source=property.
            for (Map.Entry<OidcResource.OidcKey, String> e : props.entrySet()) {
                Assertions.assertEquals(e.getValue(), inTx(() -> OidcResource.oidcConfig(e.getKey())),
                        "with no DB rows the accessor must return the property value for " + e.getKey());
                Assertions.assertEquals("property", inTx(() -> OidcResource.oidcConfigSource(e.getKey())),
                        "with no DB rows every set property key must report source=property: " + e.getKey());
            }

            // Part 2: the /oidc/login redirect carries the property-configured client_id/endpoint/
            // scope/redirect_uri (state/nonce ignored) — identical to a v3.3.0 property build.
            Response resp = noRedirectTarget.path("/oidc/login").request().get();
            Assertions.assertEquals(Status.TEMPORARY_REDIRECT.getStatusCode(), resp.getStatus());
            String location = resp.getHeaderString("Location");
            Assertions.assertTrue(location.startsWith("https://iss.example/authorize"), location);
            Assertions.assertTrue(location.contains("client_id=prop-client"), location);
            Assertions.assertTrue(location.contains("scope=openid"), location);
            Assertions.assertTrue(location.contains("redirect_uri=https"), location);

            // Part 4: GET /app oidc_enabled matches the property tier.
            OidcResource.resetConfigCacheForTest();
            JsonObject app = target().path("/app").request().get(JsonObject.class);
            Assertions.assertTrue(app.getBoolean("oidc_enabled"),
                    "GET /app oidc_enabled must reflect the property tier with no DB config");
        } finally {
            for (OidcResource.OidcKey key : props.keySet()) {
                System.clearProperty(key.propertyName());
            }
            OidcResource.resetConfigCacheForTest();
        }
    }

    // =========================================================================================
    // (g) URL / claim validation
    // =========================================================================================

    @Test
    public void postValidatesUrlsAndClaims() {
        String adminToken = adminToken();

        // Non-http(s) issuer is rejected.
        Response badIssuer = postConfigRaw(adminToken, enabledForm(
                "client_secret", "s", "issuer", "ftp://iss.example"));
        Assertions.assertEquals(Status.BAD_REQUEST.getStatusCode(), badIssuer.getStatus());

        // Blank username claim is rejected.
        Response blankClaim = postConfigRaw(adminToken, enabledForm(
                "client_secret", "s", "username_claim", ""));
        Assertions.assertEquals(Status.BAD_REQUEST.getStatusCode(), blankClaim.getStatus());

        // A non-http optional endpoint is rejected.
        Response badEndpoint = postConfigRaw(adminToken, enabledForm(
                "client_secret", "s", "jwks_uri", "notaurl"));
        Assertions.assertEquals(Status.BAD_REQUEST.getStatusCode(), badEndpoint.getStatus());
    }

    @Test
    public void firstSetupRequiresAClientSecret() {
        String adminToken = adminToken();
        // No stored secret, no property secret, blank submitted, no reset -> rejected.
        Response resp = postConfigRaw(adminToken, enabledForm("client_secret", ""));
        Assertions.assertEquals(Status.BAD_REQUEST.getStatusCode(), resp.getStatus(),
                "first-time setup must require a client secret");
    }

    @Test
    public void configOidcRequiresAdmin() {
        // Anonymous GET is forbidden.
        Response resp = target().path("/app/config_oidc").request().get();
        Assertions.assertEquals(Status.FORBIDDEN.getStatusCode(), resp.getStatus());
    }

    // --- helpers ---------------------------------------------------------------------------

    /**
     * A complete valid "enabled" form. {@code overrides} REPLACE the matching default fields (a
     * {@link Form} appends duplicate params and {@code @FormParam} binds the first, so tests must
     * replace, not append) — an override value of {@code null} sends the field blank.
     */
    private static Form enabledForm(String... overrides) {
        java.util.LinkedHashMap<String, String> fields = new java.util.LinkedHashMap<>();
        fields.put("enabled", "true");
        fields.put("issuer", "https://iss.example");
        fields.put("client_id", "test-client");
        fields.put("redirect_uri", "https://app.example/api/oidc/callback");
        fields.put("scope", "openid profile email");
        fields.put("username_claim", "preferred_username");
        fields.put("email_claim", "email");
        for (int i = 0; i < overrides.length; i += 2) {
            fields.put(overrides[i], overrides[i + 1]);
        }
        Form form = new Form();
        for (Map.Entry<String, String> e : fields.entrySet()) {
            form.param(e.getKey(), e.getValue() == null ? "" : e.getValue());
        }
        return form;
    }

    private JsonObject getConfig(String adminToken) {
        return target().path("/app/config_oidc").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class);
    }

    private void postConfig(String adminToken, Form form) {
        Response resp = postConfigRaw(adminToken, form);
        Assertions.assertEquals(Status.OK.getStatusCode(), resp.getStatus(),
                "config_oidc POST should succeed, got " + resp.getStatus());
    }

    private Response postConfigRaw(String adminToken, Form form) {
        return target().path("/app/config_oidc").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(form));
    }

    private void clearOidcState() {
        inTx(() -> {
            EntityManager em = ThreadLocalContext.get().getEntityManager();
            for (OidcResource.OidcKey key : OidcResource.OidcKey.values()) {
                em.createNativeQuery("delete from T_CONFIG where CFG_ID_C = :id")
                        .setParameter("id", key.configType().name()).executeUpdate();
            }
            return null;
        });
        // Defensive: clear any docs.oidc_* property another suite may have leaked, so the DB-first
        // precedence assertions start from a known-clean process state.
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
