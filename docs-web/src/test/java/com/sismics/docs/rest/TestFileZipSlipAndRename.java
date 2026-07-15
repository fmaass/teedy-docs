package com.sismics.docs.rest;

import com.google.common.io.ByteStreams;
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
