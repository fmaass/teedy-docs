package com.sismics.util.jpa;

import com.google.common.base.Strings;
import com.sismics.docs.core.util.DirectoryUtil;
import com.sismics.util.ConcurrencySizing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Properties;

/**
 * Entity manager factory.
 * 
 * @author jtremeaux
 */
public final class EMF {
    private static final Logger log = LoggerFactory.getLogger(EMF.class);

    /**
     * Hibernate property holding the maximum size of the connection pool. HikariCP reads
     * {@code hibernate.hikari.maximumPoolSize}, which {@link #applyConnectionPool} copies from this
     * key; this one stays because the resolution precedence and its tests are written against it.
     */
    static final String POOL_SIZE_PROPERTY = "hibernate.connection.pool_size";

    /**
     * Hibernate property selecting the JDBC connection provider, and the HikariCP one this
     * application runs on. Set explicitly rather than relying on Hibernate picking the bridge up
     * from the classpath, so the pool is never silently configured by someone else's defaults.
     */
    static final String PROVIDER_CLASS_PROPERTY = "hibernate.connection.provider_class";
    static final String HIKARI_PROVIDER_CLASS = "org.hibernate.hikaricp.internal.HikariCPConnectionProvider";

    /**
     * Prefix Hibernate strips off before handing a property to HikariCP. Everything under
     * {@code hibernate.hikari.dataSource.} is passed on again by HikariCP to the JDBC driver.
     */
    static final String HIKARI_PREFIX = "hibernate.hikari.";

    /**
     * Fixed pool behaviour (deliberately not configurable — see the DATABASE_POOL_SIZE
     * documentation). The idle floor keeps a couple of connections warm for the request path while
     * everything a burst opened above it is released after ten minutes, which is what stops an
     * instance from sitting on dozens of idle backends of a shared database server. The borrow
     * timeout bounds the wait a caller can spend queueing for a connection, and the leak threshold
     * turns a connection nobody gives back into a logged stack trace instead of a slow starvation.
     * Five minutes for that last one: the legitimate long holders (a mail send, a search index
     * rebuild) run well under it, so a warning means something worth reading.
     */
    static final int HIKARI_MINIMUM_IDLE = 2;
    static final String HIKARI_IDLE_TIMEOUT_MS = "600000";
    static final String HIKARI_CONNECTION_TIMEOUT_MS = "30000";
    static final String HIKARI_POOL_NAME = "teedy";
    static final String HIKARI_LEAK_DETECTION_THRESHOLD_MS = "300000";

    /**
     * Driver-level timeouts that have to be repeated in HikariCP's own namespace: HikariCP owns the
     * connections now, and Hibernate hands it only url/username/password/driver/isolation/autocommit
     * out of {@code hibernate.connection.*} — any other key of that namespace no longer reaches the
     * driver on its own.
     */
    private static final String[] DRIVER_TIMEOUT_PROPERTIES = {"connectTimeout", "socketTimeout", "loginTimeout"};

    /**
     * Environment variable overriding the connection pool size. Wins verbatim over the adaptive
     * default whenever it carries a value.
     */
    static final String POOL_SIZE_ENV = "DATABASE_POOL_SIZE";

    /**
     * Environment variables overriding the pgjdbc client-side connection timeouts (all in seconds).
     * See {@link #buildEnvironmentProperties} for why these exist. pgjdbc reads {@code 0} as
     * "infinite", which is the supported way an operator disables a given timeout.
     */
    static final String CONNECT_TIMEOUT_ENV = "DATABASE_CONNECT_TIMEOUT";
    static final String SOCKET_TIMEOUT_ENV = "DATABASE_SOCKET_TIMEOUT";
    static final String LOGIN_TIMEOUT_ENV = "DATABASE_LOGIN_TIMEOUT";

