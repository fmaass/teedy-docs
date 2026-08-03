package com.sismics.util;

import com.sismics.BaseTest;
import com.sismics.util.mime.MimeType;
import com.sismics.util.mime.MimeTypeUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Test of the utilities to check MIME types.
 * 
 * @author bgamard
 */
public class TestMimeTypeUtil extends BaseTest {
    @Test
    public void test() throws Exception {
        // Detect ODT files
        Path path = Paths.get(getResource(FILE_ODT).toURI());
        Assertions.assertEquals(MimeType.OPEN_DOCUMENT_TEXT, MimeTypeUtil.guessMimeType(path, FILE_ODT));

        // Detect DOCX files
        path = Paths.get(getResource(FILE_DOCX).toURI());
        Assertions.assertEquals(MimeType.OFFICE_DOCUMENT, MimeTypeUtil.guessMimeType(path, FILE_ODT));

        // Detect PPTX files
        path = Paths.get(getResource(FILE_PPTX).toURI());
        Assertions.assertEquals(MimeType.OFFICE_PRESENTATION, MimeTypeUtil.guessMimeType(path, FILE_PPTX));

        // Detect XLSX files
        path = Paths.get(getResource(FILE_XLSX).toURI());
        Assertions.assertEquals(MimeType.OFFICE_SHEET, MimeTypeUtil.guessMimeType(path, FILE_XLSX));

        // Detect TXT files
        path = Paths.get(getResource(FILE_TXT).toURI());
        Assertions.assertEquals(MimeType.TEXT_PLAIN, MimeTypeUtil.guessMimeType(path, FILE_TXT));

        // Detect CSV files
        path = Paths.get(getResource(FILE_CSV).toURI());
        Assertions.assertEquals(MimeType.TEXT_CSV, MimeTypeUtil.guessMimeType(path, FILE_CSV));

        // Detect PDF files
        path = Paths.get(getResource(FILE_PDF).toURI());
        Assertions.assertEquals(MimeType.APPLICATION_PDF, MimeTypeUtil.guessMimeType(path, FILE_PDF));

        // Detect JPEG files
        path = Paths.get(getResource(FILE_JPG).toURI());
        Assertions.assertEquals(MimeType.IMAGE_JPEG, MimeTypeUtil.guessMimeType(path, FILE_JPG));

        // Detect GIF files
        path = Paths.get(getResource(FILE_GIF).toURI());
        Assertions.assertEquals(MimeType.IMAGE_GIF, MimeTypeUtil.guessMimeType(path, FILE_GIF));

        // Detect PNG files
        path = Paths.get(getResource(FILE_PNG).toURI());
        Assertions.assertEquals(MimeType.IMAGE_PNG, MimeTypeUtil.guessMimeType(path, FILE_PNG));

        // Detect ZIP files
        path = Paths.get(getResource(FILE_ZIP).toURI());
        Assertions.assertEquals(MimeType.APPLICATION_ZIP, MimeTypeUtil.guessMimeType(path, FILE_ZIP));

        // Detect WEBM files
        path = Paths.get(getResource(FILE_WEBM).toURI());
        Assertions.assertEquals(MimeType.VIDEO_WEBM, MimeTypeUtil.guessMimeType(path, FILE_WEBM));

        // Detect MP4 files
        path = Paths.get(getResource(FILE_MP4).toURI());
        Assertions.assertEquals(MimeType.VIDEO_MP4, MimeTypeUtil.guessMimeType(path, FILE_MP4));
    }

    /**
     * WebP is detected from the BYTES, not from the name. Both fixtures are copied to a
     * name-less-looking temp file ("upload", no extension) and probed with a null name, so neither
     * {@link java.nio.file.Files#probeContentType} nor the URLConnection filename map — the only two
     * inference sources this utility had — can supply the answer. A host whose /etc/mime.types knows
     * about .webp therefore cannot make this test pass for the wrong reason.
     */
    @Test
    public void detectsWebpFromContentWithoutAnyFilenameHint(@TempDir Path tempDir) throws Exception {
        for (String fixture : new String[]{FILE_WEBP, FILE_WEBP_LOSSLESS}) {
            Path neutral = tempDir.resolve("upload-" + fixture.hashCode());
            Files.copy(Paths.get(getResource(fixture).toURI()), neutral);

            Assertions.assertEquals(MimeType.IMAGE_WEBP, MimeTypeUtil.guessMimeType(neutral, null),
                    fixture + " must be detected from its RIFF/WEBP signature with no file name at all");
            Assertions.assertEquals(MimeType.IMAGE_WEBP, MimeTypeUtil.guessMimeType(neutral, "upload"),
                    fixture + " must be detected from its signature even with an extension-less name");
        }
    }

    /**
     * The content check must not hijack files that merely share the RIFF container (WAV, AVI) or that
     * are too short to carry the 12-byte signature.
     */
    @Test
    public void riffContainersThatAreNotWebpAreNotDetectedAsWebp(@TempDir Path tempDir) throws Exception {
        Path wav = tempDir.resolve("sound");
        Files.write(wav, new byte[]{'R', 'I', 'F', 'F', 4, 0, 0, 0, 'W', 'A', 'V', 'E', 0, 0, 0, 0});
        Assertions.assertNotEquals(MimeType.IMAGE_WEBP, MimeTypeUtil.guessMimeType(wav, null),
                "a RIFF/WAVE container is not a WebP image");

        Path truncated = tempDir.resolve("tiny");
        Files.write(truncated, new byte[]{'R', 'I', 'F', 'F'});
        Assertions.assertNotEquals(MimeType.IMAGE_WEBP, MimeTypeUtil.guessMimeType(truncated, null),
                "a file too short to hold the signature is not a WebP image");

        Path empty = tempDir.resolve("empty");
        Files.write(empty, new byte[0]);
        Assertions.assertEquals(MimeType.DEFAULT, MimeTypeUtil.guessMimeType(empty, null),
                "an empty file still falls through to the default MIME type");
    }

    /**
     * A file uploaded without a name is written to ZIP/export paths as {@code <default>.<extension>}
     * ({@link com.sismics.docs.core.model.jpa.File#getFullName}); without this mapping every WebP
     * would land there as {@code .bin}.
     */
    @Test
    public void webpMapsToItsFileExtension() {
        Assertions.assertEquals("webp", MimeTypeUtil.getFileExtension(MimeType.IMAGE_WEBP));
    }
}
