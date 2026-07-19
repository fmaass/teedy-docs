package com.sismics.docs.rest;

import com.sismics.docs.core.dao.AuthenticationTokenDao;
import com.sismics.util.context.ThreadLocalContext;
import com.sismics.util.csrf.CsrfFilter;
import com.sismics.util.csrf.CsrfTokenUtil;
import com.sismics.util.filter.TokenBasedSecurityFilter;
import com.sismics.util.jpa.EMF;
import jakarta.json.JsonObject;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Form;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.function.Function;

/**
 * CSRF filter in report-only mode (default, enforcement OFF): it NEVER rejects, it evaluates and — on a
 * would-block — records telemetry. Also covers the H1 HEAD-side-effect guard and the H2 bootstrap.
 */
public class TestCsrfReportOnly extends BaseJerseyTest {

    @Test
    public void tokenlessMutationSucceedsButWouldBlock() {
        clientUtil.createUser("csrf_ro_tokenless");
        String token = clientUtil.login("csrf_ro_tokenless");

        CsrfFilter.resetEvaluationForTest();
        // A state-changing POST with a valid cookie but NO X-Csrf-Token header.
        JsonObject json = target().path("/user").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .post(Entity.form(new Form().param("email", "csrf_ro_tokenless2@docs.com")), JsonObject.class);
        // Report-only: the mutation SUCCEEDS.
        Assertions.assertEquals("ok", json.getString("status"));

        // ...but the filter recorded a would-block for it.
        CsrfFilter.Evaluation eval = CsrfFilter.getLastEvaluationForTest();
        Assertions.assertNotNull(eval);
        Assertions.assertEquals("/user", eval.path);
        Assertions.assertEquals("token-cookie", eval.mechanism);
        Assertions.assertTrue(eval.stateChanging);
        Assertions.assertTrue(eval.wouldBlock, "a tokenless mutation must be classified would-block");
        Assertions.assertEquals("token-missing", eval.reason);
    }

    @Test
    public void validTokenPasses() {
        clientUtil.createUser("csrf_ro_valid");
        String token = clientUtil.login("csrf_ro_valid");
        String csrf = CsrfTokenUtil.computeSessionToken(token);

        CsrfFilter.resetEvaluationForTest();
        JsonObject json = target().path("/user").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .header(CsrfTokenUtil.HEADER_NAME, csrf)
                .post(Entity.form(new Form().param("email", "csrf_ro_valid2@docs.com")), JsonObject.class);
        Assertions.assertEquals("ok", json.getString("status"));

        CsrfFilter.Evaluation eval = CsrfFilter.getLastEvaluationForTest();
        Assertions.assertNotNull(eval);
        Assertions.assertFalse(eval.wouldBlock, "a valid X-Csrf-Token must NOT be would-block");
    }

    @Test
    public void bootstrapIssuesCsrfCookieWhenAbsent() {
        clientUtil.createUser("csrf_ro_bootstrap");
        String token = clientUtil.login("csrf_ro_bootstrap");
        String expected = CsrfTokenUtil.computeSessionToken(token);

        // A safe, authenticated request carrying ONLY the auth_token cookie (no csrf_token).
        Response response = target().path("/document/list").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .get();
        Assertions.assertEquals(Status.OK.getStatusCode(), response.getStatus());

        NewCookie csrfCookie = response.getCookies().get(CsrfTokenUtil.SESSION_COOKIE_NAME);
        Assertions.assertNotNull(csrfCookie, "the bootstrap must issue a csrf_token cookie on the response");
        Assertions.assertEquals(expected, csrfCookie.getValue(),
                "the bootstrapped cookie must carry the derived session token");
        Assertions.assertFalse(csrfCookie.isHttpOnly(), "the csrf_token cookie must be JS-readable");
    }

    @Test
    public void headOnUserDoesNotUpdateLastConnection() {
        clientUtil.createUser("csrf_ro_headuser");
        String token = clientUtil.login("csrf_ro_headuser");

        // Precondition: a freshly logged-in session has no lastConnectionDate yet.
        Date before = readDb(em -> new AuthenticationTokenDao().get(token).getLastConnectionDate());
        Assertions.assertNull(before, "precondition: lastConnectionDate starts null");

        CsrfFilter.resetEvaluationForTest();
        Response response = target().path("/user").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .head();
        Assertions.assertEquals(Status.OK.getStatusCode(), response.getStatus());

        // The HEAD must NOT have written the short-session expiry clock.
        Date after = readDb(em -> new AuthenticationTokenDao().get(token).getLastConnectionDate());
        Assertions.assertNull(after, "a HEAD to /user must NOT refresh lastConnectionDate");

        // ...and the filter classified the HEAD as state-changing (H1 defense-in-depth telemetry).
        CsrfFilter.Evaluation eval = CsrfFilter.getLastEvaluationForTest();
        Assertions.assertNotNull(eval);
        Assertions.assertEquals("/user", eval.path);
        Assertions.assertEquals("HEAD", eval.method);
        Assertions.assertTrue(eval.stateChanging, "HEAD on a mutating-GET path must be state-changing");
    }

    @Test
    public void headOnExportDoesNotAudit() {
        clientUtil.createUser("csrf_ro_headexport");
        String token = clientUtil.login("csrf_ro_headexport");

        long before = countExportAuditRows();

        CsrfFilter.resetEvaluationForTest();
        Response response = target().path("/document/export").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .head();
        Assertions.assertEquals(Status.OK.getStatusCode(), response.getStatus());

        // The HEAD short-circuited before the audit write / permit acquisition.
        long after = countExportAuditRows();
        Assertions.assertEquals(before, after, "a HEAD to /document/export must NOT write an audit row");

        CsrfFilter.Evaluation eval = CsrfFilter.getLastEvaluationForTest();
        Assertions.assertNotNull(eval);
        Assertions.assertEquals("/document/export", eval.path);
        Assertions.assertTrue(eval.stateChanging);
    }

    private long countExportAuditRows() {
        return readDb(em -> ((Number) em.createNativeQuery(
                "select count(*) from T_AUDIT_LOG where LOG_CLASSENTITY_C = 'Export'")
                .getSingleResult()).longValue());
    }

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
