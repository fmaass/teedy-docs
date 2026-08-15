package com.sismics.util.jpa;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Properties;

/**
 * Guards the HikariCP pool configuration EMF applies on top of either configuration source.
 * Hibernate's built-in pool never shrank, threw as soon as it hit its cap and had neither a borrow
 * timeout nor an on-checkout liveness check, which is how the 2026-08-10 wedge and the #230 burst
 * failures happened. These assertions pin the properties that replace it: the provider itself, the
 * bounded sizing derived from the resolved pool size, and the pgjdbc timeouts, which only reach the
 * driver through Hikari's own {@code dataSource.*} namespace once Hikari owns the connections.
 */
public class TestEmfConnectionPool {

    private static final String PROVIDER_KEY = "hibernate.connection.provider_class";
    private static final String MAX_KEY = "hibernate.hikari.maximumPoolSize";
    private static final String MIN_IDLE_KEY = "hibernate.hikari.minimumIdle";

    private static Properties propsWithPoolSize(String poolSize) {
        Properties props = new Properties();
        props.put("hibernate.connection.url", "jdbc:postgresql://db.example.invalid:5432/docs");
        if (poolSize != null) {
            props.put(EMF.POOL_SIZE_PROPERTY, poolSize);
        }
        return props;
    }

    /**
     * The provider and the fixed defaults are applied, and the pool size the precedence chain
     * resolved becomes Hikari's MAXIMUM (its meaning is unchanged) while the informational
     * {@code hibernate.connection.pool_size} key survives.
     */
    @Test
    public void providerAndFixedDefaultsAreApplied() {
        Properties props = EMF.applyConnectionPool(propsWithPoolSize("30"));

        Assertions.assertEquals("org.hibernate.hikaricp.internal.HikariCPConnectionProvider",
                props.getProperty(PROVIDER_KEY));
        Assertions.assertEquals("30", props.getProperty(MAX_KEY));
        Assertions.assertEquals("2", props.getProperty(MIN_IDLE_KEY));
        Assertions.assertEquals("600000", props.getProperty("hibernate.hikari.idleTimeout"));
        Assertions.assertEquals("30000", props.getProperty("hibernate.hikari.connectionTimeout"));
        Assertions.assertEquals("teedy", props.getProperty("hibernate.hikari.poolName"));
        Assertions.assertEquals("300000", props.getProperty("hibernate.hikari.leakDetectionThreshold"));
        // Pinned false so the physical connections keep the built-in provider's semantics (Hibernate
        // owns the transaction; no per-statement autocommit). HikariCP would otherwise default it true.
        Assertions.assertEquals("false", props.getProperty("hibernate.hikari.autoCommit"));
        Assertions.assertEquals("30", props.getProperty(EMF.POOL_SIZE_PROPERTY));
    }

    /**
     * Whatever the precedence chain resolved is what Hikari gets as its maximum — no second sizing
     * rule is introduced here.
     */
    @Test
    public void maximumPoolSizeFollowsTheResolvedPoolSize() {
        for (String size : new String[] {"10", "14", "50"}) {
            Properties props = EMF.applyConnectionPool(propsWithPoolSize(size));
            Assertions.assertEquals(size, props.getProperty(MAX_KEY),
                    "maximumPoolSize must mirror the resolved pool size " + size);
        }
    }

    /**
     * The idle floor is 2, but never above the maximum: an operator pinning
     * {@code DATABASE_POOL_SIZE=1} must not get a configuration Hikari rejects.
     */
    @Test
    public void minimumIdleIsTwoButNeverExceedsTheMaximum() {
        Assertions.assertEquals("2", EMF.applyConnectionPool(propsWithPoolSize("30"))
                .getProperty(MIN_IDLE_KEY));
        Assertions.assertEquals("1", EMF.applyConnectionPool(propsWithPoolSize("1"))
                .getProperty(MIN_IDLE_KEY));
        Assertions.assertEquals("1", EMF.applyConnectionPool(propsWithPoolSize("1"))
                .getProperty(MAX_KEY));
    }

    /**
     * Hikari maps only url/username/password/driver/isolation/autocommit from
     * {@code hibernate.connection.*}; every other key of that namespace stops at the provider. The
     * pgjdbc timeouts therefore have to be repeated under {@code hibernate.hikari.dataSource.*},
     * which Hikari hands to the driver. The original keys stay for the non-Hikari readers.
     */
    @Test
    public void pgjdbcTimeoutsAreMirroredIntoTheHikariDataSourceNamespace() {
        Properties props = propsWithPoolSize("30");
        props.put("hibernate.connection.connectTimeout", "10");
        props.put("hibernate.connection.socketTimeout", "30");
        props.put("hibernate.connection.loginTimeout", "10");

        EMF.applyConnectionPool(props);

        Assertions.assertEquals("10", props.getProperty("hibernate.hikari.dataSource.connectTimeout"));
        Assertions.assertEquals("30", props.getProperty("hibernate.hikari.dataSource.socketTimeout"));
        Assertions.assertEquals("10", props.getProperty("hibernate.hikari.dataSource.loginTimeout"));
        Assertions.assertEquals("10", props.getProperty("hibernate.connection.connectTimeout"));
        Assertions.assertEquals("30", props.getProperty("hibernate.connection.socketTimeout"));
        Assertions.assertEquals("10", props.getProperty("hibernate.connection.loginTimeout"));
    }

    /**
     * The H2 branch carries none of the pgjdbc timeouts (the driver would not understand them), so
     * nothing is invented for it either.
     */
    @Test
    public void absentTimeoutsAreNotInvented() {
        Properties props = EMF.applyConnectionPool(propsWithPoolSize("14"));

        Assertions.assertNull(props.getProperty("hibernate.hikari.dataSource.connectTimeout"));
        Assertions.assertNull(props.getProperty("hibernate.hikari.dataSource.socketTimeout"));
        Assertions.assertNull(props.getProperty("hibernate.hikari.dataSource.loginTimeout"));
    }

    /**
     * A pool size that cannot be read as a number (only reachable through a hand-set
     * {@code DATABASE_POOL_SIZE}) must not add a new boot-crash path in the sizing code: the
     * provider is still selected and the sizing is left to Hikari's own defaults.
     */
    @Test
    public void unreadablePoolSizeStillSelectsTheProviderWithoutSizingKeys() {
        for (Properties props : new Properties[] {propsWithPoolSize(null), propsWithPoolSize("many")}) {
            EMF.applyConnectionPool(props);
            Assertions.assertEquals("org.hibernate.hikaricp.internal.HikariCPConnectionProvider",
                    props.getProperty(PROVIDER_KEY));
            Assertions.assertNull(props.getProperty(MAX_KEY));
            Assertions.assertNull(props.getProperty(MIN_IDLE_KEY));
        }
    }
}
