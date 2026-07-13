package com.sismics.docs.core.util;

import com.sismics.util.EnvironmentUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

/**
 * Unit tests for {@link DirectoryUtil#getStorageDirectoryReadOnly()} (#60): the dry-run's storage
 * lookup MUST be strictly read-only — it may never create the base or storage directory. This is the
 * load-bearing side-effect-free property of the cleanup dry-run.
 *
 * <p>{@code EnvironmentUtil.TEEDY_HOME} is captured into a static field at class load, so the test
 * sets it via reflection (and restores it) to point the base directory at a brand-new, non-existent
 * path — the only way to exercise the "would-be-created" branch deterministically.</p>
 */
public class TestDirectoryUtilReadOnly {
    @Test
    public void readOnlyStoragePathDoesNotCreateAnyDirectory() throws Exception {
        Path home = Paths.get(System.getProperty("java.io.tmpdir"), "teedy-ro-" + UUID.randomUUID());
        Field teedyHome = EnvironmentUtil.class.getDeclaredField("TEEDY_HOME");
        teedyHome.setAccessible(true);
        Object previous = teedyHome.get(null);
        teedyHome.set(null, home.toString());
        try {
            Assertions.assertFalse(Files.exists(home), "precondition: the fresh home must not exist yet");

            // The read-only resolver returns the storage path WITHOUT creating anything.
            Path storage = DirectoryUtil.getStorageDirectoryReadOnly();
            Assertions.assertEquals(home.resolve("storage"), storage, "resolves the storage sub-path");
            Assertions.assertFalse(Files.exists(home),
                    "the read-only lookup must NOT create the base directory");
            Assertions.assertFalse(Files.exists(storage),
                    "the read-only lookup must NOT create the storage directory");

            // Contrast: the mutating variant DOES create it (proves the reader/creator split is real).
            Path created = DirectoryUtil.getStorageDirectory();
            Assertions.assertTrue(Files.isDirectory(created),
                    "the mutating getStorageDirectory() creates the directory");
            Assertions.assertEquals(storage, created, "both resolve the same path");
        } finally {
            teedyHome.set(null, previous);
            try {
                Files.deleteIfExists(home.resolve("storage"));
                Files.deleteIfExists(home);
            } catch (Exception ignored) {
                // temp dir; leave it to the OS
            }
        }
    }
}
