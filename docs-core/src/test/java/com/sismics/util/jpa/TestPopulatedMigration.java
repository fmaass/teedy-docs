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
 * Data-loss guardrail for the destructive forward-only retirement migrations 037-039
 * (route/routemodel/routestep drop, vocabulary drop, LDAP_* config delete).
 *
 * <p>The existing {@link TestPostgresMigration} only proves a BLANK schema upgrades
 * cleanly. It cannot catch an over-delete, because there is no data to over-delete.
 * This test seeds a realistic POPULATED database at db.version=36 (the state immediately
 * before the retirements), including:
 * <ul>
 *   <li>rows in every table the destructive migrations touch (T_ROUTE / T_ROUTE_MODEL /
 *       T_ROUTE_STEP, T_VOCABULARY, and the LDAP_* rows in T_CONFIG);</li>
 *   <li>rows in RETAINED tables with FK relationships (T_USER, T_DOCUMENT, T_TAG,
 *       T_DOCUMENT_TAG, T_ACL, T_FILE) so an over-delete would be observable;</li>
 *   <li>both flavours of ACL that migration 037 targets (ACL_TYPE_C='ROUTING' and an ACL
 *       whose ACL_SOURCEID_C references a route-model id) plus retained USER-type ACLs.</li>
 * </ul>
 * It then runs the REAL upgrade path ({@link DbOpenHelper#open()} reading DB_VERSION=36)
 * and asserts that after the run: db.version==44, the retired rows are gone (the workflow/
 * vocabulary tables are dropped by 037/038 and reinstated empty by 042, seeded with the
 * default review model + full vocabulary), and every retained row + FK relationship survives intact.
 *
 * <p>Runs on H2 locally (no Docker). {@link #populatedMigrationPreservesRetainedDataPostgres()}
 * runs the identical fixture on real PostgreSQL when Docker is available (CI).
 */
public class TestPopulatedMigration {

    /** Target version after the full upgrade path runs (retirements 037-039 + index 040 + LDAP-origin column 041 + workflow/vocabulary reinstatement 042 + metadata vocabulary-name column 043 + saved-filter table 044). */
    private static final int TARGET_VERSION = 44;

    /** Version the fixture is seeded at (before the retirements). */
    private static final int SEED_VERSION = 36;

    @Test
    public void populatedMigrationPreservesRetainedDataH2() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:populatedmigration;DB_CLOSE_DELAY=-1", "sa", "")) {
            connection.setAutoCommit(false);
            runScenario(connection);
        }
    }

    @Test
    public void populatedMigrationPreservesRetainedDataPostgres() throws Exception {
        // Skip (not fail) when no Docker environment is available — the H2 test still runs.
        // On CI (Docker present) this exercises the identical fixture on real PostgreSQL,
        // catching the H2-passes/PostgreSQL-fails class of native-SQL bug.
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker not available; skipping the PostgreSQL flavour of the populated-migration test");

        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17")) {
            postgres.start();
            try (Connection connection = DriverManager.getConnection(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
                connection.setAutoCommit(false);
                runScenario(connection);
            }
        }
    }

    /**
     * The full scenario, dialect-agnostic (DbOpenHelper derives the dialect from the
     * connection): build a v36 schema, seed it, prove the retired rows exist, run the
     * real upgrade, prove the retired rows are gone and the retained rows survived.
     */
    private static void runScenario(Connection connection) throws Exception {
        // 1. Build the schema up to db.version=36 using the REAL migration scripts.
        buildSchemaToSeedVersion(connection);

        // 2. Seed representative rows into retired AND retained tables.
        seedPopulatedDatabase(connection);
        connection.commit();

        // 3. Assert the retired-table rows exist BEFORE the migration (proves the fixture
        //    actually populated them, so the post-migration "gone" assertions are meaningful).
        // Assert our specific seeded rows exist (migration 015 also seeds a default route
        // model, so a raw table count would be > 1 — pin to our ids).
        Assertions.assertEquals(1, count(connection, "T_ROUTE_MODEL", "RTM_ID_C = 'rtm-1'"), "seed: route model must exist pre-migration");
        Assertions.assertEquals(1, count(connection, "T_ROUTE", "RTE_ID_C = 'rte-1'"), "seed: route must exist pre-migration");
        Assertions.assertEquals(1, count(connection, "T_ROUTE_STEP", "RTP_ID_C = 'rtp-1'"), "seed: route step must exist pre-migration");
        Assertions.assertEquals(1, count(connection, "T_VOCABULARY", "VOC_ID_C = 'voc-1'"), "seed: vocabulary must exist pre-migration");
        Assertions.assertEquals(2, countLdapConfig(connection), "seed: LDAP_* config rows must exist pre-migration");
        Assertions.assertEquals(1, count(connection, "T_ACL", "ACL_TYPE_C = 'ROUTING'"),
                "seed: a ROUTING acl must exist pre-migration");
        Assertions.assertEquals(1, count(connection, "T_ACL", "ACL_SOURCEID_C = 'rtm-1'"),
                "seed: a route-model-scoped acl must exist pre-migration");

        // Snapshot of retained data that must survive untouched.
        Assertions.assertEquals(SEED_VERSION, dbVersion(connection), "seed: DB_VERSION must be 36 before upgrade");

        // 4. Run the REAL upgrade path. open() reads DB_VERSION=36 and runs onUpgrade(36, 44).
        DbOpenHelper helper = new DbOpenHelper(connection) {
            @Override
            public void onCreate() throws Exception {
                // Must never be called: DB_VERSION=36 is present, so open() upgrades.
                throw new IllegalStateException("onCreate must not run when DB_VERSION is present");
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
                "migrations 037-044 must run cleanly on a populated database");

        // 5a. Landed on target version.
        Assertions.assertEquals(TARGET_VERSION, dbVersion(connection),
                "DB_VERSION must be 44 after the full upgrade path");

        // 5a'. Migration 040 created the tag-leading covering index on T_DOCUMENT_TAG.
        Assertions.assertTrue(indexExists(connection, "IDX_DOT_TAG"),
                "040 must create the IDX_DOT_TAG index on T_DOCUMENT_TAG");

        // 5a''. Migration 044 created the per-user saved-filter table + its unique
        //       (user, name) index. Prove the table exists and is empty (a new table
        //       never carries seed rows). The FK-resolvability and unique-index
        //       enforcement are exercised at the very end (5f) so a PostgreSQL
        //       duplicate-key error — which poisons the current transaction — cannot
        //       corrupt the intervening retained-data assertions.
        Assertions.assertTrue(tableExists(connection, "T_SAVED_FILTER"),
                "044 must create the T_SAVED_FILTER table");
        Assertions.assertEquals(0, count(connection, "T_SAVED_FILTER", "1 = 1"),
                "044 must not seed any saved-filter rows");

        // 5b. The workflow/vocabulary tables were dropped by 037/038 (wiping the old rows) and then
        //     REINSTATED empty by 042. The data-loss guardrail is that the OLD seed rows did not
        //     survive the drop: the tables exist again but the retired rows are gone.
        Assertions.assertTrue(tableExists(connection, "T_ROUTE_STEP"), "T_ROUTE_STEP must be reinstated by 042");
        Assertions.assertTrue(tableExists(connection, "T_ROUTE"), "T_ROUTE must be reinstated by 042");
        Assertions.assertTrue(tableExists(connection, "T_ROUTE_MODEL"), "T_ROUTE_MODEL must be reinstated by 042");
        Assertions.assertTrue(tableExists(connection, "T_VOCABULARY"), "T_VOCABULARY must be reinstated by 042");
        Assertions.assertEquals(0, count(connection, "T_ROUTE_STEP", "RTP_ID_C = 'rtp-1'"), "old route step row must not survive the 037 drop");
        Assertions.assertEquals(0, count(connection, "T_ROUTE", "RTE_ID_C = 'rte-1'"), "old route row must not survive the 037 drop");
        Assertions.assertEquals(0, count(connection, "T_ROUTE_MODEL", "RTM_ID_C = 'rtm-1'"), "old route model row must not survive the 037 drop");
        Assertions.assertEquals(0, count(connection, "T_VOCABULARY", "VOC_ID_C = 'voc-1'"), "old vocabulary row must not survive the 038 drop");
        // 042 reinstatement seed landed: default review route model + full vocabulary.
        Assertions.assertEquals(1, count(connection, "T_ROUTE_MODEL", "RTM_ID_C = 'default-document-review'"), "042 must seed the default review route model");
        Assertions.assertEquals(270, count(connection, "T_VOCABULARY", "1 = 1"), "042 must seed 270 vocabulary rows");
        Assertions.assertEquals(3, count(connection, "T_ROUTE_MODEL_TARGET", "RMT_IDROUTEMODEL_C = 'default-document-review'"), "042 must seed 3 index rows for the default model");

        // 5c. Retired rows are gone: ROUTING and route-model-scoped ACLs deleted; LDAP_* config deleted.
        Assertions.assertEquals(0, count(connection, "T_ACL", "ACL_TYPE_C = 'ROUTING'"),
                "037 must delete ROUTING acls");
        Assertions.assertEquals(0, count(connection, "T_ACL", "ACL_SOURCEID_C = 'rtm-1'"),
                "037 must delete route-model-scoped acls");
        Assertions.assertEquals(0, countLdapConfig(connection), "039 must delete LDAP_* config rows");

        // 5d. RETAINED data survived intact — NO over-delete. This is the data-loss gate:
        //     if 037-039 over-reached, one of these counts drops and the test fails.
        Assertions.assertEquals(2, count(connection, "T_USER", "USE_ID_C in ('u-alice','u-bob')"),
                "retained users must survive");
        Assertions.assertEquals(1, count(connection, "T_DOCUMENT", "DOC_ID_C = 'doc-1'"),
                "retained document must survive");
        Assertions.assertEquals(1, count(connection, "T_TAG", "TAG_ID_C = 'tag-1'"),
                "retained tag must survive");
        Assertions.assertEquals(1, count(connection, "T_DOCUMENT_TAG", "DOT_ID_C = 'dt-1'"),
                "retained document-tag link must survive");
        Assertions.assertEquals(1, count(connection, "T_FILE", "FIL_ID_C = 'file-1'"),
                "retained file must survive");
        // Retained USER-type ACL must survive (037 only targets ROUTING / route-model-scoped acls).
        Assertions.assertEquals(1, count(connection, "T_ACL", "ACL_ID_C = 'acl-user-1'"),
                "retained USER acl must survive");
        // A retained non-LDAP config row must survive (039 only targets LDAP_* keys).
        Assertions.assertEquals(1, count(connection, "T_CONFIG", "CFG_ID_C = 'LUCENE_DIRECTORY_STORAGE'"),
                "retained config row must survive");

        // 5e. FK integrity of retained data: the surviving document still points at its user,
        //     its file, its tag link. A silent FK-cascade over-delete would break these joins.
        Assertions.assertEquals(1, scalarCount(connection,
                        "select count(*) from T_DOCUMENT d join T_USER u on d.DOC_IDUSER_C = u.USE_ID_C where d.DOC_ID_C = 'doc-1'"),
                "retained document->user FK must remain resolvable");
        Assertions.assertEquals(1, scalarCount(connection,
                        "select count(*) from T_FILE f join T_DOCUMENT d on f.FIL_IDDOC_C = d.DOC_ID_C where f.FIL_ID_C = 'file-1'"),
                "retained file->document FK must remain resolvable");
        Assertions.assertEquals(1, scalarCount(connection,
                        "select count(*) from T_DOCUMENT_TAG dt join T_TAG t on dt.DOT_IDTAG_C = t.TAG_ID_C "
                                + "join T_DOCUMENT d on dt.DOT_IDDOCUMENT_C = d.DOC_ID_C where dt.DOT_ID_C = 'dt-1'"),
                "retained document-tag->tag/document FKs must remain resolvable");

        // 5f. Saved-filter table (044) end-to-end constraint check — LAST because a
        //     PostgreSQL duplicate-key error aborts the whole transaction. A row
        //     referencing a retained user inserts (FK to T_USER resolvable); a second
        //     row with the SAME (user, name) is rejected by IDX_SFL_USER_NAME. This is
        //     the concurrency backstop the DAO's flush-translation depends on, proven
        //     on BOTH dialects. Guard with a savepoint so the poisoned txn is recovered
        //     to a clean point on PostgreSQL before the final commit; H2 tolerates it too.
        connection.commit();
        try (Statement s = connection.createStatement()) {
            s.executeUpdate("insert into T_SAVED_FILTER (SFL_ID_C, SFL_IDUSER_C, SFL_NAME_C, SFL_QUERY_C, SFL_CREATEDATE_D) "
                    + "values ('sfl-1','u-alice','Invoices','tags=t1&search=acme',NOW())");
        }
        connection.commit();
        Assertions.assertEquals(1, count(connection, "T_SAVED_FILTER", "SFL_ID_C = 'sfl-1'"),
                "044 table must accept a saved filter referencing a retained user");
        Assertions.assertEquals(1, scalarCount(connection,
                        "select count(*) from T_SAVED_FILTER f join T_USER u on f.SFL_IDUSER_C = u.USE_ID_C where f.SFL_ID_C = 'sfl-1'"),
                "saved-filter -> user FK must be resolvable");

        boolean duplicateRejected = false;
        java.sql.Savepoint sp = connection.setSavepoint("beforeDup");
        try (Statement s = connection.createStatement()) {
            s.executeUpdate("insert into T_SAVED_FILTER (SFL_ID_C, SFL_IDUSER_C, SFL_NAME_C, SFL_QUERY_C, SFL_CREATEDATE_D) "
                    + "values ('sfl-dup','u-alice','Invoices','search=other',NOW())");
        } catch (java.sql.SQLException e) {
            duplicateRejected = true;
            connection.rollback(sp);
        }
        Assertions.assertTrue(duplicateRejected,
                "044 unique index IDX_SFL_USER_NAME must reject a duplicate (user, name) saved filter");
        // NOTE ON CASE: the index column comparison is dialect-dependent. The base schema
        // runs `SET IGNORECASE TRUE` on H2 (dbupdate-000-0.sql), so on H2 the index is
        // effectively case-INSENSITIVE; PostgreSQL is case-SENSITIVE (the exact-case
        // contract). Rather than assert dialect-divergent behaviour here, the case
        // contract is verified at the resource layer (TestSavedFilterResource), whose
        // case-insensitive precheck gives users identical behaviour on both dialects. A
        // true-race differently-cased duplicate is only possible on PostgreSQL and is
        // documented as acceptable.

        connection.commit();
    }

    /**
     * Build the schema up to {@link #SEED_VERSION} by running the real migration scripts
     * 0..36 in order, exactly as production would have reached that state.
     */
    private static void buildSchemaToSeedVersion(Connection connection) throws Exception {
        // Confirm the configured current version really is >= 39 so this test exercises the
        // real destructive migrations 037-039 rather than a no-op.
        int currentVersion = Integer.parseInt(ConfigUtil.getConfigBundle().getString("db.version"));
        Assertions.assertTrue(currentVersion >= TARGET_VERSION,
                "configured db.version must be >= " + TARGET_VERSION + " for this test to exercise 037-039");

        // Drive open() but CAP the upgrade at SEED_VERSION: open() initialises the internal
        // statement and runs onCreate (no DB_VERSION yet) then onUpgrade(0, currentVersion);
        // we ignore its newVersion and stop at 36, leaving the schema at exactly v36.
        DbOpenHelper builder = new DbOpenHelper(connection) {
            @Override
            public void onCreate() throws Exception {
                executeAllScript(0);
            }

            @Override
            public void onUpgrade(int oldVersion, int newVersion) throws Exception {
                int cap = Math.min(newVersion, SEED_VERSION);
                for (int version = oldVersion + 1; version <= cap; version++) {
                    executeAllScript(version);
                }
            }
        };
        builder.open();
        Assertions.assertTrue(builder.getExceptions().isEmpty(), "building the v36 fixture schema must run cleanly");
        Assertions.assertEquals(SEED_VERSION, dbVersion(connection), "fixture schema must be at db.version 36");
    }

    /**
     * Insert representative rows: retired tables (route*, vocabulary, LDAP config) and
     * retained tables (user, document, tag, document-tag, file, acls, config) with FKs.
     */
    private static void seedPopulatedDatabase(Connection connection) throws Exception {
        try (Statement s = connection.createStatement()) {
            // Retained users (reuse the seeded role 'user'). Columns dropped by earlier
            // migrations (locale/theme/firstconnection) are gone at v36; storage/onboarding
            // columns have defaults.
            s.executeUpdate("insert into T_USER (USE_ID_C, USE_IDROLE_C, USE_USERNAME_C, USE_PASSWORD_C, USE_EMAIL_C, USE_CREATEDATE_D, USE_PRIVATEKEY_C) values ('u-alice','user','alice','x','alice@localhost',NOW(),'pk-a')");
            s.executeUpdate("insert into T_USER (USE_ID_C, USE_IDROLE_C, USE_USERNAME_C, USE_PASSWORD_C, USE_EMAIL_C, USE_CREATEDATE_D, USE_PRIVATEKEY_C) values ('u-bob','user','bob','x','bob@localhost',NOW(),'pk-b')");

            // Retained document + file owned by alice.
            s.executeUpdate("insert into T_DOCUMENT (DOC_ID_C, DOC_IDUSER_C, DOC_TITLE_C, DOC_CREATEDATE_D, DOC_UPDATEDATE_D, DOC_LANGUAGE_C) values ('doc-1','u-alice','Retained doc',NOW(),NOW(),'eng')");
            s.executeUpdate("insert into T_FILE (FIL_ID_C, FIL_IDDOC_C, FIL_IDUSER_C, FIL_MIMETYPE_C, FIL_CREATEDATE_D) values ('file-1','doc-1','u-alice','application/pdf',NOW())");

            // Retained tag + document-tag link.
            s.executeUpdate("insert into T_TAG (TAG_ID_C, TAG_IDUSER_C, TAG_NAME_C, TAG_CREATEDATE_D) values ('tag-1','u-alice','Retained tag',NOW())");
            s.executeUpdate("insert into T_DOCUMENT_TAG (DOT_ID_C, DOT_IDDOCUMENT_C, DOT_IDTAG_C) values ('dt-1','doc-1','tag-1')");

            // Retired: route model, route (attached to the retained doc), route step.
            s.executeUpdate("insert into T_ROUTE_MODEL (RTM_ID_C, RTM_NAME_C, RTM_STEPS_C, RTM_CREATEDATE_D) values ('rtm-1','Review model','[]',NOW())");
            s.executeUpdate("insert into T_ROUTE (RTE_ID_C, RTE_IDDOCUMENT_C, RTE_NAME_C, RTE_CREATEDATE_D) values ('rte-1','doc-1','Review route',NOW())");
            s.executeUpdate("insert into T_ROUTE_STEP (RTP_ID_C, RTP_IDROUTE_C, RTP_NAME_C, RTP_TYPE_C, RTP_IDTARGET_C, RTP_ORDER_N, RTP_CREATEDATE_D) values ('rtp-1','rte-1','Step 1','VALIDATE','u-bob',0,NOW())");

            // Retired: vocabulary.
            s.executeUpdate("insert into T_VOCABULARY (VOC_ID_C, VOC_NAME_C, VOC_VALUE_C, VOC_ORDER_N) values ('voc-1','coffee','arabica',0)");

            // ACLs: a ROUTING-type (deleted by 037), a route-model-scoped (deleted by 037),
            // and a retained USER-type acl on the retained document (must survive).
            s.executeUpdate("insert into T_ACL (ACL_ID_C, ACL_PERM_C, ACL_SOURCEID_C, ACL_TARGETID_C, ACL_TYPE_C) values ('acl-routing-1','READ','doc-1','u-bob','ROUTING')");
            s.executeUpdate("insert into T_ACL (ACL_ID_C, ACL_PERM_C, ACL_SOURCEID_C, ACL_TARGETID_C, ACL_TYPE_C) values ('acl-rtm-1','READ','rtm-1','u-bob','USER')");
            s.executeUpdate("insert into T_ACL (ACL_ID_C, ACL_PERM_C, ACL_SOURCEID_C, ACL_TARGETID_C, ACL_TYPE_C) values ('acl-user-1','READ','doc-1','u-alice','USER')");

            // Retired: LDAP_* config rows (written at runtime in production; seeded here).
            s.executeUpdate("insert into T_CONFIG (CFG_ID_C, CFG_VALUE_C) values ('LDAP_ENABLED','true')");
            s.executeUpdate("insert into T_CONFIG (CFG_ID_C, CFG_VALUE_C) values ('LDAP_HOST','ldap.example.com')");
        }
    }

    // --- query helpers -------------------------------------------------------

    private static int count(Connection connection, String table) throws Exception {
        return scalarCount(connection, "select count(*) from " + table);
    }

    private static int count(Connection connection, String table, String where) throws Exception {
        return scalarCount(connection, "select count(*) from " + table + " where " + where);
    }

    private static int countLdapConfig(Connection connection) throws Exception {
        return scalarCount(connection, "select count(*) from T_CONFIG where CFG_ID_C like 'LDAP\\_%' escape '\\'");
    }

    private static int scalarCount(Connection connection, String sql) throws Exception {
        try (Statement s = connection.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            Assertions.assertTrue(rs.next(), "count query returned no row: " + sql);
            return rs.getInt(1);
        }
    }

    private static int dbVersion(Connection connection) throws Exception {
        try (Statement s = connection.createStatement();
             ResultSet rs = s.executeQuery("select CFG_VALUE_C from T_CONFIG where CFG_ID_C = 'DB_VERSION'")) {
            Assertions.assertTrue(rs.next(), "DB_VERSION row must exist");
            return Integer.parseInt(rs.getString(1));
        }
    }

    /**
     * Transaction-safe existence check. A SELECT against a dropped table aborts the current
     * PostgreSQL transaction ("current transaction is aborted"), poisoning every subsequent
     * query. Probing information_schema instead never throws for a missing table, so the
     * transaction stays clean. Case-insensitive to cover both dialects: H2 stores identifiers
     * uppercase, PostgreSQL lowercase.
     */
    private static boolean tableExists(Connection connection, String table) throws Exception {
        return scalarCount(connection,
                "select count(*) from information_schema.tables where upper(table_name) = upper('" + table + "')") > 0;
    }

    /**
     * Dialect-agnostic index existence probe via JDBC {@link java.sql.DatabaseMetaData#getIndexInfo},
     * which normalises the H2 (uppercase) / PostgreSQL (lowercase) identifier-case difference for us.
     * getIndexInfo requires the table name in the driver's stored case, so probe both.
     */
    private static boolean indexExists(Connection connection, String indexName) throws Exception {
        for (String table : new String[]{"T_DOCUMENT_TAG", "t_document_tag"}) {
            try (ResultSet rs = connection.getMetaData().getIndexInfo(null, null, table, false, false)) {
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
}
