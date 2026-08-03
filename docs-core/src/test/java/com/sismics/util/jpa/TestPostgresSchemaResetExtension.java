package com.sismics.util.jpa;

import java.util.Properties;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit test for the destructive-operation guard on {@link PostgresSchemaResetExtension}. The
 * extension DROPs the {@code public} schema; it must refuse to run unless the JDBC host is an
 * explicit local/loopback/testcontainers host, so a misconfigured {@code DATABASE_URL} pointing at a
 * real server can never silently wipe it.
 */
public class TestPostgresSchemaResetExtension {

    // This class BODY is duplicated verbatim in the sibling module's copy, exactly like the
    // extension it covers. Keep the two byte-identical:
    //   diff <(sed -n '/^public class TestPostgresSchemaResetExtension/,$p' <copy-a>) \
    //        <(sed -n '/^public class TestPostgresSchemaResetExtension/,$p' <copy-b>)

    // --- host extraction ---------------------------------------------------

    @Test
    public void extractsHostFromStandardUrl() {
        Assertions.assertEquals("localhost",
                PostgresSchemaResetExtension.extractHost("jdbc:postgresql://localhost:5432/docs"));
        Assertions.assertEquals("db.internal.example.com",
                PostgresSchemaResetExtension.extractHost("jdbc:postgresql://db.internal.example.com:5432/docs"));
    }

    @Test
    public void extractsHostWithoutPort() {
        Assertions.assertEquals("prod-postgres",
                PostgresSchemaResetExtension.extractHost("jdbc:postgresql://prod-postgres/docs"));
    }

    @Test
    public void extractsHostStrippingCredentials() {
        Assertions.assertEquals("evil.example.com",
                PostgresSchemaResetExtension.extractHost("jdbc:postgresql://user:pass@evil.example.com:5432/docs"));
    }

    @Test
    public void extractsBracketedIpv6Host() {
        Assertions.assertEquals("::1",
                PostgresSchemaResetExtension.extractHost("jdbc:postgresql://[::1]:5432/docs"));
    }

    @Test
    public void extractsEmptyHostForLocalSocketForms() {
        Assertions.assertEquals("",
                PostgresSchemaResetExtension.extractHost("jdbc:postgresql:///docs"));
        Assertions.assertEquals("",
                PostgresSchemaResetExtension.extractHost("jdbc:postgresql:docs"));
    }

    // --- allowlist ---------------------------------------------------------

    @Test
    public void allowsLocalAndTestcontainersHosts() {
        Assertions.assertTrue(PostgresSchemaResetExtension.isAllowedHost("localhost"));
        Assertions.assertTrue(PostgresSchemaResetExtension.isAllowedHost("127.0.0.1"));
        Assertions.assertTrue(PostgresSchemaResetExtension.isAllowedHost("127.0.1.1"));
        Assertions.assertTrue(PostgresSchemaResetExtension.isAllowedHost("::1"));
        Assertions.assertTrue(PostgresSchemaResetExtension.isAllowedHost("0:0:0:0:0:0:0:1"));
        Assertions.assertTrue(PostgresSchemaResetExtension.isAllowedHost("host.docker.internal"));
        Assertions.assertTrue(PostgresSchemaResetExtension.isAllowedHost("host.testcontainers.internal"));
        // Local-socket form (empty host).
        Assertions.assertTrue(PostgresSchemaResetExtension.isAllowedHost(""));
        Assertions.assertTrue(PostgresSchemaResetExtension.isAllowedHost(null));
    }

    @Test
    public void refusesRealServerHosts() {
        Assertions.assertFalse(PostgresSchemaResetExtension.isAllowedHost("db.internal.example.com"));
        Assertions.assertFalse(PostgresSchemaResetExtension.isAllowedHost("prod-postgres"));
        Assertions.assertFalse(PostgresSchemaResetExtension.isAllowedHost("10.0.0.5"));
        Assertions.assertFalse(PostgresSchemaResetExtension.isAllowedHost("192.168.1.50"));
        // A hostname that merely CONTAINS "localhost" must not be allowed.
        Assertions.assertFalse(PostgresSchemaResetExtension.isAllowedHost("notlocalhost.example.com"));
        // A hostname that merely starts with "127" but is not loopback (no dot) must be refused.
        Assertions.assertFalse(PostgresSchemaResetExtension.isAllowedHost("127box.example.com"));
    }