    /**
     * Default pgjdbc client-side timeouts (seconds). socketTimeout is deliberately generous
     * (no legitimate single statement in this deployment class approaches 30s) yet still bounds a
     * dead socket, so a network blip surfaces as a failed request the healthcheck recovers from
     * rather than a container that hangs unhealthy indefinitely.
     */
    static final int DEFAULT_CONNECT_TIMEOUT_SECONDS = 10;
    static final int DEFAULT_SOCKET_TIMEOUT_SECONDS = 30;
    static final int DEFAULT_LOGIN_TIMEOUT_SECONDS = 10;

    private static Properties properties;

    private static EntityManagerFactory emfInstance;

    static {
        try {
            properties = getEntityManagerProperties();

            String jdbcUrl = (String) properties.get("hibernate.connection.url");

            // Keep the bootstrap connection open until the EMF is created.
            // This is required for in-memory databases (H2 mem:) where the
            // schema would be lost when the last connection closes.
            Connection bootstrapConnection = DriverManager.getConnection(jdbcUrl,
                    buildBootstrapConnectionProperties(properties));
            bootstrapConnection.setAutoCommit(false);

            try {
                DbOpenHelper openHelper = new DbOpenHelper(bootstrapConnection) {
                    @Override
                    public void onCreate() throws Exception {
                        executeAllScript(0);
                    }

                    @Override
                    public void onUpgrade(int oldVersion, int newVersion) throws Exception {
                        for (int version = oldVersion + 1; version <= newVersion; version++) {
                            executeAllScript(version);
                        }
                    }
                };
                openHelper.open();
                failClosedIfMigrationErrors(openHelper);

                emfInstance = Persistence.createEntityManagerFactory("transactions-optional", properties);
            } finally {
                bootstrapConnection.close();
            }
        } catch (Throwable t) {
            // Fail closed: a failed migration must refuse boot, not leave a null EMF that limps on a
            // partial schema. An error in a static initializer surfaces as ExceptionInInitializerError.
            log.error("Error creating EMF", t);
            throw new ExceptionInInitializerError(t);
        }
    }
    
    /**
     * Fail closed if the migration runner recorded any error: refuse to build the EMF on a
     * partial schema. Extracted from the static initializer so the boot-refusal decision is
     * unit-testable (the static init itself only runs once per JVM with the real, clean scripts).
     *
     * @param openHelper The migration runner after open()
     */
    static void failClosedIfMigrationErrors(DbOpenHelper openHelper) {
        if (!openHelper.getExceptions().isEmpty()) {
            throw new IllegalStateException("Database schema update reported "
                    + openHelper.getExceptions().size() + " error(s); refusing to start on a partial schema");
        }
    }

    /**
     * Build the {@link DriverManager} properties for the bootstrap/migration connection from the
     * resolved EntityManager properties. Extracted from the static initializer so the boot path's
     * connection properties are unit-testable (the static init itself only runs once per JVM).
     *
     * <p>The bootstrap connection bypasses Hibernate's pool, so it does not inherit the client-side
     * timeouts of {@link #buildEnvironmentProperties}. It carries the resolved {@code connectTimeout}
     * and {@code loginTimeout} (when the Postgres branch resolved them) so an unreachable or
     * mid-handshake-stalling database at startup fails boot fast and lets the container restart
     * policy retry, instead of hanging the static initializer forever. It deliberately does NOT
     * carry {@code socketTimeout}: the dbupdate migrations run on this connection, and a socket
     * read timeout would kill a legitimately long migration mid-apply, risking a partially-applied
     * schema. The H2 branch resolves no timeouts, so nothing is copied there — the H2 driver
     * rejects unknown connection settings.</p>
     *
     * @param emProperties The resolved EntityManager properties
     * @return The DriverManager connection properties for the bootstrap connection
     */
    static Properties buildBootstrapConnectionProperties(Properties emProperties) {
        Properties props = new Properties();
        String username = (String) emProperties.get("hibernate.connection.username");
        if (username != null) {
            props.put("user", username);
        }
        props.put("password", emProperties.getOrDefault("hibernate.connection.password", ""));
        copyConnectionProperty(emProperties, props, "connectTimeout");
        copyConnectionProperty(emProperties, props, "loginTimeout");
        return props;
    }

