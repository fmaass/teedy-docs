package com.sismics.util.filter;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the exact-equality credential-epoch check shared by both credential filters
 * ({@link SecurityFilter#epochMatches(Long, long)}). A credential is honored ONLY while its stamped
 * epoch equals the user's current epoch: a stamp below is a revoked credential, a stamp above is a
 * corrupt/future value, and a null stamp is a malformed row — all three fail closed.
 */
public class TestSecurityFilterEpoch {

    /** Minimal concrete filter to reach the protected epoch-equality helper. */
    private static final class Probe extends SecurityFilter {
        @Override
        protected AuthAttempt attempt(HttpServletRequest request) {
            return AuthAttempt.absent();
        }

        @Override
        protected String getMechanism() {
            return "probe";
        }

        boolean matches(Long stampedEpoch, long userEpoch) {
            return epochMatches(stampedEpoch, userEpoch);
        }
    }

    private final Probe probe = new Probe();

    @Test
    public void equalEpochIsAccepted() {
        Assertions.assertTrue(probe.matches(0L, 0L));
        Assertions.assertTrue(probe.matches(7L, 7L));
    }

    @Test
    public void belowEpochIsRejected() {
        // A stale, revoked credential (its user has advanced past the stamp).
        Assertions.assertFalse(probe.matches(0L, 1L));
        Assertions.assertFalse(probe.matches(4L, 5L));
    }

    @Test
    public void aboveEpochIsRejected() {
        // A future/corrupt stamp fails closed rather than surviving.
        Assertions.assertFalse(probe.matches(2L, 1L));
        Assertions.assertFalse(probe.matches(999L, 0L));
    }

    @Test
    public void nullStampIsRejected() {
        // A malformed/legacy row carrying no stamp must never authenticate.
        Assertions.assertFalse(probe.matches(null, 0L));
        Assertions.assertFalse(probe.matches(null, 3L));
    }
}
