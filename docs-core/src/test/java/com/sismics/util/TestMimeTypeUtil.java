package com.sismics.util;

import com.sismics.BaseTest;
import com.sismics.util.mime.MimeType;
import com.sismics.util.mime.MimeTypeUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

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
}
