package com.sismics.util.jpa;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Guards the client-side connection timeouts added after the 2026-08-10 outage, where a transient
 * Postgres network blip left a dead socket that Hibernate's built-in pool never timed out on and
 * every request thread parked forever (only a container restart recovered). The fix bounds the
 * pgjdbc connect/socket/login timeouts on the production (Postgres) branch only.
 */
public class TestEmfConnectionTimeouts {

    private static final String CONNECT_KEY = "hibernate.connection.connectTimeout";
    private static final String SOCKET_KEY = "hibernate.connection.socketTimeout";
    private static final String LOGIN_KEY = "hibernate.connection.loginTimeout";

    private static Properties postgresProps(String connectEnv, String socketEnv, String loginEnv) {
        return EMF.buildEnvironmentProperties("jdbc:postgresql://db.example.invalid:5432/docs",
                "docs", "secret", connectEnv, socketEnv, loginEnv);
    }

    /**
     * Deterministic guard (runs in any environment). Asserts the production properties carry the
     * three pgjdbc timeouts with their defaults, that the environment variables override them, that
     * bad values fall back to the default, that 0 (pgjdbc's "infinite" escape hatch) passes through,
     * and that the H2 branch is left without any of them. Goes red if the timeout lines are removed
     * from EMF.
     */
    @Test
    public void productionPropertiesCarryTimeoutsWithDefaultsAndOverrides() {
        // Defaults ON out of the box: 10 / 30 / 10 seconds.
        Properties defaults = postgresProps(null, null, null);
        Assertions.assertEquals("10", defaults.getProperty(CONNECT_KEY));
        Assertions.assertEquals("30", defaults.getProperty(SOCKET_KEY));
        Assertions.assertEquals("10", defaults.getProperty(LOGIN_KEY));

        // Environment variables override the defaults verbatim.
        Properties overridden = postgresProps("3", "7", "4");
        Assertions.assertEquals("3", overridden.getProperty(CONNECT_KEY));
        Assertions.assertEquals("7", overridden.getProperty(SOCKET_KEY));
        Assertions.assertEquals("4", overridden.getProperty(LOGIN_KEY));

        // Non-numeric or blank values fall back to the default without failing construction.
        Properties bad = postgresProps("abc", "", "1.5");
        Assertions.assertEquals("10", bad.getProperty(CONNECT_KEY));
        Assertions.assertEquals("30", bad.getProperty(SOCKET_KEY));
        Assertions.assertEquals("10", bad.getProperty(LOGIN_KEY));

        // Negative values are invalid to pgjdbc (>= 0 required) and fall back to the default.
        Properties negative = postgresProps("-5", "-1", "-100");
        Assertions.assertEquals("10", negative.getProperty(CONNECT_KEY));
        Assertions.assertEquals("30", negative.getProperty(SOCKET_KEY));
        Assertions.assertEquals("10", negative.getProperty(LOGIN_KEY));

        // 0 is the supported operator escape hatch (pgjdbc reads it as "infinite") and passes through.
        Properties disabled = postgresProps("0", "0", "0");
        Assertions.assertEquals("0", disabled.getProperty(CONNECT_KEY));
        Assertions.assertEquals("0", disabled.getProperty(SOCKET_KEY));
        Assertions.assertEquals("0", disabled.getProperty(LOGIN_KEY));

        // Postgres-only scoping: the embedded H2 branch (blank URL) gets none of the timeouts, as
        // the H2 driver would not understand them.
        Properties h2 = EMF.buildEnvironmentProperties("", "sa", "", "10", "30", "10");
        Assertions.assertNull(h2.getProperty(CONNECT_KEY));
        Assertions.assertNull(h2.getProperty(SOCKET_KEY));
        Assertions.assertNull(h2.getProperty(LOGIN_KEY));
    }