    /**
     * 127.x loopback must be accepted ONLY as a strict IPv4 literal (exactly four numeric
     * octets, each 0-255, first octet 127). A resolvable DNS hostname that merely STARTS
     * with "127." (e.g. 127.evil.example.com) is a remote server — a prefix check would
     * reach DROP SCHEMA on it.
     */
    @Test
    public void refusesLoopbackLookalikeHostnames() {
        Assertions.assertFalse(PostgresSchemaResetExtension.isAllowedHost("127.evil.example.com"),
                "a DNS hostname starting with 127. must be refused");
        Assertions.assertFalse(PostgresSchemaResetExtension.isAllowedHost("127.0.0.1.evil.example.com"),
                "a hostname embedding a full loopback literal as a prefix must be refused");
        Assertions.assertFalse(PostgresSchemaResetExtension.isAllowedHost("127.0.0.evil"),
                "non-numeric final octet must be refused");
        Assertions.assertFalse(PostgresSchemaResetExtension.isAllowedHost("127.0.0.1."),
                "trailing dot is a DNS name form, not an IPv4 literal");
        Assertions.assertFalse(PostgresSchemaResetExtension.isAllowedHost("127.0.0"),
                "fewer than four octets is not an IPv4 literal");
        Assertions.assertFalse(PostgresSchemaResetExtension.isAllowedHost("127.0.0.256"),
                "an octet above 255 is not an IPv4 literal");
        Assertions.assertFalse(PostgresSchemaResetExtension.isAllowedHost("127.0.0.1.2"),
                "more than four octets is not an IPv4 literal");
    }

    /** Genuine IPv4 loopback literals must stay allowed. */
    @Test
    public void allowsStrictLoopbackIpv4Literals() {
        Assertions.assertTrue(PostgresSchemaResetExtension.isAllowedHost("127.0.0.1"));
        Assertions.assertTrue(PostgresSchemaResetExtension.isAllowedHost("127.1.2.3"));
        Assertions.assertTrue(PostgresSchemaResetExtension.isAllowedHost("127.255.255.255"));
    }

    /**
     * The end-to-end refusal path: a JDBC URL pointing at a real server must be classified as
     * not-allowed, so {@code beforeAll} would throw rather than drop the schema. This asserts
     * the exact host+allowlist decision the extension makes before any DROP SCHEMA statement.
     */
    @Test
    public void refusesToResetAgainstMisconfiguredRealServer() {
        String misconfigured = "jdbc:postgresql://prod-db.company.internal:5432/teedy";
        String host = PostgresSchemaResetExtension.extractHost(misconfigured);
        Assertions.assertEquals("prod-db.company.internal", host);
        Assertions.assertFalse(PostgresSchemaResetExtension.isAllowedHost(host),
                "a real-server host must be refused so the schema is never dropped on it");
    }

    /**
     * The permit path: the CI/local testcontainers URL (localhost with a mapped port) must be
     * allowed, otherwise this guard would break the legitimate PostgreSQL test run.
     */
    @Test
    public void permitsResetAgainstLocalTestcontainers() {
        String local = "jdbc:postgresql://localhost:49531/test";
        Assertions.assertTrue(PostgresSchemaResetExtension.isAllowedHost(
                PostgresSchemaResetExtension.extractHost(local)));
    }

    // --- URL shapes the host parser cannot see through (#244) --------------
    //
    // Both cases below are URLs whose VISIBLE host is allowlisted while pgjdbc connects
    // somewhere else, so each test first pins the gap (the parser really does report an allowed
    // host) and only then asserts the refusal — otherwise the test could pass for the wrong
    // reason if the parser were changed later.

