package com.sismics.util.jpa;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Preflight check for migration 050 (the ACTIVE, case-insensitive unique-username constraint).
 *
 * <p>Migration 050 adds a unique index over {@code lower(USE_USERNAME_C)} for active
 * (non-soft-deleted) users. If the database already holds duplicate active usernames that differ
 * only in case (e.g. {@code MaxMuster} and {@code maxmuster}) — a should-never-happen anomaly the
 * app precheck + OIDC hash suffix normally prevent — creating that index fails mid-migration with a
 * cryptic constraint stacktrace. The migration SQL keeps a defence-in-depth "precondition abort"
 * (insert a NULL into a NOT NULL primary key when collisions exist) so a duplicate can never slip
 * past even if this check is bypassed — but that abort only PROVES collisions exist; it cannot name
 * them.
 *
 * <p>This preflight runs BEFORE the 050 index step and produces the actionable, named diagnostic:
 * it lists each colliding {@code lower(username)} group with the original usernames AND user IDs so
 * an admin knows exactly which rows to deduplicate. On a collision it throws
 * {@link MigrationPreconditionException}; the runner fails closed and leaves the schema untouched.
 *
 * @author fmaass
 */
final class UsernameCollisionPreflight {

    /** Path (in the resources tree / repo) of the operator remediation runbook. */
    static final String REMEDIATION_DOC = "docs-core/src/main/resources/db/remediation/README-username-collision.md";

    private UsernameCollisionPreflight() {
    }

    /**
     * A single case-insensitive collision group: the folded key plus the original usernames and
     * user IDs that fold onto it.
     */
    static final class CollisionGroup {
        final String foldedUsername;
        final List<String> usernames = new ArrayList<>();
        final List<String> userIds = new ArrayList<>();

        CollisionGroup(String foldedUsername) {
            this.foldedUsername = foldedUsername;
        }
    }

    /**
     * Query the active (USE_DELETEDATE_D is null) users for case-insensitive username collisions.
     * Soft-deleted rows are excluded: a soft-deleted case variant of an active name is NOT a
     * collision (the 050 constraint is active-only), so it must not be flagged.
     *
     * @param connection Bootstrap JDBC connection (its own transaction; read-only SELECTs only)
     * @return One {@link CollisionGroup} per {@code lower(username)} value shared by 2+ active users
     * @throws SQLException on a query error
     */
    static List<CollisionGroup> findActiveCollisions(Connection connection) throws SQLException {
        // The collision definition MUST be identical to the 050 unique index, which folds with the
        // DATABASE's lower(). Java's default-locale (or even Locale.ROOT) toLowerCase() can differ
        // from SQL lower() under some collations (the Turkish-I class of mismatch), which would omit
        // a genuinely-colliding row from the diagnostic. So we GROUP BY the database's
        // lower(USE_USERNAME_C) in SQL, and read the pre-grouped members (username + id) back — the
        // JVM never folds a username to decide membership.
        //
        // First, the folded keys that actually collide among active users. Then, for each active row,
        // its OWN database-folded key (aliased) plus the original username + id, so we bucket by the
        // DB's fold, not the JVM's. Two plain SELECTs (no DDL, no writes) keep the bootstrap
        // transaction clean on both H2 and PostgreSQL.
        List<String> collidingKeys = new ArrayList<>();
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "select lower(USE_USERNAME_C) un from T_USER where USE_DELETEDATE_D is null "
                             + "group by lower(USE_USERNAME_C) having count(*) > 1 order by lower(USE_USERNAME_C)")) {
            while (rs.next()) {
                collidingKeys.add(rs.getString(1));
            }
        }
        if (collidingKeys.isEmpty()) {
            return new ArrayList<>();
        }

        Map<String, CollisionGroup> groups = new LinkedHashMap<>();
        for (String key : collidingKeys) {
            groups.put(key, new CollisionGroup(key));
        }
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "select lower(USE_USERNAME_C) un, USE_ID_C, USE_USERNAME_C from T_USER "
                             + "where USE_DELETEDATE_D is null "
                             + "order by lower(USE_USERNAME_C), USE_USERNAME_C, USE_ID_C")) {
            while (rs.next()) {
                String key = rs.getString(1);
                String id = rs.getString(2);
                String username = rs.getString(3);
                CollisionGroup group = key == null ? null : groups.get(key);
                if (group != null) {
                    group.usernames.add(username);
                    group.userIds.add(id);
                }
            }
        }
        return new ArrayList<>(groups.values());
    }

    /**
     * Run the preflight and throw a named diagnostic if any active case-insensitive username
     * collision exists. On success (no collisions) this is a silent no-op.
     *
     * @param connection Bootstrap JDBC connection
     * @throws MigrationPreconditionException if collisions exist — the message names every group
     * @throws SQLException on a query error
     */
    static void check(Connection connection) throws MigrationPreconditionException, SQLException {
        List<CollisionGroup> collisions = findActiveCollisions(connection);
        if (collisions.isEmpty()) {
            return;
        }
        throw new MigrationPreconditionException(buildMessage(collisions));
    }

    /**
     * Build the operator-facing diagnostic message naming every collision group (usernames AND
     * user IDs), stating no changes were applied, and pointing to the remediation runbook.
     */
    static String buildMessage(List<CollisionGroup> collisions) {
        StringBuilder sb = new StringBuilder();
        sb.append("Database upgrade blocked (migration 050): ")
                .append(collisions.size())
                .append(collisions.size() == 1 ? " active username collides" : " active usernames collide")
                .append(" case-insensitively. The active, case-insensitive unique-username constraint")
                .append(" introduced in v3.6.0 cannot be applied until these are resolved.");
        sb.append(" No database changes were applied (the upgrade transaction was rolled back).");
        sb.append(" Colliding groups:");
        for (CollisionGroup group : collisions) {
            sb.append(System.lineSeparator())
                    .append("  - lower(username)='").append(group.foldedUsername).append("': ");
            for (int i = 0; i < group.usernames.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append("username='").append(group.usernames.get(i))
                        .append("' (USE_ID_C=").append(group.userIds.get(i)).append(")");
            }
        }
        sb.append(System.lineSeparator())
                .append("Remediation: back up the database, then deduplicate these active usernames")
                .append(" case-insensitively (rename or soft-delete one of each pair) and restart.")
                .append(" See ").append(REMEDIATION_DOC).append(" for the supported, transaction-wrapped procedure.");
        return sb.toString();
    }
}
