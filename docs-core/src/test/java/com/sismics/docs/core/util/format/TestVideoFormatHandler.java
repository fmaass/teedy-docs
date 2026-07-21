package com.sismics.docs.core.util.format;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;

/**
 * VideoFormatHandler's spawn-failure classification (#159): only a DETERMINATE "executable not found"
 * (ENOENT / "error=2") is the legitimate no-extractor resting state returned as terminal-empty (null); any
 * OTHER spawn failure is surfaced as a recoverable failure rather than silently indexing the video empty.
 */
public class TestVideoFormatHandler {

    private VideoFormatHandler handlerThrowing(IOException spawnFailure) {
        return new VideoFormatHandler() {
            @Override
            Process startProcess(ProcessBuilder pb) throws IOException {
                throw spawnFailure;
            }
        };
    }

    @Test
    public void absentBinaryEnoentIsTerminalEmpty() throws Exception {
        // The JVM reports a missing executable as ENOENT ("error=2").
        VideoFormatHandler handler = handlerThrowing(
                new IOException("Cannot run program \"mediainfo\": error=2, No such file or directory"));
        Assertions.assertNull(handler.extractContent("eng", Path.of("/tmp/does-not-matter.mp4")),
                "a determinate missing binary yields terminal-empty content");
    }

    @Test
    public void otherSpawnFailurePropagates() {
        // A non-ENOENT spawn failure (e.g. EACCES "error=13") may be transient and must NOT be swallowed.
        VideoFormatHandler handler = handlerThrowing(
                new IOException("Cannot run program \"mediainfo\": error=13, Permission denied"));
        Assertions.assertThrows(IOException.class,
                () -> handler.extractContent("eng", Path.of("/tmp/does-not-matter.mp4")),
                "a non-ENOENT spawn failure must surface as a recoverable failure");
    }

    @Test
    public void resourceExhaustionErrno24Propagates() {
        // The boundary case: "error=24" (EMFILE, too many open files) starts with "error=2" but is a
        // TRANSIENT resource-exhaustion failure, not ENOENT. It must propagate, not be misclassified as a
        // missing binary and swallowed to terminal-empty.
        VideoFormatHandler handler = handlerThrowing(
                new IOException("Cannot run program \"mediainfo\": error=24, Too many open files"));
        Assertions.assertThrows(IOException.class,
                () -> handler.extractContent("eng", Path.of("/tmp/does-not-matter.mp4")),
                "error=24 (EMFILE) must propagate, not be treated as a missing binary");
    }

    @Test
    public void errnoIsMatchedNumericallyNotAsSubstring() {
        // Direct helper contract: exactly errno 2 is "executable not found"; 20-29 are not.
        Assertions.assertTrue(VideoFormatHandler.isExecutableNotFound(
                "Cannot run program \"mediainfo\": error=2, No such file or directory"));
        Assertions.assertFalse(VideoFormatHandler.isExecutableNotFound(
                "Cannot run program \"mediainfo\": error=20, Not a directory"));
        Assertions.assertFalse(VideoFormatHandler.isExecutableNotFound(
                "Cannot run program \"mediainfo\": error=24, Too many open files"));
        Assertions.assertFalse(VideoFormatHandler.isExecutableNotFound(null));
    }
}