    /**
     * Guards the bootstrap/migration connection built in EMF's static initializer. That connection
     * is opened with {@link java.sql.DriverManager} directly — it never goes through Hibernate's
     * pool — so without its own properties an unreachable or mid-handshake-stalling Postgres at
     * STARTUP hangs boot forever, which the container restart policy cannot recover from. The
     * bootstrap properties must carry the resolved connectTimeout and loginTimeout (fail fast, let
     * the restart policy retry) but must NOT carry socketTimeout: migrations run on this connection,
     * and a socket read timeout would kill a legitimately long dbupdate mid-apply.
     */
    @Test
    public void bootstrapConnectionCarriesConnectAndLoginTimeoutsButNotSocketTimeout() {
        // Postgres branch, defaults: connect/login carried at 10s, socketTimeout absent.
        Properties bootDefaults = EMF.buildBootstrapConnectionProperties(postgresProps(null, null, null));
        Assertions.assertEquals("10", bootDefaults.getProperty("connectTimeout"));
        Assertions.assertEquals("10", bootDefaults.getProperty("loginTimeout"));
        Assertions.assertNull(bootDefaults.getProperty("socketTimeout"),
                "socketTimeout on the bootstrap connection would kill a long dbupdate migration mid-apply");

        // Environment overrides flow through to the bootstrap connection unchanged.
        Properties bootOverridden = EMF.buildBootstrapConnectionProperties(postgresProps("3", "7", "4"));
        Assertions.assertEquals("3", bootOverridden.getProperty("connectTimeout"));
        Assertions.assertEquals("4", bootOverridden.getProperty("loginTimeout"));
        Assertions.assertNull(bootOverridden.getProperty("socketTimeout"));

        // Credentials keep the pre-existing DriverManager semantics.
        Assertions.assertEquals("docs", bootDefaults.getProperty("user"));
        Assertions.assertEquals("secret", bootDefaults.getProperty("password"));

        // H2 branch (blank URL): no timeouts at all — the H2 driver rejects unknown settings.
        Properties bootH2 = EMF.buildBootstrapConnectionProperties(
                EMF.buildEnvironmentProperties("", "sa", "", null, null, null));
        Assertions.assertNull(bootH2.getProperty("connectTimeout"));
        Assertions.assertNull(bootH2.getProperty("socketTimeout"));
        Assertions.assertNull(bootH2.getProperty("loginTimeout"));
        Assertions.assertEquals("sa", bootH2.getProperty("user"));
    }

    /**
     * Upper-bound clamp: pgjdbc converts its timeout seconds to milliseconds with a 32-bit
     * {@code seconds * 1000}, so any value above {@code Integer.MAX_VALUE / 1000} (2,147,483 s)
     * overflows to a negative/short millisecond value and breaks Socket.connect/setSoTimeout
     * instead of meaning "very long". Such values must fall back to the default like the other
     * unusable inputs; the largest non-overflowing value still passes through verbatim.
     */
    @Test
    public void overflowingTimeoutValueFallsBackToDefault() {
        int max = Integer.MAX_VALUE / 1000;

        // One past the overflow boundary falls back to the default.
        Assertions.assertEquals(30, EMF.resolveTimeoutSeconds(String.valueOf(max + 1), 30, "TEST_ENV"));
        // Integer.MAX_VALUE itself parses fine (fits an int) but would overflow *1000: fall back.
        Assertions.assertEquals(10, EMF.resolveTimeoutSeconds(String.valueOf(Integer.MAX_VALUE), 10, "TEST_ENV"));
        // The largest safe value passes through verbatim.
        Assertions.assertEquals(max, EMF.resolveTimeoutSeconds(String.valueOf(max), 30, "TEST_ENV"));

        // And through the full production properties path.
        Properties overlarge = postgresProps(String.valueOf(max + 1), String.valueOf(Integer.MAX_VALUE), "2147483648");
        Assertions.assertEquals("10", overlarge.getProperty(CONNECT_KEY));
        Assertions.assertEquals("30", overlarge.getProperty(SOCKET_KEY));
        // 2147483648 does not fit an int at all: the existing non-numeric fallback catches it.
        Assertions.assertEquals("10", overlarge.getProperty(LOGIN_KEY));
    }

