package com.sismics.docs.core.util;

import com.google.common.io.ByteStreams;
import com.sismics.docs.core.dao.FileDao;
import com.sismics.docs.core.dao.UserDao;
import com.sismics.docs.core.model.jpa.Document;
import com.sismics.docs.core.model.jpa.File;
import com.sismics.docs.core.model.jpa.User;
import com.sismics.util.JsonUtil;
import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObjectBuilder;
import org.apache.commons.lang3.StringUtils;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Date;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds the ZIP archive produced by the full-account export endpoint.
 *
 * <p>Given a caller and their document list, this writes one decrypted copy of every file
 * into a per-document folder plus a {@code manifest.json} describing every document and file.
 * Files are decrypted with their own creator's key. A file that cannot be read or decrypted
 * is skipped and recorded as {@code exported: false} in the manifest rather than aborting the
 * whole archive. Both the document-title path segment and the file name are sanitised so a
 * crafted title or file name cannot escape the archive layout (ZIP-slip) on the recipient's
 * machine.</p>
 *
 * <p>This class does no authentication, concurrency-capping, or auditing — those remain the
 * caller's responsibility. It only serialises the archive to the given stream, which lets it
 * be unit-tested without an HTTP request.</p>
 */
public final class ExportUtil {
    private ExportUtil() {
        // Utility class
    }

    /**
     * Write the export ZIP for the given documents to {@code outputStream}. The stream is
     * closed on completion.
     *
     * @param userId Acting user ID (files are looked up scoped to this user)
     * @param username Caller username, recorded in the manifest
     * @param documentList Documents to export
     * @param outputStream Destination stream (closed on completion)
     * @throws Exception On an unrecoverable I/O error writing the archive
     */
    public static void writeExportZip(String userId, String username, List<Document> documentList,
                                      OutputStream outputStream) throws Exception {
        FileDao fileDao = new FileDao();
        UserDao userDao = new UserDao();

        JsonArrayBuilder documentsJson = Json.createArrayBuilder();
        try (ZipOutputStream zipOutputStream = new ZipOutputStream(outputStream)) {
            int docIndex = 0;
            for (Document document : documentList) {
                String folder = docIndex + "-" + sanitizePathSegment(document.getTitle());
                JsonArrayBuilder filesJson = Json.createArrayBuilder();

                List<File> fileList = fileDao.getByDocumentId(userId, document.getId());
                int fileIndex = 0;
                for (File file : fileList) {
                    // Sanitize both segments so a crafted document title or file name cannot
                    // escape the archive layout (ZIP-slip) on the recipient's machine.
                    String entryName = folder + "/" + fileIndex + "-"
                            + sanitizeFileName(file.getFullName(Integer.toString(fileIndex)));
                    java.nio.file.Path storedFile = DirectoryUtil.getStorageDirectory().resolve(file.getId());
                    // Files are encrypted with their creator's key.
                    User fileUser = userDao.getById(file.getUserId());
                    // A single missing/undecryptable file must not corrupt the whole export:
                    // skip it and record the failure in the manifest instead of aborting.
                    boolean exported = true;
                    try (InputStream fileInputStream = Files.newInputStream(storedFile);
                         InputStream decryptedStream = EncryptionUtil.decryptInputStream(fileInputStream, fileUser.getPrivateKey())) {
                        zipOutputStream.putNextEntry(new ZipEntry(entryName));
                        ByteStreams.copy(decryptedStream, zipOutputStream);
                        zipOutputStream.closeEntry();
                    } catch (Exception e) {
                        exported = false;
                    }
                    JsonObjectBuilder fileJson = Json.createObjectBuilder()
                            .add("id", file.getId())
                            .add("name", JsonUtil.nullable(file.getName()))
                            .add("mimetype", JsonUtil.nullable(file.getMimeType()))
                            .add("size", JsonUtil.nullable(file.getSize()))
                            .add("exported", exported)
                            .add("path", JsonUtil.nullable(exported ? entryName : null));
                    filesJson.add(fileJson);
                    fileIndex++;
                }

                documentsJson.add(Json.createObjectBuilder()
                        .add("id", document.getId())
                        .add("title", JsonUtil.nullable(document.getTitle()))
                        .add("description", JsonUtil.nullable(document.getDescription()))
                        .add("subject", JsonUtil.nullable(document.getSubject()))
                        .add("identifier", JsonUtil.nullable(document.getIdentifier()))
                        .add("publisher", JsonUtil.nullable(document.getPublisher()))
                        .add("format", JsonUtil.nullable(document.getFormat()))
                        .add("source", JsonUtil.nullable(document.getSource()))
                        .add("type", JsonUtil.nullable(document.getType()))
                        .add("coverage", JsonUtil.nullable(document.getCoverage()))
                        .add("rights", JsonUtil.nullable(document.getRights()))
                        .add("language", JsonUtil.nullable(document.getLanguage()))
                        .add("create_date", document.getCreateDate().getTime())
                        .add("update_date", document.getUpdateDate() != null
                                ? document.getUpdateDate().getTime() : document.getCreateDate().getTime())
                        .add("files", filesJson));
                docIndex++;
            }

            // Manifest is written last, after every document has been enumerated.
            String manifest = Json.createObjectBuilder()
                    .add("generator", "Teedy")
                    .add("username", username)
                    .add("export_date", new Date().getTime())
                    .add("document_count", documentList.size())
                    .add("documents", documentsJson)
                    .build().toString();
            zipOutputStream.putNextEntry(new ZipEntry("manifest.json"));
            zipOutputStream.write(manifest.getBytes(StandardCharsets.UTF_8));
            zipOutputStream.closeEntry();
        }
        outputStream.close();
    }

    /**
     * Sanitize a string for use as a single ZIP path segment.
     *
     * @param value Raw value
     * @return Safe path segment
     */
    public static String sanitizePathSegment(String value) {
        if (StringUtils.isBlank(value)) {
            return "document";
        }
        return value.replaceAll("\\W+", "_");
    }

    /**
     * Reduce a file name to a safe single archive entry segment: strip any path
     * separators and traversal, keeping the base name (and its extension).
     *
     * @param value Raw file name
     * @return Safe file name
     */
    public static String sanitizeFileName(String value) {
        if (StringUtils.isBlank(value)) {
            return "file";
        }
        String name = value.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        name = name.replaceAll("\\p{Cntrl}", "").trim();
        if (name.isEmpty() || ".".equals(name) || "..".equals(name)) {
            return "file";
        }
        return name;
    }
}
