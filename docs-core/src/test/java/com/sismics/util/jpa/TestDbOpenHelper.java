package com.sismics.util.jpa;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Test that the database migration runner fails fast on a broken statement
 * instead of swallowing the SQLException and booting on a partial schema.
 */
public class TestDbOpenHelper {
    /**
     * A migration script with a failing statement must abort {@link DbOpenHelper#open()}
     * (not be silently swallowed) AND roll back the successful writes that preceded it.
     * onCreate creates a canary table (DDL auto-commits on H2), onUpgrade inserts a row
     * and then runs a failing statement; after open() throws, the row must be gone.
     * The scripts live outside /db/update/ so they do not shadow the real migration
     * scripts on the classpath, and are run via the package-private executeScript once
     * open() has initialised the statement.
     */
    @Test
    public void testFailingMigrationAbortsOpenAndRollsBack() throws Exception {
        Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:failfast;DB_CLOSE_DELAY=-1", "sa", "");
        connection.setAutoCommit(false);
        try {
            DbOpenHelper helper = new DbOpenHelper(connection) {
                @Override
                public void onCreate() throws Exception {
                    executeScript(getClass().getResourceAsStream("/db/failtest/setup-canary.sql"));
                }

                @Override
                public void onUpgrade(int oldVersion, int newVersion) throws Exception {
                    executeScript(getClass().getResourceAsStream("/db/failtest/dbupdate-fail-0.sql"));
                }
            };

            Assertions.assertThrows(Exception.class, helper::open,
                    "a failing migration statement must abort open(), not be swallowed");

            // The insert that preceded the failing statement must have been rolled back.
            // Read on the SAME connection: an un-rolled-back insert would still be visible here.
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery("select count(*) from T_ROLLBACK_CANARY")) {
                Assertions.assertTrue(resultSet.next());
                Assertions.assertEquals(0, resultSet.getInt(1),
                        "the successful write before the failing statement must be rolled back");
            }
        } finally {
            connection.close();
        }
    }
}
