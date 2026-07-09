package com.sismics.docs.core.util;

import com.google.common.io.ByteStreams;
import com.sismics.docs.BaseTransactionalTest;
import com.sismics.docs.core.dao.DocumentDao;
import com.sismics.docs.core.dao.FileDao;
import com.sismics.docs.core.model.jpa.Document;
import com.sismics.docs.core.model.jpa.File;
import com.sismics.docs.core.model.jpa.User;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Test the export archive builder in isolation (no HTTP layer).
 */
public class TestExportUtil extends BaseTransactionalTest {

    @Test
    public void testSanitizePathSegment() {
        Assertions.assertEquals("document", ExportUtil.sanitizePathSegment(null));
        Assertions.assertEquals("document", ExportUtil.sanitizePathSegment("  "));
        Assertions.assertEquals("My_Doc_2024_", ExportUtil.sanitizePathSegment("My Doc/2024!"));
        Assertions.assertEquals("plain", ExportUtil.sanitizePathSegment("plain"));
    }

    @Test
    public void testSanitizeFileName() {
        Assertions.assertEquals("file", ExportUtil.sanitizeFileName(null));
        Assertions.assertEquals("report.pdf", ExportUtil.sanitizeFileName("/etc/report.pdf"));
        Assertions.assertEquals("report.pdf", ExportUtil.sanitizeFileName("..\\..\\report.pdf"));
        Assertions.assertEquals("file", ExportUtil.sanitizeFileName(".."));
    }

    @Test
    public void testWriteExportZip() throws Exception {
        // Owner with one document carrying one encrypted file.
        User owner = createUser("exportOwner");

        DocumentDao documentDao = new DocumentDao();
        Document document = new Document();
        document.setUserId(owner.getId());
        document.setLanguage("eng");
        document.setTitle("Export Doc");
        document.setCreateDate(new Date());
        String documentId = documentDao.create(document, owner.getId());

        File file = createFile(owner, FILE_JPG_SIZE);
        file.setDocumentId(documentId);
        file.setLatestVersion(true);
        new FileDao().update(file);

        List<Document> documentList = documentDao.findByUserId(owner.getId());
        Assertions.assertEquals(1, documentList.size());

        // Build the archive.
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ExportUtil.writeExportZip(owner.getId(), owner.getUsername(), documentList, out);

        // Read the ZIP back.
        Map<String, byte[]> entries = new HashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                entries.put(entry.getName(), ByteStreams.toByteArray(zis));
                zis.closeEntry();
            }
        }

        // A manifest and exactly one file entry are present.
        Assertions.assertTrue(entries.containsKey("manifest.json"), "manifest present");
        String fileEntryName = entries.keySet().stream()
                .filter(n -> !n.equals("manifest.json"))
                .findFirst().orElseThrow();
        Assertions.assertTrue(fileEntryName.startsWith("0-Export_Doc/"), "file under doc folder: " + fileEntryName);

        // The archived file is the DECRYPTED original.
        byte[] originalBytes;
        try (InputStream in = getSystemResourceAsStream(FILE_JPG)) {
            originalBytes = ByteStreams.toByteArray(in);
        }
        Assertions.assertArrayEquals(originalBytes, entries.get(fileEntryName), "file decrypted into archive");

        // Manifest describes the document and the exported file.
        JsonObject manifest;
        try (JsonReader reader = Json.createReader(new ByteArrayInputStream(entries.get("manifest.json")))) {
            manifest = reader.readObject();
        }
        Assertions.assertEquals("Teedy", manifest.getString("generator"));
        Assertions.assertEquals("exportOwner", manifest.getString("username"));
        Assertions.assertEquals(1, manifest.getInt("document_count"));
        JsonObject doc = manifest.getJsonArray("documents").getJsonObject(0);
        Assertions.assertEquals("Export Doc", doc.getString("title"));
        JsonObject fileJson = doc.getJsonArray("files").getJsonObject(0);
        Assertions.assertTrue(fileJson.getBoolean("exported"), "file marked exported");
        Assertions.assertEquals(fileEntryName, fileJson.getString("path"));
    }
}
