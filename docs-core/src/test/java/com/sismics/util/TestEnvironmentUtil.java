package com.sismics.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit test of the shared config-read helpers on {@link EnvironmentUtil}.
 *
 * <p>Each test sets/clears its own system property in a try/finally so no state leaks
 * across tests (no module-scope mutation). The property-first precedence and the
 * malformed-value fallback are the invariants exercised here.</p>
 *
 * <p>Env-var precedence is exercised against a CONTROLLED env source injected via
 * {@link EnvironmentUtil#setEnvSource} (JUnit cannot mutate the real process environment
 * portably). This makes the property-wins-over-env branch deterministic: a specific env
 * value is present, so an assertion that the property still wins (or that the env is
 * consulted when the property is unset) is falsifiable rather than passing vacuously on a
 * host that merely happens to lack the variable.</p>
 */
public class TestEnvironmentUtil {

    private static final String PROP = "docs.test_env_util";
    private static final String ENV = "DOCS_TEST_ENV_UTIL_NONEXISTENT";

    /** A distinct env var name used only by the controlled-env precedence tests. */
    private static final String CONTROLLED_ENV = "DOCS_TEST_ENV_UTIL_CONTROLLED";

    @AfterEach
    public void restoreEnvSource() {
        EnvironmentUtil.resetEnvSource();
    }

    /**
     * Install a controlled env source returning {@code value} for {@link #CONTROLLED_ENV}
     * and null for anything else, so the tests never depend on the host environment.
     */
    private static void withControlledEnv(String value) {
        EnvironmentUtil.setEnvSource(name -> CONTROLLED_ENV.equals(name) ? value : null);
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
    // These use an INJECTED env source (setEnvSource) with a known value, so each assertion
    // is falsifiable: the env var is definitely present, so "property still wins" or "env is
    // consulted" is a real claim rather than one that passes because the host lacks the var.

    /**
     * (a) A blank system property beats a "true" environment variable: getBooleanConfig
     * returns false, NOT the env's true. This is the SSRF-relevant invariant — a blank
     * docs.webhook_allow_private must not let DOCS_WEBHOOK_ALLOW_PRIVATE=true take effect.
     * The controlled env is explicitly "true", so if precedence regressed to env-wins this
     * assertion goes RED.
     */
    @Test
    public void blankPropertyBeatsTrueEnv() {
        withControlledEnv("true");
        try {
            System.setProperty(PROP, "   ");
            // Property present-but-blank wins over the "true" env value -> parses to false.
            Assertions.assertFalse(EnvironmentUtil.getBooleanConfig(PROP, CONTROLLED_ENV, true),
                    "a present-but-blank property must win over the env var and parse to false");
        } finally {
            System.clearProperty(PROP);
        }
    }

    /**
     * (b) With the property unset, the environment variable IS consulted: getStringConfig
     * returns the (trimmed) controlled env value.
     */
    @Test
    public void unsetPropertyConsultsEnv() {
        withControlledEnv("  env-string-value  ");
        System.clearProperty(PROP); // ensure truly unset
        Assertions.assertEquals("env-string-value",
                EnvironmentUtil.getStringConfig(PROP, CONTROLLED_ENV, "fallback"),
                "with the property unset, the env var value must be used");
    }

    /**
     * (b') Boolean: unset property + a controlled env var of "true" returns true (not the
     * opposite default). The default is deliberately false so only reading the env can pass.
     */
    @Test
    public void unsetPropertyConsultsEnvForBoolean() {
        withControlledEnv("true");
        System.clearProperty(PROP);
        Assertions.assertTrue(
                EnvironmentUtil.getBooleanConfig(PROP, CONTROLLED_ENV, false),
                "with the property unset, the boolean must be driven by the env var, not the default");
    }

    /**
     * (c) A blank property beats a numeric env var: getIntConfig returns the DEFAULT, not the
     * env value. The controlled env is a real number distinct from both default and expected,
     * so an env-wins regression would return 777 and fail this assertion.
     */
    @Test
    public void blankPropertyBeatsNumericEnvForInt() {
        withControlledEnv("777");
        try {
            System.setProperty(PROP, "   ");
            Assertions.assertEquals(12345, EnvironmentUtil.getIntConfig(PROP, CONTROLLED_ENV, 12345),
                    "a blank present property must yield the default, not the numeric env value");
        } finally {
            System.clearProperty(PROP);
        }
    }

    /**
     * (d) With the property unset, a numeric env var IS consulted by getIntConfig: it returns
     * the parsed env number, not the default. RED if the env source were ignored.
     */
    @Test
    public void unsetPropertyConsultsNumericEnvForInt() {
        withControlledEnv("777");
        System.clearProperty(PROP);
        Assertions.assertEquals(777, EnvironmentUtil.getIntConfig(PROP, CONTROLLED_ENV, 12345),
                "with the property unset, the numeric env var value must be used");
    }
}
