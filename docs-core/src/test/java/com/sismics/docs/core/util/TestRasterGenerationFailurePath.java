package com.sismics.docs.core.util;

import com.sismics.BaseTest;
import com.sismics.util.mime.MimeType;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Failure-path coverage for the shared raster helper's rollback + cleanup guarantees (BLOCKING-6).
 *
 * <p>The load-bearing scenario is a failure AT THE SWAP after generation has SUCCEEDED (the temps
 * exist): the first ({@code _web}) move fails, so nothing is swapped in and the persist step (the DB
 * update) never runs. The test asserts, byte-for-byte, that the prior derivative is untouched, that
 * the persist callback did not run (the DB cannot advance), and that the generated {@code <uuid>} temp
 * files were cleaned up. Each guarantee is mutation-verified in the review. The REAL-DB flavour of the
 * DB-not-advanced + old-raster-preservation guarantee lives in TestFileRotationResource (end to end
 * through the rotate endpoint against H2).
 */
public class TestRasterGenerationFailurePath extends BaseTest {

    /** Snapshot the *.tmp raster temps for a given file id in the storage dir. */
    private List<Path> tempsFor(String fileId) throws Exception {
        Path storageDir = DirectoryUtil.getStorageDirectory();
        try (Stream<Path> entries = Files.list(storageDir)) {
            return entries
                    .filter(p -> p.getFileName().toString().startsWith(fileId))
                    .filter(p -> p.getFileName().toString().endsWith(".tmp"))
                    .collect(Collectors.toList());
        }
    }

    /**
     * A swap failure after generation succeeds: the {@code _web} move is forced to fail (its target is
     * a non-empty directory, which {@code Files.move} cannot overwrite), so nothing is swapped in. The
     * pre-existing {@code _thumb} raster must be byte-for-byte intact, the persist step must NOT run
     * (the DB cannot advance without its rasters), and the generated temps must be cleaned up.
     */
    @Test
    public void swapFailureLeavesPriorRasterIntactDbNotAdvancedNoTempLeak() throws Exception {
        String fileId = "rasterfail-swap-" + UUID.randomUUID();
        Path storageDir = DirectoryUtil.getStorageDirectory();

        // A real image as the decrypted original — generation WILL succeed and produce both temps.
        Path original = storageDir.resolve(fileId + "_orig");
        Files.copy(getResource(FILE_PNG).openStream(), original, StandardCopyOption.REPLACE_EXISTING);

        // Pre-existing "old" _thumb raster with sentinel bytes that MUST survive the failed swap. The
        // _web target is made a NON-EMPTY DIRECTORY so the FIRST move fails BEFORE the _thumb move is
        // attempted — the old _thumb file is therefore never touched.
        Path webTarget = storageDir.resolve(fileId + "_web");
        Path thumbTarget = storageDir.resolve(fileId + "_thumb");
        byte[] oldThumb = "OLD_THUMB_BYTES".getBytes();
        Files.write(thumbTarget, oldThumb);
        Files.createDirectories(webTarget);
        Files.write(webTarget.resolve("blocker"), new byte[]{7});

        AtomicBoolean persistRan = new AtomicBoolean(false);
        String key = EncryptionUtil.generatePrivateKey();

        try {
            // Generation succeeds (temps written), then the _web move throws — the swap fails.
            Assertions.assertThrows(Exception.class, () ->
                    RasterGenerationUtil.regenerateRasters(fileId, original, MimeType.IMAGE_PNG,
                            () -> 90, key, () -> persistRan.set(true)));

            // (b) DB cannot advance: the persist step (DB update) never ran.
            Assertions.assertFalse(persistRan.get(),
                    "persist (DB update) must not run when the raster swap fails");
            // (a) prior derivative byte-for-byte intact.
            Assertions.assertArrayEquals(oldThumb, Files.readAllBytes(thumbTarget),
                    "the prior _thumb raster bytes must be untouched by a failed swap");
            // (c) no generated <uuid> temp left behind.
            Assertions.assertTrue(tempsFor(fileId).isEmpty(),
                    "generated temp rasters must be cleaned up when the swap fails");
        } finally {
            Files.deleteIfExists(webTarget.resolve("blocker"));
            Files.deleteIfExists(webTarget);
            Files.deleteIfExists(thumbTarget);
            Files.deleteIfExists(original);
        }
    }
}
