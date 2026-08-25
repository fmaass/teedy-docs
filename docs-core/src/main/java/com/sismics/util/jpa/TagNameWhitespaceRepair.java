package com.sismics.util.jpa;

import com.sismics.docs.core.util.TagNameNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One-time repair of tag names that already carry Unicode whitespace (#305), run when an instance
 * crosses db.version 68.
 *
 * <p>{@code ValidationUtil.validateTagName} only guards names written from now on. A name pasted
 * from a "whitespace generator" before this release is already in {@code T_TAG}: it renders as
 * something the user cannot reproduce by typing, and no amount of new validation reaches it. So the
 * upgrade rewrites the stored names once, stripping EVERY whitespace class — the invisible format
 * characters and the visible spaces alike (see
 * {@link TagNameNormalizer#stripAllWhitespace(String)} for why an already-stored name gets the
 * strip that new input gets refused for).
 *
 * <p>Four properties are deliberate:
 *
 * <ul>
 *   <li><b>It never fails startup, and a failure is never lost.</b> A tag name is a label; losing
 *       the ability to boot over one would be a far worse outcome than an unrepaired name. The whole
 *       run sits inside a SAVEPOINT, so a failure rolls back the repair alone and leaves the schema
 *       upgrade — already applied in the same transaction — intact and committable. But "swallow it
 *       and carry on" alone would be worse than it looks: the version gate closes the moment that
 *       upgrade commits, so the repair would never be attempted again and the only trace would be one
 *       log line. A failure therefore also writes a DURABLE RETRY MARKER
 *       ({@link #PENDING_MARKER_ID} in {@code T_CONFIG}), and {@link #isDue} opens on the version
 *       crossing OR that marker. A successful run deletes the marker in the same transaction as the
 *       names it rewrote, so the marker and the work it stands for can never disagree.</li>
 *   <li><b>A collision is skipped, not resolved.</b> If repairing a name would make two of the SAME
 *       OWNER'S live tags share a name, both rows are left exactly as they are and the pair is
 *       logged at WARN. Merging two tags moves document links and ACLs; that is a decision for a
 *       person, not a guess for a migration. Ownership is the scope because tag names are not unique
 *       across the instance and never have been — two users each having a "Rechnung" is the normal
 *       state of a multi-user instance, not something to warn about.</li>
 *   <li><b>Every change is logged at INFO with the tag id and the name before and after.</b> This
 *       runs without asking, so the log is the only record the operator has of what moved.</li>
 *   <li><b>A name that is nothing but whitespace is skipped with a WARN, not emptied.</b> Deliberate,
 *       and the same principle as the collision skip: this repair only ever REMOVES characters that
 *       were already there — it never invents a name. An empty tag name is a row no list can render
 *       or select, and any substitute a script could generate would be a name its owner never chose,
 *       so the row is left exactly as it is and named in the log for its owner to rename.</li>
 * </ul>
 *
 * <p>No search reindex follows. Lucene indexes documents and files ({@code title}, {@code content},
 * … in {@code LuceneIndexingHandler}); a tag reaches a query as an ID join, never as an indexed
 * name, which is why {@code TagDao.update} — the ordinary rename path — triggers no reindex either.
 * Doing one here would be cargo cult.
 *
 * @author fmaass
 */
final class TagNameWhitespaceRepair {
    private static final Logger log = LoggerFactory.getLogger(TagNameWhitespaceRepair.class);

    /** The db.version whose upgrade first runs this repair. */
    static final int REPAIR_VERSION = 68;

    /**
     * {@code T_CONFIG.CFG_ID_C} of the retry marker. Deliberately NOT a {@code ConfigType} constant:
     * that enum is the configuration the running application reads, and nothing above the migration
     * layer ever reads this row — the same reason {@code DB_VERSION}, which lives in the same table,
     * is not in the enum either. T_CONFIG is the right home regardless: the base schema creates it,
     * it is already the migration layer's own state store, and a row in it survives the restart that
     * a JVM field or a temp file would not.
     */
    static final String PENDING_MARKER_ID = "TAG_WS_REPAIR_PENDING";

    private TagNameWhitespaceRepair() {
    }

    /**
     * Whether the repair should run on this startup: either the upgrade crosses
     * {@link #REPAIR_VERSION} for the first time, or a previous attempt failed and left its marker.
     *
     * <p>Short-circuits, so the ordinary first upgrade never pays for the marker query. The marker
     * read itself cannot throw: a database that cannot answer it is answered "no", because refusing
     * to boot over this repair's own bookkeeping is precisely what this class exists not to do.
     *
     * @param connection The bootstrap/migration JDBC connection (null answers false)
     * @param oldVersion db.version found on disk before this upgrade
     * @param currentVersion db.version this build expects
     * @return True if {@link #run(Connection)} should be called
     */
    static boolean isDue(Connection connection, int oldVersion, int currentVersion) {
        if (connection == null) {
            return false;
        }
        if (oldVersion < REPAIR_VERSION && currentVersion >= REPAIR_VERSION) {
            return true;
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "select CFG_VALUE_C from T_CONFIG where CFG_ID_C = ?")) {
            ps.setString(1, PENDING_MARKER_ID);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            log.error("Unable to read the tag name repair retry marker; treating it as absent", e);
            return false;
        }
    }

    /**
     * One live tag, as the repair sees it.
     *
     * @param id Tag ID
     * @param ownerId Owner's user ID (the scope a name collision is judged in)
     * @param currentName Name as stored
     * @param repairedName Name after every whitespace class is stripped
     */
    private record LiveTag(String id, String ownerId, String currentName, String repairedName) {
        boolean changes() {
            return !currentName.equals(repairedName);
        }
    }

    /**
     * Run the repair on the migration connection, swallowing every failure.
     *
     * @param connection The bootstrap/migration JDBC connection, inside the upgrade transaction.
     *                   A null connection is the unit-test path with no database and is a no-op.
     */
    static void run(Connection connection) {
        if (connection == null) {
            return;
        }
        Savepoint savepoint = null;
        try {
            savepoint = connection.setSavepoint("tag_name_whitespace_repair");
            repair(connection);
            // Same transaction as the rewrites above: the marker is cleared if and only if the work it
            // stands for commits. A run with nothing to repair clears it too — that is a completed
            // repair, not a skipped one.
            clearPendingMarker(connection);
        } catch (Throwable t) {
            // Fail SOFT, unlike the rest of the migration runner: see the class javadoc. Roll the
            // repair back to its savepoint so the schema upgrade sharing this transaction stays
            // committable, then record that the repair still owes this instance a run.
            log.error("The one-time tag name whitespace repair (db.version " + REPAIR_VERSION + ") failed"
                    + " and was rolled back. The schema upgrade itself is unaffected and startup continues;"
                    + " the repair is marked pending and will be retried on the next startup.", t);
            rollbackQuietly(connection, savepoint);
            setPendingMarker(connection);
        }
    }

    /**
     * Record that the repair failed and still owes this instance a run. Written AFTER the rollback to
     * the savepoint, so it lands in the outer (still healthy) transaction and is committed by the
     * migration runner along with the schema upgrade.
     *
     * <p>Delete-then-insert rather than an upsert: H2 and PostgreSQL do not share one, and a repair
     * that has already failed once is not the place to discover a dialect difference. Its own failure
     * is swallowed — this is bookkeeping, and bookkeeping must not become the thing that fails a boot.
     */
    private static void setPendingMarker(Connection connection) {
        try {
            clearPendingMarker(connection);
            try (PreparedStatement ps = connection.prepareStatement(
                    "insert into T_CONFIG (CFG_ID_C, CFG_VALUE_C) values (?, ?)")) {
                ps.setString(1, PENDING_MARKER_ID);
                ps.setString(2, "true");
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            log.error("Unable to record the tag name repair retry marker; the repair will NOT be retried"
                    + " automatically and any tag name still carrying whitespace must be corrected by hand", e);
        }
    }

    /**
     * Remove the retry marker. A no-op when there is none, which is the ordinary case.
     */
    private static void clearPendingMarker(Connection connection) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "delete from T_CONFIG where CFG_ID_C = ?")) {
            ps.setString(1, PENDING_MARKER_ID);
            ps.executeUpdate();
        }
    }

    /**
     * Read every live tag, decide what each one becomes, and write the ones that both change and do
     * not collide.
     */
    private static void repair(Connection connection) throws SQLException {
        List<LiveTag> liveTags = readLiveTags(connection);
        if (liveTags.isEmpty()) {
            return;
        }

        // Group by owner and by the name each tag WOULD end up with — including the tags that are
        // not changing, because an unchanged name is exactly what a repaired one can collide with.
        // Grouping on the outcome also catches the mutual case, where two names both repair onto the
        // same third value and neither of them exists yet.
        //
        // One group is skipped more conservatively than it strictly has to be: two tags of one owner
        // whose CURRENT names are already byte-identical (and both dirty) are already ambiguous, so
        // repairing both would create no new ambiguity. They are still left alone, because the safe
        // direction here is "change nothing and name it in the log" — an operator reading that WARN
        // can repair them by hand, whereas a wrong rewrite is not something they can see happened.
        Map<String, List<LiveTag>> byOutcome = new LinkedHashMap<>();
        for (LiveTag tag : liveTags) {
            // A plain space is a safe key delimiter: the owner id is a UUID and the repaired name has
            // had every whitespace character removed, so neither half can contain one.
            byOutcome.computeIfAbsent(tag.ownerId() + " " + tag.repairedName(), key -> new ArrayList<>())
                    .add(tag);
        }

        List<LiveTag> toUpdate = new ArrayList<>();
        for (List<LiveTag> group : byOutcome.values()) {
            List<LiveTag> changing = group.stream().filter(LiveTag::changes).toList();
            if (changing.isEmpty()) {
                // Nothing in this group moves. A group of several here is a pre-existing duplicate
                // name, which this migration did not create and does not judge.
                continue;
            }
            if (group.size() > 1) {
                log.warn(collisionMessage(group));
                continue;
            }
            LiveTag tag = changing.get(0);
            if (tag.repairedName().isEmpty()) {
                // A name made entirely of whitespace has nothing left to store, and an empty tag name
                // would be a row no UI can render or select. Same verdict as a collision: leave it,
                // name it, let a person decide.
                log.warn("#305 tag name repair SKIPPED for tag {}: the stored name {} is nothing but"
                                + " whitespace, so repairing it would leave an empty name. Rename or delete"
                                + " this tag by hand.",
                        tag.id(), describe(tag.currentName()));
                continue;
            }
            toUpdate.add(tag);
        }

        if (toUpdate.isEmpty()) {
            return;
        }
        applyUpdates(connection, toUpdate);
    }

    /**
     * Read the live (non-soft-deleted) tags. A soft-deleted tag is not a name anybody can see or
     * collide with, so it is left exactly as it was — rewriting history that is already deleted
     * would only make the audit trail lie.
     */
    private static List<LiveTag> readLiveTags(Connection connection) throws SQLException {
        List<LiveTag> tags = new ArrayList<>();
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "select TAG_ID_C, TAG_IDUSER_C, TAG_NAME_C from T_TAG"
                             + " where TAG_DELETEDATE_D is null order by TAG_ID_C")) {
            while (rs.next()) {
                String name = rs.getString(3);
                if (name == null) {
                    continue;
                }
                tags.add(new LiveTag(rs.getString(1), rs.getString(2), name,
                        TagNameNormalizer.stripAllWhitespace(name)));
            }
        }
        return tags;
    }

    /**
     * Write the repaired names, logging each one. One batched statement: this runs on every upgrade
     * across 68 and a per-row round trip would be paid by instances with nothing wrong.
     */
    private static void applyUpdates(Connection connection, List<LiveTag> toUpdate) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "update T_TAG set TAG_NAME_C = ? where TAG_ID_C = ?")) {
            for (LiveTag tag : toUpdate) {
                ps.setString(1, tag.repairedName());
                ps.setString(2, tag.id());
                ps.addBatch();
                log.info("#305 tag name repaired: tag {} {} -> {}",
                        tag.id(), describe(tag.currentName()), describe(tag.repairedName()));
            }
            ps.executeBatch();
        }
        log.info("#305 tag name repair complete: {} tag name(s) rewritten.", toUpdate.size());
    }

    /**
     * The WARN a skipped collision produces: every tag id in the group, with its name before and
     * after, so the operator can see which pair to merge and which way round.
     */
    private static String collisionMessage(List<LiveTag> group) {
        StringBuilder sb = new StringBuilder("#305 tag name repair SKIPPED: repairing these tags would"
                + " give the same owner two tags with the name ")
                .append(describe(group.get(0).repairedName()))
                .append(". All of them are left unchanged — merging tags moves documents and permissions,"
                        + " so it is a manual decision. Tags:");
        for (LiveTag tag : group) {
            sb.append(" [id=").append(tag.id())
                    .append(", name=").append(describe(tag.currentName()))
                    .append(" -> ").append(describe(tag.repairedName()))
                    .append(']');
        }
        return sb.toString();
    }

    /**
     * Render a name so the log is actually readable: the whole point is characters that do not show
     * up, so a raw {@code before -> after} pair would print as two identical strings. Non-ASCII code
     * points are spelled out as {@code [U+200B]}.
     */
    private static String describe(String name) {
        if (name == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder("\"");
        name.codePoints().forEach(codePoint -> {
            if (codePoint >= 0x20 && codePoint < 0x7F) {
                sb.appendCodePoint(codePoint);
            } else {
                sb.append(String.format("[U+%04X]", codePoint));
            }
        });
        return sb.append('"').toString();
    }

    private static void rollbackQuietly(Connection connection, Savepoint savepoint) {
        if (savepoint == null) {
            return;
        }
        try {
            connection.rollback(savepoint);
        } catch (SQLException e) {
            log.error("Unable to roll back the failed tag name whitespace repair", e);
        }
    }
}
