package com.sismics.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Function;

/**
 * Environment properties utilities.
 *
 * @author jtremeaux
 */
public class EnvironmentUtil {

    private static final Logger log = LoggerFactory.getLogger(EnvironmentUtil.class);

    private static String OS = System.getProperty("os.name").toLowerCase();

    private static String APPLICATION_MODE = System.getProperty("application.mode");

    private static String WINDOWS_APPDATA = System.getenv("APPDATA");

    private static String MAC_OS_USER_HOME = System.getProperty("user.home");

    private static String TEEDY_HOME = System.getProperty("docs.home");

    /**
     * In a web application context.
     */
    private static boolean webappContext;

    /**
     * Source of environment-variable values. Defaults to the real process environment
     * ({@link System#getenv(String)}). Package-private so unit tests can inject a controlled
     * env source to exercise the property-over-env precedence deterministically, instead of
     * depending on whatever variables happen to exist on the host (which lets an env-precedence
     * assertion pass vacuously). Production always uses the real environment.
     */
    private static Function<String, String> envSource = System::getenv;

    /**
     * Override the environment-variable source (tests only). Restore with
     * {@link #resetEnvSource()} in a finally block so no state leaks across tests.
     *
     * @param source Env-var lookup function (name -&gt; value, null when absent)
     */
    static void setEnvSource(Function<String, String> source) {
        envSource = source;
    }

    /**
     * Restore the default (real process) environment source.
     */
    static void resetEnvSource() {
        envSource = System::getenv;
    }

    /**
     * Returns true if running under Microsoft Windows.
     *
     * @return Running under Microsoft Windows
     */
    public static boolean isWindows() {
        return OS.contains("win");
    }

    /**
     * Returns true if running under Mac OS.
     *
     * @return Running under Mac OS
     */
    public static boolean isMacOs() {
        return OS.contains("mac");
    }

    /**
     * Returns true if running under UNIX.
     *
     * @return Running under UNIX
     */
    public static boolean isUnix() {
        return OS.contains("nix") || OS.contains("nux") || OS.contains("aix");
    }

    /**
     * Returns true if we are in a unit testing environment.
     *
     * @return Unit testing environment
     */
    public static boolean isUnitTest() {
        return !webappContext;
    }

    /**
     * Return true if we are in dev mode.
     *
     * @return Dev mode
     */
    public static boolean isDevMode() {
        return "dev".equalsIgnoreCase(APPLICATION_MODE);
    }

    /**
     * Returns the MS Windows AppData directory of this user.
     *
     * @return AppData directory
     */
    public static String getWindowsAppData() {
        return WINDOWS_APPDATA;
    }

    /**
     * Returns the Mac OS home directory of this user.
     *
     * @return Home directory
     */
    public static String getMacOsUserHome() {
        return MAC_OS_USER_HOME;
    }

    /**
     * Returns the home directory of DOCS (e.g. /var/docs).
     *
     * @return Home directory
     */
    public static String getTeedyHome() {
        return TEEDY_HOME;
    }

