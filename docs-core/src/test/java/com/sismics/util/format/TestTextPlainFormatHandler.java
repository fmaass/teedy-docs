package com.sismics.util.format;

import com.google.common.io.Closer;
import com.sismics.BaseTest;
import com.sismics.docs.core.util.format.TextPlainFormatHandler;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Test of {@link TextPlainFormatHandler}.
 *
 * <p>A text attachment encoded in Windows-1252 / Latin-1 (a lone 0xE9 byte for "é")
 * is not valid UTF-8. A strict decode throws {@link java.nio.charset.MalformedInputException},
 * which used to fail the whole PDF export. The handler must decode leniently.</p>
 */
public class TestTextPlainFormatHandler extends BaseTest {

    /**
     * "caf" followed by a lone 0xE9 byte (Latin-1 'é'), which is invalid UTF-8.
     */
    private static final byte[] LATIN1_BYTES = new byte[]{0x63, 0x61, 0x66, (byte) 0xE9};

    @Test
    public void extractContentLatin1DoesNotThrow(@TempDir Path tempDir) throws Exception {
        Path txtFile = tempDir.resolve("latin1.txt");
        Files.write(txtFile, LATIN1_BYTES);

        TextPlainFormatHandler handler = new TextPlainFormatHandler();
        String content = handler.extractContent("eng", txtFile);
        Assertions.assertNotNull(content);
        Assertions.assertTrue(content.contains("caf"),
                "Expected extracted content to contain \"caf\", was: " + content);
    }

    @Test
    public void generatePdfLatin1DoesNotThrow(@TempDir Path tempDir) throws Exception {
        Path txtFile = tempDir.resolve("latin1.txt");
        Files.write(txtFile, LATIN1_BYTES);

        TextPlainFormatHandler handler = new TextPlainFormatHandler();
        try (PDDocument doc = new PDDocument()) {
            Closer closer = Closer.create();
            MemoryUsageSetting memUsageSettings = MemoryUsageSetting.setupMainMemoryOnly();
            handler.appendToPdf(txtFile, doc, true, 10, memUsageSettings, closer);

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            doc.save(outputStream);
            closer.close();
            Assertions.assertTrue(outputStream.toByteArray().length > 0,
                    "Expected a non-empty PDF to be produced from a Latin-1 text file");
        }
    }
}
