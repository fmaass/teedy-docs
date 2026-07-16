package com.sismics.docs.rest;

import com.sismics.docs.core.dao.UserDao;
import com.sismics.docs.core.model.jpa.User;
import com.sismics.docs.core.util.TransactionUtil;
import com.sismics.util.context.ThreadLocalContext;
import com.sismics.util.filter.TokenBasedSecurityFilter;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Form;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * End-to-end credential-epoch behaviour through the REAL security filters (Grizzly + Jersey, every
 * request commits). Exercises the proof-time stamp at mint and the exact-equality validation on the
 * next request. Runs on H2 (the {@code test} job) and on real PostgreSQL (the {@code test-web-postgres}
 * CI job).
 */
public class TestCredentialEpoch extends BaseJerseyTest {

    /** Bumps a user's credential epoch directly (the lifecycle wiring lands in Phase 2). */
    private void bumpEpoch(String username) {
        TransactionUtil.handle(() -> {
            User user = new UserDao().getActiveByUsername(username);
            new UserDao().bumpCredentialEpoch(user.getId());
        });
    }

    /** Overwrites a user's most recent credential stamps directly, for the fail-closed above-epoch test. */
    private void forceStampsAbove(String username) {
        TransactionUtil.handle(() -> {
            User user = new UserDao().getActiveByUsername(username);
            ThreadLocalContext.get().getEntityManager()
                    .createNativeQuery("update T_AUTHENTICATION_TOKEN set AUT_CREDENTIALEPOCH_N = 999 where AUT_IDUSER_C = :id")
                    .setParameter("id", user.getId()).executeUpdate();
            ThreadLocalContext.get().getEntityManager()
                    .createNativeQuery("update T_API_KEY set APK_CREDENTIALEPOCH_N = 999 where APK_IDUSER_C = :id")
                    .setParameter("id", user.getId()).executeUpdate();
        });
    }

    /** GET /apikey requires a non-anonymous principal — a clean auth probe (200 = authenticated, 403 = anonymous). */
    private int probeWithCookie(String token) {
        return target().path("/apikey").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token).get().getStatus();
    }

    private int probeWithApiKey(String rawKey) {
        return target().path("/apikey").request()
                .header("Authorization", "Bearer " + rawKey).get().getStatus();
    }

    private String createApiKey(String token) {
        JsonObject json = target().path("/apikey").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .put(Entity.form(new Form().param("name", "epoch key")), JsonObject.class);
        return json.getString("key");
    }

    @Test
    public void sessionAndKeyDieAfterCredentialEpochBumpThenFreshCredentialsWork() {
        String adminToken = adminToken();
        String rawKey = createApiKey(adminToken);

        // Equal-epoch: both the session and the freshly-minted key authenticate.
        Assertions.assertEquals(200, probeWithCookie(adminToken));
        Assertions.assertEquals(200, probeWithApiKey(rawKey));

        // A credential-invalidating event bumps the epoch.
        bumpEpoch("admin");

        // Both the pre-bump session and the pre-bump key are now dead (stamp below the user's epoch).
        Assertions.assertEquals(403, probeWithCookie(adminToken), "the pre-bump session must be revoked");
        Assertions.assertEquals(403, probeWithApiKey(rawKey), "the pre-bump API key must be revoked");

        // A fresh login stamps the new epoch and works; a fresh key minted under it works too.
        String freshToken = adminToken();
        Assertions.assertEquals(200, probeWithCookie(freshToken), "a post-bump session must work");
        String freshKey = createApiKey(freshToken);
        Assertions.assertEquals(200, probeWithApiKey(freshKey), "a post-bump API key must work");
    }

    @Test
    public void aboveEpochCredentialsAreRejectedFailClosed() {
        String adminToken = adminToken();
        String rawKey = createApiKey(adminToken);
        Assertions.assertEquals(200, probeWithCookie(adminToken));
        Assertions.assertEquals(200, probeWithApiKey(rawKey));

        // A corrupt/future stamp above the user's current epoch must fail closed, not survive.
        forceStampsAbove("admin");
        Assertions.assertEquals(403, probeWithCookie(adminToken), "an above-epoch session must be rejected");
        Assertions.assertEquals(403, probeWithApiKey(rawKey), "an above-epoch API key must be rejected");
    }

    @Test
    public void guestCannotMintApiKey() {
        String adminToken = adminToken();

        // Enable guest login.
        target().path("/app/guest_login").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form().param("enabled", "true")), JsonObject.class);

        String guestToken = clientUtil.login("guest", "", false);

        Response response = target().path("/apikey").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, guestToken)
                .put(Entity.form(new Form().param("name", "guest key")));
        Assertions.assertEquals(403, response.getStatus(), "a guest must not mint a durable API key");
    }
}
