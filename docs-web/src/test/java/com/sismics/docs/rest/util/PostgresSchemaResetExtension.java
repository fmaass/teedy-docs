package com.sismics.docs.rest.util;

import java.io.InputStream;
import java.net.URL;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Properties;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Seed-preserving per-class database reset for the docs-web REST suite when it runs
 * against a real PostgreSQL server (CI, R-032 / RR-12).
 *
 * <p>The docs-web REST tests run through a real Grizzly HTTP server, so each test
 * COMMITS data (unlike the docs-core DAO tests, which roll back). On the default H2
 * {@code mem:} database this leaks nothing across classes because surefire forks a
 * fresh JVM per test class ({@code reuseForks=false}) and the in-memory DB dies with
 * the fork. On a shared PostgreSQL server the data survives the fork, so state from
 * one class would leak into the next.
 *
 * <p>Registered globally via ServiceLoader (see
 * {@code META-INF/services/org.junit.jupiter.api.extension.Extension} plus
 * {@code junit.jupiter.extensions.autodetection.enabled=true}), this
 * {@link BeforeAllCallback} fires once per test class, BEFORE any test method and
 * therefore before {@code EMF}'s static initializer runs (EMF is first referenced in
 * the per-test {@code @BeforeEach}). With surefire's {@code reuseForks=false} that is
 * once per fork. When the configured datasource is PostgreSQL it drops and recreates
 * the {@code public} schema, so {@code DbOpenHelper} then finds no {@code T_CONFIG}
 * and re-runs the full migration set — including the {@code dbupdate-000} seed
 * (default admin user + config). Every class therefore starts on a fresh,
 * fully-seeded schema with zero cross-class leakage.
 *
 * <p>This deliberately does NOT truncate tables (which would wipe the seed and
 * violate ADR-0009); it rebuilds the schema from the migrations so the seed is
 * always present.
 *
 * <p>For the default H2 datasource this extension is a no-op — the per-fork in-memory
 * database already provides the isolation — so a plain local {@code mvn test} is
 * unaffected.
 */
public class PostgresSchemaResetExtension implements BeforeAllCallback {

    @Override
    public void beforeAll(ExtensionContext context) {
        Properties properties = loadHibernateProperties();
        if (properties == null) {
            return;
        }

        String jdbcUrl = properties.getProperty("hibernate.connection.url", "");
        // Only act on PostgreSQL. H2 mem: forks are already isolated per class.
        if (!jdbcUrl.startsWith("jdbc:postgresql:")) {
            return;
        }

        // Destructive-operation guard: this extension DROPs the public schema. Refuse hard
        // unless the target host is an explicit local/loopback/testcontainers host, so a
        // misconfigured DATABASE_URL pointing at a real server can never silently wipe it.
        String host = extractHost(jdbcUrl);
        if (!isAllowedHost(host)) {
            throw new IllegalStateException(
                    "PostgresSchemaResetExtension refuses to drop the schema on non-local host '" + host
                            + "' (from " + jdbcUrl + "). This extension is destructive and only runs against "
                            + "localhost/127.0.0.1/::1/testcontainers. Check DATABASE_URL / hibernate.connection.url.");
        }

        String user = properties.getProperty("hibernate.connection.username");
        String password = properties.getProperty("hibernate.connection.password", "");

        try (Connection connection = DriverManager.getConnection(jdbcUrl, user, password);
             Statement statement = connection.createStatement()) {
            // Seed-preserving reset (ADR-0009): drop the whole schema and let the
            // migrations re-seed it, rather than truncating (which would wipe the seed).
            statement.execute("DROP SCHEMA IF EXISTS public CASCADE");
            statement.execute("CREATE SCHEMA public");
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to reset PostgreSQL schema before test class; refusing to run on a dirty database", e);
        }
    }

