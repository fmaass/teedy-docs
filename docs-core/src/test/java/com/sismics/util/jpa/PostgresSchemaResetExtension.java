package com.sismics.util.jpa;

import java.io.InputStream;
import java.net.URL;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Properties;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Seed-preserving per-class database reset for the docs-core suite when it runs against a real
 * PostgreSQL server (the {@code test-postgres} CI job, and any local run pointed at Postgres).
 *
 * <p>Most docs-core tests extend {@code BaseTransactionalTest}, which begins a transaction in
 * {@code @BeforeEach} and rolls it back in {@code @AfterEach}, so they leave nothing behind. A
 * minority commit deliberately — concurrency, listener and reconciliation tests need rows visible
 * to a second connection or to an async listener. On the default H2 {@code mem:} database those
 * commits leak nothing: surefire forks a fresh JVM per test class ({@code reuseForks=false}) and
 * the in-memory database dies with the fork. On a real PostgreSQL server the rows survive both the
 * fork and the whole build, so the committed state accumulates — a second run against the same
 * database fails (duplicate usernames out of {@code BaseTransactionalTest.createUser}), and
 * committed config rows from one class can be observed by another class in the same run.
 *
 * <p>Registered globally via ServiceLoader (see
 * {@code META-INF/services/org.junit.jupiter.api.extension.Extension} plus
 * {@code junit.jupiter.extensions.autodetection.enabled=true}), this {@link BeforeAllCallback}
 * fires once per test class, BEFORE any test method and therefore before {@code EMF}'s static
 * initializer runs (EMF is first referenced in the per-test {@code @BeforeEach}); no transaction is
 * ever open across the reset. When the configured datasource is PostgreSQL it drops and recreates
 * the {@code public} schema, so {@code DbOpenHelper} then finds no {@code T_CONFIG} and re-runs the
 * full migration set — including the {@code dbupdate-000} seed (default admin user + config). Every
 * class therefore starts on a fresh, fully-seeded schema, whatever the previous run left behind.
 *
 * <p>This deliberately does NOT truncate tables (which would wipe the seed and violate ADR-0009);
 * it rebuilds the schema from the migrations so the seed is always present.
 *
 * <p>For the default H2 datasource this extension is a no-op — the per-fork in-memory database
 * already provides the isolation — so a plain local {@code mvn test} is unaffected. Tests that
 * bring their own database ({@code TestPostgresMigration}, {@code TestUserDaoCaseInsensitivePostgres},
 * {@code TestPopulatedMigration} and the other migration fixtures, all of which build a private H2
 * or testcontainers datasource) are likewise unaffected: the reset only ever touches the datasource
 * declared in {@code hibernate.properties}.
 *
 * <p>docs-web carries its own copy of this extension for the same reason on the REST suite. The two
 * cannot share one class: docs-core is the lower module, and publishing a docs-core test-jar for
 * docs-web to consume would put this ServiceLoader registration on the docs-web test classpath
 * alongside its own, registering the reset twice.
 */
public class PostgresSchemaResetExtension implements BeforeAllCallback {

    // This class BODY is duplicated verbatim in the sibling module's copy (docs-core
    // com.sismics.util.jpa <-> docs-web com.sismics.docs.rest.util): only the package line and the
    // class javadoc above it differ. The duplication is deliberate (see the javadoc), so keep the
    // two bodies byte-identical — a reader has to be able to prove they have not drifted with:
    //   diff <(sed -n '/^public class PostgresSchemaResetExtension/,$p' <copy-a>) \
    //        <(sed -n '/^public class PostgresSchemaResetExtension/,$p' <copy-b>)

    /**
     * The pgjdbc connection parameters known to decide the ENDPOINT at driver level: {@code PGHOST}
     * and {@code PGPORT} replace the authority-derived host and port, and {@code service} resolves
     * both out of {@code pg_service.conf}, which can name a remote server for a URL that carries no
     * host at all. They are listed only so the refusal can NAME what it found — the refusal itself
     * does not depend on this list, because no query parameter is permitted at all (see
     * {@link #firstQueryParameter}).
     */
    private static final String[] ENDPOINT_OVERRIDE_PARAMS = { "pghost", "pgport", "service" };

