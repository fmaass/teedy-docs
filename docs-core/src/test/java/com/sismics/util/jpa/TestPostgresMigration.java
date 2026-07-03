package com.sismics.util.jpa;

import com.sismics.docs.core.util.ConfigUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Runs the real database migrations against a real PostgreSQL server and asserts the
 * whole run completes cleanly and lands on the configured DB_VERSION. This is the
 * guardrail for the H2-passes/PostgreSQL-fails class of native-SQL bug (e.g. a wrong
 * column name or a missing derived-table alias) that the H2-only tests cannot catch.
 *
 * Skipped automatically when Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
public class TestPostgresMigration {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

    @Test
    public void migrationsRunCleanOnPostgres() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            connection.setAutoCommit(false);

            DbOpenHelper helper = new DbOpenHelper(connection) {
                @Override
                public void onCreate() throws Exception {
                    executeAllScript(0);
                }

                @Override
                public void onUpgrade(int oldVersion, int newVersion) throws Exception {
                    for (int version = oldVersion + 1; version <= newVersion; version++) {
                        executeAllScript(version);
                    }
                }
            };

            // Must not throw: every migration statement must succeed on real PostgreSQL.
            helper.open();
            Assertions.assertTrue(helper.getExceptions().isEmpty(),
                    "database migrations must run cleanly on PostgreSQL");

            // The schema is left at the configured version.
            int expectedVersion = Integer.parseInt(ConfigUtil.getConfigBundle().getString("db.version"));
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery(
                         "select CFG_VALUE_C from T_CONFIG where CFG_ID_C = 'DB_VERSION'")) {
                Assertions.assertTrue(resultSet.next(), "DB_VERSION row must exist after migration");
                Assertions.assertEquals(expectedVersion, Integer.parseInt(resultSet.getString(1)));
            }
        }
    }
}
