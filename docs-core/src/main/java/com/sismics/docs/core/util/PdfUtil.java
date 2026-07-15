package com.sismics.docs.core.util;

import com.google.common.base.Strings;
import com.google.common.io.ByteStreams;
import com.google.common.io.Closer;
import com.google.common.io.Resources;
import com.lowagie.text.FontFactory;
import com.sismics.docs.core.constant.Constants;
import com.sismics.docs.core.dao.dto.DocumentDto;
import com.sismics.docs.core.model.jpa.File;
import com.sismics.docs.core.util.format.FormatHandler;
import com.sismics.docs.core.util.format.FormatHandlerUtil;
import com.sismics.docs.core.util.pdf.PdfPage;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.io.ScratchFile;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.font.DocsPDType1Font;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * PDF utilities.
 * 
 * @author bgamard
 */
public class PdfUtil {
    /**
     * Logger.
     */
    private static final Logger log = LoggerFactory.getLogger(PdfUtil.class);

    /**
     * Convert a document and its files to a merged PDF file.
     * 
     * @param documentDto Document DTO
     * @param fileList List of files
     * @param fitImageToPage Fit images to the page
     * @param metadata Add a page with metadata
     * @param margin Margins in millimeters
     * @param outputStream Output stream to write to, will be closed
     */
    public static void convertToPdf(DocumentDto documentDto, List<File> fileList,
            boolean fitImageToPage, boolean metadata, int margin, OutputStream outputStream) throws Exception {
        // Setup PDFBox
        Closer closer = Closer.create();
        MemoryUsageSetting memUsageSettings = MemoryUsageSetting.setupMixed(1000000); // 1MB max memory usage
        memUsageSettings.setTempDir(new java.io.File(System.getProperty("java.io.tmpdir"))); // To OS temp

        // Plaintext temp files decrypted below; they must outlive doc.save()/closer.close()
        // because format handlers may lazily read them via the deferred closer, so they are
        // deleted only after the PDF has been fully written.
        List<Path> tempFiles = new java.util.ArrayList<>();

        // Create a blank PDF
        try (PDDocument doc = new PDDocument(() -> new ScratchFile(memUsageSettings))) {
            // Add metadata
            if (metadata) {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PdfPage pdfPage = new PdfPage(doc, page, margin * Constants.MM_PER_INCH, DocsPDType1Font.HELVETICA, 12)) {
                    SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
                    pdfPage.addText(documentDto.getTitle(), true, DocsPDType1Font.HELVETICA_BOLD, 16)
                        .newLine()
                        .addText("Created by " + documentDto.getCreator()
                            + " on " + dateFormat.format(new Date(documentDto.getCreateTimestamp())), true)
                        .newLine()
                        .addText(DescriptionTextUtil.toPlainText(documentDto.getDescription(), 4000))
                        .newLine();
                    if (!Strings.isNullOrEmpty(documentDto.getSubject())) {
                        pdfPage.addText("Subject: " + documentDto.getSubject());
                    }
                    if (!Strings.isNullOrEmpty(documentDto.getIdentifier())) {
                        pdfPage.addText("Identifier: " + documentDto.getIdentifier());
                    }
                    if (!Strings.isNullOrEmpty(documentDto.getPublisher())) {
                        pdfPage.addText("Publisher: " + documentDto.getPublisher());
                    }
                    if (!Strings.isNullOrEmpty(documentDto.getFormat())) {
                        pdfPage.addText("Format: " + documentDto.getFormat());
                    }
                    if (!Strings.isNullOrEmpty(documentDto.getSource())) {
                        pdfPage.addText("Source: " + documentDto.getSource());
                    }
                    if (!Strings.isNullOrEmpty(documentDto.getType())) {
                        pdfPage.addText("Type: " + documentDto.getType());
                    }
                    if (!Strings.isNullOrEmpty(documentDto.getCoverage())) {
                        pdfPage.addText("Coverage: " + documentDto.getCoverage());
                    }
                    if (!Strings.isNullOrEmpty(documentDto.getRights())) {
                        pdfPage.addText("Rights: " + documentDto.getRights());
                    }
                    pdfPage.addText("Language: " + documentDto.getLanguage())
                        .newLine()
                        .addText("Files in this document : " + fileList.size(), false, DocsPDType1Font.HELVETICA_BOLD, 12);
                }
            }
            
            // Add files
            for (File file : fileList) {
                Path storedFile = DirectoryUtil.getStorageDirectory().resolve(file.getId());

                // Decrypt the file to a temporary file
                Path unencryptedFile = EncryptionUtil.decryptFile(storedFile, file.getPrivateKey());
                if (unencryptedFile != null && !unencryptedFile.equals(storedFile)) {
                    tempFiles.add(unencryptedFile);
                }
                FormatHandler formatHandler = FormatHandlerUtil.find(file.getMimeType());
                if (formatHandler != null) {
                    formatHandler.appendToPdf(unencryptedFile, doc, fitImageToPage, margin, memUsageSettings, closer);
                }
            }

            doc.save(outputStream); // Write to the output stream
            closer.close(); // Close all remaining opened PDF
        } finally {
            // Delete the plaintext temp files now that the PDF is fully written
            for (Path tempFile : tempFiles) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException e) {
                    log.warn("Unable to delete temporary file: " + tempFile, e);
                }
            }
        }
    }

    /**
     * The bundled mono font extracted to disk, cached across AppContext starts. FontFactory stores the
     * PATH and loads the font lazily on first use, so the extracted file must stay readable; it is
     * extracted at most once per JVM (never per AppContext start) and never deleted by us — but a tmp
     * reaper (tmpwatch/systemd-tmpfiles) may remove it mid-life, so registerFonts re-extracts when the
     * cached file no longer exists rather than trusting the cache blindly.
     */
    private static Path monoFontFile;

    /**
     * Register fonts.
     */
    public static synchronized void registerFonts() {
        try {
            if (monoFontFile == null || !Files.exists(monoFontFile)) {
                // Extract into a process-private temp directory (never a predictable fixed /tmp name),
                // because registerFonts runs on every AppContext start and FontFactory reads the
                // registered path lazily — a fresh extraction per start would leak one temp per start,
                // while a stale cache pointing at a reaped file would break font loads until restart.
                Path fontDir = Files.createTempDirectory("sismics_docs_font_mono");
                Path file = fontDir.resolve("LiberationMono-Regular.ttf");
                URL url = Resources.getResource("fonts/LiberationMono-Regular.ttf");
                try (InputStream is = url.openStream(); OutputStream os = Files.newOutputStream(file)) {
                    ByteStreams.copy(is, os);
                }
                monoFontFile = file;
            }
            FontFactory.register(monoFontFile.toAbsolutePath().toString(), "LiberationMono-Regular");
            FontFactory.registerDirectories();
        } catch (IOException | RuntimeException e) {
            // FontFactory wraps I/O problems in its unchecked ExceptionConverter; font registration is
            // non-fatal, so never let it abort an AppContext start.
            log.error("Error loading font", e);
        }
    }
}
