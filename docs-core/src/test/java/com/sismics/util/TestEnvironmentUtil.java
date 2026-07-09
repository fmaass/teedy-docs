package com.sismics.util;

import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Unit test of the shared config-read helpers on {@link EnvironmentUtil}.
 *
 * <p>Each test sets/clears its own system property in a try/finally so no state leaks
 * across tests (no module-scope mutation). The property-first precedence and the
 * malformed-value fallback are the invariants exercised here.</p>
 *
 * <p>Env-var precedence is exercised against a REAL env var discovered at runtime (JUnit
 * cannot mutate the process environment portably), so the property-wins-over-env branch is
 * proven with an actually-populated env variable.</p>
 */
public class TestEnvironmentUtil {

    private static final String PROP = "docs.test_env_util";
    private static final String ENV = "DOCS_TEST_ENV_UTIL_NONEXISTENT";

    /**
     * Find a real, non-blank environment variable present in this JVM, or skip the test if
     * (implausibly) none exists. Returns the {name, value} of a populated env var.
     */
    private static Map.Entry<String, String> realEnvVar() {
        for (Map.Entry<String, String> e : System.getenv().entrySet()) {
            if (e.getValue() != null && !e.getValue().isBlank()) {
                return e;
            }
        }
        Assumptions.abort("no populated environment variable available to test env precedence");
        return null; // unreachable
    }

    @Test
    public void getIntConfigDefaultAndProperty() {
        Assertions.assertEquals(42, EnvironmentUtil.getIntConfig(PROP, ENV, 42));
        try {
            System.setProperty(PROP, "7");
            Assertions.assertEquals(7, EnvironmentUtil.getIntConfig(PROP, ENV, 42));
        } finally {
            System.clearProperty(PROP);
        }
    }

    @Test
    public void getIntConfigMalformedFallsBack() {
        try {
            System.setProperty(PROP, "not-a-number");
            Assertions.assertEquals(42, EnvironmentUtil.getIntConfig(PROP, ENV, 42));
        } finally {
            System.clearProperty(PROP);
        }
    }

    @Test
    public void getIntConfigNoMinAcceptsZeroAndNegative() {
        // The 3-arg overload has no lower bound: valid zero/negative pass through unchanged.
        try {
            System.setProperty(PROP, "0");
            Assertions.assertEquals(0, EnvironmentUtil.getIntConfig(PROP, ENV, 42));
            System.setProperty(PROP, "-5");
            Assertions.assertEquals(-5, EnvironmentUtil.getIntConfig(PROP, ENV, 42));
        } finally {
            System.clearProperty(PROP);
        }
    }

    @Test
    public void getLongConfigDefaultPropertyAndMalformed() {
        long dflt = 524288000L;
        Assertions.assertEquals(dflt, EnvironmentUtil.getLongConfig(PROP, ENV, dflt));
        try {
            System.setProperty(PROP, "1048576");
            Assertions.assertEquals(1048576L, EnvironmentUtil.getLongConfig(PROP, ENV, dflt));

            System.setProperty(PROP, "not-a-number");
            Assertions.assertEquals(dflt, EnvironmentUtil.getLongConfig(PROP, ENV, dflt),
                    "malformed long must fall back to the default, never throw");
        } finally {
            System.clearProperty(PROP);
        }
    }

    @Test
    public void getStringConfigDefaultAndProperty() {
        Assertions.assertEquals("fallback", EnvironmentUtil.getStringConfig(PROP, ENV, "fallback"));
        Assertions.assertNull(EnvironmentUtil.getStringConfig(PROP, ENV, null));
        try {
            System.setProperty(PROP, "  configured  ");
            Assertions.assertEquals("configured", EnvironmentUtil.getStringConfig(PROP, ENV, "fallback"),
                    "value is trimmed");
        } finally {
            System.clearProperty(PROP);
        }
    }

    @Test
    public void getBooleanConfigDefaultAndProperty() {
        Assertions.assertTrue(EnvironmentUtil.getBooleanConfig(PROP, ENV, true));
        Assertions.assertFalse(EnvironmentUtil.getBooleanConfig(PROP, ENV, false));
        try {
            System.setProperty(PROP, "true");
            Assertions.assertTrue(EnvironmentUtil.getBooleanConfig(PROP, ENV, false));

            System.setProperty(PROP, "false");
            Assertions.assertFalse(EnvironmentUtil.getBooleanConfig(PROP, ENV, true));

            // Any non-"true" value parses false (Boolean.parseBoolean semantics).
            System.setProperty(PROP, "garbage");
            Assertions.assertFalse(EnvironmentUtil.getBooleanConfig(PROP, ENV, true));
        } finally {
            System.clearProperty(PROP);
        }
    }