    /**
     * Copy a resolved {@code hibernate.connection.*} entry into raw driver properties, stripping
     * the prefix Hibernate would strip at runtime. Absent entries (the H2 branch, a pinned
     * hibernate.properties file) are skipped.
     *
     * @param emProperties The resolved EntityManager properties
     * @param driverProps The raw DriverManager properties to copy into
     * @param key The driver-level property name
     */
    private static void copyConnectionProperty(Properties emProperties, Properties driverProps, String key) {
        String value = emProperties.getProperty("hibernate.connection." + key);
        if (value != null) {
            driverProps.put(key, value);
        }
    }

    private static Properties getEntityManagerProperties() {
        // Use properties file if exists
        try {
            URL hibernatePropertiesUrl = EMF.class.getResource("/hibernate.properties");
            if (hibernatePropertiesUrl != null) {
                log.info("Configuring EntityManager from hibernate.properties");

                InputStream is = hibernatePropertiesUrl.openStream();
                Properties properties = new Properties();
                properties.load(is);
                // A hibernate.properties that pins the pool size keeps it (the test configurations
                // do, deliberately). One that omits it — the shipped dev/H2 configuration — gets the
                // same adaptive default as the environment path below, because this file is read
                // BEFORE that path and would otherwise leave `mvn jetty:run` on a many-core box with
                // whatever Hibernate defaults to while the async buses size themselves off the CPU
                // count (#230).
                applyPoolSize(properties, properties.getProperty(POOL_SIZE_PROPERTY));
                return applyConnectionPool(properties);
            }
        } catch (IOException | IllegalArgumentException e) {
            log.error("Error reading hibernate.properties", e);
        }

        // Use environment parameters
        return applyConnectionPool(buildEnvironmentProperties(
                System.getenv("DATABASE_URL"),
                System.getenv("DATABASE_USER"),
                System.getenv("DATABASE_PASSWORD"),
                System.getenv(CONNECT_TIMEOUT_ENV),
                System.getenv(SOCKET_TIMEOUT_ENV),
                System.getenv(LOGIN_TIMEOUT_ENV)));
    }

