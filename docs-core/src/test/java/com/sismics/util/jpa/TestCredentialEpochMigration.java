package com.sismics.util.jpa;

import com.sismics.docs.core.util.ConfigUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Forced-logout migration test for dbupdate-055 (credential epoch).
 *
 * <p>Seeds a realistic db.version-54 database holding a user with an existing session token and API key
 * (both pre-epoch), runs migration 055, and asserts the forced logout: the user advances to epoch 1
 * while the token and key stay at epoch 0 — so every pre-migration credential is already epoch-dead the
 * instant the migration lands (0 != 1). Runs on H2 and, when Docker is available, on real PostgreSQL 17
 * (the {@code test-postgres} CI job), because ADD COLUMN / CHECK-constraint portability and the seed are
 * dialect-sensitive. The actual filter-level rejection of a stale credential is proven at the REST layer
 * (docs-web {@code TestCredentialEpoch}); here the assertion is the numeric forced-logout seed.</p>
 */
public class TestCredentialEpochMigration {

    private static final int PRE_VERSION = 54;

    @Test
    public void forcedLogoutSeedH2() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:credepoch055;DB_CLOSE_DELAY=-1", "sa", "")) {
            connection.setAutoCommit(false);
            runForcedLogoutScenario(connection, false);
        }
    }

    @Test
    public void forcedLogoutSeedPostgres() throws Exception {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker not available; skipping the PostgreSQL flavour of the migration-055 forced-logout test");
        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17")) {
            postgres.start();
            try (Connection connection = DriverManager.getConnection(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
                connection.setAutoCommit(false);
                runForcedLogoutScenario(connection, true);
            }
        }
    }

    private void runForcedLogoutScenario(Connection connection, boolean postgres) throws Exception {
        // Confirm the configured current version really includes 055 so this test is not a no-op.
        int currentVersion = Integer.parseInt(ConfigUtil.getConfigBundle().getString("db.version"));
        Assertions.assertTrue(currentVersion >= 55,
                "configured db.version must be >= 55 for this test to exercise migration 055");

        // Build the schema through v54 (before the epoch columns exist).
        buildSchemaToVersion(connection, PRE_VERSION);
        Assertions.assertEquals(PRE_VERSION, dbVersion(connection), "fixture must be at db.version 54 before 055");

        // Seed a user with a pre-epoch session token AND an API key.
        try (Statement s = connection.createStatement()) {
            s.executeUpdate("insert into T_USER (USE_ID_C, USE_IDROLE_C, USE_USERNAME_C, USE_PASSWORD_C, USE_EMAIL_C, USE_CREATEDATE_D, USE_PRIVATEKEY_C) values ('u-carol','user','carol','x','carol@localhost',NOW(),'pk-c')");
            s.executeUpdate("insert into T_AUTHENTICATION_TOKEN (AUT_ID_C, AUT_IDUSER_C, AUT_LONGLASTED_B, AUT_CREATIONDATE_D) values ('tok-carol','u-carol', " + (postgres ? "false" : "0") + ", NOW())");
            s.executeUpdate("insert into T_API_KEY (APK_ID_C, APK_IDUSER_C, APK_NAME_C, APK_KEYHASH_C, APK_PREFIX_C, APK_CREATEDATE_D) values ('key-carol','u-carol','k','hash-carol','tdapi_carol',NOW())");
        }
        connection.commit();

        // Run migration 055.
        runMigration(connection, 55);
        Assertions.assertTrue(dbVersion(connection) == 55, "db.version must be 55 after migration 055");

        // Forced logout: the user is at epoch 1 while its existing token and key stay at epoch 0.
        Assertions.assertEquals(1L, epoch(connection, "T_USER", "USE_CREDENTIALEPOCH_N", "USE_ID_C", "u-carol"),
                "every existing user is seeded to epoch 1");
        Assertions.assertEquals(0L, epoch(connection, "T_AUTHENTICATION_TOKEN", "AUT_CREDENTIALEPOCH_N", "AUT_ID_C", "tok-carol"),
                "an existing token stays at epoch 0 — dead against the user's epoch 1");
        Assertions.assertEquals(0L, epoch(connection, "T_API_KEY", "APK_CREDENTIALEPOCH_N", "APK_ID_C", "key-carol"),
                "an existing key stays at epoch 0 — dead against the user's epoch 1");

        if (!postgres) {
            // Rerun-safety on H2 (auto-commit DDL): resetting the version marker and re-running the script
            // must skip the already-created columns/constraints (ADD ... IF NOT EXISTS) and re-apply the
            // absolute seed cleanly. On PostgreSQL a partial failure rolls back transactionally, so the
            // plain ADD CONSTRAINT only ever runs on a clean slate — this rerun scenario is H2-specific.
            try (Statement s = connection.createStatement()) {
                s.executeUpdate("update T_CONFIG set CFG_VALUE_C = '54' where CFG_ID_C = 'DB_VERSION'");
            }
            connection.commit();
            runMigration(connection, 55);
            Assertions.assertEquals(55, dbVersion(connection), "re-running migration 055 is idempotence-safe on H2");
            Assertions.assertEquals(1L, epoch(connection, "T_USER", "USE_CREDENTIALEPOCH_N", "USE_ID_C", "u-carol"),
                    "the re-run leaves the seeded epoch unchanged");
        }
    }

    /** Builds the schema up to {@code targetVersion} by running the real migration scripts in order. */
    private static void buildSchemaToVersion(Connection connection, int targetVersion) throws Exception {
        DbOpenHelper builder = new DbOpenHelper(connection) {
            @Override
            public void onCreate() throws Exception {
                executeAllScript(0);
            }

            @Override
            public void onUpgrade(int oldVersion, int newVersion) throws Exception {
                int cap = Math.min(newVersion, targetVersion);
                for (int version = oldVersion + 1; version <= cap; version++) {
                    executeAllScript(version);
                }
            }
        };
        builder.open();
        Assertions.assertTrue(builder.getExceptions().isEmpty(),
                "building the v" + targetVersion + " fixture schema must run cleanly");
    }

    /** Runs exactly one migration version's scripts through the real DbOpenHelper. */
    private static void runMigration(Connection connection, int version) throws Exception {
        DbOpenHelper helper = new DbOpenHelper(connection) {
            @Override
            public void onCreate() {
                throw new IllegalStateException("onCreate must not run; DB_VERSION is already present");
            }

            @Override
            public void onUpgrade(int oldVersion, int newVersion) throws Exception {
                executeAllScript(version);
            }
        };
        helper.open();
        Assertions.assertTrue(helper.getExceptions().isEmpty(),
                "migration " + version + " must apply cleanly; exceptions: " + helper.getExceptions());
    }

    private static long epoch(Connection connection, String table, String column, String idColumn, String id) throws Exception {
        try (Statement s = connection.createStatement();
             ResultSet rs = s.executeQuery("select " + column + " from " + table + " where " + idColumn + " = '" + id + "'")) {
            Assertions.assertTrue(rs.next(), "row not found: " + table + "." + idColumn + "=" + id);
            return rs.getLong(1);
        }
    }

    private static int dbVersion(Connection connection) throws Exception {
        try (Statement s = connection.createStatement();
             ResultSet rs = s.executeQuery("select CFG_VALUE_C from T_CONFIG where CFG_ID_C = 'DB_VERSION'")) {
            Assertions.assertTrue(rs.next(), "DB_VERSION row must exist");
            return Integer.parseInt(rs.getString(1));
        }
    }
}