    /**
     * Extract the host component from a PostgreSQL JDBC URL. Supports the common forms:
     * <ul>
     *   <li>{@code jdbc:postgresql://host:port/db}</li>
     *   <li>{@code jdbc:postgresql://host/db}</li>
     *   <li>{@code jdbc:postgresql://[::1]:port/db} (bracketed IPv6)</li>
     *   <li>{@code jdbc:postgresql:///db} and {@code jdbc:postgresql:db} (local, empty host)</li>
     * </ul>
     * Returns an empty string for the host-less local forms (treated as local/allowed).
     *
     * @param jdbcUrl PostgreSQL JDBC URL
     * @return Lower-cased host, or "" when the URL targets the local socket / no host
     */
    static String extractHost(String jdbcUrl) {
        if (jdbcUrl == null) {
            return "";
        }
        final String marker = "//";
        int idx = jdbcUrl.indexOf(marker);
        if (idx < 0) {
            // jdbc:postgresql:dbname — no authority component, local.
            return "";
        }
        String authority = jdbcUrl.substring(idx + marker.length());
        // Strip everything from the first path/query separator onward.
        int end = authority.length();
        for (int i = 0; i < authority.length(); i++) {
            char c = authority.charAt(i);
            if (c == '/' || c == '?') {
                end = i;
                break;
            }
        }
        authority = authority.substring(0, end);
        // Strip credentials if present (user:pass@host).
        int at = authority.lastIndexOf('@');
        if (at >= 0) {
            authority = authority.substring(at + 1);
        }
        // jdbc:postgresql:///db -> authority is "" (empty host, local).
        if (authority.isEmpty()) {
            return "";
        }
        // Bracketed IPv6: [::1]:5432 -> ::1
        if (authority.startsWith("[")) {
            int close = authority.indexOf(']');
            if (close > 0) {
                return authority.substring(1, close).toLowerCase();
            }
        }
        // host:port -> host (a bare IPv6 with no brackets and no port has no ':' ambiguity here
        // because unbracketed IPv6 authorities are not valid in a JDBC URL).
        int colon = authority.indexOf(':');
        if (colon >= 0) {
            authority = authority.substring(0, colon);
        }
        return authority.toLowerCase();
    }

    /**
     * True only for explicit local/loopback/testcontainers hosts. Anything else (a real
     * server hostname or non-loopback IP) is refused before the destructive schema drop.
     *
     * @param host Host extracted from the JDBC URL (may be empty for the local-socket form)
     * @return Whether the destructive reset is permitted against this host
     */
    static boolean isAllowedHost(String host) {
        if (host == null || host.isEmpty()) {
            // jdbc:postgresql:///db and jdbc:postgresql:db use the local socket.
            return true;
        }
        String h = host.toLowerCase();
        if (h.equals("localhost") || h.equals("::1") || h.equals("0:0:0:0:0:0:0:1")) {
            return true;
        }
        // Any IPv4 loopback address (127.0.0.0/8) — but ONLY as a strict IPv4 literal.
        // A prefix check would also accept a resolvable DNS hostname like
        // 127.evil.example.com and reach DROP SCHEMA against a remote server.
        if (isLoopbackIpv4Literal(h)) {
            return true;
        }
        // Testcontainers commonly maps the container port onto the Docker host reachable via
        // host.docker.internal (and its testcontainers alias); both are local to the test host.
        if (h.equals("host.docker.internal") || h.equals("host.testcontainers.internal")) {
            return true;
        }
        return false;
    }

    /**
     * True only for a strict IPv4 loopback literal: exactly four dot-separated purely-numeric
     * octets, each 0-255, first octet 127 — no trailing dot, no alpha, no extra octets. This
     * deliberately rejects DNS hostnames that merely start with "127." (they resolve to a
     * remote server).
     *
     * @param host Lower-cased host string
     * @return Whether the host is a 127.0.0.0/8 IPv4 literal
     */
    private static boolean isLoopbackIpv4Literal(String host) {
        // split with limit -1 keeps trailing empty strings, so "127.0.0.1." yields 5 parts.
        String[] octets = host.split("\\.", -1);
        if (octets.length != 4) {
            return false;
        }
        for (String octet : octets) {
            if (octet.isEmpty() || octet.length() > 3) {
                return false;
            }
            for (int i = 0; i < octet.length(); i++) {
                char c = octet.charAt(i);
                if (c < '0' || c > '9') {
                    return false;
                }
            }
            if (Integer.parseInt(octet) > 255) {
                return false;
            }
        }
        return octets[0].equals("127");
    }

    private static Properties loadHibernateProperties() {
        URL url = PostgresSchemaResetExtension.class.getResource("/hibernate.properties");
        if (url == null) {
            return null;
        }
        try (InputStream is = url.openStream()) {
            Properties properties = new Properties();
            properties.load(is);
            return properties;
        } catch (Exception e) {
            throw new IllegalStateException("Unable to read hibernate.properties for PostgreSQL schema reset", e);
        }
    }
}
