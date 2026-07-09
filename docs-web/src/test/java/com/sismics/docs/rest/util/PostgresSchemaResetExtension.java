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