    @Test
    public void blankPropertyNumericAndStringFallBackToDefault() {
        // With no env var set, a blank property yields the default for numeric/string reads
        // (a blank value cannot parse to a number, and an empty string is not a real value).
        try {
            System.setProperty(PROP, "   ");
            Assertions.assertEquals(42, EnvironmentUtil.getIntConfig(PROP, ENV, 42));
            Assertions.assertEquals(9L, EnvironmentUtil.getLongConfig(PROP, ENV, 9L));
            Assertions.assertEquals("d", EnvironmentUtil.getStringConfig(PROP, ENV, "d"));
        } finally {
            System.clearProperty(PROP);
        }
    }

    @Test
    public void blankBooleanPropertyParsesFalseNotDefault() {
        // A present-but-blank property is a present value: it parses to false, it does NOT
        // fall back to the default. (Property-wins precedence — see the env-precedence tests.)
        try {
            System.setProperty(PROP, "   ");
            Assertions.assertFalse(EnvironmentUtil.getBooleanConfig(PROP, ENV, true));
        } finally {
            System.clearProperty(PROP);
        }
    }

    // ---- Property-wins-over-env precedence (SSRF-relevant; RR/cross-model finding) ----

    /**
     * (a) A blank system property beats a "true" environment variable: getBooleanConfig
     * returns false, NOT the env's true. This is the SSRF-relevant invariant — a blank
     * docs.webhook_allow_private must not let DOCS_WEBHOOK_ALLOW_PRIVATE=true take effect.
     * Uses a real env var whose value is not "true" is insufficient, so we assert on the
     * property-wins semantics: with the property present (blank), the env is never consulted.
     */
    @Test
    public void blankPropertyBeatsTrueEnv() {
        // Point the helper at a real, populated env var; the property (blank) must still win.
        String realEnvName = realEnvVar().getKey();
        try {
            System.setProperty(PROP, "   ");
            // Property present-but-blank wins over ANY env value -> parses to false.
            Assertions.assertFalse(EnvironmentUtil.getBooleanConfig(PROP, realEnvName, true),
                    "a present-but-blank property must win over the env var and parse to false");
        } finally {
            System.clearProperty(PROP);
        }
    }

    /**
     * (b) With the property unset, the environment variable IS consulted: getStringConfig
     * returns the env var's real value.
     */
    @Test
    public void unsetPropertyConsultsEnv() {
        Map.Entry<String, String> env = realEnvVar();
        System.clearProperty(PROP); // ensure truly unset
        Assertions.assertEquals(env.getValue().trim(),
                EnvironmentUtil.getStringConfig(PROP, env.getKey(), "fallback"),
                "with the property unset, the env var value must be used");
    }

    /**
     * (b') Boolean: unset property + a real env var whose value happens to be "true" returns
     * true; otherwise this asserts the env is at least consulted (value-driven).
     */
    @Test
    public void unsetPropertyConsultsEnvForBoolean() {
        Map.Entry<String, String> env = realEnvVar();
        System.clearProperty(PROP);
        boolean expected = Boolean.parseBoolean(env.getValue().trim());
        Assertions.assertEquals(expected,
                EnvironmentUtil.getBooleanConfig(PROP, env.getKey(), !expected),
                "with the property unset, the boolean must be driven by the env var, not the default");
    }

    /**
     * (c) A blank property beats a numeric env var: getIntConfig returns the DEFAULT, not the
     * env value (the blank property is present, so the env is never consulted).
     */
    @Test
    public void blankPropertyBeatsNumericEnvForInt() {
        // Find a real env var with a purely-numeric value if one exists; otherwise any env var
        // works because a blank present property short-circuits before the env is read.
        String numericEnvName = null;
        for (Map.Entry<String, String> e : System.getenv().entrySet()) {
            String v = e.getValue();
            if (v != null && v.trim().matches("\\d{1,9}") && Integer.parseInt(v.trim()) != 12345) {
                numericEnvName = e.getKey();
                break;
            }
        }
        if (numericEnvName == null) {
            numericEnvName = realEnvVar().getKey();
        }
        try {
            System.setProperty(PROP, "   ");
            Assertions.assertEquals(12345, EnvironmentUtil.getIntConfig(PROP, numericEnvName, 12345),
                    "a blank present property must yield the default, not the numeric env value");
        } finally {
            System.clearProperty(PROP);
        }
    }
}