    /**
     * A comma-separated multi-host authority. pgjdbc tries the endpoints in order, so with the
     * first one refusing connections it falls through to the second — a host the allowlist never
     * inspected, on a URL whose text begins with localhost.
     */
    @Test
    public void refusesMultiHostAuthority() {
        String multiHost = "jdbc:postgresql://localhost:1,db.prod.example.com:5432/docs";
        Assertions.assertEquals("localhost", PostgresSchemaResetExtension.extractHost(multiHost),
                "the host parser sees only the first endpoint — that is the gap being closed");
        Assertions.assertTrue(PostgresSchemaResetExtension.isAllowedHost(
                        PostgresSchemaResetExtension.extractHost(multiHost)),
                "the allowlist alone would pass this URL");
        String refusal = PostgresSchemaResetExtension.refusalReason(multiHost);
        Assertions.assertNotNull(refusal, "a multi-host authority must be refused outright");
        Assertions.assertTrue(refusal.contains("more than one host"), refusal);
    }

    /** The same shape with credentials in front of it — the comma is behind the '@'. */
    @Test
    public void refusesMultiHostAuthorityWithCredentials() {
        Assertions.assertNotNull(PostgresSchemaResetExtension.refusalReason(
                "jdbc:postgresql://user:pass@127.0.0.1:1,db.prod.example.com:5432/docs"));
    }

    /**
     * The comma hidden in what READS as credentials, which is the harder half of the same shape:
     * pgjdbc has no userinfo concept in a JDBC URL — it splits the whole authority on ',' first and
     * only then each element on ':' — so this is two hosts to the driver, the REMOTE one first,
     * while a parser that strips through '@' is left holding nothing but localhost.
     */
    @Test
    public void refusesMultiHostAuthorityHiddenInCredentials() {
        String hidden = "jdbc:postgresql://db.prod.example.com,ignored@localhost:5432/docs";
        Assertions.assertEquals("localhost", PostgresSchemaResetExtension.extractHost(hidden),
                "credential stripping really does hide the remote host — that is the gap being closed");
        Assertions.assertTrue(PostgresSchemaResetExtension.isAllowedHost(
                        PostgresSchemaResetExtension.extractHost(hidden)),
                "the allowlist alone would pass this URL");
        String refusal = PostgresSchemaResetExtension.refusalReason(hidden);
        Assertions.assertNotNull(refusal, "a comma anywhere in the raw authority must be refused");
        Assertions.assertTrue(refusal.contains("more than one host"), refusal);
        Assertions.assertTrue(refusal.contains("db.prod.example.com"),
                "the refusal must name the host it found, not the one it was shown: " + refusal);
    }

    /**
     * {@code PGHOST}/{@code PGPORT} as query parameters. pgjdbc folds a URL's query parameters
     * into the connection properties AFTER the authority-derived ones, so they replace the
     * visible endpoint outright. Property names are matched case-insensitively by the driver, so
     * every spelling has to be refused.
     */
    @Test
    public void refusesDriverLevelEndpointOverrides() {
        String[] overridden = {
            "jdbc:postgresql://localhost:5432/docs?PGHOST=db.prod.example.com",
            "jdbc:postgresql://localhost:5432/docs?pghost=db.prod.example.com",
            "jdbc:postgresql://localhost:5432/docs?ssl=true&PgHost=db.prod.example.com",
            "jdbc:postgresql://127.0.0.1:5432/docs?PGPORT=6432",
            "jdbc:postgresql://localhost/docs?ApplicationName=teedy&pgport=6432&ssl=false",
        };
        for (String url : overridden) {
            Assertions.assertTrue(PostgresSchemaResetExtension.isAllowedHost(
                            PostgresSchemaResetExtension.extractHost(url)),
                    "the allowlist alone would pass " + url);
            String refusal = PostgresSchemaResetExtension.refusalReason(url);
            Assertions.assertNotNull(refusal, "endpoint override must be refused: " + url);
            Assertions.assertTrue(refusal.contains("endpoint override"), refusal);
        }
    }

