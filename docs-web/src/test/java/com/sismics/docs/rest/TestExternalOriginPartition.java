package com.sismics.docs.rest;

import com.sismics.docs.core.dao.ApiKeyDao;
import com.sismics.docs.core.dao.UserDao;
import com.sismics.docs.core.model.jpa.User;
import com.sismics.docs.core.util.TransactionUtil;
import com.sismics.util.filter.TokenBasedSecurityFilter;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Form;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * End-to-end tests of the external-origin auth partition through the REAL security filters (Grizzly +
 * Jersey). An account provisioned by an external identity provider (OIDC or LDAP) must never be able to use a
 * local password — even a known/recovered one — nor mint a durable local API key.
 *
 * <p>Each test creates an ordinary internal account (whose local password is therefore known — modelling a
 * password planted through recovery), obtains a valid session while it is still internal, then stamps an
 * external origin onto it directly. Runs on H2 (the {@code test} job) and on real PostgreSQL (the
 * {@code test-web-postgres} CI job).</p>
 */
public class TestExternalOriginPartition extends BaseJerseyTest {

    /** Stamps OIDC origin (issuer+subject) onto an existing account, without bumping its epoch. */
    private void makeOidcOrigin(String username) {
        TransactionUtil.handle(() -> {
            User user = new UserDao().getActiveByUsername(username);
            user.setOidcIssuer("https://idp.example.com");
            user.setOidcSubject("sub-" + username);
        });
    }

    /** Stamps LDAP origin onto an existing account, without bumping its epoch. */
    private void makeLdapOrigin(String username) {
        TransactionUtil.handle(() -> new UserDao().getActiveByUsername(username).setLdap(true));
    }

    /** Number of API keys currently persisted for an account (any epoch). */
    private int apiKeyCount(String username) {
        int[] count = new int[1];
        TransactionUtil.handle(() -> {
            User user = new UserDao().getActiveByUsername(username);
            count[0] = new ApiKeyDao().getByUserId(user.getId()).size();
        });
        return count[0];
    }

    private int loginStatus(String username, String password) {
        return target().path("/user/login").request()
                .post(Entity.form(new Form().param("username", username).param("password", password)))
                .getStatus();
    }

    @Test
    public void oidcOriginAccountCannotLoginWithLocalPassword() {
        clientUtil.createUser("origin_login_user");
        // Internal account logs in fine before the transition.
        Assertions.assertEquals(200, loginStatus("origin_login_user", "Test1234"));

        makeOidcOrigin("origin_login_user");

        // With OIDC origin set, the same (valid) local password is refused by internal auth.
        Assertions.assertEquals(403, loginStatus("origin_login_user", "Test1234"),
                "an OIDC-origin account must not authenticate with a local password");
    }

    @Test
    public void oidcOriginAccountCannotDisableTotpWithRecoveredPassword() {
        clientUtil.createUser("origin_totp_user");
        String token = clientUtil.login("origin_totp_user");
        makeOidcOrigin("origin_totp_user");

        // The session is still valid (no epoch bump), but disable_totp reauthenticates the local password
        // through the origin-aware handler chain, which refuses the OIDC-origin account.
        Response response = target().path("/user/disable_totp").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .post(Entity.form(new Form().param("password", "Test1234")));
        Assertions.assertEquals(403, response.getStatus(),
                "a recovered local password on an OIDC-origin account must not disable TOTP");
    }

    @Test
    public void oidcOriginAccountCannotMintApiKeyAndLeavesNoSideEffect() {
        clientUtil.createUser("origin_oidc_apikey_user");
        String token = clientUtil.login("origin_oidc_apikey_user");
        makeOidcOrigin("origin_oidc_apikey_user");

        Response response = target().path("/apikey").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .put(Entity.form(new Form().param("name", "should be refused")));
        Assertions.assertEquals(403, response.getStatus(), "an OIDC-origin account must not mint an API key");
        Assertions.assertEquals(0, apiKeyCount("origin_oidc_apikey_user"),
                "a refused mint must leave no API key row");
    }

    @Test
    public void ldapOriginAccountCannotMintApiKeyAndLeavesNoSideEffect() {
        clientUtil.createUser("origin_ldap_apikey_user");
        String token = clientUtil.login("origin_ldap_apikey_user");
        makeLdapOrigin("origin_ldap_apikey_user");

        Response response = target().path("/apikey").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .put(Entity.form(new Form().param("name", "should be refused")));
        Assertions.assertEquals(403, response.getStatus(), "an LDAP-origin account must not mint an API key");
        Assertions.assertEquals(0, apiKeyCount("origin_ldap_apikey_user"),
                "a refused mint must leave no API key row");
    }

    @Test
    public void internalAccountCanStillMintApiKey() {
        clientUtil.createUser("origin_internal_apikey_user");
        String token = clientUtil.login("origin_internal_apikey_user");

        JsonObject json = target().path("/apikey").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .put(Entity.form(new Form().param("name", "internal key")), JsonObject.class);
        Assertions.assertTrue(json.getString("key").startsWith("tdapi_"),
                "an internal account must still mint an API key");
        Assertions.assertEquals(1, apiKeyCount("origin_internal_apikey_user"));
    }
}
