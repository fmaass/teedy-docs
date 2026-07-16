package com.sismics.docs.rest;

import com.sismics.docs.core.dao.UserDao;
import com.sismics.docs.core.model.jpa.User;
import com.sismics.docs.core.util.PasswordRecoveryUtil;
import com.sismics.docs.core.util.TransactionUtil;
import com.sismics.util.context.ThreadLocalContext;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Form;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Verifies that password recovery is intentionally origin-agnostic: it does NOT branch on account origin, so
 * an existing external-origin (OIDC/LDAP) account and an existing internal account take the SAME observable
 * path (status + body), and the nonexistent-account lane spends only side-effect-free work.
 *
 * <p>The OIDC-recovery -> local-login bypass is closed at the internal-auth handler (a recovered password on an
 * SSO account is unusable), NOT by declining recovery for external accounts — declining would add an
 * origin/timing oracle. Runs on H2 and on real PostgreSQL (the {@code test-web-postgres} CI job).</p>
 */
public class TestRecoveryOriginAgnostic extends BaseJerseyTest {

    private long activeRecoveryRows(String username) {
        long[] count = new long[1];
        TransactionUtil.handle(() -> count[0] = (Long) ThreadLocalContext.get().getEntityManager()
                .createQuery("select count(r) from PasswordRecovery r where r.username = :u and r.deleteDate is null")
                .setParameter("u", username).getSingleResult());
        return count[0];
    }

    private long totalActiveRecoveryRows() {
        long[] count = new long[1];
        TransactionUtil.handle(() -> count[0] = (Long) ThreadLocalContext.get().getEntityManager()
                .createQuery("select count(r) from PasswordRecovery r where r.deleteDate is null")
                .getSingleResult());
        return count[0];
    }

    private void makeOidcOrigin(String username) {
        TransactionUtil.handle(() -> {
            User user = new UserDao().getActiveByUsername(username);
            user.setOidcIssuer("https://idp.example.com");
            user.setOidcSubject("sub-" + username);
        });
    }

    private JsonObject passwordLost(String username) {
        return target().path("/user/password_lost").request()
                .post(Entity.form(new Form().param("username", username)), JsonObject.class);
    }

    @Test
    public void existingExternalAndInternalTakeSameObservablePath() {
        clientUtil.createUser("recovery_internal_user");
        clientUtil.createUser("recovery_external_user");
        makeOidcOrigin("recovery_external_user");

        JsonObject internal = passwordLost("recovery_internal_user");
        JsonObject external = passwordLost("recovery_external_user");

        // Identical observable body/status regardless of origin: recovery does not branch on origin.
        Assertions.assertEquals("ok", internal.getString("status"));
        Assertions.assertEquals("ok", external.getString("status"));

        // Both existing accounts take the REAL recovery path -> exactly one active recovery row each.
        Assertions.assertEquals(1L, activeRecoveryRows("recovery_internal_user"),
                "an existing internal account creates one recovery row");
        Assertions.assertEquals(1L, activeRecoveryRows("recovery_external_user"),
                "an existing external-origin account takes the same recovery path as an internal one");
    }

    @Test
    public void nonexistentAccountLaneIsSideEffectFree() {
        String ghost = "recovery_ghost_account";
        JsonObject ghostResp = passwordLost(ghost);

        Assertions.assertEquals("ok", ghostResp.getString("status"),
                "a nonexistent account returns the same generic OK");
        Assertions.assertEquals(0L, activeRecoveryRows(ghost),
                "the nonexistent-account dummy must be side-effect-free: no recovery row created or altered");
    }

    /**
     * Discriminating guard on the equalizer's READ-ONLY contract: seed one ACTIVE recovery key for a real
     * account, invoke {@link PasswordRecoveryUtil#equalizeNonexistentRecovery()} directly, and assert the
     * key — and every recovery row — is untouched. The former nonexistent-account dummy soft-deleted active
     * keys (a state MUTATION), so against that behavior this assertion fails; only a genuinely read-only
     * equalizer leaves the active row count unchanged. This pins the property the endpoint's OK-with-zero-rows
     * response cannot distinguish (a zero-row UPDATE also returns OK with zero rows).
     */
    @Test
    public void equalizerIsReadOnlyAndLeavesExistingKeysUntouched() {
        clientUtil.createUser("recovery_readonly_user");
        // Seed exactly one active recovery key for this real account via the real recovery path.
        passwordLost("recovery_readonly_user");
        Assertions.assertEquals(1L, activeRecoveryRows("recovery_readonly_user"),
                "precondition: the real account has one active recovery key");
        long totalActiveBefore = totalActiveRecoveryRows();

        // Invoke the nonexistent-account equalizer directly, in its own committed transaction.
        TransactionUtil.handle(PasswordRecoveryUtil::equalizeNonexistentRecovery);

        // A state-mutating equalizer (the former soft-delete-active-keys dummy) would drop the seeded key
        // and shrink the active row count; a read-only equalizer changes neither.
        Assertions.assertEquals(1L, activeRecoveryRows("recovery_readonly_user"),
                "the equalizer must not delete or alter an existing account's active recovery key");
        Assertions.assertEquals(totalActiveBefore, totalActiveRecoveryRows(),
                "the equalizer must not change the active recovery row count (no write of any kind)");
    }
}
