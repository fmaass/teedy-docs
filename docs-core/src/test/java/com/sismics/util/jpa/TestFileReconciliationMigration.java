package com.sismics.util.jpa;

import com.sismics.docs.core.util.ConfigUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Migration 059 (#159): the reconciliation columns are added and every row present at upgrade time is
 * stamped COMPLETE — including a row with a NULL create date (the legacy stamp COALESCEs it, so it does
 * not leave a NULL marker that would re-select the row forever). Consequently the reconciler is a no-op on
 * the first post-upgrade boot (no active file is left unprocessed). Runs on H2 always, and on real
 * PostgreSQL when Docker is available (CI), so the native migration is proven on both engines.
 */
public class TestFileReconciliationMigration {

    private static final int PRE_VERSION = 58;
    private static final int TARGET_VERSION = 59;

    @Test
    public void legacyStampMarksAllExistingRowsH2() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:recon059;DB_CLOSE_DELAY=-1", "sa", "")) {
            connection.setAutoCommit(false);
            runScenario(connection);
        }
    }

    @Test
    public void legacyStampMarksAllExistingRowsPostgres() throws Exception {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker not available; skipping the PostgreSQL flavour of the migration-059 test");
        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17")) {
            postgres.start();
            try (Connection connection = DriverManager.getConnection(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
                connection.setAutoCommit(false);
                runScenario(connection);
            }
        }
    }

    private static void runScenario(Connection connection) throws Exception {
        // 1. Build the full schema at the version immediately before 059.
        buildSchemaToVersion(connection, PRE_VERSION);
        Assertions.assertEquals(PRE_VERSION, dbVersion(connection), "fixture must be at db.version 58");

        // 2. Seed a user and files: one with a create date, one with a NULL create date, one soft-deleted.
        try (Statement s = connection.createStatement()) {
            s.executeUpdate("insert into T_USER (USE_ID_C, USE_IDROLE_C, USE_USERNAME_C, USE_PASSWORD_C, USE_EMAIL_C, USE_CREATEDATE_D, USE_PRIVATEKEY_C) values ('recon-u','user','reconuser','x','recon@localhost',NOW(),'pk-recon')");
            s.executeUpdate("insert into T_FILE (FIL_ID_C, FIL_IDUSER_C, FIL_MIMETYPE_C, FIL_CREATEDATE_D) values ('recon-dated','recon-u','text/plain',NOW())");
            s.executeUpdate("insert into T_FILE (FIL_ID_C, FIL_IDUSER_C, FIL_MIMETYPE_C, FIL_CREATEDATE_D) values ('recon-nulldate','recon-u','text/plain',NULL)");
            s.executeUpdate("insert into T_FILE (FIL_ID_C, FIL_IDUSER_C, FIL_MIMETYPE_C, FIL_CREATEDATE_D, FIL_DELETEDATE_D) values ('recon-deleted','recon-u','text/plain',NOW(),NOW())");
        }
        connection.commit();

        // The marker column does not exist yet at v58: prove the fixture is genuinely pre-migration.
        Assertions.assertFalse(columnExists(connection, "T_FILE", "FIL_PROCESSED_D"),
                "FIL_PROCESSED_D must not exist before migration 059");

        // 3. Run the real upgrade path to 059.
        DbOpenHelper helper = new DbOpenHelper(connection) {
            @Override
            public void onCreate() {
                throw new IllegalStateException("onCreate must not run; DB_VERSION=58 is present");
            }

            @Override
            public void onUpgrade(int oldVersion, int newVersion) throws Exception {
                for (int version = oldVersion + 1; version <= newVersion; version++) {
                    executeAllScript(version);
                }
            }
        };
        helper.open();
        Assertions.assertTrue(helper.getExceptions().isEmpty(),
                "migration 059 must run clean; exceptions: " + helper.getExceptions());

        // 4. db.version advanced, the columns + index exist, and EVERY pre-existing row is stamped complete.
        Assertions.assertEquals(TARGET_VERSION, dbVersion(connection), "059 must advance db.version to 59");
        Assertions.assertTrue(columnExists(connection, "T_FILE", "FIL_PROCESSED_D"), "FIL_PROCESSED_D added");
        Assertions.assertTrue(columnExists(connection, "T_FILE", "FIL_PROCESSINGAT_D"), "FIL_PROCESSINGAT_D added");
        Assertions.assertTrue(columnExists(connection, "T_FILE", "FIL_PROCESSINGTOKEN_C"), "FIL_PROCESSINGTOKEN_C added");
        Assertions.assertTrue(columnExists(connection, "T_FILE", "FIL_PROCATTEMPTS_N"), "FIL_PROCATTEMPTS_N added");
        Assertions.assertTrue(indexExists(connection, "IDX_FIL_PROCESSED", "T_FILE"),
                "the marker scan index IDX_FIL_PROCESSED must exist");

        Assertions.assertEquals(1, scalarCount(connection,
                        "select count(*) from T_FILE where FIL_ID_C = 'recon-dated' and FIL_PROCESSED_D is not null"),
                "a dated pre-existing row is stamped complete");
        Assertions.assertEquals(1, scalarCount(connection,
                        "select count(*) from T_FILE where FIL_ID_C = 'recon-nulldate' and FIL_PROCESSED_D is not null"),
                "a NULL-create-date row is stamped complete (the legacy stamp COALESCEs it, not left NULL)");
        Assertions.assertEquals(1, scalarCount(connection,
                        "select count(*) from T_FILE where FIL_ID_C = 'recon-deleted' and FIL_PROCESSED_D is not null"),
                "even a soft-deleted row is stamped (the stamp is unconditional over existing rows)");

        // 5. The reconciler's drain predicate is zero: no active file is left unprocessed on first boot.
        Assertions.assertEquals(0, scalarCount(connection,
                        "select count(*) from T_FILE where FIL_PROCESSED_D is null and FIL_DELETEDATE_D is null"),
                "no active file is left unprocessed after the legacy stamp (reconciler is a no-op on first boot)");
    }

    // --- helpers (self-contained; the DbOpenHelper anonymous-subclass shape mirrors TestPopulatedMigration) -

    private static void buildSchemaToVersion(Connection connection, int targetVersion) throws Exception {
        DbOpenHelper helper = new DbOpenHelper(connection) {
            @Override
            public void onCreate() throws Exception {
                executeAllScript(0);
            }

            @Override
            public void onUpgrade(int oldVersion, int newVersion) throws Exception {
                for (int version = oldVersion + 1; version <= targetVersion; version++) {
                    executeAllScript(version);
                }
            }
        };
        helper.open();
        Assertions.assertTrue(helper.getExceptions().isEmpty(),
                "building the schema to v" + targetVersion + " must be clean; exceptions: " + helper.getExceptions());
    }

    private static int dbVersion(Connection connection) throws Exception {
        try (Statement s = connection.createStatement();
             ResultSet rs = s.executeQuery("select CFG_VALUE_C from T_CONFIG where CFG_ID_C = 'DB_VERSION'")) {
            Assertions.assertTrue(rs.next(), "DB_VERSION row must exist");
            return Integer.parseInt(rs.getString(1));
        }
    }

    private static int scalarCount(Connection connection, String sql) throws Exception {
        try (Statement s = connection.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            Assertions.assertTrue(rs.next(), "a scalar count must return a row");
            return rs.getInt(1);
        }
    }

    /** Transaction-safe column existence probe (case-insensitive for H2 vs PostgreSQL). */
    private static boolean columnExists(Connection connection, String table, String column) throws Exception {
        try (Statement s = connection.createStatement();
             ResultSet rs = s.executeQuery(
                     "select count(*) from information_schema.columns where upper(table_name) = upper('"
                             + table + "') and upper(column_name) = upper('" + column + "')")) {
            return rs.next() && rs.getInt(1) > 0;
        }
    }

    /** Dialect-agnostic index existence via JDBC metadata, probing both name cases and both table cases. */
    private static boolean indexExists(Connection connection, String indexName, String table) throws Exception {
        DatabaseMetaData metaData = connection.getMetaData();
        for (String tableName : new String[]{table, table.toLowerCase(), table.toUpperCase()}) {
            try (ResultSet rs = metaData.getIndexInfo(null, null, tableName, false, false)) {
                while (rs.next()) {
                    String name = rs.getString("INDEX_NAME");
                    if (name != null && name.equalsIgnoreCase(indexName)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Guard: the configured db.version must be at least the version this test upgrades to. */
    @Test
    public void configuredVersionCoversMigration() {
        int configured = Integer.parseInt(ConfigUtil.getConfigBundle().getString("db.version"));
        Assertions.assertTrue(configured >= TARGET_VERSION,
                "configured db.version (" + configured + ") must be >= " + TARGET_VERSION);
    }
}
