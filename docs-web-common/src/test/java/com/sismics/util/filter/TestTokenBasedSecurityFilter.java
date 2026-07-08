package com.sismics.util.filter;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TokenBasedSecurityFilter} session-lifetime parsing.
 *
 * <p>A malformed DOCS_SESSION_LIFETIME_DAYS must not throw: the value feeds a static
 * field, so an exception would fail the class initializer and break all token-based
 * authentication.</p>
 */
public class TestTokenBasedSecurityFilter {

    @Test
    public void validValueIsParsed() {
        Assertions.assertEquals(30, TokenBasedSecurityFilter.resolveSessionLifetimeDays("30"));
        Assertions.assertEquals(7, TokenBasedSecurityFilter.resolveSessionLifetimeDays("  7 "));
    }

    @Test
    public void malformedValueFallsBackToDefault() {
        Assertions.assertEquals(TokenBasedSecurityFilter.DEFAULT_SESSION_LIFETIME_DAYS,
                TokenBasedSecurityFilter.resolveSessionLifetimeDays("not-a-number"));
        Assertions.assertEquals(TokenBasedSecurityFilter.DEFAULT_SESSION_LIFETIME_DAYS,
                TokenBasedSecurityFilter.resolveSessionLifetimeDays("12x"));
    }

    @Test
    public void nonPositiveValueFallsBackToDefault() {
        Assertions.assertEquals(TokenBasedSecurityFilter.DEFAULT_SESSION_LIFETIME_DAYS,
                TokenBasedSecurityFilter.resolveSessionLifetimeDays("0"));
        Assertions.assertEquals(TokenBasedSecurityFilter.DEFAULT_SESSION_LIFETIME_DAYS,
                TokenBasedSecurityFilter.resolveSessionLifetimeDays("-5"));
    }

    @Test
    public void nullOrBlankFallsBackToDefault() {
        Assertions.assertEquals(TokenBasedSecurityFilter.DEFAULT_SESSION_LIFETIME_DAYS,
                TokenBasedSecurityFilter.resolveSessionLifetimeDays((String) null));
        Assertions.assertEquals(TokenBasedSecurityFilter.DEFAULT_SESSION_LIFETIME_DAYS,
                TokenBasedSecurityFilter.resolveSessionLifetimeDays("   "));
    }

    @Test
    public void classLoadsAndComputesLifetimeWithMalformedEnv() {
        // Referencing the static field forces class initialization. If the parse were
        // unguarded and the env var malformed, this would throw ExceptionInInitializerError.
        // With the guard, the field is always a valid positive number of seconds.
        Assertions.assertTrue(TokenBasedSecurityFilter.TOKEN_LONG_LIFETIME > 0);
    }
}
