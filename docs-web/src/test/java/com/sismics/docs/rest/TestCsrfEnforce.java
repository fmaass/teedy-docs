package com.sismics.docs.rest;

import com.sismics.util.csrf.CsrfFilter;
import com.sismics.util.csrf.CsrfTokenUtil;
import com.sismics.util.filter.HeaderBasedSecurityFilter;
import com.sismics.util.filter.TokenBasedSecurityFilter;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Form;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * CSRF filter with enforcement ON ({@code docs.csrf_enforce=true}): the same evaluation the report-only
 * mode records as a would-block now actually rejects the request with 403 BEFORE the handler runs, while a
 * correctly-mirrored token is accepted. Covers both ambient mechanisms — the session token-cookie path
 * (Teedy/OIDC/LDAP sessions) and the trusted-header proxy path — and confirms the bootstrap cookie is still
 * issued on a rejected response so the client's retry-once (H3) has a fresh token to read.
 *
 * <p>Enforcement is scoped with {@link #withEnforcement} to ONLY the request under test: the arrange phase
 * (create/login helpers, which are authenticated state-changing calls that legitimately carry no CSRF token)
 * runs in the default report-only mode, so it is not itself rejected. The flag is a JVM system property
 * saved and restored around the block so it never leaks into the report-only tests, which encode the
 * default-OFF contract.</p>
 */
public class TestCsrfEnforce extends BaseJerseyTest {

    private static final String ENFORCE_PROPERTY = "docs.csrf_enforce";

    /**
     * Runs {@code body} with CSRF enforcement forced ON, restoring the prior flag value afterwards. The
     * filter reads the property per-request, so this toggles enforcement for exactly the requests issued
     * inside the block — not the report-only arrange phase before it.
     */
    private void withEnforcement(Runnable body) {
        String previous = System.getProperty(ENFORCE_PROPERTY);
        System.setProperty(ENFORCE_PROPERTY, "true");
        try {
            body.run();
        } finally {
            if (previous == null) {
                System.clearProperty(ENFORCE_PROPERTY);
            } else {
                System.setProperty(ENFORCE_PROPERTY, previous);
            }
        }
    }

    @Test
    public void tokenlessMutationRejected() {
        clientUtil.createUser("csrf_enf_tokenless");
        String token = clientUtil.login("csrf_enf_tokenless");

        withEnforcement(() -> {
            CsrfFilter.resetEvaluationForTest();
            // A state-changing POST with a valid session cookie but NO X-Csrf-Token header.
            Response response = target().path("/user").request()
                    .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                    .post(Entity.form(new Form().param("email", "csrf_enf_tokenless2@docs.com")));
            // Enforcement ON: the mutation is REJECTED before the handler runs.
            Assertions.assertEquals(Status.FORBIDDEN.getStatusCode(), response.getStatus(),
                    "a tokenless mutation must be rejected with 403 when enforcement is ON");
            String body = response.readEntity(String.class);
            Assertions.assertTrue(body.contains("\"type\":\"CsrfError\""),
                    "the 403 body must carry the machine-readable CsrfError signal, was: " + body);

            // The bootstrap ran before the block, so the rejected response still re-sets the expected cookie
            // for the client's retry-once (H3) to read.
            NewCookie csrfCookie = response.getCookies().get(CsrfTokenUtil.SESSION_COOKIE_NAME);
            Assertions.assertNotNull(csrfCookie, "a rejected response must still bootstrap the csrf_token cookie");
            Assertions.assertEquals(CsrfTokenUtil.computeSessionToken(token), csrfCookie.getValue());

            CsrfFilter.Evaluation eval = CsrfFilter.getLastEvaluationForTest();
            Assertions.assertNotNull(eval);
            Assertions.assertEquals("token-cookie", eval.mechanism);
            Assertions.assertTrue(eval.wouldBlock);
            Assertions.assertEquals("token-missing", eval.reason);
        });
    }

    @Test
    public void validTokenAccepted() {
        clientUtil.createUser("csrf_enf_valid");
        String token = clientUtil.login("csrf_enf_valid");
        String csrf = CsrfTokenUtil.computeSessionToken(token);

        withEnforcement(() -> {
            CsrfFilter.resetEvaluationForTest();
            JsonObject json = target().path("/user").request()
                    .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                    .header(CsrfTokenUtil.HEADER_NAME, csrf)
                    .post(Entity.form(new Form().param("email", "csrf_enf_valid2@docs.com")), JsonObject.class);
            // Enforcement ON: a correctly-mirrored token is accepted and the mutation succeeds.
            Assertions.assertEquals("ok", json.getString("status"));

            CsrfFilter.Evaluation eval = CsrfFilter.getLastEvaluationForTest();
            Assertions.assertNotNull(eval);
            Assertions.assertFalse(eval.wouldBlock, "a valid X-Csrf-Token must be accepted under enforcement");
        });
    }

    @Test
    public void safeGetNotRejected() {
        clientUtil.createUser("csrf_enf_safeget");
        String token = clientUtil.login("csrf_enf_safeget");

        withEnforcement(() -> {
            // A non-state-changing GET carrying no CSRF token must not be rejected even under enforcement.
            Response response = target().path("/document/list").request()
                    .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                    .get();
            Assertions.assertEquals(Status.OK.getStatusCode(), response.getStatus(),
                    "a safe GET must never be rejected by CSRF enforcement");
        });
    }

    /**
     * The saved-filter update route (POST /savedfilter/{id}) is CSRF-evaluated with no per-route
     * registration: {@link CsrfFilter#isStateChanging} classifies EVERY method outside
     * {GET, HEAD, OPTIONS} as state-changing, so a new POST is covered the moment it exists — which
     * is exactly why {@code TestCsrfGetInventory} only enumerates {@code @GET} routes. This test is
     * the executable proof of that auto-coverage claim for the new endpoint: the token-less update is
     * rejected BEFORE the handler runs, so the filter is not persisted.
     */
    @Test
    public void savedFilterUpdateIsCsrfEvaluated() {
        clientUtil.createUser("csrf_enf_savedfilter");
        String token = clientUtil.login("csrf_enf_savedfilter");

        // Arrange in the default report-only mode: a filter to attempt an update against.
        JsonObject created = target().path("/savedfilter").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .put(Entity.form(new Form().param("name", "Csrf target").param("query", "search=a")), JsonObject.class);
        String id = created.getString("id");

        withEnforcement(() -> {
            CsrfFilter.resetEvaluationForTest();
            Response response = target().path("/savedfilter/" + id).request()
                    .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                    .post(Entity.form(new Form().param("name", "Renamed by forgery").param("query", "search=b")));
            Assertions.assertEquals(Status.FORBIDDEN.getStatusCode(), response.getStatus(),
                    "a token-less saved-filter update must be rejected with 403 when enforcement is ON");

            CsrfFilter.Evaluation eval = CsrfFilter.getLastEvaluationForTest();
            Assertions.assertNotNull(eval, "the POST must have been CSRF-evaluated");
            Assertions.assertEquals("/savedfilter/" + id, eval.path);
            Assertions.assertEquals("POST", eval.method);
            Assertions.assertTrue(eval.stateChanging, "a POST is state-changing without any route registration");
            Assertions.assertTrue(eval.wouldBlock);
            Assertions.assertEquals("token-missing", eval.reason);
        });

        // The rejection happened BEFORE the handler: the filter is unchanged.
        JsonObject stored = target().path("/savedfilter").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .get(JsonObject.class)
                .getJsonArray("saved_filters").getJsonObject(0);
        Assertions.assertEquals("Csrf target", stored.getString("name"));
        Assertions.assertEquals("search=a", stored.getString("query"));
    }

    @Test
    public void proxyTokenlessMutationRejected() {
        clientUtil.createUser("csrf_enf_proxy_block");

        withEnforcement(() -> {
            CsrfFilter.resetEvaluationForTest();
            Response response = target().path("/user").request()
                    .header(HeaderBasedSecurityFilter.AUTHENTICATED_USER_HEADER, "csrf_enf_proxy_block")
                    .post(Entity.form(new Form().param("email", "csrf_enf_proxy_block2@docs.com")));
            Assertions.assertEquals(Status.FORBIDDEN.getStatusCode(), response.getStatus(),
                    "a proxy mutation with no X-Csrf-Proxy must be rejected under enforcement");

            // The proxy bootstrap ran before the block, so a fresh __Host-csrf_proxy cookie is available.
            NewCookie proxyCookie = response.getCookies().get(CsrfTokenUtil.PROXY_COOKIE_NAME);
            Assertions.assertNotNull(proxyCookie, "a rejected proxy response must still bootstrap the proxy cookie");

            CsrfFilter.Evaluation eval = CsrfFilter.getLastEvaluationForTest();
            Assertions.assertNotNull(eval);
            Assertions.assertEquals("trusted-header", eval.mechanism);
            Assertions.assertTrue(eval.wouldBlock);
            Assertions.assertEquals("token-missing", eval.reason);
        });
    }

    @Test
    public void proxyValidTokenAccepted() {
        clientUtil.createUser("csrf_enf_proxy_valid");

        // Bootstrap the proxy token via a safe GET (seeds the server key on first use); report-only is fine here.
        Response boot = target().path("/document/list").request()
                .header(HeaderBasedSecurityFilter.AUTHENTICATED_USER_HEADER, "csrf_enf_proxy_valid")
                .get();
        String proxyToken = boot.getCookies().get(CsrfTokenUtil.PROXY_COOKIE_NAME).getValue();

        withEnforcement(() -> {
            CsrfFilter.resetEvaluationForTest();
            JsonObject json = target().path("/user").request()
                    .header(HeaderBasedSecurityFilter.AUTHENTICATED_USER_HEADER, "csrf_enf_proxy_valid")
                    .header(CsrfTokenUtil.PROXY_HEADER_NAME, proxyToken)
                    .post(Entity.form(new Form().param("email", "csrf_enf_proxy_valid2@docs.com")), JsonObject.class);
            Assertions.assertEquals("ok", json.getString("status"));

            CsrfFilter.Evaluation eval = CsrfFilter.getLastEvaluationForTest();
            Assertions.assertNotNull(eval);
            Assertions.assertEquals("trusted-header", eval.mechanism);
            Assertions.assertFalse(eval.wouldBlock, "a valid proxy token in X-Csrf-Proxy must be accepted under enforcement");
        });
    }
}
