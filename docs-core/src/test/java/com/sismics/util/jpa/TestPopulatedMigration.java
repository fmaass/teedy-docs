package com.sismics.util.jpa;

import com.sismics.docs.core.util.ConfigUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

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
 * and asserts that after the run: db.version==55, the retired rows are gone (the workflow/
 * vocabulary tables are dropped by 037/038 and reinstated empty by 042, seeded with the
 * default review model + full vocabulary), and every retained row + FK relationship survives intact.
 *
 * <p>Runs on H2 locally (no Docker). {@link #populatedMigrationPreservesRetainedDataPostgres()}
 * runs the identical fixture on real PostgreSQL when Docker is available (CI).
 */
public class TestPopulatedMigration {

    /** Target version after the full upgrade path runs (retirements 037-039 + index 040 + LDAP-origin column 041 + workflow/vocabulary reinstatement 042 + metadata vocabulary-name column 043 + saved-filter table 044 + T_CONFIG.CFG_VALUE_C widening 045 + OIDC state provider-binding columns 046 + favorite table 047 + DOC_DESCRIPTION_C widening 048 + FIL_ROTATION_N column 049 + OIDC active-unique-username constraint 050 + T_CLEANUP_RUN protocol table 051 + CLEAN_STORAGE_LOCK sentinel 052 + T_INBOX_RECEIPT idempotency table + GLOBAL_QUOTA_LOCK sentinel 053 + T_USER locale column 054 + credential-epoch columns + forced-logout seed 055). */
    private static final int TARGET_VERSION = 55;

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

    // --- 053 receipt unique index: CONCURRENT claim race on PostgreSQL (H2 is insufficient) --------

    /**
     * The import's exactly-once guarantee rests on the receipt's unique index serializing concurrent
     * claims of the SAME (identity digest, UIDVALIDITY, UID). H2's in-memory engine cannot faithfully
     * reproduce the two-connection race, so this is a PostgreSQL-specific test: two connections race to
     * insert the same triple with a barrier; exactly ONE commit succeeds and the other fails with a
     * constraint violation (SQLState class 23). This is the DB-level backstop the InboxService's
     * claim-first + fresh-transaction-confirm control flow (exercised on H2 in the docs-web
     * TestInboxSync) depends on.
     */
    @Test
    public void receiptUniqueIndexSerializesConcurrentClaimsPostgres() throws Exception {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker not available; skipping the PostgreSQL concurrent receipt-claim race test");
        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17")) {
            postgres.start();
            String url = postgres.getJdbcUrl();
            String user = postgres.getUsername();
            String pass = postgres.getPassword();

            // Build the full schema (including T_INBOX_RECEIPT from 053) on a setup connection.
            try (Connection setup = DriverManager.getConnection(url, user, pass)) {
                setup.setAutoCommit(false);
                buildSchemaToVersion(setup, TARGET_VERSION);
            }

            final java.util.concurrent.CyclicBarrier barrier = new java.util.concurrent.CyclicBarrier(2);
            final java.util.concurrent.atomic.AtomicInteger successes = new java.util.concurrent.atomic.AtomicInteger();
            final java.util.concurrent.atomic.AtomicInteger constraintFailures = new java.util.concurrent.atomic.AtomicInteger();

            Runnable claimer = () -> {
                try (Connection connection = DriverManager.getConnection(url, user, pass)) {
                    connection.setAutoCommit(false);
                    barrier.await();
                    try (java.sql.PreparedStatement ps = connection.prepareStatement(
                            "insert into T_INBOX_RECEIPT (INR_ID_C, INR_IDENTITY_C, INR_UIDVALIDITY_N, INR_UID_N, INR_CREATEDATE_D)"
                                    + " values (?, 'race-digest', 7, 500, NOW())")) {
                        ps.setString(1, java.util.UUID.randomUUID().toString());
                        ps.executeUpdate();
                        connection.commit();
                        successes.incrementAndGet();
                    } catch (SQLException e) {
                        connection.rollback();
                        String state = e.getSQLState();
                        if (state != null && state.startsWith("23")) {
                            constraintFailures.incrementAndGet();
                        } else {
                            throw e;
                        }
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            };

            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
            try {
                java.util.concurrent.Future<?> f1 = pool.submit(claimer);
                java.util.concurrent.Future<?> f2 = pool.submit(claimer);
                f1.get(30, java.util.concurrent.TimeUnit.SECONDS);
                f2.get(30, java.util.concurrent.TimeUnit.SECONDS);
            } finally {
                pool.shutdownNow();
            }

            Assertions.assertEquals(1, successes.get(),
                    "exactly one concurrent claim of the same receipt identity must commit");
            Assertions.assertEquals(1, constraintFailures.get(),
                    "the losing concurrent claim must fail with a unique-constraint violation");

            try (Connection verify = DriverManager.getConnection(url, user, pass)) {
                Assertions.assertEquals(1, count(verify, "T_INBOX_RECEIPT",
                                "INR_IDENTITY_C = 'race-digest' and INR_UIDVALIDITY_N = 7 and INR_UID_N = 500"),
                        "exactly one receipt row must exist for the raced identity");
            }
        }
    }

    // --- migration 050 duplicate-active-username PRECONDITION ABORT (both dialects) ---------------

    @Test
    public void migration050AbortsOnDuplicateActiveUsernameH2() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:migration050abort;DB_CLOSE_DELAY=-1", "sa", "")) {
            connection.setAutoCommit(false);
            runDuplicateAbortScenario(connection);
        }
    }

    @Test
    public void migration050AbortsOnDuplicateActiveUsernamePostgres() throws Exception {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker not available; skipping the PostgreSQL flavour of the migration-050 abort test");
        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17")) {
            postgres.start();
            try (Connection connection = DriverManager.getConnection(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
                connection.setAutoCommit(false);
                runDuplicateAbortScenario(connection);
            }
        }
    }

    /**
     * Migration 050 adds a unique active-username constraint. If the DB already holds duplicate
     * ACTIVE (case-insensitive) usernames — a should-never-happen anomaly the app precheck + OIDC
     * hash suffix prevent — 050 must ABORT the whole upgrade (a controlled precondition failure)
     * rather than silently rename rows. This seeds exactly that anomaly at v49 and asserts the 050
     * step fails, DB_VERSION stays at 49 (transaction rolled back), and the index is NOT created.
     */
    private static void runDuplicateAbortScenario(Connection connection) throws Exception {
        // Build the schema through v49 (the version immediately before 050).
        buildSchemaToVersion(connection, 49);
        Assertions.assertEquals(49, dbVersion(connection), "fixture must be at db.version 49 before 050");

        // Seed a DUPLICATE ACTIVE (case-insensitive) username: 'dupe' and 'DUPE', both active.
        try (Statement s = connection.createStatement()) {
            s.executeUpdate("insert into T_USER (USE_ID_C, USE_IDROLE_C, USE_USERNAME_C, USE_PASSWORD_C, USE_EMAIL_C, USE_CREATEDATE_D, USE_PRIVATEKEY_C) values ('dup-1','user','dupe','x','d1@localhost',NOW(),'pk1')");
            s.executeUpdate("insert into T_USER (USE_ID_C, USE_IDROLE_C, USE_USERNAME_C, USE_PASSWORD_C, USE_EMAIL_C, USE_CREATEDATE_D, USE_PRIVATEKEY_C) values ('dup-2','user','DUPE','x','d2@localhost',NOW(),'pk2')");
        }
        connection.commit();

        // Run ONLY the 050 step and assert it fails (the precondition abort fires).
        final boolean[] failed = {false};
        DbOpenHelper helper = new DbOpenHelper(connection) {
            @Override
            public void onCreate() {
                throw new IllegalStateException("onCreate must not run; DB_VERSION=49 is present");
            }

            @Override
            public void onUpgrade(int oldVersion, int newVersion) throws Exception {
                // open() passes newVersion = configured db.version (50); run just the 050 script.
                executeAllScript(50);
            }
        };
        try {
            helper.open();
        } catch (IllegalStateException expected) {
            failed[0] = true;
        }
        Assertions.assertTrue(failed[0] || !helper.getExceptions().isEmpty(),
                "migration 050 must ABORT when duplicate active usernames exist (precondition failure)");

        // The failed upgrade transaction was rolled back: DB_VERSION stays at 49 and the index was
        // not created (both dupes still present, unchanged — no silent rename).
        Assertions.assertEquals(49, dbVersion(connection),
                "a failed 050 upgrade must leave DB_VERSION at 49 (transaction rolled back)");
        Assertions.assertFalse(indexExists(connection, "IDX_USER_USERNAME_ACTIVE", "T_USER"),
                "the unique index must NOT exist after an aborted 050 upgrade");
        Assertions.assertEquals(1, count(connection, "T_USER", "USE_ID_C = 'dup-1' and USE_USERNAME_C = 'dupe'"),
                "050 must NOT rename the duplicate rows (abort, not auto-rename)");
        Assertions.assertEquals(1, count(connection, "T_USER", "USE_ID_C = 'dup-2' and USE_USERNAME_C = 'DUPE'"),
                "050 must NOT rename the duplicate rows (abort, not auto-rename)");
    }

    // --- migration 050 Java preflight: NAMED collision diagnostic + fail-closed (both dialects) ----

    @Test
    public void migration050PreflightNamesCollisionsH2() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:migration050preflight;DB_CLOSE_DELAY=-1", "sa", "")) {
            connection.setAutoCommit(false);
            runPreflightNamedDiagnosticScenario(connection);
        }
    }

    @Test
    public void migration050PreflightNamesCollisionsPostgres() throws Exception {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker not available; skipping the PostgreSQL flavour of the migration-050 preflight test");
        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17")) {
            postgres.start();
            try (Connection connection = DriverManager.getConnection(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
                connection.setAutoCommit(false);
                runPreflightNamedDiagnosticScenario(connection);
            }
        }
    }

    @Test
    public void migration050PreflightIgnoresSoftDeletedH2() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:migration050softdel;DB_CLOSE_DELAY=-1", "sa", "")) {
            connection.setAutoCommit(false);
            runPreflightSoftDeletedNotFlaggedScenario(connection);
        }
    }

    @Test
    public void migration050PreflightIgnoresSoftDeletedPostgres() throws Exception {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker not available; skipping the PostgreSQL flavour of the migration-050 soft-delete test");
        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17")) {
            postgres.start();
            try (Connection connection = DriverManager.getConnection(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
                connection.setAutoCommit(false);
                runPreflightSoftDeletedNotFlaggedScenario(connection);
            }
        }
    }

    @Test
    public void migration050PreflightFiresBeforeAnyDdlFromBelow49H2() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:migration050below49;DB_CLOSE_DELAY=-1", "sa", "")) {
            connection.setAutoCommit(false);
            runPreflightFiresBeforeAnyDdlScenario(connection);
        }
    }

    @Test
    public void migration050PreflightFiresBeforeAnyDdlFromBelow49Postgres() throws Exception {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker not available; skipping the PostgreSQL flavour of the migration-050 below-49 preflight test");
        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17")) {
            postgres.start();
            try (Connection connection = DriverManager.getConnection(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
                connection.setAutoCommit(false);
                runPreflightFiresBeforeAnyDdlScenario(connection);
            }
        }
    }

    /**
     * Fix #1 proof: on a MULTI-version jump that crosses 50 from BELOW v49 (seed at v46), the
     * preflight must fire BEFORE any migration script runs — so the intervening scripts' DDL
     * (e.g. 047's T_FAVORITE) never executes and the "no database changes were applied" diagnostic
     * is TRUE. Seed a case-collision at v46, run the full upgrade to the configured target, and
     * assert: it throws {@link MigrationPreconditionException}; DB_VERSION stays 46; the 050 index
     * is absent; AND the 047 table T_FAVORITE was NOT created (proving no partial schema).
     */
    private static void runPreflightFiresBeforeAnyDdlScenario(Connection connection) throws Exception {
        buildSchemaToVersion(connection, 46);
        Assertions.assertEquals(46, dbVersion(connection), "fixture must be at db.version 46");
        // The 050 index does not exist yet at v46, so a case-collision can be seeded directly.
        Assertions.assertFalse(tableExists(connection, "T_FAVORITE"),
                "T_FAVORITE (created by 047) must not exist at v46");

        try (Statement s = connection.createStatement()) {
            s.executeUpdate("insert into T_USER (USE_ID_C, USE_IDROLE_C, USE_USERNAME_C, USE_PASSWORD_C, USE_EMAIL_C, USE_CREATEDATE_D, USE_PRIVATEKEY_C) values ('b49-1','user','Nina','x','n1@localhost',NOW(),'pk-n1')");
            s.executeUpdate("insert into T_USER (USE_ID_C, USE_IDROLE_C, USE_USERNAME_C, USE_PASSWORD_C, USE_EMAIL_C, USE_CREATEDATE_D, USE_PRIVATEKEY_C) values ('b49-2','user','NINA','x','n2@localhost',NOW(),'pk-n2')");
        }
        connection.commit();

        MigrationPreconditionException thrown = null;
        DbOpenHelper helper = new DbOpenHelper(connection) {
            @Override
            public void onCreate() {
                throw new IllegalStateException("onCreate must not run; DB_VERSION=46 is present");
            }

            @Override
            public void onUpgrade(int oldVersion, int newVersion) throws Exception {
                // If the preflight did NOT fire before this loop, 047's DDL would run and create
                // T_FAVORITE, leaving a partial schema after rollback on H2. The assertions below
                // prove that did not happen.
                for (int version = oldVersion + 1; version <= newVersion; version++) {
                    executeAllScript(version);
                }
            }
        };
        try {
            helper.open();
        } catch (IllegalStateException wrapped) {
            Throwable cause = wrapped.getCause();
            Assertions.assertInstanceOf(MigrationPreconditionException.class, cause,
                    "a below-49 upgrade that crosses 50 with a collision must fail with a MigrationPreconditionException cause");
            thrown = (MigrationPreconditionException) cause;
        }
        Assertions.assertNotNull(thrown, "the preflight must throw on a below-49 upgrade that crosses 50");
        Assertions.assertTrue(thrown.getMessage().contains("No database changes were applied"),
                "the diagnostic must state no changes were applied; was: " + thrown.getMessage());
        Assertions.assertTrue(thrown.getMessage().contains("Nina") && thrown.getMessage().contains("NINA"),
                "the diagnostic must name both colliding usernames; was: " + thrown.getMessage());

        // NO migration DDL ran: version unchanged, 050 index absent, AND the 047 table was never
        // created (the load-bearing proof the preflight fired before the loop, not inside 050).
        Assertions.assertEquals(46, dbVersion(connection),
                "a preflight abort from v46 must leave DB_VERSION at 46 (no migration ran)");
        Assertions.assertFalse(indexExists(connection, "IDX_USER_USERNAME_ACTIVE", "T_USER"),
                "the 050 index must NOT exist after a preflight-aborted below-49 upgrade");
        Assertions.assertFalse(tableExists(connection, "T_FAVORITE"),
                "047's T_FAVORITE must NOT exist — the preflight must fire BEFORE any migration DDL");
    }

    @Test
    public void migration050IdempotentAfterResolutionH2() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:migration050idem;DB_CLOSE_DELAY=-1", "sa", "")) {
            connection.setAutoCommit(false);
            runPreflightIdempotencyScenario(connection);
        }
    }

    @Test
    public void migration050IdempotentAfterResolutionPostgres() throws Exception {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker not available; skipping the PostgreSQL flavour of the migration-050 idempotency test");
        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17")) {
            postgres.start();
            try (Connection connection = DriverManager.getConnection(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
                connection.setAutoCommit(false);
                runPreflightIdempotencyScenario(connection);
            }
        }
    }

    /**
     * Seed a 2-way (MaxMuster + maxmuster) AND a 3-way (Foo/FOO/foo) active collision at v49, then
     * run the 050 step. The Java preflight must throw a {@link MigrationPreconditionException} whose
     * message NAMES each colliding group's usernames AND user IDs, states no changes were applied,
     * and points at the remediation doc — and NO schema change may result (DB_VERSION stays 49, the
     * index is absent, and no row was renamed).
     */
    private static void runPreflightNamedDiagnosticScenario(Connection connection) throws Exception {
        buildSchemaToVersion(connection, 49);
        Assertions.assertEquals(49, dbVersion(connection), "fixture must be at db.version 49 before 050");

        try (Statement s = connection.createStatement()) {
            // 2-way collision.
            s.executeUpdate("insert into T_USER (USE_ID_C, USE_IDROLE_C, USE_USERNAME_C, USE_PASSWORD_C, USE_EMAIL_C, USE_CREATEDATE_D, USE_PRIVATEKEY_C) values ('mm-1','user','MaxMuster','x','mm1@localhost',NOW(),'pk-mm1')");
            s.executeUpdate("insert into T_USER (USE_ID_C, USE_IDROLE_C, USE_USERNAME_C, USE_PASSWORD_C, USE_EMAIL_C, USE_CREATEDATE_D, USE_PRIVATEKEY_C) values ('mm-2','user','maxmuster','x','mm2@localhost',NOW(),'pk-mm2')");
            // 3-way collision.
            s.executeUpdate("insert into T_USER (USE_ID_C, USE_IDROLE_C, USE_USERNAME_C, USE_PASSWORD_C, USE_EMAIL_C, USE_CREATEDATE_D, USE_PRIVATEKEY_C) values ('foo-1','user','Foo','x','foo1@localhost',NOW(),'pk-foo1')");
            s.executeUpdate("insert into T_USER (USE_ID_C, USE_IDROLE_C, USE_USERNAME_C, USE_PASSWORD_C, USE_EMAIL_C, USE_CREATEDATE_D, USE_PRIVATEKEY_C) values ('foo-2','user','FOO','x','foo2@localhost',NOW(),'pk-foo2')");
            s.executeUpdate("insert into T_USER (USE_ID_C, USE_IDROLE_C, USE_USERNAME_C, USE_PASSWORD_C, USE_EMAIL_C, USE_CREATEDATE_D, USE_PRIVATEKEY_C) values ('foo-3','user','foo','x','foo3@localhost',NOW(),'pk-foo3')");
        }
        connection.commit();

        MigrationPreconditionException thrown = null;
        DbOpenHelper helper = new DbOpenHelper(connection) {
            @Override
            public void onCreate() {
                throw new IllegalStateException("onCreate must not run; DB_VERSION=49 is present");
            }

            @Override
            public void onUpgrade(int oldVersion, int newVersion) throws Exception {
                executeAllScript(50);
            }
        };
        try {
            helper.open();
        } catch (IllegalStateException wrapped) {
            // open() wraps the preflight exception as the cause of an IllegalStateException.
            Throwable cause = wrapped.getCause();
            Assertions.assertInstanceOf(MigrationPreconditionException.class, cause,
                    "the 050 preflight must fail closed with a MigrationPreconditionException cause");
            thrown = (MigrationPreconditionException) cause;
        }
        Assertions.assertNotNull(thrown, "the 050 preflight must throw MigrationPreconditionException on active collisions");

        String msg = thrown.getMessage();
        // Names every colliding username AND user id for both groups.
        for (String needle : new String[]{
                "MaxMuster", "maxmuster", "mm-1", "mm-2",
                "Foo", "FOO", "foo", "foo-1", "foo-2", "foo-3"}) {
            Assertions.assertTrue(msg.contains(needle),
                    "preflight message must name '" + needle + "'; was: " + msg);
        }
        Assertions.assertTrue(msg.contains("No database changes were applied"),
                "preflight message must state no changes were applied; was: " + msg);
        Assertions.assertTrue(msg.contains("README-username-collision"),
                "preflight message must point at the remediation doc; was: " + msg);

        // NO schema change: version unchanged, index absent, no rename.
        Assertions.assertEquals(49, dbVersion(connection),
                "a preflight-aborted 050 upgrade must leave DB_VERSION at 49");
        Assertions.assertFalse(indexExists(connection, "IDX_USER_USERNAME_ACTIVE", "T_USER"),
                "the unique index must NOT exist after a preflight-aborted 050 upgrade");
        Assertions.assertEquals(1, count(connection, "T_USER", "USE_ID_C = 'mm-1' and USE_USERNAME_C = 'MaxMuster'"),
                "050 preflight must NOT rename the colliding rows");
        Assertions.assertEquals(1, count(connection, "T_USER", "USE_ID_C = 'foo-2' and USE_USERNAME_C = 'FOO'"),
                "050 preflight must NOT rename the colliding rows");

        // Direct helper contract: two groups, with the expected members.
        connection.commit();
        List<UsernameCollisionPreflight.CollisionGroup> groups =
                UsernameCollisionPreflight.findActiveCollisions(connection);
        Assertions.assertEquals(2, groups.size(), "exactly two active collision groups expected");
        UsernameCollisionPreflight.CollisionGroup maxGroup = groups.stream()
                .filter(g -> g.foldedUsername.equals("maxmuster")).findFirst().orElseThrow();
        Assertions.assertEquals(2, maxGroup.usernames.size(), "the maxmuster group has 2 members");
        Assertions.assertTrue(maxGroup.userIds.containsAll(java.util.List.of("mm-1", "mm-2")),
                "the maxmuster group names both user ids");
        UsernameCollisionPreflight.CollisionGroup fooGroup = groups.stream()
                .filter(g -> g.foldedUsername.equals("foo")).findFirst().orElseThrow();
        Assertions.assertEquals(3, fooGroup.usernames.size(), "the foo group has 3 members");
    }

    /**
     * A case-variant where exactly ONE row is soft-deleted must NOT be flagged: the 050 constraint
     * is active-only. Seed active 'alice' + soft-deleted 'Alice' at v49; the preflight must find no
     * collision and the full 050 step must proceed (DB_VERSION advances, index created).
     */
    private static void runPreflightSoftDeletedNotFlaggedScenario(Connection connection) throws Exception {
        buildSchemaToVersion(connection, 49);
        Assertions.assertEquals(49, dbVersion(connection), "fixture must be at db.version 49 before 050");

        try (Statement s = connection.createStatement()) {
            s.executeUpdate("insert into T_USER (USE_ID_C, USE_IDROLE_C, USE_USERNAME_C, USE_PASSWORD_C, USE_EMAIL_C, USE_CREATEDATE_D, USE_PRIVATEKEY_C) values ('al-active','user','alice','x','al1@localhost',NOW(),'pk-al1')");
            s.executeUpdate("insert into T_USER (USE_ID_C, USE_IDROLE_C, USE_USERNAME_C, USE_PASSWORD_C, USE_EMAIL_C, USE_CREATEDATE_D, USE_DELETEDATE_D, USE_PRIVATEKEY_C) values ('al-deleted','user','Alice','x','al2@localhost',NOW(),NOW(),'pk-al2')");
        }
        connection.commit();

        // Helper contract: no active collision (the soft-deleted variant is excluded).
        Assertions.assertTrue(UsernameCollisionPreflight.findActiveCollisions(connection).isEmpty(),
                "a soft-deleted case variant must NOT be flagged as an active collision");

        // Run just the 050 step: it must proceed cleanly.
        DbOpenHelper helper = new DbOpenHelper(connection) {
            @Override
            public void onCreate() {
                throw new IllegalStateException("onCreate must not run; DB_VERSION=49 is present");
            }

            @Override
            public void onUpgrade(int oldVersion, int newVersion) throws Exception {
                executeAllScript(50);
            }
        };
        helper.open();
        Assertions.assertTrue(helper.getExceptions().isEmpty(),
                "050 must proceed when the only case variant is soft-deleted");
        Assertions.assertEquals(50, dbVersion(connection),
                "050 must advance DB_VERSION to 50 when no active collision exists");
        Assertions.assertTrue(indexExists(connection, "IDX_USER_USERNAME_ACTIVE", "T_USER"),
                "050 must create the active-username unique index when no active collision exists");
    }

    /**
     * Idempotency: (a) after collisions are resolved the same 050 path proceeds, and (b) a DB
     * already at db.version &gt;= 50 does NOT re-run 050 (so the preflight never re-fires on an
     * already-migrated DB even if a later re-introduced collision would trip it).
     */
    private static void runPreflightIdempotencyScenario(Connection connection) throws Exception {
        buildSchemaToVersion(connection, 49);

        // Seed a collision, resolve it (rename one), then confirm 050 proceeds.
        try (Statement s = connection.createStatement()) {
            s.executeUpdate("insert into T_USER (USE_ID_C, USE_IDROLE_C, USE_USERNAME_C, USE_PASSWORD_C, USE_EMAIL_C, USE_CREATEDATE_D, USE_PRIVATEKEY_C) values ('idem-1','user','Sam','x','s1@localhost',NOW(),'pk-s1')");
            s.executeUpdate("insert into T_USER (USE_ID_C, USE_IDROLE_C, USE_USERNAME_C, USE_PASSWORD_C, USE_EMAIL_C, USE_CREATEDATE_D, USE_PRIVATEKEY_C) values ('idem-2','user','sam','x','s2@localhost',NOW(),'pk-s2')");
        }
        connection.commit();
        Assertions.assertEquals(1, UsernameCollisionPreflight.findActiveCollisions(connection).size(),
                "seeded collision must be detected");

        // Resolve: rename one (the supported remediation outcome).
        try (Statement s = connection.createStatement()) {
            s.executeUpdate("update T_USER set USE_USERNAME_C = 'sam2' where USE_ID_C = 'idem-2'");
        }
        connection.commit();
        Assertions.assertTrue(UsernameCollisionPreflight.findActiveCollisions(connection).isEmpty(),
                "no collision must remain after the rename");

        // The same path now proceeds to 050.
        DbOpenHelper up = new DbOpenHelper(connection) {
            @Override
            public void onCreate() {
                throw new IllegalStateException("onCreate must not run; DB_VERSION present");
            }

            @Override
            public void onUpgrade(int oldVersion, int newVersion) throws Exception {
                for (int version = oldVersion + 1; version <= 50; version++) {
                    executeAllScript(version);
                }
            }
        };
        up.open();
        Assertions.assertTrue(up.getExceptions().isEmpty(), "050 must proceed after resolution");
        Assertions.assertEquals(50, dbVersion(connection), "DB advanced to 50 after resolution");

        // (b) Already at >=50 with a case-collision PRESENT in the data: the preflight must be
        //     SKIPPED (oldVersion >= 50), so an upgrade to the target does NOT throw. To create a
        //     genuine active case-collision at v50 we must first drop the 050 unique index (which
        //     would otherwise reject the second active insert); the collision then exists in the
        //     rows exactly as a re-introduced anomaly would, yet the >=50 upgrade completes cleanly
        //     because the crossing-50 preflight never fires. This is the real proof of the skip.
        try (Statement s = connection.createStatement()) {
            s.executeUpdate("drop index IDX_USER_USERNAME_ACTIVE");
        }
        connection.commit();
        try (Statement s = connection.createStatement()) {
            s.executeUpdate("insert into T_USER (USE_ID_C, USE_IDROLE_C, USE_USERNAME_C, USE_PASSWORD_C, USE_EMAIL_C, USE_CREATEDATE_D, USE_PRIVATEKEY_C) values ('reintro-1','user','Kim','x','k1@localhost',NOW(),'pk-k1')");
            s.executeUpdate("insert into T_USER (USE_ID_C, USE_IDROLE_C, USE_USERNAME_C, USE_PASSWORD_C, USE_EMAIL_C, USE_CREATEDATE_D, USE_PRIVATEKEY_C) values ('reintro-2','user','KIM','x','k2@localhost',NOW(),'pk-k2')");
        }
        connection.commit();
        Assertions.assertEquals(1, UsernameCollisionPreflight.findActiveCollisions(connection).size(),
                "a genuine active case-collision must be present at v50 for this to prove the skip");

        DbOpenHelper rerun = new DbOpenHelper(connection) {
            @Override
            public void onCreate() {
                throw new IllegalStateException("onCreate must not run; DB_VERSION present");
            }

            @Override
            public void onUpgrade(int oldVersion, int newVersion) throws Exception {
                Assertions.assertTrue(oldVersion >= 50,
                        "an already-migrated DB must report oldVersion >= 50 so the crossing-50 preflight is skipped");
                for (int version = oldVersion + 1; version <= newVersion; version++) {
                    executeAllScript(version);
                }
            }
        };
        // The whole point: this must NOT throw even though a case-collision is present, because the
        // crossing-50 preflight is skipped for oldVersion >= 50.
        rerun.open();
        Assertions.assertTrue(rerun.getExceptions().isEmpty(),
                "an upgrade from >=50 must skip the preflight and complete cleanly despite a present collision");
        int target = Integer.parseInt(ConfigUtil.getConfigBundle().getString("db.version"));
        Assertions.assertEquals(target, dbVersion(connection),
                "the DB lands on the configured target version without re-running (or re-firing) the 050 preflight");
    }

    // --- remediation SCRIPTS: H2 leaves DB unchanged on bad input; N-way group resolvable ----------

    /**
     * FIX #1 proof (H2 script). Running the real H2 remediation Section-2 block through a
     * stop-on-error runner (H2 {@code RunScript}) with BAD input must leave T_USER UNCHANGED —
     * no rename persisted. Two bad inputs are proven, each on a fresh v49 fixture:
     * (a) a missing target USE_ID_C, and (b) a new name that case-insensitively collides with
     * another active user. In both cases the pre-check guard forces a NOT-NULL-PK violation that
     * halts the run BEFORE the rename and BEFORE COMMIT, so nothing is committed.
     *
     * <p>H2 only: the mechanism is the RunScript stop-on-error halt. On PostgreSQL the equivalent
     * guard {@code RAISE}s and aborts the transaction (covered structurally by the script design);
     * this behavioural test exercises the H2-specific abort mechanism that FIX #1 addresses.
     */
    @Test
    public void remediationH2BadInputLeavesDbUnchanged() throws Exception {
        // (a) missing target id.
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:remH2badTarget;DB_CLOSE_DELAY=-1", "sa", "")) {
            connection.setAutoCommit(false);
            seedThreeWayCollisionAtV49(connection);
            String block = h2Section2()
                    .replace("<TARGET_ID>", "no-such-id")
                    .replace("<NEW_NAME>", "foo_one")
                    .replace("<OLD_NAME>", "Foo");
            boolean halted = runH2Section2ExpectingHalt(connection, block);
            Assertions.assertTrue(halted,
                    "the H2 remediation must HALT on a missing target id (pre-check guard fires)");
            assertUsernameUnchanged(connection, "foo-1", "Foo");
            assertUsernameUnchanged(connection, "foo-2", "FOO");
            assertUsernameUnchanged(connection, "foo-3", "foo");
        }

        // (b) new name collides case-insensitively with another active user.
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:remH2badCollide;DB_CLOSE_DELAY=-1", "sa", "")) {
            connection.setAutoCommit(false);
            seedThreeWayCollisionAtV49(connection);
            String block = h2Section2()
                    .replace("<TARGET_ID>", "foo-1")
                    .replace("<NEW_NAME>", "FOO")   // collides (case-insensitively) with foo-2 'FOO'
                    .replace("<OLD_NAME>", "Foo");
            boolean halted = runH2Section2ExpectingHalt(connection, block);
            Assertions.assertTrue(halted,
                    "the H2 remediation must HALT when the new name collides with another active user");
            assertUsernameUnchanged(connection, "foo-1", "Foo");
            assertUsernameUnchanged(connection, "foo-2", "FOO");
            assertUsernameUnchanged(connection, "foo-3", "foo");
        }
    }

    /**
     * FIX #2 proof (H2 script). A genuine 3-way ACTIVE collision (Foo/FOO/foo) is fully resolved by
     * TWO successive single-member renames to distinct non-colliding names, with NO spurious abort
     * (the old-name "still collides" check was removed), and the global Section-3 check then reports
     * zero remaining collisions. Proven against the REAL H2 remediation Section-2 block via RunScript.
     */
    @Test
    public void remediationH2ThreeWayCollisionResolvesOneAtATime() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:remH2threeway;DB_CLOSE_DELAY=-1", "sa", "")) {
            connection.setAutoCommit(false);
            seedThreeWayCollisionAtV49(connection);
            String block = h2Section2();

            // Rename #1: foo-1 (Foo) -> foo_one. foo-2/foo-3 still collide; this must NOT abort.
            org.h2.tools.RunScript.execute(connection, new java.io.StringReader(
                    block.replace("<TARGET_ID>", "foo-1")
                         .replace("<NEW_NAME>", "foo_one")
                         .replace("<OLD_NAME>", "Foo")));
            assertUsernameUnchanged(connection, "foo-1", "foo_one");

            // Rename #2: foo-2 (FOO) -> foo_two. Now no group collides.
            org.h2.tools.RunScript.execute(connection, new java.io.StringReader(
                    block.replace("<TARGET_ID>", "foo-2")
                         .replace("<NEW_NAME>", "foo_two")
                         .replace("<OLD_NAME>", "FOO")));
            assertUsernameUnchanged(connection, "foo-2", "foo_two");
            assertUsernameUnchanged(connection, "foo-3", "foo");   // the kept member is untouched

            // Global Section-3 check: zero colliding active groups remain.
            Assertions.assertEquals(0, scalarCount(connection,
                    "select count(*) from (select lower(USE_USERNAME_C) un from T_USER "
                            + "where USE_DELETEDATE_D is null group by lower(USE_USERNAME_C) having count(*) > 1) g"),
                    "after two single-member renames the global collision check must report zero groups");
        }
    }

    /**
     * FIX #2 proof (PostgreSQL script), when Docker is available. The same 3-way collision is
     * resolved one member at a time by running the REAL PostgreSQL Section-2 block (with the psql
     * {@code :'var'} variables pre-substituted, since JDBC has no psql variable layer) — no spurious
     * abort — and the global check then reports zero collisions. Skipped (not failed) without Docker.
     */
    @Test
    public void remediationPostgresThreeWayCollisionResolvesOneAtATime() throws Exception {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker not available; skipping the PostgreSQL flavour of the remediation-script test");
        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17")) {
            postgres.start();
            try (Connection connection = DriverManager.getConnection(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
                connection.setAutoCommit(true);   // let each pg Section-2 block manage its own BEGIN/COMMIT
                seedThreeWayCollisionAtV49Postgres(connection);

                runPgSection2(connection, "foo-1", "foo_one", "Foo");
                assertUsernameUnchanged(connection, "foo-1", "foo_one");
                runPgSection2(connection, "foo-2", "foo_two", "FOO");
                assertUsernameUnchanged(connection, "foo-2", "foo_two");
                assertUsernameUnchanged(connection, "foo-3", "foo");

                Assertions.assertEquals(0, scalarCount(connection,
                        "select count(*) from (select lower(USE_USERNAME_C) un from T_USER "
                                + "where USE_DELETEDATE_D is null group by lower(USE_USERNAME_C) having count(*) > 1) g"),
                        "after two single-member renames the global collision check must report zero groups (PG)");

                // Bad input on PG: a colliding new name must RAISE and abort — nothing renamed.
                boolean aborted = false;
                try {
                    runPgSection2(connection, "foo-3", "foo_two", "foo");   // 'foo_two' now taken by foo-2
                } catch (SQLException e) {
                    aborted = true;
                    // The block's own BEGIN left the transaction aborted after the RAISE; clear it so
                    // the read-back below runs on a clean transaction (an operator would ROLLBACK too).
                    try (Statement s = connection.createStatement()) {
                        s.execute("ROLLBACK");
                    }
                }
                Assertions.assertTrue(aborted,
                        "the PostgreSQL remediation must abort when the new name collides with another active user");
                assertUsernameUnchanged(connection, "foo-3", "foo");
            }
        }
    }

    /** Extract the Section-2 block (SET AUTOCOMMIT OFF; .. COMMIT;) from the real H2 remediation script. */
    private static String h2Section2() throws Exception {
        return section2Block(readRemediationScript("username-collision-h2.sql"));
    }

    /**
     * Run the H2 Section-2 block via {@link org.h2.tools.RunScript} (stop-on-error) and report whether
     * it HALTED on an error. On a halt the connection is rolled back to the last committed state,
     * exactly as an operator discarding a failed run would leave it.
     */
    private static boolean runH2Section2ExpectingHalt(Connection connection, String block) throws Exception {
        try {
            org.h2.tools.RunScript.execute(connection, new java.io.StringReader(block));
            return false;
        } catch (SQLException expected) {
            connection.rollback();
            return true;
        }
    }

    /** Read a remediation script from the classpath (/db/remediation/&lt;name&gt;). */
    private static String readRemediationScript(String name) throws Exception {
        try (InputStream is = TestPopulatedMigration.class.getResourceAsStream("/db/remediation/" + name)) {
            Assertions.assertNotNull(is, "remediation script must be on the classpath: " + name);
            return new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    /** Slice out the Section-2 block from "SET AUTOCOMMIT OFF;" through the first standalone "COMMIT;". */
    private static String section2Block(String script) {
        int start = script.indexOf("SET AUTOCOMMIT OFF;");
        Assertions.assertTrue(start >= 0, "H2 script must contain 'SET AUTOCOMMIT OFF;'");
        int commit = script.indexOf("\nCOMMIT;", start);
        Assertions.assertTrue(commit >= 0, "H2 script must contain a standalone 'COMMIT;' after Section 2");
        return script.substring(start, commit + "\nCOMMIT;".length());
    }

    /**
     * Run the PostgreSQL Section-2 rename block against a live connection. psql {@code :'var'}
     * variables have no JDBC equivalent, so pre-substitute them (single-quoted, matching the psql
     * {@code :'var'} form) before executing the block as one JDBC statement batch.
     */
    private static void runPgSection2(Connection connection, String targetId, String newName, String oldName)
            throws Exception {
        String block = section2BlockPg(readRemediationScript("username-collision-postgresql.sql"));
        String sql = block
                .replace(":'target_id'", "'" + targetId + "'")
                .replace(":'new_name'", "'" + newName + "'");
        try (Statement s = connection.createStatement()) {
            s.execute(sql);
        }
    }

    /** Slice the PostgreSQL Section-2 block from "BEGIN;" through the first standalone "COMMIT;". */
    private static String section2BlockPg(String script) {
        int start = script.indexOf("\nBEGIN;");
        Assertions.assertTrue(start >= 0, "PG script must contain a standalone 'BEGIN;'");
        int commit = script.indexOf("\nCOMMIT;", start);
        Assertions.assertTrue(commit >= 0, "PG script must contain a standalone 'COMMIT;' after Section 2");
        return script.substring(start + 1, commit + "\nCOMMIT;".length());
    }

    /** Seed the minimal v49 schema plus a 3-way active collision (Foo/FOO/foo) on an H2 connection. */
    private static void seedThreeWayCollisionAtV49(Connection connection) throws Exception {
        buildSchemaToVersion(connection, 49);
        try (Statement s = connection.createStatement()) {
            s.executeUpdate("insert into T_USER (USE_ID_C, USE_IDROLE_C, USE_USERNAME_C, USE_PASSWORD_C, USE_EMAIL_C, USE_CREATEDATE_D, USE_PRIVATEKEY_C) values ('foo-1','user','Foo','x','foo1@localhost',NOW(),'pk-f1')");
            s.executeUpdate("insert into T_USER (USE_ID_C, USE_IDROLE_C, USE_USERNAME_C, USE_PASSWORD_C, USE_EMAIL_C, USE_CREATEDATE_D, USE_PRIVATEKEY_C) values ('foo-2','user','FOO','x','foo2@localhost',NOW(),'pk-f2')");
            s.executeUpdate("insert into T_USER (USE_ID_C, USE_IDROLE_C, USE_USERNAME_C, USE_PASSWORD_C, USE_EMAIL_C, USE_CREATEDATE_D, USE_PRIVATEKEY_C) values ('foo-3','user','foo','x','foo3@localhost',NOW(),'pk-f3')");
        }
        connection.commit();
    }

    /**
     * PostgreSQL variant: the 050 unique index rejects a second active case-variant, so build to v49
     * (before 050), seed the collision, and stay pre-050. autoCommit is true here.
     */
    private static void seedThreeWayCollisionAtV49Postgres(Connection connection) throws Exception {
        boolean prevAuto = connection.getAutoCommit();
        connection.setAutoCommit(false);
        buildSchemaToVersion(connection, 49);
        try (Statement s = connection.createStatement()) {
            s.executeUpdate("insert into T_USER (USE_ID_C, USE_IDROLE_C, USE_USERNAME_C, USE_PASSWORD_C, USE_EMAIL_C, USE_CREATEDATE_D, USE_PRIVATEKEY_C) values ('foo-1','user','Foo','x','foo1@localhost',NOW(),'pk-f1')");
            s.executeUpdate("insert into T_USER (USE_ID_C, USE_IDROLE_C, USE_USERNAME_C, USE_PASSWORD_C, USE_EMAIL_C, USE_CREATEDATE_D, USE_PRIVATEKEY_C) values ('foo-2','user','FOO','x','foo2@localhost',NOW(),'pk-f2')");
            s.executeUpdate("insert into T_USER (USE_ID_C, USE_IDROLE_C, USE_USERNAME_C, USE_PASSWORD_C, USE_EMAIL_C, USE_CREATEDATE_D, USE_PRIVATEKEY_C) values ('foo-3','user','foo','x','foo3@localhost',NOW(),'pk-f3')");
        }
        connection.commit();
        connection.setAutoCommit(prevAuto);
    }

    /** Assert an active user's committed username equals the expected value (case-sensitive). */
    private static void assertUsernameUnchanged(Connection connection, String userId, String expected) throws Exception {
        try (Statement s = connection.createStatement();
             ResultSet rs = s.executeQuery(
                     "select USE_USERNAME_C from T_USER where USE_ID_C = '" + userId + "'")) {
            Assertions.assertTrue(rs.next(), "user " + userId + " must exist");
            Assertions.assertEquals(expected, rs.getString(1),
                    "user " + userId + " username must be '" + expected + "'");
        }
    }

    /**
     * The full scenario, dialect-agnostic (DbOpenHelper derives the dialect from the
     * connection): build a v36 schema, seed it, prove the retired rows exist, run the
     * real upgrade, prove the retired rows are gone and the retained rows survived. The FIL_ROTATION_N
     * column added by 049 is proven to round-trip on the retained file row (5j).
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

        // 4. Run the REAL upgrade path. open() reads DB_VERSION=36 and runs onUpgrade(36, 50).
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
                "migrations 037-050 must run cleanly on a populated database");

        // 5a. Landed on target version.
        Assertions.assertEquals(TARGET_VERSION, dbVersion(connection),
                "DB_VERSION must be " + TARGET_VERSION + " after the full upgrade path");

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

        // 5g. Migration 045 widened T_CONFIG.CFG_VALUE_C from varchar(250) to varchar(4000)
        //     so a footer-links JSON blob (or any long config value) fits. Prove a value
        //     LONGER than the old 250-char limit now inserts AND round-trips unchanged on
        //     BOTH dialects. Pre-045 this insert would have failed the length constraint.
        connection.commit();
        String longValue = "x".repeat(300);
        try (java.sql.PreparedStatement ps = connection.prepareStatement(
                "insert into T_CONFIG (CFG_ID_C, CFG_VALUE_C) values ('LONG_CONFIG_PROBE', ?)")) {
            ps.setString(1, longValue);
            ps.executeUpdate();
        }
        connection.commit();
        try (java.sql.PreparedStatement ps = connection.prepareStatement(
                "select CFG_VALUE_C from T_CONFIG where CFG_ID_C = 'LONG_CONFIG_PROBE'");
             ResultSet rs = ps.executeQuery()) {
            Assertions.assertTrue(rs.next(), "045 widened column: the long-value probe row must exist");
            Assertions.assertEquals(longValue, rs.getString(1),
                    "045 must widen CFG_VALUE_C so a >250-char value round-trips unchanged");
        }

        // 5h. Migration 047 created the per-user favorite table + its unique (user, document)
        //     index, both FKs resolvable. A favorite referencing a retained user + document
        //     inserts; a second favorite with the SAME (user, document) is rejected by
        //     IDX_FAV_USER_DOCUMENT (the concurrency backstop the DAO's idempotent create
        //     depends on), proven on BOTH dialects. Guarded with a savepoint so PostgreSQL's
        //     duplicate-key error is recovered before the final commit.
        connection.commit();
        Assertions.assertTrue(tableExists(connection, "T_FAVORITE"),
                "047 must create the T_FAVORITE table");
        Assertions.assertEquals(0, count(connection, "T_FAVORITE", "1 = 1"),
                "047 must not seed any favorite rows");
        try (Statement s = connection.createStatement()) {
            s.executeUpdate("insert into T_FAVORITE (FAV_ID_C, FAV_IDUSER_C, FAV_IDDOCUMENT_C, FAV_CREATEDATE_D) "
                    + "values ('fav-1','u-alice','doc-1',NOW())");
        }
        connection.commit();
        Assertions.assertEquals(1, scalarCount(connection,
                        "select count(*) from T_FAVORITE f join T_USER u on f.FAV_IDUSER_C = u.USE_ID_C "
                                + "join T_DOCUMENT d on f.FAV_IDDOCUMENT_C = d.DOC_ID_C where f.FAV_ID_C = 'fav-1'"),
                "favorite -> user AND document FKs must be resolvable");

        boolean favoriteDuplicateRejected = false;
        java.sql.Savepoint favSp = connection.setSavepoint("beforeFavDup");
        try (Statement s = connection.createStatement()) {
            s.executeUpdate("insert into T_FAVORITE (FAV_ID_C, FAV_IDUSER_C, FAV_IDDOCUMENT_C, FAV_CREATEDATE_D) "
                    + "values ('fav-dup','u-alice','doc-1',NOW())");
        } catch (java.sql.SQLException e) {
            favoriteDuplicateRejected = true;
            connection.rollback(favSp);
        }
        Assertions.assertTrue(favoriteDuplicateRejected,
                "047 unique index IDX_FAV_USER_DOCUMENT must reject a duplicate (user, document) favorite");

        // NOTE ON CASE: the index column comparison is dialect-dependent. The base schema
        // runs `SET IGNORECASE TRUE` on H2 (dbupdate-000-0.sql), so on H2 the index is
        // effectively case-INSENSITIVE; PostgreSQL is case-SENSITIVE (the exact-case
        // contract). Rather than assert dialect-divergent behaviour here, the case
        // contract is verified at the resource layer (TestSavedFilterResource), whose
        // case-insensitive precheck gives users identical behaviour on both dialects. A
        // true-race differently-cased duplicate is only possible on PostgreSQL and is
        // documented as acceptable.

        // 5i. Migration 048 widened T_DOCUMENT.DOC_DESCRIPTION_C from varchar(4000) to
        //     varchar(50000) so a rich sanitized-HTML description fits. Prove a value
        //     LONGER than the old 4000-char limit now stores AND round-trips unchanged on
        //     BOTH dialects. Pre-048 this update would have overflowed the column.
        connection.commit();
        String longDescription = "d".repeat(5000);
        try (java.sql.PreparedStatement ps = connection.prepareStatement(
                "update T_DOCUMENT set DOC_DESCRIPTION_C = ? where DOC_ID_C = 'doc-1'")) {
            ps.setString(1, longDescription);
            Assertions.assertEquals(1, ps.executeUpdate(),
                    "048 widened column: the long-description update must affect the retained document");
        }
        connection.commit();
        try (java.sql.PreparedStatement ps = connection.prepareStatement(
                "select DOC_DESCRIPTION_C from T_DOCUMENT where DOC_ID_C = 'doc-1'");
             ResultSet rs = ps.executeQuery()) {
            Assertions.assertTrue(rs.next(), "048 widened column: the retained document row must exist");
            Assertions.assertEquals(longDescription, rs.getString(1),
                    "048 must widen DOC_DESCRIPTION_C so a >4000-char description round-trips unchanged");
        }

        // 5j. Migration 049 added T_FILE.FIL_ROTATION_N (int, default 0). Prove the retained file row
        //     defaulted to 0 (no crash on the ADD COLUMN over populated data) AND that a rotation
        //     value round-trips unchanged on BOTH dialects. Pre-049 the column does not exist, so this
        //     update would fail on an unknown column.
        connection.commit();
        try (java.sql.PreparedStatement ps = connection.prepareStatement(
                "select FIL_ROTATION_N from T_FILE where FIL_ID_C = 'file-1'");
             ResultSet rs = ps.executeQuery()) {
            Assertions.assertTrue(rs.next(), "049 added column: the retained file row must exist");
            Assertions.assertEquals(0, rs.getInt(1),
                    "049 ADD COLUMN must default the existing file row's rotation to 0");
        }
        try (java.sql.PreparedStatement ps = connection.prepareStatement(
                "update T_FILE set FIL_ROTATION_N = ? where FIL_ID_C = 'file-1'")) {
            ps.setInt(1, 270);
            Assertions.assertEquals(1, ps.executeUpdate(),
                    "049 column: the rotation update must affect the retained file row");
        }
        connection.commit();
        try (java.sql.PreparedStatement ps = connection.prepareStatement(
                "select FIL_ROTATION_N from T_FILE where FIL_ID_C = 'file-1'");
             ResultSet rs = ps.executeQuery()) {
            Assertions.assertTrue(rs.next(), "049 column: the retained file row must exist");
            Assertions.assertEquals(270, rs.getInt(1),
                    "049 must add FIL_ROTATION_N so a rotation value round-trips unchanged");
        }

        // 5k. Migration 050 added the ACTIVE, CASE-INSENSITIVE unique username constraint
        //     (IDX_USER_USERNAME_ACTIVE), expressed as a PG partial index or an H2 generated
        //     column + plain unique index. The retained active user 'alice' (u-alice) already
        //     holds that name. Prove on BOTH dialects that: (a) a second ACTIVE user with a
        //     case-variant name 'ALICE' is REJECTED; (b) a SOFT-DELETED user may reuse 'alice'
        //     (NULLs/partial-predicate exclude soft-deleted rows). The DAO/resource layer relies
        //     on this as the race backstop for verbatim OIDC provisioning. Savepoint-guarded so a
        //     PostgreSQL duplicate-key error is recovered before the final commit.
        connection.commit();
        Assertions.assertTrue(indexExists(connection, "IDX_USER_USERNAME_ACTIVE", "T_USER"),
                "050 must create the IDX_USER_USERNAME_ACTIVE unique index on T_USER");

        boolean activeDuplicateRejected = false;
        java.sql.Savepoint dupSp = connection.setSavepoint("beforeUsernameDup");
        try (Statement s = connection.createStatement()) {
            s.executeUpdate("insert into T_USER (USE_ID_C, USE_IDROLE_C, USE_USERNAME_C, USE_PASSWORD_C, USE_EMAIL_C, USE_CREATEDATE_D, USE_PRIVATEKEY_C) "
                    + "values ('u-alice2','user','ALICE','x','alice2@localhost',NOW(),'pk-a2')");
        } catch (java.sql.SQLException e) {
            activeDuplicateRejected = true;
            connection.rollback(dupSp);
        }
        Assertions.assertTrue(activeDuplicateRejected,
                "050 constraint must reject a second ACTIVE user with a case-insensitive-duplicate username");

        // A SOFT-DELETED user reusing the same name must be ACCEPTED (the constraint is active-only).
        connection.commit();
        try (Statement s = connection.createStatement()) {
            s.executeUpdate("insert into T_USER (USE_ID_C, USE_IDROLE_C, USE_USERNAME_C, USE_PASSWORD_C, USE_EMAIL_C, USE_CREATEDATE_D, USE_DELETEDATE_D, USE_PRIVATEKEY_C) "
                    + "values ('u-alice-del','user','alice','x','alicedel@localhost',NOW(),NOW(),'pk-ad')");
        }
        connection.commit();
        Assertions.assertEquals(1, count(connection, "T_USER", "USE_ID_C = 'u-alice-del'"),
                "050 constraint must allow a SOFT-DELETED user to reuse an active username (NULLs don't collide)");

        // 5l. Migration 053 created the IMAP import-receipt table (T_INBOX_RECEIPT) with a physical
        //     UNIQUE(identity digest, UIDVALIDITY, UID) index, and seeded the GLOBAL_QUOTA_LOCK sentinel
        //     alongside 052's CLEAN_STORAGE_LOCK. Prove on BOTH dialects: (a) the table exists and is
        //     empty; (b) a receipt referencing a retained document inserts and its FK is resolvable;
        //     (c) a second receipt with the SAME (digest, UIDVALIDITY, UID) is REJECTED by the unique
        //     index (the exactly-once backstop) while a different UID is accepted; (d) both sentinel
        //     rows exist. Savepoint-guarded so PostgreSQL's duplicate-key error is recovered before the
        //     final commit.
        connection.commit();
        Assertions.assertTrue(tableExists(connection, "T_INBOX_RECEIPT"),
                "053 must create the T_INBOX_RECEIPT table");
        Assertions.assertEquals(0, count(connection, "T_INBOX_RECEIPT", "1 = 1"),
                "053 must not seed any receipt rows");
        try (Statement s = connection.createStatement()) {
            s.executeUpdate("insert into T_INBOX_RECEIPT (INR_ID_C, INR_IDENTITY_C, INR_UIDVALIDITY_N, INR_UID_N, INR_ACCOUNT_C, INR_FOLDER_C, INR_IDDOCUMENT_C, INR_CREATEDATE_D) "
                    + "values ('inr-1','abc123',42,1001,'imap.example.com:993:imap:u@x','INBOX','doc-1',NOW())");
        }
        connection.commit();
        Assertions.assertEquals(1, scalarCount(connection,
                        "select count(*) from T_INBOX_RECEIPT r join T_DOCUMENT d on r.INR_IDDOCUMENT_C = d.DOC_ID_C where r.INR_ID_C = 'inr-1'"),
                "receipt -> document FK must be resolvable");

        boolean receiptDuplicateRejected = false;
        java.sql.Savepoint inrSp = connection.setSavepoint("beforeReceiptDup");
        try (Statement s = connection.createStatement()) {
            // Same (identity, UIDVALIDITY, UID), different id and null document — must be rejected.
            s.executeUpdate("insert into T_INBOX_RECEIPT (INR_ID_C, INR_IDENTITY_C, INR_UIDVALIDITY_N, INR_UID_N, INR_CREATEDATE_D) "
                    + "values ('inr-dup','abc123',42,1001,NOW())");
        } catch (java.sql.SQLException e) {
            receiptDuplicateRejected = true;
            connection.rollback(inrSp);
        }
        Assertions.assertTrue(receiptDuplicateRejected,
                "053 unique index IDX_INR_IDENTITY must reject a duplicate (digest, UIDVALIDITY, UID) receipt");

        // A different UID under the same digest/UIDVALIDITY is a different message — must be accepted.
        connection.commit();
        try (Statement s = connection.createStatement()) {
            s.executeUpdate("insert into T_INBOX_RECEIPT (INR_ID_C, INR_IDENTITY_C, INR_UIDVALIDITY_N, INR_UID_N, INR_CREATEDATE_D) "
                    + "values ('inr-2','abc123',42,1002,NOW())");
        }
        connection.commit();
        Assertions.assertEquals(1, count(connection, "T_INBOX_RECEIPT", "INR_ID_C = 'inr-2'"),
                "a distinct UID must be accepted (only the exact triple collides)");

        // (d) both mutual-exclusion sentinel rows exist.
        Assertions.assertEquals(1, count(connection, "T_CONFIG", "CFG_ID_C = 'CLEAN_STORAGE_LOCK'"),
                "052 CLEAN_STORAGE_LOCK sentinel must exist after the full upgrade");
        Assertions.assertEquals(1, count(connection, "T_CONFIG", "CFG_ID_C = 'GLOBAL_QUOTA_LOCK'"),
                "053 GLOBAL_QUOTA_LOCK sentinel must exist after the full upgrade");

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
     * Build the schema up to an arbitrary {@code targetVersion} by running the real migration
     * scripts {@code 0..targetVersion} in order (onCreate for script 0, then each upgrade step).
     * Used by the migration-050 abort test to reach the state immediately before 050.
     */
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
        Assertions.assertEquals(targetVersion, dbVersion(connection),
                "fixture schema must be at db.version " + targetVersion);
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
        return indexExists(connection, indexName, "T_DOCUMENT_TAG");
    }

    /** As {@link #indexExists(Connection, String)} but probes an arbitrary table (both case variants). */
    private static boolean indexExists(Connection connection, String indexName, String table) throws Exception {
        for (String tableName : new String[]{table.toUpperCase(), table.toLowerCase()}) {
            try (ResultSet rs = connection.getMetaData().getIndexInfo(null, null, tableName, false, false)) {
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
