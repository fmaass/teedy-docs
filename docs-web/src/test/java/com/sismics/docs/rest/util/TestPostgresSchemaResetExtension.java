package com.sismics.docs.rest.util;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit test for the destructive-operation guard on {@link PostgresSchemaResetExtension}
 * (BL-029). The extension DROPs the {@code public} schema; it must refuse to run unless the
 * JDBC host is an explicit local/loopback/testcontainers host, so a misconfigured
 * {@code DATABASE_URL} pointing at a real server can never silently wipe it.
 */
public class TestPostgresSchemaResetExtension {

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
}
