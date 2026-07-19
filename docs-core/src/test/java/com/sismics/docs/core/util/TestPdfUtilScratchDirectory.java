package com.sismics.docs.core.util;

import com.google.common.collect.Lists;
import com.sismics.BaseTest;
import com.sismics.docs.core.dao.dto.DocumentDto;
import com.sismics.docs.core.model.jpa.File;
import com.sismics.docs.core.service.FileService;
import com.sismics.util.mime.MimeType;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Regression guard for the PDFBox scratch location of {@link PdfUtil#convertToPdf}.
 *
 * <p>Decrypted document content flows through PDFBox's scratch file when the working set exceeds the 1&nbsp;MB
 * in-memory ceiling {@code convertToPdf} configures. That scratch must land in the application's private,
 * owner-only temp directory ({@link FileService#getTemporaryDirectory()}) — the same location the rc.7
 * page-operations code uses — never in the world-listable shared OS temp root, where any other local user
 * could enumerate and read the spilled plaintext.</p>
 *
 * <p>The test forces a real disk spill (a multi-megabyte, high-entropy JPEG whose DCTDecode image stream far
 * exceeds the 1&nbsp;MB ceiling) and uses a {@link WatchService} to observe which directory receives the
 * {@code PDFBox*.tmp} scratch file. The watch reliably records the creation even though PDFBox deletes the
 * scratch when the document closes. Against the pre-fix code, which pointed the scratch at the OS temp root,
 * the scratch appears directly under it and this test fails.</p>
 */
public class TestPdfUtilScratchDirectory extends BaseTest {

    @Test
    public void scratchSpillsIntoPrivateTempDirectoryNotOsRoot() throws Exception {
        // The shared OS temp root convertToPdf would spill into pre-fix, and the private owner-only directory
        // it must use instead. The private directory lives strictly inside the OS temp root, so a
        // non-recursive watch on the OS temp root sees only files written DIRECTLY there (the leak), never the
        // grandchild scratch created inside the private directory.
        Path osTempRoot = Paths.get(System.getProperty("java.io.tmpdir")).toRealPath();
        Path privateTempDir = FileService.getTemporaryDirectory().toRealPath();
        Assertions.assertTrue(privateTempDir.startsWith(osTempRoot) && !privateTempDir.equals(osTempRoot),
                "the private temp dir must sit strictly inside the OS temp root for this test to distinguish them: "
                        + privateTempDir + " vs " + osTempRoot);

        // A large, high-entropy JPEG: its DCTDecode image stream (stored verbatim by PDFBox) far exceeds the
        // 1 MB in-memory ceiling convertToPdf configures, forcing a scratch spill to disk.
        byte[] jpeg = buildLargeIncompressibleJpeg();
        Assertions.assertTrue(jpeg.length > 2_000_000,
                "the synthetic JPEG must exceed the 1 MB scratch ceiling to force a disk spill, was " + jpeg.length);

        String fileId = "scratch_probe_" + System.nanoTime();
        Path stored = DirectoryUtil.getStorageDirectory().resolve(fileId);
        Files.write(stored, jpeg);

        DocumentDto documentDto = new DocumentDto();
        documentDto.setTitle("scratch probe");
        documentDto.setLanguage("eng");
        documentDto.setCreator("test");
        documentDto.setCreateTimestamp(System.currentTimeMillis());

        // A null private key makes convertToPdf read the stored bytes directly (the unit-test decrypt path),
        // so this exercises the scratch configuration without needing an AppContext or database.
        File file = new File();
        file.setId(fileId);
        file.setMimeType(MimeType.IMAGE_JPEG);

        WatchService watchService = osTempRoot.getFileSystem().newWatchService();
        try {
            WatchKey osRootKey = osTempRoot.register(watchService, StandardWatchEventKinds.ENTRY_CREATE);
            WatchKey privateKey = privateTempDir.register(watchService, StandardWatchEventKinds.ENTRY_CREATE);

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            PdfUtil.convertToPdf(documentDto, Lists.newArrayList(file), true, false, 10, outputStream);
            Assertions.assertTrue(outputStream.size() > 0, "convertToPdf must produce PDF bytes");

            List<String> scratchInOsRoot = new ArrayList<>();
            List<String> scratchInPrivate = new ArrayList<>();
            long deadline = System.currentTimeMillis() + 5000;
            WatchKey key;
            while (System.currentTimeMillis() < deadline
                    && (key = watchService.poll(1, TimeUnit.SECONDS)) != null) {
                for (WatchEvent<?> event : key.pollEvents()) {
                    if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                        continue;
                    }
                    Path name = (Path) event.context();
                    if (name != null && name.getFileName().toString().startsWith("PDFBox")) {
                        if (key == osRootKey) {
                            scratchInOsRoot.add(name.toString());
                        } else if (key == privateKey) {
                            scratchInPrivate.add(name.toString());
                        }
                    }
                }
                key.reset();
            }

            // The security contract: convertToPdf's PDFBox scratch must never be written into the shared OS
            // temp root. Pre-fix this list is non-empty and the test fails (base-red).
            Assertions.assertTrue(scratchInOsRoot.isEmpty(),
                    "convertToPdf leaked a PDFBox scratch file into the shared OS temp root " + osTempRoot
                            + ": " + scratchInOsRoot);
            // Guard against a vacuous pass: prove a spill actually occurred and landed in the private directory.
            Assertions.assertFalse(scratchInPrivate.isEmpty(),
                    "expected convertToPdf's PDFBox scratch to spill into the private temp directory "
                            + privateTempDir + " (no spill observed — the test would otherwise pass vacuously)");
        } finally {
            watchService.close();
            Files.deleteIfExists(stored);
        }
    }

    /**
     * Build a multi-megabyte JPEG from random-noise pixels at maximum quality, so its encoded (and therefore
     * its stored DCTDecode) size reliably exceeds the 1 MB scratch ceiling.
     */
    private static byte[] buildLargeIncompressibleJpeg() throws Exception {
        int side = 1600;
        BufferedImage image = new BufferedImage(side, side, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(42);
        for (int y = 0; y < side; y++) {
            for (int x = 0; x < side; x++) {
                image.setRGB(x, y, random.nextInt(0xFFFFFF));
            }
        }
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(ios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(1.0f);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }
}
