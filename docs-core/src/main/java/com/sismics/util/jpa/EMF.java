package com.sismics.util.jpa;

import com.google.common.base.Strings;
import com.sismics.docs.core.util.DirectoryUtil;
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
                return properties;
            }
        } catch (IOException | IllegalArgumentException e) {
            log.error("Error reading hibernate.properties", e);
        }
        
        // Use environment parameters
        String databaseUrl = System.getenv("DATABASE_URL");
        String databaseUsername = System.getenv("DATABASE_USER");
        String databasePassword = System.getenv("DATABASE_PASSWORD");
        String databasePoolSize = System.getenv("DATABASE_POOL_SIZE");
        if(databasePoolSize == null) {
            databasePoolSize = "10";
        }

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
        }
        props.put("hibernate.hbm2ddl.auto", "");
        props.put("hibernate.show_sql", "false");
        props.put("hibernate.format_sql", "false");
        props.put("hibernate.max_fetch_depth", "5");
        props.put("hibernate.cache.use_second_level_cache", "false");
        props.put("hibernate.connection.initial_pool_size", "1");
        props.put("hibernate.connection.pool_size", databasePoolSize);
        props.put("hibernate.connection.pool_validation_interval", "5");
        return props;
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
