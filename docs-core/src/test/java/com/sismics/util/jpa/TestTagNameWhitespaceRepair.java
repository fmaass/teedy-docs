package com.sismics.util.jpa;

import org.apache.log4j.AppenderSkeleton;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.spi.LoggingEvent;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * #305 one-time repair of tag names that already carry Unicode whitespace.
 *
 * <p>Validation only guards names written from now on. A name pasted from a "whitespace generator"
 * before this release is already in {@code T_TAG}, invisible in the UI and impossible to type — so
 * the upgrade to db.version 68 repairs the stored names once.
 *
 * <p>The contract under test:
 * <ul>
 *   <li>every whitespace class is stripped from a LIVE tag's name — the invisible format characters
 *       (zero-width space/joiner, BOM) AND the visible ones (thin space, no-break space, tab);</li>
 *   <li>a repair that would make two tags OF THE SAME OWNER share a name is SKIPPED — both rows are
 *       left exactly as they were, because merging two tags is a decision, not a script's guess;</li>
 *   <li>a soft-deleted tag is not touched;</li>
 *   <li>two owners may hold the same name (that is the normal state of a multi-user instance), so a
 *       name matching ANOTHER user's tag is not a collision and is repaired;</li>
 *   <li>it runs exactly once: the version gate means a second startup repairs nothing.</li>
 * </ul>
 */
public class TestTagNameWhitespaceRepair {

    /** U+200B ZERO WIDTH SPACE — invisible format character (Unicode category Cf). */
    private static final String ZWSP = new String(Character.toChars(0x200B));

    /** U+FEFF ZERO WIDTH NO-BREAK SPACE / BOM — invisible format character (Cf). */
    private static final String BOM = new String(Character.toChars(0xFEFF));

    /** U+2009 THIN SPACE — visible whitespace (Zs). */
    private static final String THIN_SPACE = new String(Character.toChars(0x2009));

    /** U+00A0 NO-BREAK SPACE — visible whitespace (Zs). */
    private static final String NBSP = new String(Character.toChars(0x00A0));

    /** The version whose upgrade carries the one-time repair. */
    private static final int REPAIR_VERSION = 68;

