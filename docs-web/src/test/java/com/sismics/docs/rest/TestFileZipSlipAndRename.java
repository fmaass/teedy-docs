package com.sismics.docs.rest;

import com.google.common.io.ByteStreams;
import com.google.common.io.Resources;
import com.sismics.util.context.ThreadLocalContext;
import com.sismics.util.filter.TokenBasedSecurityFilter;
import com.sismics.util.jpa.EMF;
import jakarta.json.JsonObject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityTransaction;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Form;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Endpoint-level regressions for the download zip-slip guard and the file-rename input guard. These drive
 * the REAL REST wiring (GET /file/zip, POST /file/{id}) rather than a reconstruction of the sanitizer, so
 * they fail if the resource stops sanitizing/validating.
 */
public class TestFileZipSlipAndRename extends BaseJerseyTest {

    /**
     * A file whose stored name is a {@code ../}-style traversal (legacy/imported data) must be exported
     * with an archive entry name that stays under the extraction directory — no traversal, no separator.
     * The name is injected directly into T_FILE to simulate an existing malicious record (the rename
     * endpoint now rejects such names on the way in).
     */
    @Test
    public void zipDownloadNeutralizesTraversalFileName() throws Exception {
        clientUtil.createUser("zipslip_user");
        String token = clientUtil.login("zipslip_user");
        String documentId = clientUtil.createDocument(token);
        String fileId = clientUtil.addFileToDocument(FILE_PIA_00452_JPG, token, documentId);

        // Simulate a legacy/malicious stored file name (bypassing the rename guard).
        setFileNameDirectly(fileId, "../../../../etc/passwd");

        Response response = target().path("/file/zip")
                .queryParam("id", documentId)
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .get();
        Assertions.assertEquals(Status.OK, Status.fromStatusCode(response.getStatus()));

        Path target = java.nio.file.Files.createTempDirectory("zipslip-endpoint-").toRealPath();
        try (InputStream is = (InputStream) response.getEntity();
             ZipInputStream zis = new ZipInputStream(is)) {
            ZipEntry entry;
            int count = 0;
            while ((entry = zis.getNextEntry()) != null) {
                Path resolved = target.resolve(entry.getName()).normalize();
                Assertions.assertTrue(resolved.startsWith(target),
                        "ZIP entry must stay under the target dir: " + entry.getName());
                Assertions.assertFalse(entry.getName().contains("/"),
                        "ZIP entry name must contain no path separator: " + entry.getName());
                Assertions.assertFalse(entry.getName().contains(".."),
                        "ZIP entry name must contain no traversal: " + entry.getName());
                ByteStreams.toByteArray(zis);
                zis.closeEntry();
                count++;
            }
            Assertions.assertEquals(1, count, "exactly one file entry expected");
        } finally {
            java.nio.file.Files.deleteIfExists(target);
        }
    }

