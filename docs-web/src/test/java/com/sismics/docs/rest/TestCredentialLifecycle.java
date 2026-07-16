package com.sismics.docs.rest;

import com.sismics.docs.core.dao.ApiKeyDao;
import com.sismics.docs.core.dao.AuthenticationTokenDao;
import com.sismics.docs.core.dao.PasswordRecoveryDao;
import com.sismics.docs.core.dao.UserDao;
import com.sismics.docs.core.model.jpa.ApiKey;
import com.sismics.docs.core.model.jpa.AuthenticationToken;
import com.sismics.docs.core.model.jpa.PasswordRecovery;
import com.sismics.docs.core.model.jpa.User;
import com.sismics.docs.core.util.CredentialLifecycleUtil;
import com.sismics.docs.core.util.TransactionUtil;
import com.sismics.util.filter.ApiKeyBasedSecurityFilter;
import com.sismics.util.filter.TokenBasedSecurityFilter;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Form;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.UUID;

/**
 * End-to-end credential-lifecycle wiring through the REAL security filters (Grizzly + Jersey, every
 * request commits): the uniform epoch bump at reset / admin-change / disable / self-change, the
 * extension of revocation to API keys, and the #111 self-delete guard at the endpoint. Each assertion
 * fails against pre-P2 code (which bumped the epoch nowhere and let a re-enabled account resurrect its
 * old credentials). Runs on H2 and on real PostgreSQL.
 */
