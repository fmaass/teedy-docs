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
     * End-to-end proof that the timeout survives the REAL Hibernate -> pgjdbc path the app runs, not
     * just that the property lands in the map. The properties are built by the production code path
     * ({@link EMF#buildEnvironmentProperties}) and handed to
     * {@code Persistence.createEntityManagerFactory("transactions-optional", ...)} exactly as the
     * static EMF does, then a query is issued against a dead socket. With socketTimeout/loginTimeout
     * = 2s that query THROWS within a few seconds — which can only happen if Hibernate stripped the
     * {@code hibernate.connection.} prefix and pgjdbc actually received {@code socketTimeout}. Remove
     * the timeouts from EMF and this hangs past the bound (red). No Postgres/Docker — only a
     * black-hole loopback socket, so the connection attempt has nothing to read.
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
            Properties props = EMF.buildEnvironmentProperties(
                    "jdbc:postgresql://127.0.0.1:" + port + "/docs", "u", "p", null, "2", "2");

            Future<Throwable> attempt = executor.submit(() -> {
                EntityManagerFactory emf = null;
                EntityManager em = null;
                try {
                    // The full app chain: real Hibernate EMF from the same PU, then an actual query that
                    // forces a JDBC connection through Hibernate's built-in connection provider.
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
                        + "s against a dead socket — Hibernate did not forward socketTimeout to pgjdbc "
                        + "(the client-side timeout was not applied).");
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
