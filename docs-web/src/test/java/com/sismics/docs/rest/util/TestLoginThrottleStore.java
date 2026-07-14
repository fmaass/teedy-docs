package com.sismics.docs.rest.util;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Unit tests for the multi-dimensional login/recovery throttle store.
 */
public class TestLoginThrottleStore {
    private static LoginThrottleStore store(int maxAttempts, AtomicLong clock,
                                            int loginCap, double loginRate,
                                            int recoveryCap, double recoveryRate,
                                            long counterMaxSize) {
        return new LoginThrottleStore(maxAttempts, 60_000, 900_000,
                loginCap, loginRate, recoveryCap, recoveryRate, counterMaxSize, clock::get);
    }

    @Test
    public void locksAfterThresholdWithBoundedRetryAfter() {
        LoginThrottleStore store = store(3, new AtomicLong(), 100, 50, 10, 5, 1000);
        Assertions.assertFalse(store.checkLoginBlocked("bob", "10.0.0.1").isBlocked());
        for (int i = 0; i < 3; i++) {
            store.recordLoginFailure("bob", "10.0.0.1");
        }
        LoginThrottleStore.ThrottleDecision d = store.checkLoginBlocked("bob", "10.0.0.1");
        Assertions.assertTrue(d.isBlocked());
        Assertions.assertTrue(d.getRetryAfterSeconds() > 0);
        // Retry-After is bounded by the 15-minute cap.
        Assertions.assertTrue(d.getRetryAfterSeconds() <= 15 * 60);
    }

    @Test
    public void successClearsAccountAndPair() {
        LoginThrottleStore store = store(3, new AtomicLong(), 100, 50, 10, 5, 1000);
        store.recordLoginFailure("bob", "10.0.0.1");
        store.recordLoginFailure("bob", "10.0.0.1");
        store.recordLoginSuccess("bob", "10.0.0.1");
        // Two more failures should NOT lock yet (counter was reset by the success).
        store.recordLoginFailure("bob", "10.0.0.1");
        store.recordLoginFailure("bob", "10.0.0.1");
        Assertions.assertFalse(store.checkLoginBlocked("bob", "10.0.0.1").isBlocked());
    }

    @Test
    public void lockedAccountSurvivesCounterEviction() {
        // A tiny counter cache stands in for the real high-cardinality churn.
        LoginThrottleStore store = store(3, new AtomicLong(), 100, 50, 10, 5, 8);

        for (int i = 0; i < 3; i++) {
            store.recordLoginFailure("victim", "10.0.0.1");
        }
        // Blocked via the account dimension even from a DIFFERENT network (isolates the account block).
        Assertions.assertTrue(store.checkLoginBlocked("victim", "203.0.113.9").isBlocked());

        // Simulate the eviction that high-cardinality account churn would cause on the evictable counter.
        store.evictLoginAccountCounterForTest("victim");
        Assertions.assertFalse(store.loginAccountCounterPresent("victim"),
                "the evictable account counter should be gone");

        // The active-lockout retention cache, not the counter, keeps the account blocked until its deadline.
        Assertions.assertTrue(store.checkLoginBlocked("victim", "203.0.113.9").isBlocked(),
                "a locked account must survive counter eviction");
    }

    @Test
    public void counterCacheIsBounded() {
        long maxSize = 8;
        LoginThrottleStore store = store(3, new AtomicLong(), 100, 50, 10, 5, maxSize);
        // Insert far more distinct account keys than the configured maximum.
        for (int i = 0; i < 500; i++) {
            store.recordLoginFailure("acct" + i, "10.1." + (i % 200) + ".1");
        }
        store.runMaintenance();
        // The account counter cache must stay within its configured maximum despite 500 distinct keys —
        // proving the bound actually holds, not merely that the store does not throw.
        long size = store.accountCounterEstimatedSize();
        Assertions.assertTrue(size <= maxSize,
                "account counter cache must stay within its maximum (was " + size + ", max " + maxSize + ")");
    }

