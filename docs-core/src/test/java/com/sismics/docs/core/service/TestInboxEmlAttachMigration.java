package com.sismics.docs.core.service;

import com.sismics.docs.BaseTransactionalTest;
import com.sismics.util.context.ThreadLocalContext;
import com.sismics.util.jpa.DialectUtil;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * (#197) The raw {@code .eml} toggle is SEEDED, not defaulted, and the two seeds must produce opposite
 * answers: a fresh installation gets it ON (the base install script), an upgraded one gets it OFF
 * (migration 064). A code default cannot express that — neither case has a row — so the asymmetry lives
 * entirely in which script runs, and is tested by running the SHIPPED statements themselves against the
 * real database rather than by asserting on their text.
 *
 * <p>Only the statements that touch this key are executed: the rest of the base install script is the
 * schema, which this database already has. Each selection is asserted non-empty first, so a filter that
 * silently stopped matching (a renamed key, a rewritten script) fails loudly instead of making every
 * assertion below vacuous.</p>
 */
public class TestInboxEmlAttachMigration extends BaseTransactionalTest {
    private static final String KEY = "INBOX_EML_ATTACH";

    private static final String CREATE_SCRIPT = "/db/update/dbupdate-000-0.sql";

    private static final String MIGRATION_SCRIPT = "/db/update/dbupdate-064-0.sql";

    /**
     * The database this suite runs against is created from scratch (an in-memory H2), so it IS a fresh
     * installation: the base install seed must have survived every later migration, 064 included.
     */
    @Test
    public void aFreshlyCreatedDatabaseHasTheToggleOn() {
        Assertions.assertEquals("true", storedValue(),
                "a fresh installation must have the raw .eml attachment enabled");
    }

    /**
     * Fresh install, replayed: the base install seeds 'true', then migration 064 runs (as it does on a
     * fresh database, after the create script) and must leave that 'true' alone.
     */
    @Test
    public void freshInstallSeedsOnAndTheMigrationLeavesItAlone() throws IOException {
        deleteRow();
        Assertions.assertNull(storedValue(), "precondition: the key must be absent");

        execute(statementsMentioningKey(CREATE_SCRIPT));
        Assertions.assertEquals("true", storedValue(), "the base install must seed the toggle ON");

        execute(allStatements(MIGRATION_SCRIPT));
        Assertions.assertEquals("true", storedValue(),
                "migration 064 must not turn a fresh installation's toggle off");
    }

    /**
     * Upgrade: the key is absent (no base install ran for it), so migration 064 inserts it OFF — today's
     * behaviour is preserved for an installation that never asked for the extra copy.
     */
    @Test
    public void upgradeSeedsOffWhenTheKeyIsAbsent() throws IOException {
        deleteRow();
        Assertions.assertNull(storedValue(), "precondition: the key must be absent");

        execute(allStatements(MIGRATION_SCRIPT));
        Assertions.assertEquals("false", storedValue(),
                "an upgrade must seed the toggle OFF");
    }

    /**
     * Only-when-absent, in both directions: a re-run of the migration (H2 auto-commits DDL, so a partially
     * applied migration can be re-run) must never clobber the value that is there — including one an
     * operator has since turned on.
     */
    @Test
    public void theMigrationNeverOverwritesAnExistingValue() throws IOException {
        deleteRow();
        execute(allStatements(MIGRATION_SCRIPT));
        Assertions.assertEquals("false", storedValue());

        setValue("true");
        execute(allStatements(MIGRATION_SCRIPT));
        Assertions.assertEquals("true", storedValue(),
                "a re-run must not reset an operator's own value");
    }

    /**
     * The stored value of the toggle, or null when the row is absent.
     */
    private String storedValue() {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        List<?> results = em.createNativeQuery("select CFG_VALUE_C from T_CONFIG where CFG_ID_C = '" + KEY + "'")
                .getResultList();
        return results.isEmpty() ? null : String.valueOf(results.get(0));
    }

    private void deleteRow() {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        em.createNativeQuery("delete from T_CONFIG where CFG_ID_C = '" + KEY + "'").executeUpdate();
    }

    private void setValue(String value) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        em.createNativeQuery("update T_CONFIG set CFG_VALUE_C = '" + value + "' where CFG_ID_C = '" + KEY + "'")
                .executeUpdate();
    }

    private void execute(List<String> statements) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        for (String statement : statements) {
            em.createNativeQuery(statement).executeUpdate();
        }
    }

    private List<String> allStatements(String resource) throws IOException {
        List<String> statements = readStatements(resource, null);
        Assertions.assertFalse(statements.isEmpty(), "no statement read from " + resource);
        return statements;
    }

    private List<String> statementsMentioningKey(String resource) throws IOException {
        List<String> statements = readStatements(resource, KEY);
        Assertions.assertFalse(statements.isEmpty(), "no " + KEY + " statement found in " + resource);
        return statements;
    }

    /**
     * Read a shipped migration script exactly as {@code DbOpenHelper.executeScript} does — one statement
     * per line, comments skipped, each line put through the dialect transform for the database in use —
     * optionally keeping only the statements that mention a token.
     */
    private List<String> readStatements(String resource, String token) throws IOException {
        List<String> statements = new ArrayList<>();
        try (InputStream inputStream = getClass().getResourceAsStream(resource);
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(java.util.Objects.requireNonNull(inputStream, resource), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("--")) {
                    continue;
                }
                if (token != null && !line.contains(token)) {
                    continue;
                }
                String transformed = DialectUtil.transform(line);
                if (transformed != null) {
                    statements.add(transformed);
                }
            }
        }
        return statements;
    }
}
