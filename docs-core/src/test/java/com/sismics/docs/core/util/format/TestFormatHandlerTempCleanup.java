package com.sismics.docs.core.util.format;

import com.sismics.BaseTest;
import com.sismics.docs.core.model.context.AppContext;
import com.sismics.docs.core.service.FileService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Each office/text format handler renders its source into a plaintext temporary PDF. A conversion that fails
 * after that temp is created must delete it DETERMINISTICALLY (not wait for the phantom-reference GC
 * backstop), so decrypted-derived content is never stranded on disk. Each test drives a handler's real
 * failure path — a missing source file fails the conversion after the temp has been created — and asserts no
 * temp survives.
 */
public class TestFormatHandlerTempCleanup extends BaseTest {

    @Test
    public void textPlainHandlerDeletesTempOnConversionFailure() throws Exception {
        assertNoTempLeak(() -> new TextPlainFormatHandler().generateThumbnail(missingSource()));
    }

    @Test
    public void docxHandlerDeletesTempOnConversionFailure() throws Exception {
        assertNoTempLeak(() -> new DocxFormatHandler().generateThumbnail(missingSource()));
    }

    @Test
    public void odtHandlerDeletesTempOnConversionFailure() throws Exception {
        assertNoTempLeak(() -> new OdtFormatHandler().generateThumbnail(missingSource()));
    }

    /** A path that does not exist, so the conversion fails AFTER the handler has created its temp file. */
    private static Path missingSource() {
        return Paths.get(System.getProperty("java.io.tmpdir"), "no_such_source_" + UUID.randomUUID() + ".bin");
    }

    private void assertNoTempLeak(Executable failingConversion) throws Exception {
        // Ensure the application context (and its startup temp sweep) has fully started before snapshotting,
        // so pre-existing startup temps cancel out of the before/after delta.
        AppContext.getInstance();
        Path tempDir = FileService.getTemporaryDirectory();
        Set<Path> before = listTemps(tempDir);

        Assertions.assertThrows(Exception.class, failingConversion,
                "converting a missing source must fail after the temp is created");

        Set<Path> after = listTemps(tempDir);
        after.removeAll(before);
        Assertions.assertTrue(after.isEmpty(),
                "a failed conversion must leave no plaintext temp behind, but found: " + after);
    }

    private static Set<Path> listTemps(Path dir) throws IOException {
        try (Stream<Path> stream = Files.list(dir)) {
            Set<Path> result = new HashSet<>();
            stream.filter(p -> p.getFileName().toString().startsWith("sismics_docs")).forEach(result::add);
            return result;
        }
    }
}
