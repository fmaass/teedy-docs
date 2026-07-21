package com.sismics.docs.core.util.format;

import com.google.common.collect.Lists;
import com.google.common.io.ByteStreams;
import com.google.common.io.Closer;
import com.sismics.util.io.InputStreamReaderThread;
import com.sismics.util.mime.MimeType;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Video format handler.
 *
 * @author bgamard
 */
public class VideoFormatHandler implements FormatHandler {
    /**
     * Logger.
     */
    private static final Logger log = LoggerFactory.getLogger(VideoFormatHandler.class);

    @Override
    public boolean accept(String mimeType) {
        return mimeType.equals(MimeType.VIDEO_MP4) || mimeType.equals(MimeType.VIDEO_WEBM);
    }

    @Override
    public BufferedImage generateThumbnail(Path file) throws IOException {
        List<String> result = Lists.newLinkedList(Arrays.asList("ffmpeg", "-i"));
        result.add(file.toAbsolutePath().toString());
        result.addAll(Arrays.asList("-vf", "thumbnail", "-frames:v", "1", "-f", "mjpeg", "-"));
        ProcessBuilder pb = new ProcessBuilder(result);
        Process process = pb.start();

        // Consume the process error stream
        final String commandName = pb.command().get(0);
        new InputStreamReaderThread(process.getErrorStream(), commandName).start();

        // Consume the data as an image
        try (InputStream is = process.getInputStream()) {
            return ImageIO.read(is);
        }
    }

    @Override
    public String extractContent(String language, Path file) throws Exception {
        List<String> result = Lists.newLinkedList();
        result.add("mediainfo");
        result.add(file.toAbsolutePath().toString());
        ProcessBuilder pb = new ProcessBuilder(result);
        Process process;
        try {
            process = startProcess(pb);
        } catch (IOException e) {
            // Only a DETERMINATE "executable not found" is a legitimate no-extractor resting state (like an
            // unsupported format), returned as terminal-empty. The JVM reports a missing program as ENOENT —
            // "error=2" in the IOException message ("Cannot run program \"mediainfo\": error=2, ..."). Parse
            // the errno NUMERICALLY (not a substring match, which would also match error=20..29 such as
            // "error=24, Too many open files") and treat ONLY errno 2 as terminal. Any other spawn failure (a
            // permission error, a resource limit) may be transient, so surface it as a recoverable failure
            // rather than silently indexing the video empty and stamping it complete (#159).
            if (isExecutableNotFound(e.getMessage())) {
                log.warn("mediainfo is not installed; skipping video metadata extraction for {}", file, e);
                return null;
            }
            throw e;
        }

        // Consume the process error stream
        final String commandName = pb.command().get(0);
        new InputStreamReaderThread(process.getErrorStream(), commandName).start();

        // Consume the data as a string. A read failure here is a TRANSIENT loss of otherwise-extractable
        // metadata, so surface it (do not swallow to null) — otherwise the file is indexed empty and stamped
        // complete, permanently excluded from later reprocessing (#159).
        try (InputStream is = process.getInputStream()) {
            return new String(ByteStreams.toByteArray(is), StandardCharsets.UTF_8);
        }
    }

    /**
     * Start the mediainfo process. Package-private so a test can drive the spawn-failure classification (a
     * determinate ENOENT vs. any other IOException) without depending on the host's installed binaries.
     *
     * @param pb Configured process builder
     * @return the started process
     * @throws IOException if the process cannot be started
     */
    Process startProcess(ProcessBuilder pb) throws IOException {
        return pb.start();
    }

    /**
     * Errno embedded by the JVM in a process-spawn IOException message ("... error=&lt;errno&gt;, ...").
     * The number is captured so it can be compared NUMERICALLY — a plain {@code contains("error=2")} would
     * also match error=20..29 (e.g. "error=24, Too many open files"), misclassifying a transient
     * resource-exhaustion failure as a missing binary.
     */
    private static final Pattern SPAWN_ERRNO = Pattern.compile("error=(\\d+)");

    /**
     * True only for a DETERMINATE "executable not found" spawn failure — errno 2 (ENOENT). Any other errno
     * (or an unparseable message) is not treated as a missing binary, so it propagates as recoverable.
     *
     * @param message The spawn IOException message (may be null)
     * @return true if the message reports ENOENT (errno 2)
     */
    static boolean isExecutableNotFound(String message) {
        if (message == null) {
            return false;
        }
        Matcher matcher = SPAWN_ERRNO.matcher(message);
        return matcher.find() && "2".equals(matcher.group(1));
    }

    @Override
    public void appendToPdf(Path file, PDDocument doc, boolean fitImageToPage, int margin, MemoryUsageSetting memUsageSettings, Closer closer) {
        // Video cannot be appended to PDF files
    }
}