    @Test
    public void repairStripsWhitespaceFromStoredTagNamesH2() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:tagnamerepair;DB_CLOSE_DELAY=-1", "sa", "")) {
            connection.setAutoCommit(false);
            runRepairScenario(connection);
        }
    }

    @Test
    public void repairStripsWhitespaceFromStoredTagNamesPostgres() throws Exception {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker not available; skipping the PostgreSQL flavour of the tag-name repair test");
        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17")) {
            postgres.start();
            try (Connection connection = DriverManager.getConnection(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
                connection.setAutoCommit(false);
                runRepairScenario(connection);
            }
        }
    }

    /**
     * Every repaired name is written to the application log at INFO with the tag id and the name
     * before and after, and every skipped collision at WARN naming BOTH tag ids — that log IS the
     * operator's record of what the upgrade silently changed under them, and the merge decision the
     * skipped pair still needs.
     */
    @Test
    public void repairLogsEveryChangeAndEverySkippedCollision() throws Exception {
        CapturingAppender appender = new CapturingAppender();
        Logger.getRootLogger().addAppender(appender);
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:tagnamerepairlog;DB_CLOSE_DELAY=-1", "sa", "")) {
            connection.setAutoCommit(false);
            runRepairScenario(connection);
        } finally {
            Logger.getRootLogger().removeAppender(appender);
        }

        // The names are logged with their invisible code points SPELLED OUT: a raw before/after pair
        // would print as two strings that look identical, which is the defect, not a report of it.
        String infoLines = appender.messagesAt(Level.INFO);
        Assertions.assertTrue(infoLines.contains("t-zwsp")
                        && infoLines.contains("\"Rech[U+200B]nung\" -> \"Rechnung\""),
                "a repaired name must be logged at INFO with the tag id and the name before and after; got: "
                        + visible(infoLines));

        String warnLines = appender.messagesAt(Level.WARN);
        Assertions.assertTrue(warnLines.contains("t-col-a") && warnLines.contains("t-col-b"),
                "a skipped collision must be logged at WARN naming BOTH tag ids; got: " + visible(warnLines));
    }

    /**
     * Seed a v67 instance holding every interesting shape of stored name, run the upgrade, and check
     * each one. Then run the upgrade path a second time and check that nothing moves — the repair is
     * gated on the version crossing, so it must be a no-op on an instance that already ran it.
     */
    private static void runRepairScenario(Connection connection) throws Exception {
        buildSchemaToVersion(connection, REPAIR_VERSION - 1);

        seedUser(connection, "u-alice", "alice");
        seedUser(connection, "u-bob", "bob");
        // Invisible format character in the middle of a word.
        seedTag(connection, "t-zwsp", "u-alice", "Rech" + ZWSP + "nung");
        // Visible whitespace that the validation now refuses but older versions stored.
        seedTag(connection, "t-thin", "u-alice", "Test" + THIN_SPACE + "123");
        // Several classes at once, including leading/trailing.
        seedTag(connection, "t-mixed", "u-alice", NBSP + "Ab" + BOM + "c\t");
        // Nothing to do.
        seedTag(connection, "t-clean", "u-alice", "Sauber");
        // Collision pair OF THE SAME OWNER: repairing t-col-a would duplicate t-col-b's name.
        seedTag(connection, "t-col-a", "u-alice", "Dop" + ZWSP + "pelt");
        seedTag(connection, "t-col-b", "u-alice", "Doppelt");
        // Soft-deleted: not a live name.
        seedDeletedTag(connection, "t-deleted", "u-alice", "Alt" + ZWSP + "Tag");
        // ANOTHER owner's tag repairing onto a name alice already holds is not a collision.
        seedTag(connection, "t-crossowner", "u-bob", "Rech" + ZWSP + "nung");
        connection.commit();

        runUpgradeStep(connection, REPAIR_VERSION);
        Assertions.assertEquals(REPAIR_VERSION, dbVersion(connection),
                "the repair step must advance DB_VERSION to " + REPAIR_VERSION);

        assertTagName(connection, "t-zwsp", "Rechnung",
                "an invisible format character must be stripped from a stored name");
        assertTagName(connection, "t-thin", "Test123",
                "a visible whitespace character must be stripped from a stored name");
        assertTagName(connection, "t-mixed", "Abc",
                "every whitespace class must be stripped, including leading and trailing");
        assertTagName(connection, "t-clean", "Sauber",
                "a name with nothing to repair must be left byte-identical");
        assertTagName(connection, "t-col-a", "Dop" + ZWSP + "pelt",
                "a repair that would collide with the same owner's other tag must be SKIPPED");
        assertTagName(connection, "t-col-b", "Doppelt",
                "the tag a skipped repair would have collided with must be untouched");
        assertTagName(connection, "t-deleted", "Alt" + ZWSP + "Tag",
                "a soft-deleted tag must not be repaired");
        assertTagName(connection, "t-crossowner", "Rechnung",
                "another owner holding the same name is not a collision");

        // Second run: the version gate has closed, so nothing may move.
        runUpgradeStep(connection, REPAIR_VERSION);
        Assertions.assertEquals(REPAIR_VERSION, dbVersion(connection),
                "a second run must leave DB_VERSION where it was");
        assertTagName(connection, "t-zwsp", "Rechnung", "a second run must change nothing");
        assertTagName(connection, "t-col-a", "Dop" + ZWSP + "pelt", "a second run must change nothing");
        assertTagName(connection, "t-deleted", "Alt" + ZWSP + "Tag", "a second run must change nothing");

        Assertions.assertNull(pendingMarker(connection),
                "a repair that completed must leave no retry marker");

        // The gate is not the only thing standing between this and a double repair: run the repair
        // itself again, directly, and it must still find nothing to do. Idempotence has to be a
        // property of the rewrite, or an operator re-running an upgrade by hand would corrupt names.
        TagNameWhitespaceRepair.run(connection);
        assertTagName(connection, "t-zwsp", "Rechnung",
                "the repair itself must be idempotent, not merely gated");
        assertTagName(connection, "t-thin", "Test123",
                "the repair itself must be idempotent, not merely gated");
        assertTagName(connection, "t-col-a", "Dop" + ZWSP + "pelt",
                "a skipped collision must stay skipped on a re-run");
        assertTagName(connection, "t-col-b", "Doppelt",
                "a skipped collision must stay skipped on a re-run");
    }

    /**
     * A repair that THROWS must not be lost. The version gate closes as soon as the upgrade commits,
     * so "swallow the error and carry on" would mean the instance never repairs those names again and
     * the only trace is one log line nobody reads. A failure therefore writes a durable retry marker,
     * and the next startup runs the repair again on the strength of that marker alone.
     *
     * <p>The failure is forced through the REAL code path rather than a stub: the table the repair
     * reads is renamed out from under it, so the production SELECT genuinely fails.
     */
    @Test
    public void aFailedRepairIsRetriedOnTheNextStartupH2() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:tagnamerepairretry;DB_CLOSE_DELAY=-1", "sa", "")) {
            connection.setAutoCommit(false);
            runFailedRepairRetryScenario(connection);
        }
    }

    @Test
    public void aFailedRepairIsRetriedOnTheNextStartupPostgres() throws Exception {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker not available; skipping the PostgreSQL flavour of the repair-retry test");
        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17")) {
            postgres.start();
            try (Connection connection = DriverManager.getConnection(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
                connection.setAutoCommit(false);
                runFailedRepairRetryScenario(connection);
            }
        }
    }

    private static void runFailedRepairRetryScenario(Connection connection) throws Exception {
        buildSchemaToVersion(connection, REPAIR_VERSION);
        Assertions.assertNull(pendingMarker(connection),
                "a repair that ran cleanly must leave no retry marker behind");

        seedUser(connection, "u-alice", "alice");
        seedTag(connection, "t-retry", "u-alice", "Rech" + ZWSP + "nung");
        connection.commit();

        // Force a genuine failure: the repair's own SELECT cannot find its table.
        execute(connection, "alter table T_TAG rename to T_TAG_HIDDEN");
        connection.commit();
        TagNameWhitespaceRepair.run(connection);
        connection.commit();
        Assertions.assertEquals("true", pendingMarker(connection),
                "a failed repair must leave a durable retry marker, not just a log line");

        execute(connection, "alter table T_TAG_HIDDEN rename to T_TAG");
        connection.commit();
        assertTagName(connection, "t-retry", "Rech" + ZWSP + "nung",
                "the failed repair must have changed nothing");

        // Next startup. The VERSION gate is shut (68 -> 68); only the marker can trigger the retry.
        runUpgradeStep(connection, REPAIR_VERSION);
        assertTagName(connection, "t-retry", "Rechnung",
                "the retry marker must make the next startup run the repair again");
        Assertions.assertNull(pendingMarker(connection),
                "a successful retry must clear the marker in the same transaction");

        // Third startup: neither gate is open, so nothing happens.
        runUpgradeStep(connection, REPAIR_VERSION);
        assertTagName(connection, "t-retry", "Rechnung", "a third startup must change nothing");
        Assertions.assertNull(pendingMarker(connection), "a third startup must write no marker");
    }

    // --- fixture helpers ------------------------------------------------------------------------

    /**
     * Build the schema up to the given version with the real migration runner, exactly as
     * {@code TestPopulatedMigration} does.
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

    /** Run the real runner over exactly one migration step. */
    private static void runUpgradeStep(Connection connection, int version) throws Exception {
        DbOpenHelper helper = new DbOpenHelper(connection) {
            @Override
            public void onCreate() {
                throw new IllegalStateException("onCreate must not run; DB_VERSION is present");
            }

            @Override
            public void onUpgrade(int oldVersion, int newVersion) throws Exception {
                if (oldVersion < version) {
                    executeAllScript(version);
                }
            }
        };
        helper.open();
        Assertions.assertTrue(helper.getExceptions().isEmpty(),
                "the " + version + " step must run cleanly on a populated database");
    }

    private static void seedUser(Connection connection, String id, String username) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "insert into T_USER (USE_ID_C, USE_IDROLE_C, USE_USERNAME_C, USE_PASSWORD_C, USE_EMAIL_C,"
                        + " USE_CREATEDATE_D, USE_PRIVATEKEY_C) values (?,'user',?,'x',?,NOW(),?)")) {
            ps.setString(1, id);
            ps.setString(2, username);
            ps.setString(3, username + "@localhost");
            ps.setString(4, "pk-" + id);
            ps.executeUpdate();
        }
    }

    private static void seedTag(Connection connection, String id, String userId, String name) throws Exception {
        insertTag(connection, id, userId, name, false);
    }

    private static void seedDeletedTag(Connection connection, String id, String userId, String name)
            throws Exception {
        insertTag(connection, id, userId, name, true);
    }

    /**
     * Insert through a PreparedStatement, never a literal: a zero-width character in a hand-built SQL
     * string is invisible in the source and would not survive review.
     */
    private static void insertTag(Connection connection, String id, String userId, String name,
                                  boolean deleted) throws Exception {
        String sql = deleted
                ? "insert into T_TAG (TAG_ID_C, TAG_IDUSER_C, TAG_NAME_C, TAG_CREATEDATE_D, TAG_DELETEDATE_D)"
                + " values (?,?,?,NOW(),NOW())"
                : "insert into T_TAG (TAG_ID_C, TAG_IDUSER_C, TAG_NAME_C, TAG_CREATEDATE_D)"
                + " values (?,?,?,NOW())";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.setString(2, userId);
            ps.setString(3, name);
            ps.executeUpdate();
        }
    }

    private static void assertTagName(Connection connection, String tagId, String expected, String because)
            throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "select TAG_NAME_C from T_TAG where TAG_ID_C = ?")) {
            ps.setString(1, tagId);
            try (ResultSet rs = ps.executeQuery()) {
                Assertions.assertTrue(rs.next(), "seeded tag " + tagId + " must still exist");
                Assertions.assertEquals(visible(expected), visible(rs.getString(1)), because);
            }
        }
    }

    /** The retry marker's stored value, or null when no marker row exists. */
    private static String pendingMarker(Connection connection) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "select CFG_VALUE_C from T_CONFIG where CFG_ID_C = ?")) {
            ps.setString(1, "TAG_WS_REPAIR_PENDING");
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static void execute(Connection connection, String sql) throws Exception {
        try (Statement s = connection.createStatement()) {
            s.executeUpdate(sql);
        }
    }

    private static int dbVersion(Connection connection) throws Exception {
        try (Statement s = connection.createStatement();
             ResultSet rs = s.executeQuery(
                     "select CFG_VALUE_C from T_CONFIG where CFG_ID_C = 'DB_VERSION'")) {
            Assertions.assertTrue(rs.next(), "DB_VERSION row must exist");
            return Integer.parseInt(rs.getString(1));
        }
    }

    /**
     * Render a name with its non-ASCII code points spelled out, so an assertion failure says
     * {@code Rech[U+200B]nung} instead of a string that looks identical to the expected one.
     */
    private static String visible(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder();
        value.codePoints().forEach(cp -> {
            if (cp >= 0x20 && cp < 0x7F) {
                sb.appendCodePoint(cp);
            } else {
                sb.append(String.format("[U+%04X]", cp));
            }
        });
        return sb.toString();
    }

    /** Collects log events so the test can assert on what the repair actually reported. */
    private static final class CapturingAppender extends AppenderSkeleton {
        private final List<LoggingEvent> events = new ArrayList<>();

        @Override
        protected synchronized void append(LoggingEvent event) {
            events.add(event);
        }

        synchronized String messagesAt(Level level) {
            StringBuilder sb = new StringBuilder();
            for (LoggingEvent event : events) {
                if (event.getLevel().equals(level) && event.getMessage() != null) {
                    sb.append(event.getMessage()).append('\n');
                }
            }
            return sb.toString();
        }

        @Override
        public void close() {
            // NOP
        }

        @Override
        public boolean requiresLayout() {
            return false;
        }
    }
}