    /**
     * {@code service=} resolves the host and port out of {@code pg_service.conf}, so it can put a
     * remote server behind a URL that names no host at all — and a host-less URL is otherwise the
     * permitted local-socket form. Verified against the pinned driver: pgjdbc 42.7 declares
     * {@code PGProperty.SERVICE}.
     */
    @Test
    public void refusesServiceParameter() {
        String service = "jdbc:postgresql:docs?service=production";
        Assertions.assertEquals("", PostgresSchemaResetExtension.extractHost(service),
                "a service URL names no host — the local-socket form the allowlist permits");
        Assertions.assertTrue(PostgresSchemaResetExtension.isAllowedHost(
                        PostgresSchemaResetExtension.extractHost(service)),
                "the allowlist alone would pass this URL");
        String refusal = PostgresSchemaResetExtension.refusalReason(service);
        Assertions.assertNotNull(refusal, "a service parameter must be refused");
        Assertions.assertTrue(refusal.contains("endpoint override"), refusal);
        Assertions.assertTrue(refusal.contains("service"), refusal);
        // The same parameter on an authority-carrying URL, and the case-insensitive spellings.
        for (String url : new String[] {
            "jdbc:postgresql://localhost:5432/docs?service=production",
            "jdbc:postgresql:docs?SERVICE=production",
            "jdbc:postgresql:///docs?Service=production",
        }) {
            Assertions.assertNotNull(PostgresSchemaResetExtension.refusalReason(url), url);
        }
    }

    /**
     * No query parameter is permitted at all — the guard fails closed rather than tracking which of
     * pgjdbc's growing property set can move the endpoint. These are harmless parameters, and they
     * are refused anyway; the refusal names the parameter so the fix is obvious.
     */
    @Test
    public void refusesAnyConnectionParameter() {
        for (String url : new String[] {
            "jdbc:postgresql://localhost:5432/docs?ssl=false",
            "jdbc:postgresql://127.0.0.1:5432/docs?ApplicationName=teedy",
            "jdbc:postgresql://localhost/docs?connectTimeout=10&currentSchema=public",
            "jdbc:postgresql:docs?socketFactory=com.example.Factory",
        }) {
            String refusal = PostgresSchemaResetExtension.refusalReason(url);
            Assertions.assertNotNull(refusal, "every query parameter must be refused: " + url);
            Assertions.assertTrue(refusal.contains("connection parameter"), refusal);
        }
    }

    /**
     * The hardening must not turn into "refuse everything": the URLs a legitimate PostgreSQL test
     * run actually uses still have to pass. Both PostgreSQL CI jobs configure a bare
     * {@code jdbc:postgresql://localhost:5432/docs} with no query string, and a host-less URL
     * WITHOUT a service parameter stays the permitted local-socket form.
     */
    @Test
    public void stillPermitsLegitimateLocalUrls() {
        String[] permitted = {
            "jdbc:postgresql://localhost:5432/docs",
            "jdbc:postgresql://localhost:49531/test",
            "jdbc:postgresql://127.0.0.1:5432/docs",
            "jdbc:postgresql://[::1]:5432/docs",
            "jdbc:postgresql://host.docker.internal:5432/docs",
            "jdbc:postgresql://user:pass@localhost:5432/docs",
            "jdbc:postgresql:///docs",
            "jdbc:postgresql:docs",
        };
        for (String url : permitted) {
            Assertions.assertNull(PostgresSchemaResetExtension.refusalReason(url),
                    "a legitimate local URL must not be refused: " + url);
        }
    }

    /** A plain real-server URL is refused by the same single entry point. */
    @Test
    public void refusesPlainRealServerUrl() {
        String refusal = PostgresSchemaResetExtension.refusalReason(
                "jdbc:postgresql://prod-db.company.internal:5432/teedy");
        Assertions.assertNotNull(refusal);
        Assertions.assertTrue(refusal.contains("prod-db.company.internal"), refusal);
    }

    // --- wiring (#244) -----------------------------------------------------
    //
    // Everything above calls the guard's PARTS. None of it touches beforeAll, so deleting the
    // guard call from the callback — or inverting it — would leave every assertion above green
    // while DROP SCHEMA ran against whatever the URL pointed at. The tests below drive the real
    // callback instead, which is the only thing that proves the guard is still wired in.

