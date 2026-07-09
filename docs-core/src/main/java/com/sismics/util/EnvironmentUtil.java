package com.sismics.util;

/**
 * Environment properties utilities.
 *
 * @author jtremeaux
 */
public class EnvironmentUtil {

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
        String raw = System.getProperty(systemProperty);
        if (raw == null || raw.isBlank()) {
            raw = System.getenv(envVariable);
        }
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            return value < minValue ? defaultValue : value;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
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