    /**
     * POST /file/{id} with a name containing a path separator is REJECTED (ValidationError), so a rename
     * can never store a name that would later escape an extraction directory. Drives the endpoint wiring,
     * not the validator in isolation.
     */
    @Test
    public void renameRejectsPathSeparators() throws Exception {
        clientUtil.createUser("rename_guard_user");
        String token = clientUtil.login("rename_guard_user");
        String documentId = clientUtil.createDocument(token);
        String fileId = clientUtil.addFileToDocument(FILE_PIA_00452_JPG, token, documentId);

        for (String badName : new String[] {"a/b.txt", "..\\..\\evil.txt", "../escape.txt"}) {
            Response response = target().path("/file/" + fileId).request()
                    .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                    .post(Entity.form(new Form().param("name", badName)));
            Assertions.assertEquals(Status.BAD_REQUEST, Status.fromStatusCode(response.getStatus()),
                    "rename must reject a name with a separator: " + badName);
            JsonObject json = response.readEntity(JsonObject.class);
            Assertions.assertEquals("ValidationError", json.getString("type"));
        }

        // A clean rename still succeeds.
        JsonObject ok = target().path("/file/" + fileId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .post(Entity.form(new Form().param("name", "clean-name.txt")), JsonObject.class);
        Assertions.assertEquals("ok", ok.getString("status"));
    }

    /**
     * A multi-select POST zip spanning two documents whose files share a name: the archive carries each
     * file's real name, the collision is disambiguated with a " (N)" suffix before the extension, and the
     * suffix assignment follows the SUBMITTED id order (the first submitted file keeps the verbatim name).
     * Verified by content, so it proves both the ordering and that no entry name is ever duplicated.
     */
    @Test
    public void zipMultiSelectCrossDocumentCollisionFollowsSubmittedOrder() throws Exception {
        clientUtil.createUser("zip_dupnames");
        String token = clientUtil.login("zip_dupnames");

        String document1Id = clientUtil.createDocument(token);
        String file1Id = clientUtil.addFileToDocument(FILE_PIA_00452_JPG, token, document1Id);
        renameFile(file1Id, token, "report.pdf");

        String document2Id = clientUtil.createDocument(token);
        String file2Id = clientUtil.addFileToDocument(FILE_EINSTEIN_ROOSEVELT_LETTER_PNG, token, document2Id);
        renameFile(file2Id, token, "report.pdf");

        byte[] piaBytes = Resources.toByteArray(Resources.getResource(FILE_PIA_00452_JPG));
        byte[] einsteinBytes = Resources.toByteArray(Resources.getResource(FILE_EINSTEIN_ROOSEVELT_LETTER_PNG));

        // Submit file1 then file2: the first submitted file keeps the verbatim name; the collision is
        // pushed onto the second and suffixed before the extension.
        LinkedHashMap<String, byte[]> forward = readZipEntries(postZip(token, file1Id, file2Id));
        Assertions.assertEquals(List.of("report.pdf", "report (1).pdf"), new ArrayList<>(forward.keySet()));
        Assertions.assertArrayEquals(piaBytes, forward.get("report.pdf"));
        Assertions.assertArrayEquals(einsteinBytes, forward.get("report (1).pdf"));

        // Reverse the submission: the suffix assignment tracks the submitted order, so file2 now owns the
        // verbatim name and file1 is the suffixed one.
        LinkedHashMap<String, byte[]> reversed = readZipEntries(postZip(token, file2Id, file1Id));
        Assertions.assertEquals(List.of("report.pdf", "report (1).pdf"), new ArrayList<>(reversed.keySet()));
        Assertions.assertArrayEquals(einsteinBytes, reversed.get("report.pdf"));
        Assertions.assertArrayEquals(piaBytes, reversed.get("report (1).pdf"));
    }

    /**
     * The multi-select POST zip emits entries in the SUBMITTED id order, not the database's arbitrary
     * "in :ids" order: two distinct-named files produce entries whose order flips when the submission order
     * flips.
     */
    @Test
    public void zipMultiSelectEmitsEntriesInSubmittedOrder() throws Exception {
        clientUtil.createUser("zip_order");
        String token = clientUtil.login("zip_order");

        String document1Id = clientUtil.createDocument(token);
        String file1Id = clientUtil.addFileToDocument(FILE_PIA_00452_JPG, token, document1Id);
        renameFile(file1Id, token, "zeta.pdf");

        String document2Id = clientUtil.createDocument(token);
        String file2Id = clientUtil.addFileToDocument(FILE_EINSTEIN_ROOSEVELT_LETTER_PNG, token, document2Id);
        renameFile(file2Id, token, "alpha.pdf");

        Assertions.assertEquals(List.of("zeta.pdf", "alpha.pdf"),
                new ArrayList<>(readZipEntries(postZip(token, file1Id, file2Id)).keySet()));
        Assertions.assertEquals(List.of("alpha.pdf", "zeta.pdf"),
                new ArrayList<>(readZipEntries(postZip(token, file2Id, file1Id)).keySet()));
    }

    /**
     * POSTs the zip-by-files endpoint with the given file ids in order and asserts an OK response.
     */
    private Response postZip(String token, String... fileIds) {
        Form form = new Form();
        for (String fileId : fileIds) {
            form.param("files", fileId);
        }
        Response response = target().path("/file/zip").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .post(Entity.form(form));
        Assertions.assertEquals(Status.OK, Status.fromStatusCode(response.getStatus()));
        return response;
    }

    /**
     * Renames a file through the real REST endpoint.
     */
    private void renameFile(String fileId, String token, String newName) {
        JsonObject ok = target().path("/file/" + fileId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .post(Entity.form(new Form().param("name", newName)), JsonObject.class);
        Assertions.assertEquals("ok", ok.getString("status"));
    }

    /**
     * Drains a ZIP response into an insertion-ordered map of entry name to its decrypted bytes.
     */
    private LinkedHashMap<String, byte[]> readZipEntries(Response response) throws Exception {
        LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
        try (InputStream is = (InputStream) response.getEntity();
             ZipInputStream zis = new ZipInputStream(is)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                entries.put(entry.getName(), ByteStreams.toByteArray(zis));
                zis.closeEntry();
            }
        }
        return entries;
    }

    /**
     * Sets a file's stored name directly in the database (bypassing REST validation) to seed a legacy /
     * malicious record. Runs on the test thread with its own transaction against the shared in-memory DB.
     */
    private void setFileNameDirectly(String fileId, String name) {
        EntityManager em = EMF.get().createEntityManager();
        ThreadLocalContext.get().setEntityManager(em);
        EntityTransaction tx = em.getTransaction();
        tx.begin();
        try {
            em.createNativeQuery("update T_FILE set FIL_NAME_C = :name where FIL_ID_C = :id")
                    .setParameter("name", name)
                    .setParameter("id", fileId)
                    .executeUpdate();
            tx.commit();
        } catch (RuntimeException e) {
            if (tx.isActive()) {
                tx.rollback();
            }
            throw e;
        } finally {
            if (em.isOpen()) {
                em.close();
            }
            ThreadLocalContext.cleanup();
        }
    }
}