    @Test
    public void globalBulkheadShedsThenSelfHeals() {
        AtomicLong clock = new AtomicLong(0);
        // Login bucket capacity 2, refill 1/sec.
        LoginThrottleStore store = store(3, clock, 2, 1.0, 2, 1.0, 1000);

        Assertions.assertTrue(store.tryAdmitLoginWork());
        Assertions.assertTrue(store.tryAdmitLoginWork());
        // Bucket exhausted -> shed.
        Assertions.assertFalse(store.tryAdmitLoginWork());
        Assertions.assertTrue(store.loginWorkRetryAfterSeconds() >= 0);

        // Advance the clock by 1s -> one token refills -> admitted again (never a permanent lockout).
        clock.addAndGet(1_000_000_000L);
        Assertions.assertTrue(store.tryAdmitLoginWork());
    }

    @Test
    public void recoveryBulkheadIsPartitionedFromLogin() {
        AtomicLong clock = new AtomicLong(0);
        // Login bucket exhausted, recovery bucket independent.
        LoginThrottleStore store = store(3, clock, 1, 1.0, 5, 1.0, 1000);
        Assertions.assertTrue(store.tryAdmitLoginWork());
        Assertions.assertFalse(store.tryAdmitLoginWork(), "login bucket exhausted");
        // Recovery still admits from a fresh network/account despite login being shed.
        Assertions.assertTrue(store.tryRecovery("carol", "203.0.113.20"));
    }

    @Test
    public void recoveryLimitsPerAccountAfterThreshold() {
        LoginThrottleStore store = store(3, new AtomicLong(), 100, 50, 100, 100, 1000);
        Assertions.assertTrue(store.tryRecovery("dave", "203.0.113.30"));
        Assertions.assertTrue(store.tryRecovery("dave", "203.0.113.30"));
        // Third attempt reaches the per-account recovery threshold -> suppressed.
        Assertions.assertFalse(store.tryRecovery("dave", "203.0.113.30"));
    }

    @Test
    public void locallyLimitedRecoveryDoesNotDrainGlobalBucketForOthers() {
        // Clock never advances, so the recovery bucket never refills: any token spent is spent for good.
        AtomicLong clock = new AtomicLong(0);
        // maxAttempts=2, recovery bucket capacity 5.
        LoginThrottleStore store = store(2, clock, 100, 50, 5, 1.0, 1000);

        // A single attacker hammers the recovery endpoint for one account 20 times. Only the attempts BEFORE
        // it locks locally (i.e. one, since the 2nd attempt reaches the threshold and is suppressed before the
        // global reserve) may consume a global token; every locally-suppressed attempt afterwards must NOT
        // touch the shared bucket. Pre-fix, each attempt consumed a global token FIRST and drained the bucket.
        for (int i = 0; i < 20; i++) {
            store.tryRecovery("victim", "203.0.113.100");
        }

        // A fresh, un-limited account on a different network must still find recovery tokens available: the
        // hammering attacker did not drain the shared bulkhead for everyone else.
        Assertions.assertTrue(store.tryRecovery("innocent", "198.51.100.42"),
                "a locally-limited attacker must not drain the global recovery bucket for other accounts");
    }

    @Test
    public void networkKeyCanonicalizesToPrefixes() {
        Assertions.assertEquals("203.0.113.9", LoginThrottleStore.networkKey("203.0.113.9"));
        // IPv6 addresses in the same /64 collapse to one key.
        String a = LoginThrottleStore.networkKey("2001:db8:0:1::5");
        String b = LoginThrottleStore.networkKey("2001:db8:0:1::99");
        Assertions.assertEquals(a, b);
        Assertions.assertTrue(a.endsWith("/64"));
        String other = LoginThrottleStore.networkKey("2001:db8:0:2::5");
        Assertions.assertNotEquals(a, other);
    }
}
