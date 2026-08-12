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
     * Hibernate property holding the maximum size of its internal connection pool.
     */
    static final String POOL_SIZE_PROPERTY = "hibernate.connection.pool_size";

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
            String jdbcUser = (String) properties.get("hibernate.connection.username");
            String jdbcPassword = (String) properties.getOrDefault("hibernate.connection.password", "");

            // Keep the bootstrap connection open until the EMF is created.
            // This is required for in-memory databases (H2 mem:) where the
            // schema would be lost when the last connection closes.
            Connection bootstrapConnection = DriverManager.getConnection(jdbcUrl, jdbcUser, jdbcPassword);
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
                return properties;
            }
        } catch (IOException | IllegalArgumentException e) {
            log.error("Error reading hibernate.properties", e);
        }

        // Use environment parameters
        return buildEnvironmentProperties(
                System.getenv("DATABASE_URL"),
                System.getenv("DATABASE_USER"),
                System.getenv("DATABASE_PASSWORD"),
                System.getenv(CONNECT_TIMEOUT_ENV),
                System.getenv(SOCKET_TIMEOUT_ENV),
                System.getenv(LOGIN_TIMEOUT_ENV));
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
     * connection pool never timed out on: it has no borrow timeout and no on-checkout liveness
     * check, so every request thread parked forever acquiring a connection and only a container
     * restart recovered. Without a client-side socket timeout the JDBC read on that dead socket
     * blocks indefinitely; these bound the TCP connect, socket read and login handshake so the
     * blip surfaces as a thrown error the healthcheck recovers from instead of a permanent hang.
     * They are set only on the Postgres branch — the H2 driver does not understand them.</p>
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
        props.put("hibernate.connection.initial_pool_size", "1");
        applyPoolSize(props, null);
        props.put("hibernate.connection.pool_validation_interval", "5");
        return props;
    }

    /**
     * Resolve a client-side timeout in seconds from a raw environment value, never failing EMF
     * construction on bad input: a missing, blank, non-numeric or negative value falls back to
     * {@code defaultSeconds}. A value of {@code 0} passes through unchanged — pgjdbc reads it as
     * "infinite", the supported way an operator disables the timeout.
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
     * processing bursts (#230).</p>
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
