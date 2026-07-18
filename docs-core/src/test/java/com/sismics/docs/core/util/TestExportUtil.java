package com.sismics.docs.core.util;

import com.google.common.io.ByteStreams;
import com.sismics.docs.BaseTransactionalTest;
import com.sismics.docs.core.dao.DocumentDao;
import com.sismics.docs.core.dao.FileDao;
import com.sismics.docs.core.model.jpa.Document;
import com.sismics.docs.core.model.jpa.File;
import com.sismics.docs.core.model.jpa.User;
import com.sismics.util.context.ThreadLocalContext;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    /**
     * Zip-slip guard for the file-download archive: {@code FileResource.sendZippedFiles} names each
     * entry {@code index + "-" + ExportUtil.sanitizeFileName(fullName)}. This builds a real ZIP whose
     * source names are a {@code ../}-traversal attack and a second name that sanitizes to the SAME
     * basename, then extracts it and asserts every entry stays under the target directory and the two
     * colliding basenames do not overwrite each other (the unique index prefix de-collides them).
     */
    @Test
    public void zipEntryNamesAreTraversalSafeAndDeCollide() throws Exception {
        String[] sourceNames = {"../../../../etc/passwd", "passwd"};

        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(buffer)) {
            int index = 0;
            for (String sourceName : sourceNames) {
                // Exactly the entry-name expression used by FileResource.sendZippedFiles.
                ZipEntry entry = new ZipEntry(index + "-" + ExportUtil.sanitizeFileName(sourceName));
                zos.putNextEntry(entry);
                zos.write(("content-" + index).getBytes());
                zos.closeEntry();
                index++;
            }
        }

        java.nio.file.Path target = java.nio.file.Files.createTempDirectory("zipslip-target-").toRealPath();
        try {
            java.util.List<String> extracted = new java.util.ArrayList<>();
            try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(buffer.toByteArray()))) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    java.nio.file.Path resolved = target.resolve(entry.getName()).normalize();
                    Assertions.assertTrue(resolved.startsWith(target),
                            "extracted entry must stay under the target dir: " + entry.getName());
                    Assertions.assertFalse(entry.getName().contains("/"),
                            "entry name must carry no path separator: " + entry.getName());
                    extracted.add(entry.getName());
                    zis.closeEntry();
                }
            }
            Assertions.assertEquals(2, extracted.size(), "both entries present");
            Assertions.assertEquals(2, new java.util.HashSet<>(extracted).size(),
                    "the two same-basename files must not collide (unique index prefix)");
        } finally {
            java.nio.file.Files.deleteIfExists(target);
        }
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
        new FileDao().update(file);
        // A freshly created single file IS the latest version. The generic update no longer writes that
        // CAS-managed column (chain identity changes only through the CAS paths), so establish this fixture's
        // latest state directly — the same approach seedStorageCurrent uses for the quota column.
        ThreadLocalContext.get().getEntityManager()
                .createNativeQuery("update T_FILE set FIL_LATESTVERSION_B = true where FIL_ID_C = :id")
                .setParameter("id", file.getId())
                .executeUpdate();

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

    /**
     * Error-path contract for the writer: a file that fails to decrypt mid-copy must be OMITTED from the
     * archive entirely (never left as a half-written entry) and recorded {@code exported:false} with a
     * {@code null} path, so the manifest and the archive always agree. Builds a document with one good file
     * and one whose stored blob is unreadable — its storage path is replaced with a directory, so
     * {@code Files.newInputStream} opens but the first read throws ("Is a directory") DURING the copy, exactly
     * when a naive writer would already hold an open ZIP entry. Then asserts the archive holds only the good
     * entry and the manifest's exported/path fields match the archive exactly.
     */
    @Test
    public void testWriteExportZipOmitsUnreadableFileCleanly() throws Exception {
        User owner = createUser("exportFailOwner");

        DocumentDao documentDao = new DocumentDao();
        Document document = new Document();
        document.setUserId(owner.getId());
        document.setLanguage("eng");
        document.setTitle("Fail Doc");
        document.setCreateDate(new Date());
        String documentId = documentDao.create(document, owner.getId());

        FileDao fileDao = new FileDao();

        // A good, fully-decryptable file.
        File goodFile = createFile(owner, FILE_JPG_SIZE);
        goodFile.setName("good.jpg");
        goodFile.setDocumentId(documentId);
        fileDao.update(goodFile);
        setLatestVersion(goodFile.getId());

        // A broken file: created normally, then its stored blob is REPLACED with a directory so the
        // decrypt read fails ("Is a directory") mid-copy — the deterministic failure the writer must survive.
        File badFile = createFile(owner, FILE_JPG_SIZE);
        badFile.setName("broken.bin");
        badFile.setDocumentId(documentId);
        fileDao.update(badFile);
        setLatestVersion(badFile.getId());
        Path badStored = DirectoryUtil.getStorageDirectory().resolve(badFile.getId());
        Files.deleteIfExists(badStored);
        Files.createDirectory(badStored);

        List<Document> documentList = documentDao.findByUserId(owner.getId());
        Assertions.assertEquals(1, documentList.size());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ExportUtil.writeExportZip(owner.getId(), owner.getUsername(), documentList, out);

        // Collect every archive entry.
        Map<String, byte[]> entries = new HashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                entries.put(entry.getName(), ByteStreams.toByteArray(zis));
                zis.closeEntry();
            }
        }
        Set<String> archivePaths = new HashSet<>(entries.keySet());
        archivePaths.remove("manifest.json");

        // The broken file is OMITTED entirely: only the good file's entry survives (no partial entry).
        Assertions.assertEquals(1, archivePaths.size(), "only the good file is archived: " + archivePaths);

        JsonObject manifest;
        try (JsonReader reader = Json.createReader(new ByteArrayInputStream(entries.get("manifest.json")))) {
            manifest = reader.readObject();
        }
        JsonArray files = manifest.getJsonArray("documents").getJsonObject(0).getJsonArray("files");
        Assertions.assertEquals(2, files.size(), "both files are described in the manifest");

        JsonObject goodJson = null;
        JsonObject badJson = null;
        Set<String> manifestExportedPaths = new HashSet<>();
        for (int i = 0; i < files.size(); i++) {
            JsonObject f = files.getJsonObject(i);
            if ("good.jpg".equals(f.getString("name", null))) {
                goodJson = f;
            } else if ("broken.bin".equals(f.getString("name", null))) {
                badJson = f;
            }
            if (f.getBoolean("exported")) {
                manifestExportedPaths.add(f.getString("path"));
            } else {
                // A non-exported file must carry NO path — it points at nothing in the archive.
                Assertions.assertTrue(f.isNull("path"), "a non-exported file must have a null path");
            }
        }
        Assertions.assertNotNull(goodJson, "good file present in manifest");
        Assertions.assertNotNull(badJson, "bad file present in manifest");

        // The broken file: not exported, null path, and absent from the archive.
        Assertions.assertFalse(badJson.getBoolean("exported"), "broken file marked not exported");
        Assertions.assertTrue(badJson.isNull("path"), "broken file has a null path");

        // The good file: exported with a path that is a REAL archive entry.
        Assertions.assertTrue(goodJson.getBoolean("exported"), "good file marked exported");
        String goodPath = goodJson.getString("path");
        Assertions.assertTrue(archivePaths.contains(goodPath),
                "good file's manifest path is a real archive entry: " + goodPath);

        // Manifest and archive agree exactly: exported:true paths == archive entries, no more, no less.
        Assertions.assertEquals(manifestExportedPaths, archivePaths,
                "the manifest's exported paths must equal the archive's entries");

        // The good file's bytes are the intact decrypted original, not a truncated/garbage copy.
        byte[] originalBytes;
        try (InputStream in = getSystemResourceAsStream(FILE_JPG)) {
            originalBytes = ByteStreams.toByteArray(in);
        }
        Assertions.assertArrayEquals(originalBytes, entries.get(goodPath), "good file decrypted intact");
    }

    /**
     * Truncation contract: AES/CTR is a stream cipher, so a stored ciphertext that is truncated decrypts to
     * a SHORTER plaintext without throwing. Such a file must be OMITTED from the archive (its length no longer
     * matches its recorded decrypted size, so archiving it would contradict the manifest) rather than written
     * as a short entry marked exported:true. Builds a document with one good file and one whose stored blob is
     * truncated to 100 bytes (its recorded size stays the full length), then asserts the archive holds only
     * the good entry and the manifest's exported/path fields match the archive exactly.
     */
    @Test
    public void testWriteExportZipOmitsTruncatedFileCleanly() throws Exception {
        User owner = createUser("exportTruncOwner");

        DocumentDao documentDao = new DocumentDao();
        Document document = new Document();
        document.setUserId(owner.getId());
        document.setLanguage("eng");
        document.setTitle("Trunc Doc");
        document.setCreateDate(new Date());
        String documentId = documentDao.create(document, owner.getId());

        FileDao fileDao = new FileDao();

        // A good, whole file: its decrypted length equals its recorded size.
        File goodFile = createFile(owner, FILE_JPG_SIZE);
        goodFile.setName("good.jpg");
        goodFile.setDocumentId(documentId);
        fileDao.update(goodFile);
        setLatestVersion(goodFile.getId());

        // A truncated file: recorded size is the full FILE_JPG_SIZE, but its stored ciphertext is cut to 100
        // bytes, so decryption yields only 100 bytes of plaintext — a length that disagrees with the record.
        File truncFile = createFile(owner, FILE_JPG_SIZE);
        truncFile.setName("truncated.jpg");
        truncFile.setDocumentId(documentId);
        fileDao.update(truncFile);
        setLatestVersion(truncFile.getId());
        Path truncStored = DirectoryUtil.getStorageDirectory().resolve(truncFile.getId());
        Files.write(truncStored, Arrays.copyOf(Files.readAllBytes(truncStored), 100));

        List<Document> documentList = documentDao.findByUserId(owner.getId());
        Assertions.assertEquals(1, documentList.size());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ExportUtil.writeExportZip(owner.getId(), owner.getUsername(), documentList, out);

        Map<String, byte[]> entries = new HashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                entries.put(entry.getName(), ByteStreams.toByteArray(zis));
                zis.closeEntry();
            }
        }
        Set<String> archivePaths = new HashSet<>(entries.keySet());
        archivePaths.remove("manifest.json");

        // The truncated file is OMITTED: only the good file's entry survives.
        Assertions.assertEquals(1, archivePaths.size(), "only the good file is archived: " + archivePaths);

        JsonObject manifest;
        try (JsonReader reader = Json.createReader(new ByteArrayInputStream(entries.get("manifest.json")))) {
            manifest = reader.readObject();
        }
        JsonArray files = manifest.getJsonArray("documents").getJsonObject(0).getJsonArray("files");
        Assertions.assertEquals(2, files.size(), "both files are described in the manifest");

        JsonObject goodJson = null;
        JsonObject truncJson = null;
        Set<String> manifestExportedPaths = new HashSet<>();
        for (int i = 0; i < files.size(); i++) {
            JsonObject f = files.getJsonObject(i);
            if ("good.jpg".equals(f.getString("name", null))) {
                goodJson = f;
            } else if ("truncated.jpg".equals(f.getString("name", null))) {
                truncJson = f;
            }
            if (f.getBoolean("exported")) {
                manifestExportedPaths.add(f.getString("path"));
            } else {
                Assertions.assertTrue(f.isNull("path"), "a non-exported file must have a null path");
            }
        }
        Assertions.assertNotNull(goodJson, "good file present in manifest");
        Assertions.assertNotNull(truncJson, "truncated file present in manifest");

        // The truncated file: not exported, null path, and absent from the archive.
        Assertions.assertFalse(truncJson.getBoolean("exported"), "truncated file marked not exported");
        Assertions.assertTrue(truncJson.isNull("path"), "truncated file has a null path");

        // The good file: exported with a path that is a REAL archive entry.
        Assertions.assertTrue(goodJson.getBoolean("exported"), "good file marked exported");
        Assertions.assertTrue(archivePaths.contains(goodJson.getString("path")),
                "good file's manifest path is a real archive entry: " + goodJson.getString("path"));

        // Manifest and archive agree exactly: exported:true paths == archive entries.
        Assertions.assertEquals(manifestExportedPaths, archivePaths,
                "the manifest's exported paths must equal the archive's entries");
    }

    /**
     * Mark a file row as the current latest version directly (a single-column native UPDATE), matching how
     * {@code testWriteExportZip} seeds its fixture — the CAS-managed latest flag is not written by the generic
     * update, and {@code getByDocumentId} only returns latest rows.
     */
    private void setLatestVersion(String fileId) {
        ThreadLocalContext.get().getEntityManager()
                .createNativeQuery("update T_FILE set FIL_LATESTVERSION_B = true where FIL_ID_C = :id")
                .setParameter("id", fileId)
                .executeUpdate();
    }
}