    /**
     * Returns an integer configuration value read from a system property, falling back to an
     * environment variable, and finally to a default. Invalid or non-positive values fall back
     * to the default so a misconfiguration can never disable the bound.
     *
     * @param systemProperty System property name (e.g. docs.async_queue_capacity)
     * @param envVariable Environment variable name (e.g. DOCS_ASYNC_QUEUE_CAPACITY)
     * @param defaultValue Default value
     * @param minValue Minimum accepted value (values below this fall back to the default)
     * @return Configured integer value
     */
    public static int getIntConfig(String systemProperty, String envVariable, int defaultValue, int minValue) {
        String raw = readRawConfig(systemProperty, envVariable);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            return value < minValue ? defaultValue : value;
        } catch (NumberFormatException e) {
            log.warn("Invalid value for {}/{}: {}, using default {}", systemProperty, envVariable, raw, defaultValue);
            return defaultValue;
        }
    }

    /**
     * Returns an integer configuration value with the standard property-then-env-then-default
     * precedence and no lower bound (any parseable int, including zero and negatives, is
     * accepted). A missing or malformed value falls back to the default, never throwing.
     *
     * @param systemProperty System property name
     * @param envVariable Environment variable name
     * @param defaultValue Default value
     * @return Configured integer value
     */
    public static int getIntConfig(String systemProperty, String envVariable, int defaultValue) {
        return getIntConfig(systemProperty, envVariable, defaultValue, Integer.MIN_VALUE);
    }

    /**
     * Returns a long configuration value read from a system property, falling back to an
     * environment variable, and finally to a default. A missing or malformed value falls back
     * to the default so a misconfiguration can never throw.
     *
     * @param systemProperty System property name
     * @param envVariable Environment variable name
     * @param defaultValue Default value
     * @return Configured long value
     */
    public static long getLongConfig(String systemProperty, String envVariable, long defaultValue) {
        String raw = readRawConfig(systemProperty, envVariable);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            log.warn("Invalid value for {}/{}: {}, using default {}", systemProperty, envVariable, raw, defaultValue);
            return defaultValue;
        }
    }

    /**
     * Returns a string configuration value read from a system property, falling back to an
     * environment variable, and finally to a default.
     *
     * @param systemProperty System property name
     * @param envVariable Environment variable name
     * @param defaultValue Default value (may be null)
     * @return Configured string value (trimmed), or the default if the resolved value is
     *         absent or blank
     */
    public static String getStringConfig(String systemProperty, String envVariable, String defaultValue) {
        String raw = readRawConfig(systemProperty, envVariable);
        return (raw == null || raw.isBlank()) ? defaultValue : raw.trim();
    }

    /**
     * Returns a boolean configuration value read from a system property, falling back to an
     * environment variable, and finally to a default. Parsed with {@link Boolean#parseBoolean}
     * (any value other than a case-insensitive {@code "true"} is false); a missing value falls
     * back to the default.
     *
     * @param systemProperty System property name
     * @param envVariable Environment variable name
     * @param defaultValue Default value
     * @return Configured boolean value
     */
    public static boolean getBooleanConfig(String systemProperty, String envVariable, boolean defaultValue) {
        String raw = readRawConfig(systemProperty, envVariable);
        return raw != null ? Boolean.parseBoolean(raw.trim()) : defaultValue;
    }

    /**
     * Read a raw configuration value with strict property-wins precedence: if the system
     * property is set (non-null), it wins and is returned AS-IS — even if blank — and the
     * environment variable is NOT consulted. Only when the property is truly unset (null) is
     * the environment variable consulted. Returns null only when NEITHER source is set.
     *
     * <p>The precedence decision is made on presence (non-null), not on blankness, so a
     * present-but-blank property cannot silently fall through to the env var and flip a
     * security-relevant default (e.g. a blank {@code docs.webhook_allow_private} must not let
     * a {@code DOCS_WEBHOOK_ALLOW_PRIVATE=true} env var take effect). Downstream parsing then
     * treats a blank value as the default for numeric reads (parse fails, falls back) and as
     * false for {@link Boolean#parseBoolean}.</p>
     *
     * @param systemProperty System property name
     * @param envVariable Environment variable name
     * @return Raw value (property AS-IS if set, else env var), or null if neither is set
     */
    private static String readRawConfig(String systemProperty, String envVariable) {
        String prop = System.getProperty(systemProperty);
        if (prop != null) {
            return prop;
        }
        return envSource.apply(envVariable);
    }

    /**
     * Getter of webappContext.
     *
     * @return webappContext
     */
    public static boolean isWebappContext() {
        return webappContext;
    }

    /**
     * Setter of webappContext.
     *
     * @param webappContext webappContext
     */
    public static void setWebappContext(boolean webappContext) {
        EnvironmentUtil.webappContext = webappContext;
    }
}