    /**
     * Build the EntityManager properties from the deployment environment. Extracted from the
     * environment path of {@link #getEntityManagerProperties()} — with the timeout inputs passed
     * in rather than read from {@link System#getenv} inside — so the production (Postgres) branch
     * and its client-side timeouts are reachable from a unit test without mutating process env.
     * Runtime behaviour is unchanged: {@link #getEntityManagerProperties()} calls this with the
     * same environment variables it read before.
     *
     * <p>The Postgres branch carries client-side connect/socket/login timeouts because on
     * 2026-08-10 a transient Postgres network blip left a dead socket that Hibernate's built-in
     * connection pool never timed out on: it had no borrow timeout and no on-checkout liveness
     * check, so every request thread parked forever acquiring a connection and only a container
     * restart recovered. The pool is HikariCP now ({@link #applyConnectionPool}), which bounds the
     * borrow wait and validates a connection before handing it out; the driver-level timeouts stay
     * because they are the layer that bounds the JDBC read itself — without a client-side socket
     * timeout the read on a dead socket blocks indefinitely and the pool can only fail the borrow,
     * never the socket. They are set only on the Postgres branch — the H2 driver does not
     * understand them — and {@link #applyConnectionPool} mirrors them into HikariCP's
     * {@code dataSource} namespace, which is what actually reaches the driver.</p>
     *
     * @param databaseUrl JDBC URL from the environment (blank/null selects the embedded H2 fallback)
     * @param databaseUsername Database user (Postgres branch only)
     * @param databasePassword Database password (Postgres branch only)
     * @param connectTimeoutEnv Raw {@value #CONNECT_TIMEOUT_ENV} value, or null/blank when unset
     * @param socketTimeoutEnv Raw {@value #SOCKET_TIMEOUT_ENV} value, or null/blank when unset
     * @param loginTimeoutEnv Raw {@value #LOGIN_TIMEOUT_ENV} value, or null/blank when unset
     * @return The resolved EntityManager properties
     */
    static Properties buildEnvironmentProperties(String databaseUrl, String databaseUsername,
            String databasePassword, String connectTimeoutEnv, String socketTimeoutEnv,
            String loginTimeoutEnv) {
        log.info("Configuring EntityManager from environment parameters");
        Properties props = new Properties();
        Path dbDirectory = DirectoryUtil.getDbDirectory();
        String dbFile = dbDirectory.resolve("docs").toAbsolutePath().toString();
        if (Strings.isNullOrEmpty(databaseUrl)) {
            log.warn("Using an embedded H2 database. Only suitable for testing purpose, not for production!");
            props.put("hibernate.connection.driver_class", "org.h2.Driver");
            props.put("hibernate.dialect", "org.hibernate.dialect.HSQLDialect");
            props.put("hibernate.connection.url", "jdbc:h2:file:" + dbFile + ";CACHE_SIZE=65536;LOCK_TIMEOUT=10000");
            props.put("hibernate.connection.username", "sa");
        } else {
            props.put("hibernate.connection.driver_class", "org.postgresql.Driver");
            props.put("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect");
            props.put("hibernate.connection.url", databaseUrl);
            props.put("hibernate.connection.username", databaseUsername);
            props.put("hibernate.connection.password", databasePassword);
            // Client-side timeouts against the 2026-08-10 pool-wedge outage (see method javadoc).
            // Passed through to pgjdbc as connection properties (Hibernate strips the
            // "hibernate.connection." prefix). Values are in seconds; pgjdbc treats 0 as infinite.
            props.put("hibernate.connection.connectTimeout", String.valueOf(
                    resolveTimeoutSeconds(connectTimeoutEnv, DEFAULT_CONNECT_TIMEOUT_SECONDS, CONNECT_TIMEOUT_ENV)));
            props.put("hibernate.connection.socketTimeout", String.valueOf(
                    resolveTimeoutSeconds(socketTimeoutEnv, DEFAULT_SOCKET_TIMEOUT_SECONDS, SOCKET_TIMEOUT_ENV)));
            props.put("hibernate.connection.loginTimeout", String.valueOf(
                    resolveTimeoutSeconds(loginTimeoutEnv, DEFAULT_LOGIN_TIMEOUT_SECONDS, LOGIN_TIMEOUT_ENV)));
        }
        props.put("hibernate.hbm2ddl.auto", "");
        props.put("hibernate.show_sql", "false");
        props.put("hibernate.format_sql", "false");
        props.put("hibernate.max_fetch_depth", "5");
        props.put("hibernate.cache.use_second_level_cache", "false");
        applyPoolSize(props, null);
        return props;
    }

    /**
     * Resolve a client-side timeout in seconds from a raw environment value, never failing EMF
     * construction on bad input: a missing, blank, non-numeric, negative or overlarge value falls
     * back to {@code defaultSeconds}. A value of {@code 0} passes through unchanged — pgjdbc reads
     * it as "infinite", the supported way an operator disables the timeout. The upper bound is
     * {@code Integer.MAX_VALUE / 1000}: pgjdbc converts its timeout seconds to milliseconds with a
     * 32-bit multiply, so anything larger overflows to a negative/short value that breaks
     * Socket.connect/setSoTimeout instead of meaning "very long".
     *
     * @param envValue Raw environment value, or null/blank when unset
     * @param defaultSeconds Default to use when the value is absent or unusable
     * @param envName Environment variable name, for the warning log only
     * @return The resolved timeout in seconds
     */
    static int resolveTimeoutSeconds(String envValue, int defaultSeconds, String envName) {
        if (Strings.isNullOrEmpty(envValue)) {
            return defaultSeconds;
        }
        try {
            int parsed = Integer.parseInt(envValue.trim());
            if (parsed < 0) {
                log.warn("Ignoring negative {}={}; falling back to {}s", envName, envValue, defaultSeconds);
                return defaultSeconds;
            }
            if (parsed > Integer.MAX_VALUE / 1000) {
                log.warn("Ignoring overlarge {}={} (would overflow pgjdbc's millisecond conversion); "
                        + "falling back to {}s", envName, envValue, defaultSeconds);
                return defaultSeconds;
            }
            return parsed;
        } catch (NumberFormatException e) {
            log.warn("Ignoring non-numeric {}={}; falling back to {}s", envName, envValue, defaultSeconds);
            return defaultSeconds;
        }
    }

