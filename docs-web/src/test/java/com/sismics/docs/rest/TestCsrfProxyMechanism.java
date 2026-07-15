package com.sismics.docs.rest;

import com.sismics.util.csrf.CsrfFilter;
import com.sismics.util.csrf.CsrfTokenUtil;
import com.sismics.util.filter.HeaderBasedSecurityFilter;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Form;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Trusted-header ("proxy") CSRF mechanism, report-only. Exercises the full path: the server-keyed proxy
 * token bootstrap (which seeds the {@code CSRF_PROXY_KEY} idempotently and durably on first use), the
 * {@code __Host-csrf_proxy} cookie delivery, and validation of the token submitted in the dedicated
 * {@code X-Csrf-Proxy} header. Header auth + a trusted loopback proxy are enabled by BaseJerseyTest.
 */
public class TestCsrfProxyMechanism extends BaseJerseyTest {

    @Test
    public void proxyBootstrapIssuesHostCookie() {
        clientUtil.createUser("csrf_proxy_boot");

        Response response = target().path("/document/list").request()
                .header(HeaderBasedSecurityFilter.AUTHENTICATED_USER_HEADER, "csrf_proxy_boot")
                .get();
        Assertions.assertEquals(Status.OK.getStatusCode(), response.getStatus());

        NewCookie proxyCookie = response.getCookies().get(CsrfTokenUtil.PROXY_COOKIE_NAME);
        Assertions.assertNotNull(proxyCookie,
                "a trusted-header session must be bootstrapped with a __Host-csrf_proxy cookie");
        Assertions.assertFalse(proxyCookie.getValue().isEmpty());
    }

    @Test
    public void proxyTokenInHeaderPasses() {
        clientUtil.createUser("csrf_proxy_valid");

        // First request bootstraps and returns the proxy token (seeding the key on first use).
        Response boot = target().path("/document/list").request()
                .header(HeaderBasedSecurityFilter.AUTHENTICATED_USER_HEADER, "csrf_proxy_valid")
                .get();
        String proxyToken = boot.getCookies().get(CsrfTokenUtil.PROXY_COOKIE_NAME).getValue();

        // A mutating request presenting that token in X-Csrf-Proxy must NOT be a would-block (report-only).
        CsrfFilter.resetEvaluationForTest();
        JsonObject json = target().path("/user").request()
                .header(HeaderBasedSecurityFilter.AUTHENTICATED_USER_HEADER, "csrf_proxy_valid")
                .header(CsrfTokenUtil.PROXY_HEADER_NAME, proxyToken)
                .post(Entity.form(new Form().param("email", "csrf_proxy_valid2@docs.com")), JsonObject.class);
        Assertions.assertEquals("ok", json.getString("status"));

        CsrfFilter.Evaluation eval = CsrfFilter.getLastEvaluationForTest();
        Assertions.assertNotNull(eval);
        Assertions.assertEquals("trusted-header", eval.mechanism);
        Assertions.assertFalse(eval.wouldBlock, "a valid proxy token in X-Csrf-Proxy must pass");
    }

    @Test
    public void proxyTokenlessMutationWouldBlockButSucceeds() {
        clientUtil.createUser("csrf_proxy_block");

        CsrfFilter.resetEvaluationForTest();
        JsonObject json = target().path("/user").request()
                .header(HeaderBasedSecurityFilter.AUTHENTICATED_USER_HEADER, "csrf_proxy_block")
                .post(Entity.form(new Form().param("email", "csrf_proxy_block2@docs.com")), JsonObject.class);
        Assertions.assertEquals("ok", json.getString("status"), "report-only must not reject");

        CsrfFilter.Evaluation eval = CsrfFilter.getLastEvaluationForTest();
        Assertions.assertNotNull(eval);
        Assertions.assertEquals("trusted-header", eval.mechanism);
        Assertions.assertTrue(eval.wouldBlock, "a proxy mutation with no X-Csrf-Proxy must be would-block");
        Assertions.assertEquals("token-missing", eval.reason);
    }
}
