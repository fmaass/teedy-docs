package com.sismics.util.jpa;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Test that EMF refuses to build the EntityManagerFactory when the migration runner
 * reported errors (fail closed), rather than booting on a partial schema. The static
 * initializer runs once per JVM against the real, clean scripts, so the boot-refusal
 * decision is exercised through the extracted failClosedIfMigrationErrors seam.
 */
public class TestEmfFailClosed {
    private static DbOpenHelper helperReporting(List<Exception> exceptions) {
        return new DbOpenHelper(null) {
            @Override
            public void onCreate() {
            }

            @Override
            public void onUpgrade(int oldVersion, int newVersion) {
            }

            @Override
            public List<?> getExceptions() {
                return exceptions;
            }
        };
    }

    @Test
    public void refusesBootWhenMigrationReportedErrors() {
        DbOpenHelper failed = helperReporting(List.of(new RuntimeException("migration failed")));
        IllegalStateException e = Assertions.assertThrows(IllegalStateException.class,
                () -> EMF.failClosedIfMigrationErrors(failed));
        Assertions.assertTrue(e.getMessage().contains("refusing to start"));
    }

    @Test
    public void allowsBootWhenNoErrors() {
        DbOpenHelper ok = helperReporting(List.of());
        Assertions.assertDoesNotThrow(() -> EMF.failClosedIfMigrationErrors(ok));
    }
}