    @Override
    public void beforeAll(ExtensionContext context) {
        Properties properties = hibernateProperties();
        if (properties == null) {
            return;
        }

        String jdbcUrl = properties.getProperty("hibernate.connection.url", "");
        // Only act on PostgreSQL. H2 mem: forks are already isolated per class.
        if (!jdbcUrl.startsWith("jdbc:postgresql:")) {
            return;
        }

        // Destructive-operation guard: this extension DROPs the public schema. Refuse hard unless
        // the URL provably resolves to ONE explicit local/loopback/testcontainers endpoint, so a
        // misconfigured DATABASE_URL pointing at a real server can never silently wipe it.
        String refusal = refusalReason(jdbcUrl);
        if (refusal != null) {
            throw new IllegalStateException(
                    "PostgresSchemaResetExtension REFUSES to drop the schema: " + refusal
                            + " Offending URL: " + jdbcUrl
                            + " — this extension is destructive (DROP SCHEMA public CASCADE) and runs only "
                            + "against a single, unambiguous local endpoint named once in the URL: "
                            + "localhost, a 127.0.0.0/8 literal, ::1, the local socket, "
                            + "host.docker.internal or host.testcontainers.internal — and with no "
                            + "connection parameters of any kind, since none of them can be proven "
                            + "harmless from the URL text. "
                            + "Check DATABASE_URL / hibernate.connection.url.");
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
     * Why the destructive reset must NOT run against {@code jdbcUrl}, or {@code null} when it may.
     *
     * <p>This is the single verdict {@link #beforeAll} asks for. {@link #extractHost} and
     * {@link #isAllowedHost} are its parts and are NOT a verdict on their own: both read one host
     * out of a credential-stripped authority, which three pgjdbc URL shapes make a lie. All three
     * are refused outright rather than allowlisted, because in each of them the endpoint this guard
     * can see is not the endpoint the driver will connect to:
     * <ul>
     *   <li>a comma-separated multi-host authority
     *       ({@code jdbc:postgresql://localhost:1,db.prod.example.com:5432/docs}): pgjdbc tries the
     *       endpoints in turn, so with the first one refusing connections it falls through to one
     *       this guard never inspected — while the text still begins with {@code localhost};</li>
     *   <li>the same, with the comma hidden in what reads as credentials
     *       ({@code jdbc:postgresql://db.prod.example.com,ignored@localhost:5432/docs}): pgjdbc has
     *       no userinfo concept in the URL — it splits the WHOLE authority on {@code ','} first and
     *       only then each element on {@code ':'} — so this is two hosts to the driver, the first of
     *       them remote, while a parser that strips through {@code '@'} sees only
     *       {@code localhost}. The comma is therefore checked on the RAW authority;</li>
     *   <li>any query parameter at all ({@code ?PGHOST=…}, {@code ?PGPORT=…},
     *       {@code jdbc:postgresql:docs?service=production}): {@code PGHOST}/{@code PGPORT} replace
     *       the authority-derived host and port, and {@code service} resolves both out of
     *       {@code pg_service.conf} — which can put a remote server behind a URL that names no host
     *       at all and is otherwise permitted as the local-socket form.</li>
     * </ul>
     *
     * <p>The parameter rule refuses EVERY query parameter rather than enumerating the dangerous
     * ones, and that is deliberate. Enumerating is a denylist over a driver surface that grows:
     * pgjdbc 42.7 alone ships {@code PGHOST}, {@code PGPORT}, {@code PGDBNAME}, {@code service} and
     * {@code socketFactory}, and a later release can add another endpoint-affecting property this
     * guard would then silently permit. Refusing everything fails closed and needs no such
     * tracking, and it costs nothing here: every datasource URL this repository configures carries
     * no query string at all (the {@code test-postgres}/{@code test-web-postgres} jobs both write a
     * bare {@code jdbc:postgresql://localhost:5432/docs}, and the extension passes user and password
     * to {@code DriverManager} separately rather than in the URL). The price of a false refusal is a
     * loud, self-describing failure on a test run; the price of a false permit is a dropped schema
     * on a real server.
     *
     * @param jdbcUrl PostgreSQL JDBC URL from {@code hibernate.connection.url}
     * @return A human-readable reason to refuse, or {@code null} when the reset may proceed
     */
    static String refusalReason(String jdbcUrl) {
        // The RAW authority, before any credential stripping: that is the string pgjdbc splits on
        // ',', so it is the only one in which a hidden second host is visible.
        String rawAuthority = rawAuthority(jdbcUrl);
        if (rawAuthority.indexOf(',') >= 0) {
            return multiHostReason(rawAuthority);
        }
        // Strictly subsumed by the check above today — the credential-stripped authority is a
        // suffix of the raw one — and kept anyway, so that narrowing rawAuthority() in future
        // cannot silently reopen the multi-host gap without this line also being removed.
        String authority = authority(jdbcUrl);
        if (authority.indexOf(',') >= 0) {
            return multiHostReason(authority);
        }
        String parameter = firstQueryParameter(jdbcUrl);
        if (parameter != null) {
            for (String known : ENDPOINT_OVERRIDE_PARAMS) {
                if (parameter.equals(known)) {
                    return "the JDBC URL carries the driver-level endpoint override '" + parameter
                            + "', which pgjdbc resolves after the authority, so the endpoint it connects "
                            + "to need not be the one this guard inspected.";
                }
            }
            return "the JDBC URL carries the connection parameter '" + parameter
                    + "'; no query parameter is permitted, because this guard cannot prove from the "
                    + "URL text that a parameter leaves the endpoint alone.";
        }
        String host = extractHost(jdbcUrl);
        if (!isAllowedHost(host)) {
            return "host '" + host + "' is not a local/loopback/testcontainers host.";
        }
        return null;
    }

    private static String multiHostReason(String authority) {
        return "the JDBC URL declares more than one host ('" + authority
                + "'), so the driver can fall through to an endpoint this guard never inspected.";
    }

    /**
     * The name of the first query parameter of the URL, lower-cased, or {@code null} when it has
     * none. An {@link #ENDPOINT_OVERRIDE_PARAMS} entry anywhere in the query wins over the
     * positional first, so a URL that hides {@code PGHOST} behind a harmless-looking parameter is
     * still reported by the name that matters. pgjdbc matches connection-property names
     * case-insensitively, so this comparison is case-insensitive too.
     *
     * @param jdbcUrl PostgreSQL JDBC URL
     * @return The lower-cased parameter name to refuse on, or {@code null} when there is no query
     */
    private static String firstQueryParameter(String jdbcUrl) {
        if (jdbcUrl == null) {
            return null;
        }
        int query = jdbcUrl.indexOf('?');
        if (query < 0) {
            return null;
        }
        String first = null;
        for (String pair : jdbcUrl.substring(query + 1).split("&")) {
            int equals = pair.indexOf('=');
            String name = (equals >= 0 ? pair.substring(0, equals) : pair).trim().toLowerCase();
            if (name.isEmpty()) {
                continue; // a stray '?' or '&' names no parameter
            }
            for (String known : ENDPOINT_OVERRIDE_PARAMS) {
                if (name.equals(known)) {
                    return name;
                }
            }
            if (first == null) {
                first = name;
            }
        }
        return first;
    }

    /**
     * The authority component of a PostgreSQL JDBC URL exactly as written — credentials INCLUDED,
     * because pgjdbc does not treat them as credentials: {@code host}, {@code host:port},
     * {@code [::1]:port}, or a comma-separated list of those. Empty for the host-less local forms
     * ({@code jdbc:postgresql:///db}, {@code jdbc:postgresql:db}).
     *
     * @param jdbcUrl PostgreSQL JDBC URL
     * @return The raw authority, or "" when the URL targets the local socket / no host
     */
    private static String rawAuthority(String jdbcUrl) {
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
        return authority.substring(0, end);
    }

    /**
     * {@link #rawAuthority} with anything up to and including the last {@code '@'} removed, which is
     * how a {@code user:pass@host} form is conventionally read. Used only to name the host; the
     * safety decisions that must not be fooled by an {@code '@'} are made on the raw form.
     *
     * @param jdbcUrl PostgreSQL JDBC URL
     * @return The credential-stripped authority, or "" when the URL targets the local socket
     */
    private static String authority(String jdbcUrl) {
        String authority = rawAuthority(jdbcUrl);
        int at = authority.lastIndexOf('@');
        return at >= 0 ? authority.substring(at + 1) : authority;
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
     * <p>This reads the FIRST host of the authority and nothing else — it does not, and cannot,
     * account for a multi-host authority or a driver-level endpoint override. It is therefore a
     * parser, not a safety verdict: {@link #refusalReason} is the guard.
     *
     * @param jdbcUrl PostgreSQL JDBC URL
     * @return Lower-cased host, or "" when the URL targets the local socket / no host
     */
    static String extractHost(String jdbcUrl) {
        String authority = authority(jdbcUrl);
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

    /**
     * The datasource configuration this reset acts on. Package-private and overridable for ONE
     * reason: it lets a test drive {@link #beforeAll} itself against a URL of its choosing. Without
     * that seam the guard is only reachable through {@code /hibernate.properties}, so every test has
     * to assert on {@link #refusalReason} instead — and deleting the call in {@code beforeAll} would
     * leave the whole suite green while the DROP SCHEMA ran unguarded. Production behaviour is
     * unchanged: it is always {@code /hibernate.properties}.
     *
     * @return The hibernate configuration, or {@code null} when there is none on the classpath
     */
    Properties hibernateProperties() {
        return loadHibernateProperties();
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
