package com.sismics.docs.rest;

import com.sismics.util.context.ThreadLocalContext;
import com.sismics.util.filter.HeaderBasedSecurityFilter;
import com.sismics.util.filter.TokenBasedSecurityFilter;
import com.sismics.util.jpa.EMF;
import jakarta.json.JsonObject;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Form;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.function.Function;

/**
 * Credential precedence (explicit-scheme-first) + credential-conflict rejection with a durable audit row.
 *
 * <p>These pin the NEW rule (the characterization of the pre-change fall-through behaviour lives in the
 * phase report): an invalid {@code tdapi_*} explicit credential is a controlled 401 that terminates the
 * chain (even alongside a valid cookie), and two valid credentials resolving to DIFFERENT principals are
 * rejected (401) with an audit row persisted through an independent post-rollback transaction.</p>
 */
public class TestCsrfPrecedence extends BaseJerseyTest {

    @Test
    public void invalidApiKeyAloneIsUnauthorized() {
        Response response = target().path("/document/list").request()
                .header("Authorization", "Bearer tdapi_deadbeefdeadbeefdeadbeef")
                .get();
        Assertions.assertEquals(Status.UNAUTHORIZED.getStatusCode(), response.getStatus(),
                "an invalid tdapi_* key must be a 401, not a silent fall-through to anonymous");
    }

    @Test
    public void cookieWithInvalidApiKeyIsUnauthorized() {
        clientUtil.createUser("csrf_prec_cookie");
        String token = clientUtil.login("csrf_prec_cookie");

        // A valid cookie is present, but the explicit (invalid) api key wins precedence and 401s.
        Response response = target().path("/document/list").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .header("Authorization", "Bearer tdapi_deadbeefdeadbeefdeadbeef")
                .get();
        Assertions.assertEquals(Status.UNAUTHORIZED.getStatusCode(), response.getStatus(),
                "cookie + invalid tdapi_* must be a 401 (api-key precedence), not cookie-auth");
    }

    @Test
    public void lowercaseSchemeInvalidApiKeyWithCookieIsUnauthorized() {
        clientUtil.createUser("csrf_prec_lc");
        String token = clientUtil.login("csrf_prec_lc");

        // Lowercase auth-scheme with an invalid tdapi_* key + a valid cookie: the scheme is
        // case-insensitive, so this is still an explicit (invalid) api-key credential -> 401, not cookie auth.
        Response response = target().path("/document/list").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .header("Authorization", "bearer tdapi_deadbeefdeadbeefdeadbeef")
                .get();
        Assertions.assertEquals(Status.UNAUTHORIZED.getStatusCode(), response.getStatus(),
                "a lowercase-scheme invalid tdapi_* key must be a 401, not a cookie fall-through");
    }

    @Test
    public void sameUserApiKeyAndCookieSucceeds() {
        clientUtil.createUser("csrf_prec_same");
        String token = clientUtil.login("csrf_prec_same");

        // Create an API key for the SAME user.
        JsonObject keyJson = target().path("/apikey").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .put(Entity.form(new Form().param("name", "same")), JsonObject.class);
        String rawKey = keyJson.getString("key");

        // Both credentials resolve to the same principal: no conflict.
        Response response = target().path("/document/list").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .header("Authorization", "Bearer " + rawKey)
                .get();
        Assertions.assertEquals(Status.OK.getStatusCode(), response.getStatus(),
                "api-key + cookie for the SAME user must succeed");
    }

    @Test
    public void conflictingCredentialsAreRejectedAndAudited() {
        String adminToken = adminToken();

        // Admin's API key.
        JsonObject keyJson = target().path("/apikey").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form().param("name", "conflict")), JsonObject.class);
        String adminKey = keyJson.getString("key");

        // A different user's cookie.
        clientUtil.createUser("csrf_prec_conflict");
        String otherToken = clientUtil.login("csrf_prec_conflict");

        long auditBefore = countAuthenticationAuditRows();

        // api-key installs admin (mechanism api-key, runs first); the cookie resolves to a DIFFERENT
        // principal -> conflict -> 401.
        Response response = target().path("/document/list").request()
                .header("Authorization", "Bearer " + adminKey)
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, otherToken)
                .get();
        Assertions.assertEquals(Status.UNAUTHORIZED.getStatusCode(), response.getStatus(),
                "conflicting valid credentials for different principals must be rejected (401)");

        // The audit row must be persisted through the independent post-rollback transaction even though
        // the 4xx rolled the request transaction back.
        long auditAfter = countAuthenticationAuditRows();
        Assertions.assertEquals(auditBefore + 1, auditAfter,
                "a credential conflict must durably persist exactly one AUTHENTICATION audit row across the rollback");
    }

    @Test
    public void trustedHeaderConflictingWithCookieIsRejectedAndAudited() {
        // A cookie for user B (installs mechanism token-cookie), plus a trusted-proxy X-Authenticated-User
        // header naming user A. The header filter (last) resolves a DIFFERENT principal -> conflict -> 401.
        clientUtil.createUser("csrf_prec_hdr_a");
        clientUtil.createUser("csrf_prec_hdr_b");
        String bToken = clientUtil.login("csrf_prec_hdr_b");

        long auditBefore = countAuthenticationAuditRows();

        Response response = target().path("/document/list").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, bToken)
                .header(HeaderBasedSecurityFilter.AUTHENTICATED_USER_HEADER, "csrf_prec_hdr_a")
                .get();
        Assertions.assertEquals(Status.UNAUTHORIZED.getStatusCode(), response.getStatus(),
                "a trusted header resolving to a different principal than the cookie must be rejected (401)");

        long auditAfter = countAuthenticationAuditRows();
        Assertions.assertEquals(auditBefore + 1, auditAfter,
                "the trusted-header credential conflict must durably persist one AUTHENTICATION audit row");
    }

    /**
     * Counts durable AUTHENTICATION audit rows using a fresh entity manager (independent of any request).
     */
    private long countAuthenticationAuditRows() {
        return readDb(em -> ((Number) em.createNativeQuery(
                "select count(*) from T_AUDIT_LOG where LOG_TYPE_C = 'AUTHENTICATION'")
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