    /**
     * Resolve the effective connection pool size and write it into {@code props}, logging it so the
     * value a deployment is actually running with is readable from the boot log.
     *
     * <p>Precedence: the {@value #POOL_SIZE_ENV} environment variable wins unconditionally
     * (verbatim — an operator setting it must never be silently outvoted by a stale
     * hibernate.properties value), then an explicit value in the configuration source, then the
     * adaptive default derived from the CPU count by
     * {@link ConcurrencySizing#defaultConnectionPoolSize()}. The adaptive default replaces the
     * historical fixed 10, which lost to the application's own async worker count on many-core
     * hosts and produced "The internal connection pool has reached its maximum size" during
     * processing bursts (#230). The resolved value is the pool's MAXIMUM — the pool now shrinks
     * back to its idle floor between bursts, so a generous maximum no longer means a permanently
     * held set of connections ({@link #applyConnectionPool}).</p>
     *
     * @param props Properties to write the resolved size into
     * @param configuredValue Value already carried by hibernate.properties, or null/blank when absent
     *                        (the environment path has no such source and always passes null)
     */
    private static void applyPoolSize(Properties props, String configuredValue) {
        String envValue = System.getenv(POOL_SIZE_ENV);
        if (!Strings.isNullOrEmpty(envValue)) {
            log.info("Database connection pool size: {} (from {})", envValue, POOL_SIZE_ENV);
            props.put(POOL_SIZE_PROPERTY, envValue);
            return;
        }
        if (!Strings.isNullOrEmpty(configuredValue)) {
            log.info("Database connection pool size: {} (explicitly configured in hibernate.properties)",
                    configuredValue);
            props.put(POOL_SIZE_PROPERTY, configuredValue);
            return;
        }
        int availableProcessors = Runtime.getRuntime().availableProcessors();
        int poolSize = ConcurrencySizing.defaultConnectionPoolSize(availableProcessors);
        log.info("Database connection pool size: {} (adaptive default for {} available processors, "
                + "{} threads on each of {} async event buses plus headroom; override with {})",
                poolSize, availableProcessors, ConcurrencySizing.asyncBusThreadCount(availableProcessors),
                ConcurrencySizing.ASYNC_BUS_COUNT, POOL_SIZE_ENV);
        props.put(POOL_SIZE_PROPERTY, String.valueOf(poolSize));
    }
    
