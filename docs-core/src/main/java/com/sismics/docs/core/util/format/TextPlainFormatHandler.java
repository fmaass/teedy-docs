package com.sismics.docs.core.util.format;

import com.google.common.io.Closer;
import com.lowagie.text.*;
import com.lowagie.text.pdf.PdfWriter;
import com.sismics.util.mime.MimeType;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.pdmodel.PDDocument;

import java.awt.image.BufferedImage;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Text plain format handler.
 *
 * @author bgamard
 */
public class TextPlainFormatHandler implements FormatHandler {
    @Override
    public boolean accept(String mimeType) {
        return mimeType.equals(MimeType.TEXT_CSV) || mimeType.equals(MimeType.TEXT_PLAIN);
    }

    @Override
    public BufferedImage generateThumbnail(Path file) throws Exception {
        Path tempFile = generatePdf(file);

        // Use the PDF format handler
        return new PdfFormatHandler().generateThumbnail(tempFile);
    }

    /**
     * Generate a PDF from this text file.
     *
     * @param file Text file
     * @return PDF file
     * @throws Exception e
     */
    private Path generatePdf(Path file) throws Exception {
        // On a conversion failure (e.g. reading the source file throws after the temp is created) the temp is
        // deleted before the exception propagates, so a plaintext-derived PDF is never stranded on disk.
        return FormatConversionUtil.convertToTemporaryPdf(tempFile -> {
            Document output = new Document(PageSize.A4, 40, 40, 40, 40);
            try (OutputStream pdfOutputStream = Files.newOutputStream(tempFile)) {
                PdfWriter.getInstance(output, pdfOutputStream);

                output.open();
                String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                Font font = FontFactory.getFont("LiberationMono-Regular");
                Paragraph paragraph = new Paragraph(content, font);
                paragraph.setAlignment(Element.ALIGN_LEFT);
                output.add(paragraph);
                output.close();
            }
        });
    }

    @Override
    public String extractContent(String language, Path file) throws Exception {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    @Override
    public void appendToPdf(Path file, PDDocument doc, boolean fitImageToPage, int margin, MemoryUsageSetting memUsageSettings, Closer closer) throws Exception {
        new PdfFormatHandler().appendToPdf(generatePdf(file), doc, fitImageToPage, margin, memUsageSettings, closer);
    }
}
