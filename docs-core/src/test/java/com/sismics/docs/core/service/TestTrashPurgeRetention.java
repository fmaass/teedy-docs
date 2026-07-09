package com.sismics.docs.core.service;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit test of {@link TrashPurgeService#getRetentionDays()} config resolution.
 *
 * <p>Env-var precedence cannot be exercised from JUnit (the env is immutable), but the
 * system-property path, malformed-fallback, and the {@code <= 0}/disabled semantics are
 * covered here. Each test cleans up its property in a finally block.</p>
 */
public class TestTrashPurgeRetention {

    private static final String PROP = "docs.trash_retention_days";

    @Test
    public void defaultWhenUnset() {
        Assertions.assertEquals(30, TrashPurgeService.getRetentionDays());
    }

    @Test
    public void readsSystemProperty() {
        try {
            System.setProperty(PROP, "15");
            Assertions.assertEquals(15, TrashPurgeService.getRetentionDays());
        } finally {
            System.clearProperty(PROP);
        }
    }

    @Test
    public void malformedFallsBackToDefault() {
        try {
            System.setProperty(PROP, "not-a-number");
            Assertions.assertEquals(30, TrashPurgeService.getRetentionDays());
        } finally {
            System.clearProperty(PROP);
        }
    }

    @Test
    public void nonPositiveValuePreservedForDisable() {
        // <= 0 must be returned as-is (it disables purging), NOT folded to the default.
        try {
            System.setProperty(PROP, "0");
            Assertions.assertEquals(0, TrashPurgeService.getRetentionDays());
            System.setProperty(PROP, "-1");
            Assertions.assertEquals(-1, TrashPurgeService.getRetentionDays());
        } finally {
            System.clearProperty(PROP);
        }
    }
}