    /**
     * Build the extension with a chosen datasource configuration, so {@code beforeAll} can be
     * driven against a URL that is not the one on the test classpath.
     */
    private static PostgresSchemaResetExtension extensionFor(String jdbcUrl) {
        Properties properties = new Properties();
        properties.setProperty("hibernate.connection.url", jdbcUrl);
        properties.setProperty("hibernate.connection.username", "docs");
        properties.setProperty("hibernate.connection.password", "docs");
        return new PostgresSchemaResetExtension() {
            @Override
            Properties hibernateProperties() {
                return properties;
            }
        };
    }

    /**
     * The callback itself must refuse a non-allowlisted host.
     *
     * <p>The assertion is on the MESSAGE, not the exception type: with the guard removed this same
     * call still throws {@link IllegalStateException} — the wrapped connection failure from
     * {@code DriverManager} — so asserting the type alone would keep passing with the guard gone.
     * The refusal wording plus the absence of a cause is what proves no connection was ever
     * attempted.
     */
    @Test
    public void beforeAllRefusesANonAllowlistedHost() {
        PostgresSchemaResetExtension extension =
                extensionFor("jdbc:postgresql://prod-db.company.internal:5432/teedy");
        IllegalStateException thrown =
                Assertions.assertThrows(IllegalStateException.class, () -> extension.beforeAll(null));
        Assertions.assertTrue(thrown.getMessage().contains("REFUSES to drop the schema"),
                thrown.getMessage());
        Assertions.assertNull(thrown.getCause(),
                "the refusal must precede any connection attempt, so it carries no wrapped cause");
    }

    /** The callback must refuse the multi-host authority too, not just classify it. */
    @Test
    public void beforeAllRefusesAMultiHostAuthority() {
        PostgresSchemaResetExtension extension =
                extensionFor("jdbc:postgresql://localhost:1,db.prod.example.com:5432/docs");
        IllegalStateException thrown =
                Assertions.assertThrows(IllegalStateException.class, () -> extension.beforeAll(null));
        Assertions.assertTrue(thrown.getMessage().contains("more than one host"), thrown.getMessage());
        Assertions.assertNull(thrown.getCause());
    }

    /** ...and the driver-level endpoint override. */
    @Test
    public void beforeAllRefusesADriverLevelEndpointOverride() {
        PostgresSchemaResetExtension extension =
                extensionFor("jdbc:postgresql://localhost:5432/docs?PGHOST=db.prod.example.com");
        IllegalStateException thrown =
                Assertions.assertThrows(IllegalStateException.class, () -> extension.beforeAll(null));
        Assertions.assertTrue(thrown.getMessage().contains("endpoint override"), thrown.getMessage());
        Assertions.assertNull(thrown.getCause());
    }

    /** ...and the multi-host authority disguised as credentials. */
    @Test
    public void beforeAllRefusesAMultiHostAuthorityHiddenInCredentials() {
        PostgresSchemaResetExtension extension =
                extensionFor("jdbc:postgresql://db.prod.example.com,ignored@localhost:5432/docs");
        IllegalStateException thrown =
                Assertions.assertThrows(IllegalStateException.class, () -> extension.beforeAll(null));
        Assertions.assertTrue(thrown.getMessage().contains("more than one host"), thrown.getMessage());
        Assertions.assertNull(thrown.getCause());
    }

    /** ...and a service parameter on an otherwise-permitted host-less URL. */
    @Test
    public void beforeAllRefusesAServiceParameter() {
        PostgresSchemaResetExtension extension = extensionFor("jdbc:postgresql:docs?service=production");
        IllegalStateException thrown =
                Assertions.assertThrows(IllegalStateException.class, () -> extension.beforeAll(null));
        Assertions.assertTrue(thrown.getMessage().contains("endpoint override"), thrown.getMessage());
        Assertions.assertNull(thrown.getCause());
    }

    /**
     * The no-op path, so the guard cannot be "proven" by a callback that throws on everything:
     * the default H2 datasource must pass straight through without touching a database.
     */
    @Test
    public void beforeAllIsANoOpForNonPostgresDatasources() {
        PostgresSchemaResetExtension extension = extensionFor("jdbc:h2:mem:docs;DB_CLOSE_DELAY=-1");
        Assertions.assertDoesNotThrow(() -> extension.beforeAll(null));
    }
}