    /**
     * Configure HikariCP as the connection pool on top of an already-resolved configuration,
     * whichever source produced it. Run as one post-step after both branches of
     * {@link #getEntityManagerProperties()} so the pool the CI PostgreSQL jobs exercise is the pool
     * production runs.
     *
     * <p>Hibernate's built-in pool grew one connection at a time, threw
     * "The internal connection pool has reached its maximum size" the moment it was full (#230),
     * never handed a connection back to the database once opened, and had neither a borrow timeout
     * nor an on-checkout liveness check — a dead socket parked every request thread forever
     * (2026-08-10). HikariCP replaces all four behaviours: a burst waits (bounded) instead of
     * failing, connections above the idle floor are closed again after ten minutes, a borrowed
     * connection is validated, and one held longer than the leak threshold logs the stack that took
     * it as an apparent leak.</p>
     *
     * <p>The size resolved by {@link #applyPoolSize} is HikariCP's MAXIMUM; that key stays in the
     * properties as the single source of the resolved value. autoCommit is pinned false to match the
     * built-in provider's default (HikariCP would otherwise turn it on), so the swap changes nothing
     * about how a connection behaves. The pgjdbc timeouts are mirrored into
     * {@code hibernate.hikari.dataSource.*}, the only route left to the driver once HikariCP owns
     * the connections.</p>
     *
     * @param props Resolved EntityManager properties, mutated in place
     * @return The same properties, for use as a return expression
     */
    static Properties applyConnectionPool(Properties props) {
        props.put(PROVIDER_CLASS_PROPERTY, HIKARI_PROVIDER_CLASS);
        // Pin autoCommit false: the built-in provider handed out connections with autoCommit off and
        // HikariCP defaults it on, so pinning it keeps the physical-connection semantics unchanged.
        props.put(HIKARI_PREFIX + "autoCommit", "false");
        props.put(HIKARI_PREFIX + "idleTimeout", HIKARI_IDLE_TIMEOUT_MS);
        props.put(HIKARI_PREFIX + "connectionTimeout", HIKARI_CONNECTION_TIMEOUT_MS);
        props.put(HIKARI_PREFIX + "poolName", HIKARI_POOL_NAME);
        props.put(HIKARI_PREFIX + "leakDetectionThreshold", HIKARI_LEAK_DETECTION_THRESHOLD_MS);

        Integer maximumPoolSize = readResolvedPoolSize(props);
        if (maximumPoolSize != null) {
            props.put(HIKARI_PREFIX + "maximumPoolSize", String.valueOf(maximumPoolSize));
            // The floor never outgrows the ceiling: an operator pinning a pool size of 1 must get a
            // configuration HikariCP accepts, not a refusal to boot.
            props.put(HIKARI_PREFIX + "minimumIdle", String.valueOf(Math.min(HIKARI_MINIMUM_IDLE, maximumPoolSize)));
        }

        for (String name : DRIVER_TIMEOUT_PROPERTIES) {
            String value = props.getProperty("hibernate.connection." + name);
            if (value != null) {
                props.put(HIKARI_PREFIX + "dataSource." + name, value);
            }
        }
        return props;
    }

    /**
     * Read back the pool size {@link #applyPoolSize} resolved. A value that is absent or not a
     * positive number can only come from a hand-set {@value #POOL_SIZE_ENV}; it is reported and the
     * sizing is left to HikariCP's own defaults rather than turned into a new boot failure.
     *
     * @param props Resolved EntityManager properties
     * @return The pool size, or null when it cannot be read as a positive number
     */
    private static Integer readResolvedPoolSize(Properties props) {
        String configured = props.getProperty(POOL_SIZE_PROPERTY);
        if (Strings.isNullOrEmpty(configured)) {
            log.warn("No {} resolved; leaving the connection pool sizing at the pool's own defaults",
                    POOL_SIZE_PROPERTY);
            return null;
        }
        try {
            int parsed = Integer.parseInt(configured.trim());
            if (parsed < 1) {
                log.warn("Ignoring non-positive {}={}; leaving the connection pool sizing at the pool's "
                        + "own defaults", POOL_SIZE_PROPERTY, configured);
                return null;
            }
            return parsed;
        } catch (NumberFormatException e) {
            log.warn("Ignoring non-numeric {}={}; leaving the connection pool sizing at the pool's own "
                    + "defaults", POOL_SIZE_PROPERTY, configured);
            return null;
        }
    }

    /**
     * Private constructor.
     */
    private EMF() {
    }

    /**
     * Returns an instance of EMF.
     * 
     * @return Instance of EMF
     */
    public static EntityManagerFactory get() {
        return emfInstance;
    }

    public static boolean isDriverH2() {
        String driver = getDriver();
        return driver.contains("h2");
    }

    public static boolean isDriverPostgresql() {
        String driver = getDriver();
        return driver.contains("postgresql");
    }

    public static String getDriver() {
        return (String) properties.get("hibernate.connection.driver_class");
    }
}
