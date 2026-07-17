package com.sismics.docs.core.util.format;

import com.sismics.docs.core.model.context.AppContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Helpers shared by the format handlers that render a source document into a temporary PDF.
 *
 * @author bgamard
 */
final class FormatConversionUtil {

    private FormatConversionUtil() {
    }

    /**
     * A conversion that writes rendered output into the freshly created temporary file.
     */
    @FunctionalInterface
    interface TempFileWriter {
        void write(Path tempFile) throws Exception;
    }

    /**
     * Create a plaintext temporary file, run the conversion into it, and return it. If the conversion throws
     * or otherwise does not complete, the temporary file is deleted BEFORE the failure propagates — so a
     * decrypted, plaintext-derived PDF is never stranded on disk on a failure path. This is deterministic
     * cleanup, not merely the phantom-reference GC backstop (which only fires at an unbounded later time).
     *
     * @param writer the conversion to run into the temporary file
     * @return the temporary file, on success
     * @throws Exception the conversion's own failure, after the temporary file has been deleted
     */
    static Path convertToTemporaryPdf(TempFileWriter writer) throws Exception {
        Path tempFile = AppContext.getInstance().getFileService().createTemporaryFile();
        boolean success = false;
        try {
            writer.write(tempFile);
            success = true;
            return tempFile;
        } finally {
            if (!success) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException suppressed) {
                    // Best-effort cleanup; never mask the original failure.
                }
            }
        }
    }
}