    /**
     * The environment (production) path composed with the pool post-step, exactly as
     * {@code getEntityManagerProperties()} composes them — that method is private, so the
     * composition is asserted here rather than through it. Two things have to survive the
     * composition: HikariCP must be the connection provider on this path (the CI PostgreSQL jobs
     * exercise the hibernate.properties path, so only this assertion covers the wiring production
     * runs), and the pgjdbc timeouts must reach the driver. Once Hikari owns the connections, the
     * plain {@code hibernate.connection.connectTimeout} keys asserted above are no longer forwarded
     * — Hikari maps only url/username/password/driver/isolation/autocommit — so the timeouts have to
     * be repeated under {@code hibernate.hikari.dataSource.*}. Drop that forwarding and the 2026-08-10
     * dead-socket protection is silently gone again.
     */
    @Test
    public void environmentPathAppliesHikariWithTheTimeoutsForwardedToTheDriver() {
        Properties production = EMF.applyConnectionPool(postgresProps(null, null, null));

        Assertions.assertEquals("org.hibernate.hikaricp.internal.HikariCPConnectionProvider",
                production.getProperty("hibernate.connection.provider_class"));
        Assertions.assertEquals("false", production.getProperty("hibernate.hikari.autoCommit"));
        Assertions.assertEquals("10", production.getProperty("hibernate.hikari.dataSource.connectTimeout"));
        Assertions.assertEquals("30", production.getProperty("hibernate.hikari.dataSource.socketTimeout"));
        Assertions.assertEquals("10", production.getProperty("hibernate.hikari.dataSource.loginTimeout"));

        // Overrides travel the whole way too, not just the defaults.
        Properties overridden = EMF.applyConnectionPool(postgresProps("3", "7", "4"));
        Assertions.assertEquals("3", overridden.getProperty("hibernate.hikari.dataSource.connectTimeout"));
        Assertions.assertEquals("7", overridden.getProperty("hibernate.hikari.dataSource.socketTimeout"));
        Assertions.assertEquals("4", overridden.getProperty("hibernate.hikari.dataSource.loginTimeout"));

        // The embedded H2 branch gets the provider but none of the pgjdbc timeouts.
        Properties h2 = EMF.applyConnectionPool(EMF.buildEnvironmentProperties("", "sa", "", null, null, null));
        Assertions.assertEquals("org.hibernate.hikaricp.internal.HikariCPConnectionProvider",
                h2.getProperty("hibernate.connection.provider_class"));
        Assertions.assertEquals("false", h2.getProperty("hibernate.hikari.autoCommit"));
        Assertions.assertNull(h2.getProperty("hibernate.hikari.dataSource.connectTimeout"));
        Assertions.assertNull(h2.getProperty("hibernate.hikari.dataSource.socketTimeout"));
        Assertions.assertNull(h2.getProperty("hibernate.hikari.dataSource.loginTimeout"));
    }