public class TestCredentialLifecycle extends BaseJerseyTest {

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
                .put(Entity.form(new Form().param("name", "lifecycle key")), JsonObject.class);
        return json.getString("key");
    }

    /** Seeds a single-use password-recovery key for a username directly, returning the raw key token. */
    private String seedRecoveryKey(String username) {
        String[] out = new String[1];
        TransactionUtil.handle(() -> {
            PasswordRecovery pr = new PasswordRecovery();
            pr.setUsername(username);
            out[0] = new PasswordRecoveryDao().create(pr);
        });
        return out[0];
    }

    private String userId(String username) {
        String[] id = new String[1];
        TransactionUtil.handle(() -> id[0] = new UserDao().getActiveByUsername(username).getId());
        return id[0];
    }

    private long currentEpoch(String username) {
        long[] epoch = new long[1];
        TransactionUtil.handle(() -> epoch[0] = CredentialLifecycleUtil.currentEpoch(
                new UserDao().getActiveByUsername(username).getId()));
        return epoch[0];
    }

    /**
     * Directly inserts an authentication token stamped {@code epoch}, simulating an in-flight login's row
     * landing AFTER an earlier token wipe. Returns the token id (the cookie value).
     */
    private String insertTokenStampedEpoch(String username, long epoch) {
        String[] tokenId = new String[1];
        TransactionUtil.handle(() -> {
            User user = new UserDao().getActiveByUsername(username);
            tokenId[0] = new AuthenticationTokenDao().create(new AuthenticationToken()
                    .setUserId(user.getId()).setLongLasted(false).setCredentialEpoch(epoch));
        });
        return tokenId[0];
    }

    /** Directly inserts an API key stamped {@code epoch}; returns the raw bearer token. */
    private String insertApiKeyStampedEpoch(String username, long epoch) {
        String rawKey = "tdapi_" + UUID.randomUUID().toString().replace("-", "");
        TransactionUtil.handle(() -> {
            User user = new UserDao().getActiveByUsername(username);
            ApiKey apiKey = new ApiKey();
            apiKey.setUserId(user.getId());
            apiKey.setName("in-flight key");
            apiKey.setKeyHash(ApiKeyBasedSecurityFilter.sha256Hex(rawKey));
            apiKey.setPrefix(rawKey.substring(0, 12));
            apiKey.setCredentialEpoch(epoch);
            new ApiKeyDao().create(apiKey);
        });
        return rawKey;
    }

    private int loginStatus(String username, String password) {
        return target().path("/user/login").request()
                .post(Entity.form(new Form()
                        .param("username", username).param("password", password).param("remember", "false")))
                .getStatus();
    }

    // (a) #96 + (b) #97 — a password reset revokes the pre-reset session AND API key.
    @Test
    public void passwordResetRevokesSessionsAndApiKeys() {
        clientUtil.createUser("clc_reset");
        String token = clientUtil.login("clc_reset");
        String rawKey = createApiKey(token);
        Assertions.assertEquals(200, probeWithCookie(token));
        Assertions.assertEquals(200, probeWithApiKey(rawKey));

        String key = seedRecoveryKey("clc_reset");
        Response r = target().path("/user/password_reset").request()
                .post(Entity.form(new Form().param("key", key).param("password", "NewReset123")));
        Assertions.assertEquals(200, r.getStatus());

        Assertions.assertEquals(403, probeWithCookie(token), "the pre-reset session must be revoked (#96)");
        Assertions.assertEquals(403, probeWithApiKey(rawKey), "the pre-reset API key must be revoked (#97)");

        String freshToken = clientUtil.login("clc_reset", "NewReset123", false);
        Assertions.assertEquals(200, probeWithApiKey(createApiKey(freshToken)), "a post-reset key works");
    }

    // (a) #96 race arm — an in-flight login's credential that LANDS AFTER the reset's token wipe but carries
    // the pre-reset proof-time epoch must still be rejected. deleteAllByUserId cannot catch this row (it ran
    // before the row existed); ONLY the epoch check does, so this is the true #96 discriminator.
    @Test
    public void resetRejectsInFlightCredentialStampedAtTheOldEpoch() {
        clientUtil.createUser("clc_race96"); // fresh user: epoch 0 is the proof-time epoch a login would stamp

        // A recovery reset commits end-to-end: token wipe + epoch 0 -> 1 + new password.
        String key = seedRecoveryKey("clc_race96");
        Response r = target().path("/user/password_reset").request()
                .post(Entity.form(new Form().param("key", key).param("password", "NewReset123")));
        Assertions.assertEquals(200, r.getStatus());
        Assertions.assertEquals(1L, currentEpoch("clc_race96"), "the reset advanced the epoch");

        // Now the in-flight login's rows land AFTER the wipe, stamped the pre-reset epoch 0. Prove BOTH the
        // token-cookie filter and the api-key filter reject them end-to-end.
        String staleToken = insertTokenStampedEpoch("clc_race96", 0L);
        String staleRawKey = insertApiKeyStampedEpoch("clc_race96", 0L);
        Assertions.assertEquals(403, probeWithCookie(staleToken),
                "a session that landed post-reset but stamped the pre-reset epoch is rejected (#96)");
        Assertions.assertEquals(403, probeWithApiKey(staleRawKey),
                "an API key stamped the pre-reset epoch is rejected (#96/#97)");
    }

    // (c) #108 — an admin password change revokes the target's session AND API key.
    @Test
    public void adminPasswordChangeRevokesTargetSessionsAndApiKeys() {
        clientUtil.createUser("clc_admpw");
        String token = clientUtil.login("clc_admpw");
        String rawKey = createApiKey(token);
        Assertions.assertEquals(200, probeWithApiKey(rawKey));

        String adminToken = adminToken();
        Response r = target().path("/user/clc_admpw").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form().param("password", "AdminSet1234")));
        Assertions.assertEquals(200, r.getStatus());

        Assertions.assertEquals(403, probeWithCookie(token), "an admin password change revokes the session");
        Assertions.assertEquals(403, probeWithApiKey(rawKey), "an admin password change revokes the API key (#108)");
    }

    // (d) #110 — disable revokes the session AND key, and they STAY dead across a re-enable.
    @Test
    public void disableRevokesAndReenableDoesNotResurrect() {
        clientUtil.createUser("clc_disable");
        String token = clientUtil.login("clc_disable");
        String rawKey = createApiKey(token);
        Assertions.assertEquals(200, probeWithCookie(token));
        Assertions.assertEquals(200, probeWithApiKey(rawKey));

        String adminToken = adminToken();
        target().path("/user/clc_disable").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form().param("disabled", "true")), JsonObject.class);
        Assertions.assertEquals(403, probeWithCookie(token), "a disabled user's session is dead");
        Assertions.assertEquals(403, probeWithApiKey(rawKey), "a disabled user's API key is dead");

        target().path("/user/clc_disable").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form().param("disabled", "false")), JsonObject.class);
        Assertions.assertEquals(403, probeWithCookie(token), "re-enable must NOT resurrect the pre-disable session (#110)");
        Assertions.assertEquals(403, probeWithApiKey(rawKey), "re-enable must NOT resurrect the pre-disable API key (#110)");
    }

    // (e) A self password-change revokes the user's OWN API keys.
    @Test
    public void selfPasswordChangeRevokesOwnApiKeys() {
        clientUtil.createUser("clc_self");
        String token = clientUtil.login("clc_self");
        String rawKey = createApiKey(token);
        Assertions.assertEquals(200, probeWithApiKey(rawKey));

        Response r = target().path("/user").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .post(Entity.form(new Form()
                        .param("password", "SelfNew1234")
                        .param("current_password", "Test1234")));
        Assertions.assertEquals(200, r.getStatus());

        Assertions.assertEquals(403, probeWithApiKey(rawKey), "a self password-change revokes the user's own API keys (e)");
    }

    // (f) An attacker's concurrent old-password credentials die on the victim's self-change. The session
    // was already killed pre-P2 by the token wipe; the API key (which the pre-P2 self-change did NOT touch)
    // is the discriminating probe that the epoch bump — not the token delete — is what revokes it.
    @Test
    public void attackerOldPasswordCredentialsDieOnSelfChange() {
        clientUtil.createUser("clc_victim");
        String attackerSession = clientUtil.login("clc_victim");
        String attackerKey = createApiKey(attackerSession);
        Assertions.assertEquals(200, probeWithCookie(attackerSession));
        Assertions.assertEquals(200, probeWithApiKey(attackerKey));

        String victimSession = clientUtil.login("clc_victim");
        Response r = target().path("/user").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, victimSession)
                .post(Entity.form(new Form().param("password", "VictimNew123").param("current_password", "Test1234")));
        Assertions.assertEquals(200, r.getStatus());

        Assertions.assertEquals(403, probeWithCookie(attackerSession),
                "the attacker's old-password session dies on the victim's self-change (f)");
        Assertions.assertEquals(403, probeWithApiKey(attackerKey),
                "the attacker's API key dies on the victim's self-change via the epoch bump (f)");
    }

    // (g) reset -> self-change does not revive the reset-killed credential.
    @Test
    public void resetThenSelfChangeDoesNotReviveKilledCredential() {
        clientUtil.createUser("clc_g");
        String oldSession = clientUtil.login("clc_g");
        Assertions.assertEquals(200, probeWithCookie(oldSession));

        String key = seedRecoveryKey("clc_g");
        target().path("/user/password_reset").request()
                .post(Entity.form(new Form().param("key", key).param("password", "ResetPw1234")));
        Assertions.assertEquals(403, probeWithCookie(oldSession));

        String s2 = clientUtil.login("clc_g", "ResetPw1234", false);
        target().path("/user").request().cookie(TokenBasedSecurityFilter.COOKIE_NAME, s2)
                .post(Entity.form(new Form().param("password", "AnotherPw12").param("current_password", "ResetPw1234")),
                        JsonObject.class);

        Assertions.assertEquals(403, probeWithCookie(oldSession),
                "a later self-change must not revive the reset-killed session (g)");
    }

    // (h) The conditional self-change aborts when a REAL recovery reset moved the epoch after the
    // verification-time snapshot. A valid session captures its verification epoch E; a full reset commits
    // end-to-end (E -> E+1 + new password); then the self-change is driven with the STALE E — the exact
    // seam the endpoint invokes — and MUST abort (-1) with the reset's password left standing. (Driving the
    // real changeOwnPassword seam with a stale E is necessary because the endpoint re-verifies the current
    // password first, so a full REST self-change can never reach the abort once the reset changed the
    // password — the reauth fails first. The blocking behaviour of the lock is proven in the race suite.)
    @Test
    public void selfChangeAbortsWhenARealResetMovedTheEpoch() {
        clientUtil.createUser("clc_hcond");
        String session = clientUtil.login("clc_hcond");
        Assertions.assertEquals(200, probeWithCookie(session), "a valid session; its self-change would observe epoch E");
        long verifiedEpoch = currentEpoch("clc_hcond"); // E = the verification-time epoch (0 for a fresh user)

        // A full recovery reset commits end-to-end: epoch E -> E+1 and a new password.
        String key = seedRecoveryKey("clc_hcond");
        Response reset = target().path("/user/password_reset").request()
                .post(Entity.form(new Form().param("key", key).param("password", "ResetWon1234")));
        Assertions.assertEquals(200, reset.getStatus());
        Assertions.assertEquals(verifiedEpoch + 1, currentEpoch("clc_hcond"), "the reset moved the epoch past E");

        // Drive the real self-change seam with the STALE verification epoch: it must abort (-1).
        String uid = userId("clc_hcond");
        long[] result = new long[1];
        TransactionUtil.handle(() -> result[0] =
                CredentialLifecycleUtil.changeOwnPassword(uid, "SelfLost1234", verifiedEpoch, uid));
        Assertions.assertEquals(-1L, result[0], "the self-change aborts against the completed reset (h)");

        // The reset's password stands; the abandoned self-change password never took effect.
        Assertions.assertEquals(200, loginStatus("clc_hcond", "ResetWon1234"), "the reset's password still logs in");
        Assertions.assertEquals(403, loginStatus("clc_hcond", "SelfLost1234"), "the abandoned self-change password is not set");
    }

    // #111 — self-delete is blocked while owning a directly-shared document, allowed otherwise.
    @Test
    public void selfDeleteBlockedWhenOwningSharedDocumentsAllowedOtherwise() {
        clientUtil.createUser("clc_del_owner");
        clientUtil.createUser("clc_del_other");
        String ownerToken = clientUtil.login("clc_del_owner");
        String docId = clientUtil.createDocument(ownerToken);

        target().path("/acl").request().cookie(TokenBasedSecurityFilter.COOKIE_NAME, ownerToken)
                .put(Entity.form(new Form().param("source", docId).param("perm", "READ")
                        .param("target", "clc_del_other").param("type", "USER")), JsonObject.class);

        Response blocked = target().path("/user").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, ownerToken).delete();
        Assertions.assertEquals(400, blocked.getStatus(),
                "self-delete blocked while owning a directly-shared document (#111)");

        clientUtil.createUser("clc_del_solo");
        String soloToken = clientUtil.login("clc_del_solo");
        clientUtil.createDocument(soloToken);
        Response allowed = target().path("/user").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, soloToken).delete();
        Assertions.assertEquals(200, allowed.getStatus(),
                "self-delete allowed when owning only unshared documents");
    }
}