    /**
     * Realistic-red incident reproduction of the 2026-08-10 signature: a socket that accepts the TCP
     * connection but never sends a byte back (TCP up, no data). A pgjdbc connection built with the
     * same timeout properties EMF applies — but short values — must THROW within a bounded wall-clock
     * time instead of hanging forever. Without the timeouts the read on the dead socket blocks
     * indefinitely; the bounded-time assertion then trips (the connect never returns), so the test
     * goes red if the fix is removed. Uses only a plain loopback socket — no Postgres, no Docker.
     */
    @Test
    public void deadSocketConnectionThrowsQuicklyInsteadOfHanging() throws Exception {
        ServerSocket blackHole = openBlackHoleSocket();

        final int boundSeconds = 15;
        try {
            int port = blackHole.getLocalPort();

            // Build the production properties EMF would produce, with SHORT timeouts, then pass the
            // very same timeout values through to pgjdbc (Hibernate strips the "hibernate.connection."
            // prefix at runtime; here we strip it explicitly for a direct DriverManager connection).
            Properties emfProps = postgresProps("2", "2", "2");
            Properties pg = new Properties();
            pg.setProperty("user", "docs");
            pg.setProperty("password", "secret");
            copyTimeout(emfProps, pg, "connectTimeout");
            copyTimeout(emfProps, pg, "socketTimeout");
            copyTimeout(emfProps, pg, "loginTimeout");

            String jdbcUrl = "jdbc:postgresql://127.0.0.1:" + port + "/docs";
            Class.forName("org.postgresql.Driver");

            ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "teedy69-deadsocket-connect");
                t.setDaemon(true);
                return t;
            });
            try {
                Future<Throwable> attempt = executor.submit(() -> {
                    try (Connection c = DriverManager.getConnection(jdbcUrl, pg)) {
                        return null; // unexpected: the black hole never speaks Postgres
                    } catch (Throwable t) {
                        return t;
                    }
                });

                long startNanos = System.nanoTime();
                Throwable thrown;
                try {
                    thrown = attempt.get(boundSeconds, TimeUnit.SECONDS);
                } catch (TimeoutException te) {
                    attempt.cancel(true);
                    Assertions.fail("pgjdbc connection did not return within " + boundSeconds
                            + "s against a dead socket — the client-side timeouts were not applied "
                            + "(EMF omitted socketTimeout/loginTimeout).");
                    return;
                }
                long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;

                Assertions.assertNotNull(thrown,
                        "expected the connection attempt to throw against a dead socket, but it returned");
                Assertions.assertTrue(thrown instanceof SQLException,
                        "expected a SQLException from the timeout, got: " + thrown);
                Assertions.assertTrue(elapsedMs < boundSeconds * 1000L,
                        "connection threw but only after " + elapsedMs + "ms");
            } finally {
                executor.shutdownNow();
            }
        } finally {
            blackHole.close();
        }
    }

    /**
     * End-to-end proof that the timeout survives the REAL Hibernate -> HikariCP -> pgjdbc path the
     * app runs, not just that the property lands in the map. The properties are built by the
     * production code path ({@link EMF#buildEnvironmentProperties} composed with
     * {@link EMF#applyConnectionPool}, as {@code getEntityManagerProperties()} composes them) and
     * handed to {@code Persistence.createEntityManagerFactory("transactions-optional", ...)} exactly
     * as the static EMF does, then a query is issued against a dead socket. With
     * socketTimeout/loginTimeout = 2s that query THROWS within a few seconds — which can only happen
     * if the timeouts reached pgjdbc through HikariCP's {@code dataSource} namespace. Drop that
     * forwarding (or the timeouts themselves) and the pool's very first connection attempt blocks on
     * the dead socket with nothing to bound it, so this hangs past the bound (red). No
     * Postgres/Docker — only a black-hole loopback socket, so the connection attempt has nothing to
     * read.
     */
    @Test
    public void timeoutFiresThroughRealHibernatePersistencePath() throws Exception {
        ServerSocket blackHole = openBlackHoleSocket();
        final int boundSeconds = 10;
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "teedy69-e2e-emf-connect");
            t.setDaemon(true);
            return t;
        });
        try {
            int port = blackHole.getLocalPort();

            // Build the EM properties via the SAME production code path the app runs (socket/login = 2s;
            // connect left at its default). NOT a hand-rolled props map.
            Properties props = EMF.applyConnectionPool(EMF.buildEnvironmentProperties(
                    "jdbc:postgresql://127.0.0.1:" + port + "/docs", "u", "p", null, "2", "2"));

            Future<Throwable> attempt = executor.submit(() -> {
                EntityManagerFactory emf = null;
                EntityManager em = null;
                try {
                    // The full app chain: real Hibernate EMF from the same PU, then an actual query that
                    // forces a JDBC connection through the configured connection pool.
                    emf = Persistence.createEntityManagerFactory("transactions-optional", props);
                    em = emf.createEntityManager();
                    em.createNativeQuery("select 1").getSingleResult();
                    return null; // connected + queried: impossible against a black hole
                } catch (Throwable t) {
                    return t;
                } finally {
                    if (em != null) {
                        try {
                            em.close();
                        } catch (RuntimeException ignore) {
                            // best-effort cleanup
                        }
                    }
                    if (emf != null) {
                        try {
                            emf.close();
                        } catch (RuntimeException ignore) {
                            // best-effort cleanup
                        }
                    }
                }
            });

            long startNanos = System.nanoTime();
            Throwable thrown;
            try {
                thrown = attempt.get(boundSeconds, TimeUnit.SECONDS);
            } catch (TimeoutException te) {
                attempt.cancel(true);
                Assertions.fail("the real Hibernate/pgjdbc query did not return within " + boundSeconds
                        + "s against a dead socket — socketTimeout did not reach pgjdbc through the "
                        + "pool (the client-side timeout was not applied).");
                return;
            }
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;

            Assertions.assertNotNull(thrown,
                    "expected the query to throw against a dead socket, but it returned");
            Assertions.assertTrue(elapsedMs < boundSeconds * 1000L,
                    "query threw but only after " + elapsedMs + "ms");
        } finally {
            executor.shutdownNow();
            // Closing the listener RSTs the backlogged black-hole connection, unblocking any read the
            // connect thread is still parked on (a plain socket read ignores thread interrupt).
            blackHole.close();
        }
    }

    /**
     * Open a loopback socket that accepts the TCP handshake (kernel backlog) but never sends a byte:
     * a genuine black hole with TCP up and no data, the exact 2026-08-10 signature. Aborts the test
     * (rather than failing) if a loopback socket cannot be opened in this environment.
     */
    private static ServerSocket openBlackHoleSocket() {
        try {
            return new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        } catch (IOException e) {
            Assumptions.abort("cannot open a loopback ServerSocket in this environment: " + e.getMessage());
            throw new AssertionError("unreachable: Assumptions.abort always throws", e);
        }
    }

    private static void copyTimeout(Properties emfProps, Properties pg, String pgKey) {
        String value = emfProps.getProperty("hibernate.connection." + pgKey);
        if (value != null) {
            pg.setProperty(pgKey, value);
        }
    }
}
