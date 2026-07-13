package com.sismics.docs.rest;

import com.google.common.io.ByteStreams;
import com.sismics.docs.core.util.DirectoryUtil;
import com.sismics.util.filter.TokenBasedSecurityFilter;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Form;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * Integration test for the persisted, non-destructive file-rotation endpoint (POST /file/:id/rotation).
 *
 * <p>The rectangular fixture (Einstein-Roosevelt-letter.png, 1500x881 — landscape) makes the
 * portrait/landscape flip after a 90° rotation observable on the served web raster, which the
 * no-crop enum-overload guarantees (a cropping rotation would keep the landscape box).
 */
public class TestFileRotationResource extends BaseJerseyTest {

    /**
     * Read the file's persisted rotation from GET /file/list.
     */
    private int listedRotation(String documentId, String fileId, String token) {
        JsonObject json = target().path("/file/list")
                .queryParam("id", documentId)
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .get(JsonObject.class);
        JsonArray files = json.getJsonArray("files");
        for (int i = 0; i < files.size(); i++) {
            JsonObject file = files.getJsonObject(i);
            if (file.getString("id").equals(fileId)) {
                return file.getInt("rotation");
            }
        }
        Assertions.fail("file not found in list: " + fileId);
        return -1;
    }

    /**
     * True once every file of the document has cleared its processing flag (raster generation done).
     */
    private boolean processingDone(String documentId, String token) {
        JsonObject json = target().path("/file/list")
                .queryParam("id", documentId)
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .get(JsonObject.class);
        JsonArray files = json.getJsonArray("files");
        if (files.isEmpty()) {
            return false;
        }
        for (int i = 0; i < files.size(); i++) {
            if (files.getJsonObject(i).getBoolean("processing")) {
                return false;
            }
        }
        return true;
    }

    /**
     * Block until the document's async (re)processing has finished, or fail after a bounded wait.
     * In the unit-test harness the event bus is synchronous, but polling the real processing flag
     * makes the assertion robust to that changing and proves the reprocess actually ran to completion
     * before we assert the rotation survived it.
     */
    private void waitProcessingDone(String documentId, String token) throws Exception {
        for (int i = 0; i < 200; i++) {
            if (processingDone(documentId, token)) {
                return;
            }
            Thread.sleep(50);
        }
        Assertions.fail("processing did not complete within the wait budget for document " + documentId);
    }

    /**
     * Fetch the served (decrypted) web raster bytes.
     */
    private byte[] fetchWebRasterBytes(String fileId, String token) throws Exception {
        Response response = target().path("/file/" + fileId + "/data")
                .queryParam("size", "web")
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .get();
        Assertions.assertEquals(Status.OK, Status.fromStatusCode(response.getStatus()));
        try (InputStream is = (InputStream) response.getEntity()) {
            return ByteStreams.toByteArray(is);
        }
    }

    /**
     * Decode the served (decrypted) web raster into a BufferedImage so its orientation can be checked.
     */
    private BufferedImage fetchWebRaster(String fileId, String token) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(fetchWebRasterBytes(fileId, token)));
    }

    private enum Orientation {
        LANDSCAPE, PORTRAIT;

        boolean matches(BufferedImage image) {
            return this == LANDSCAPE ? image.getWidth() > image.getHeight()
                    : image.getHeight() > image.getWidth();
        }
    }

    /**
     * Poll the served {@code _web} raster until it decodes to the expected orientation, then return
     * that raster. The {@code _web} derivative is produced by ASYNC raster (re)generation — the
     * initial upload processing on a background thread, and (defensively) any regeneration — so
     * immediately after an upload/rotate the served bytes can transiently be the not-yet-regenerated
     * raster, a decode-in-progress swap (a 503 from {@code /file/{id}/data}, retried here as
     * not-ready), or the generic placeholder. Waiting on the OBSERVABLE ready state — a real raster of
     * the expected orientation — proves the same orientation the callers assert, without a fixed sleep
     * that races the async work. The orientation check IS the assertion; nothing is weakened.
     *
     * @param fileId      File ID
     * @param token       Auth token
     * @param orientation The orientation the regenerated raster must show
     * @return The served web raster once it shows {@code orientation}
     */
    private BufferedImage awaitWebRaster(String fileId, String token, Orientation orientation) throws Exception {
        BufferedImage[] latest = {null};
        awaitCondition("web raster never reached expected orientation " + orientation
                + " for file " + fileId, () -> {
            BufferedImage image = fetchWebRaster(fileId, token);
            latest[0] = image;
            return image != null && orientation.matches(image);
        });
        return latest[0];
    }

    /**
     * Fetch the served ORIGINAL (unrotated, size=null) decrypted file bytes.
     */
    private byte[] fetchOriginalBytes(String fileId, String token) throws Exception {
        Response response = target().path("/file/" + fileId + "/data")
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .get();
        Assertions.assertEquals(Status.OK, Status.fromStatusCode(response.getStatus()));
        try (InputStream is = (InputStream) response.getEntity()) {
            return ByteStreams.toByteArray(is);
        }
    }

    /**
     * Non-destructive guarantee: the ORIGINAL uploaded file is NEVER touched by raster generation —
     * not by upload processing, and not by a rotate. Reads {@code GET /file/{id}/data} (size=null, the
     * original bytes) after upload and after a rotate and asserts it is byte-for-byte the uploaded
     * content each time. This is the regression guard for the class of bug where raster regeneration
     * (temp naming, a stray move, or a finally-cleanup) empties or corrupts the stored original.
     */
    @Test
    public void originalFileIsUntouchedByUploadAndRotate() throws Exception {
        clientUtil.createUser("rotate_orig");
        String token = clientUtil.login("rotate_orig");
        String documentId = clientUtil.createDocument(token);

        byte[] uploaded = com.google.common.io.Resources.toByteArray(
                com.google.common.io.Resources.getResource(FILE_EINSTEIN_ROOSEVELT_LETTER_PNG));
        Assertions.assertTrue(uploaded.length > 0, "fixture must be non-empty");

        String fileId = clientUtil.addFileToDocument(FILE_EINSTEIN_ROOSEVELT_LETTER_PNG, token, documentId);
        waitProcessingDone(documentId, token);

        // After upload processing, the served ORIGINAL is the full, byte-for-byte uploaded content.
        byte[] afterUpload = fetchOriginalBytes(fileId, token);
        Assertions.assertTrue(afterUpload.length > 0,
                "the original file must not be empty after upload processing");
        Assertions.assertArrayEquals(uploaded, afterUpload,
                "the original file must be byte-for-byte the uploaded content after upload processing");

        // Rotate 90°, then re-read the ORIGINAL — it must STILL be the untouched uploaded content.
        target().path("/file/" + fileId + "/rotation").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .post(Entity.form(new Form().param("rotation", "90")), JsonObject.class);
        byte[] afterRotate = fetchOriginalBytes(fileId, token);
        Assertions.assertArrayEquals(uploaded, afterRotate,
                "the original file must be byte-for-byte the uploaded content after a rotate (non-destructive)");
    }

    @Test
    public void rotationPersistsAndRegeneratesRaster() throws Exception {
        clientUtil.createUser("rotate_owner");
        String token = clientUtil.login("rotate_owner");
        String documentId = clientUtil.createDocument(token);
        String fileId = clientUtil.addFileToDocument(FILE_EINSTEIN_ROOSEVELT_LETTER_PNG, token, documentId);

        // Await the async upload processing before rotating: the rotate endpoint rejects with a 500
        // ProcessingError while the file is still processing (FileResource.isProcessingFile guard), so
        // rotating before initial processing completes is a race under load.
        waitProcessingDone(documentId, token);

        // Baseline: no rotation, the web raster is landscape (source is 1500x881). Await the async
        // upload raster generation to land the real derivative before asserting its orientation.
        Assertions.assertEquals(0, listedRotation(documentId, fileId, token));
        BufferedImage upright = awaitWebRaster(fileId, token, Orientation.LANDSCAPE);
        Assertions.assertTrue(upright.getWidth() > upright.getHeight(),
                "baseline web raster must be landscape (source is 1500x881)");

        // Rotate 90°.
        JsonObject json = target().path("/file/" + fileId + "/rotation").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .post(Entity.form(new Form().param("rotation", "90")), JsonObject.class);
        Assertions.assertEquals("ok", json.getString("status"));
        Assertions.assertEquals(90, json.getInt("rotation"));

        // Persisted: read back via the authoritative /file/list.
        Assertions.assertEquals(90, listedRotation(documentId, fileId, token),
                "rotation must persist and be read back from /file/list");

        // Regenerated raster is now PORTRAIT — the full content was preserved with swapped axes
        // (a cropping rotation would have kept the landscape box).
        BufferedImage rotated = awaitWebRaster(fileId, token, Orientation.PORTRAIT);
        Assertions.assertTrue(rotated.getHeight() > rotated.getWidth(),
                "after 90° the web raster must be portrait (no crop, axes swapped)");
    }

    @Test
    public void rotateBackToZeroRegeneratesUpright() throws Exception {
        clientUtil.createUser("rotate_reset");
        String token = clientUtil.login("rotate_reset");
        String documentId = clientUtil.createDocument(token);
        String fileId = clientUtil.addFileToDocument(FILE_EINSTEIN_ROOSEVELT_LETTER_PNG, token, documentId);

        // Await initial async processing before rotating (the rotate endpoint 500s while processing).
        waitProcessingDone(documentId, token);

        // Rotate 90° then back to 0.
        target().path("/file/" + fileId + "/rotation").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .post(Entity.form(new Form().param("rotation", "90")), JsonObject.class);
        target().path("/file/" + fileId + "/rotation").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .post(Entity.form(new Form().param("rotation", "0")), JsonObject.class);

        Assertions.assertEquals(0, listedRotation(documentId, fileId, token));
        BufferedImage upright = awaitWebRaster(fileId, token, Orientation.LANDSCAPE);
        Assertions.assertTrue(upright.getWidth() > upright.getHeight(),
                "rotate→0 must regenerate the upright (landscape) raster");
    }

    @Test
    public void reRotatingSameValueIsIdempotent() throws Exception {
        clientUtil.createUser("rotate_idem");
        String token = clientUtil.login("rotate_idem");
        String documentId = clientUtil.createDocument(token);
        String fileId = clientUtil.addFileToDocument(FILE_EINSTEIN_ROOSEVELT_LETTER_PNG, token, documentId);

        target().path("/file/" + fileId + "/rotation").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .post(Entity.form(new Form().param("rotation", "90")), JsonObject.class);
        BufferedImage first = awaitWebRaster(fileId, token, Orientation.PORTRAIT);

        // Apply the SAME absolute value again: rotation must not compound (still portrait, same dims).
        target().path("/file/" + fileId + "/rotation").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .post(Entity.form(new Form().param("rotation", "90")), JsonObject.class);
        Assertions.assertEquals(90, listedRotation(documentId, fileId, token));
        BufferedImage second = awaitWebRaster(fileId, token, Orientation.PORTRAIT);

        Assertions.assertEquals(first.getWidth(), second.getWidth(), "re-rotating the same value must not compound");
        Assertions.assertEquals(first.getHeight(), second.getHeight(), "re-rotating the same value must not compound");
        Assertions.assertTrue(second.getHeight() > second.getWidth(), "still portrait, not double-rotated");
    }

    @Test
    public void reprocessPreservesRotation() throws Exception {
        clientUtil.createUser("rotate_reproc");
        String token = clientUtil.login("rotate_reproc");
        String documentId = clientUtil.createDocument(token);
        String fileId = clientUtil.addFileToDocument(FILE_EINSTEIN_ROOSEVELT_LETTER_PNG, token, documentId);

        // Ensure the initial upload processing finished before rotating.
        waitProcessingDone(documentId, token);

        // Rotate 90°, then trigger a manual reprocess — the current rotation must be re-baked, not
        // reverted to upright (R11). The reprocess reads the rotation FRESH from the DB inside the
        // per-file lock, so it re-bakes 90 rather than a stale 0.
        target().path("/file/" + fileId + "/rotation").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .post(Entity.form(new Form().param("rotation", "90")), JsonObject.class);
        target().path("/file/" + fileId + "/process").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .post(Entity.form(new Form()), JsonObject.class);

        // WAIT for the reprocess to actually complete (raster regenerated) before asserting — so the
        // test cannot pass by inspecting the pre-reprocess raster while a reverting reprocess is still
        // in flight.
        waitProcessingDone(documentId, token);

        Assertions.assertEquals(90, listedRotation(documentId, fileId, token),
                "reprocess must not revert the stored rotation");
        BufferedImage rotated = awaitWebRaster(fileId, token, Orientation.PORTRAIT);
        Assertions.assertTrue(rotated.getHeight() > rotated.getWidth(),
                "reprocess must re-bake the rotation (portrait), not revert to upright");
    }

    @Test
    public void readOnlyPrincipalIsForbidden() throws Exception {
        clientUtil.createUser("rotate_owner2");
        clientUtil.createUser("rotate_reader");
        String ownerToken = clientUtil.login("rotate_owner2");
        String readerToken = clientUtil.login("rotate_reader");
        String documentId = clientUtil.createDocument(ownerToken);
        String fileId = clientUtil.addFileToDocument(FILE_EINSTEIN_ROOSEVELT_LETTER_PNG, ownerToken, documentId);

        // Grant the reader READ-only on the parent document.
        target().path("/acl").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, ownerToken)
                .put(Entity.form(new Form()
                        .param("source", documentId)
                        .param("perm", "READ")
                        .param("target", "rotate_reader")
                        .param("type", "USER")), JsonObject.class);

        // The READ-only principal must be forbidden from rotating (WRITE required).
        Response response = target().path("/file/" + fileId + "/rotation").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, readerToken)
                .post(Entity.form(new Form().param("rotation", "90")));
        Assertions.assertEquals(Status.FORBIDDEN, Status.fromStatusCode(response.getStatus()),
                "a READ-only principal must get 403 from the rotate endpoint");

        // The rotation must be unchanged (the forbidden call did not persist).
        Assertions.assertEquals(0, listedRotation(documentId, fileId, ownerToken));
    }

    @Test
    public void nonMultipleOf90IsRejected() throws Exception {
        clientUtil.createUser("rotate_validate");
        String token = clientUtil.login("rotate_validate");
        String documentId = clientUtil.createDocument(token);
        String fileId = clientUtil.addFileToDocument(FILE_EINSTEIN_ROOSEVELT_LETTER_PNG, token, documentId);

        Response response = target().path("/file/" + fileId + "/rotation").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .post(Entity.form(new Form().param("rotation", "45")));
        Assertions.assertEquals(Status.BAD_REQUEST, Status.fromStatusCode(response.getStatus()),
                "a non-multiple-of-90 rotation must be rejected");
        Assertions.assertEquals(0, listedRotation(documentId, fileId, token));
    }

    @Test
    public void noTemporaryRasterFilesLeftAfterRotate() throws Exception {
        clientUtil.createUser("rotate_temp");
        String token = clientUtil.login("rotate_temp");
        String documentId = clientUtil.createDocument(token);
        String fileId = clientUtil.addFileToDocument(FILE_EINSTEIN_ROOSEVELT_LETTER_PNG, token, documentId);

        target().path("/file/" + fileId + "/rotation").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .post(Entity.form(new Form().param("rotation", "90")), JsonObject.class);

        // No leftover *.tmp raster temp for this file in the storage directory.
        Path storageDir = DirectoryUtil.getStorageDirectory();
        try (Stream<Path> entries = Files.list(storageDir)) {
            long tmpCount = entries
                    .filter(p -> p.getFileName().toString().startsWith(fileId))
                    .filter(p -> p.getFileName().toString().endsWith(".tmp"))
                    .count();
            Assertions.assertEquals(0, tmpCount, "no temp raster files must remain after a rotate");
        }
    }

    /**
     * End-to-end POST-GENERATION rollback guarantee against a REAL DB row: after a successful rotation
     * to 90, a second rotation whose SWAP fails — generation SUCCEEDS (temps written) but the {@code
     * _web} move fails because its destination has been replaced with a non-empty directory — must
     * leave the DB rotation at 90 (DB-last), the served {@code _web} raster byte-for-byte the 90° one,
     * and no {@code <uuid>} temp behind. Removing the original (decrypt failure) only exercises a
     * PRE-generation failure and so cannot prove DB-first ordering or the swap/temp guarantees; this
     * test injects the failure AT the swap, through the real endpoint + real transaction + real H2 row.
     */
    @Test
    public void rasterSwapFailureLeavesDbAndRasterIntact() throws Exception {
        clientUtil.createUser("rotate_fail");
        String token = clientUtil.login("rotate_fail");
        String documentId = clientUtil.createDocument(token);
        String fileId = clientUtil.addFileToDocument(FILE_EINSTEIN_ROOSEVELT_LETTER_PNG, token, documentId);
        waitProcessingDone(documentId, token);

        // Rotate to 90 successfully; snapshot the authoritative DB rotation and the served raster bytes.
        target().path("/file/" + fileId + "/rotation").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .post(Entity.form(new Form().param("rotation", "90")), JsonObject.class);
        Assertions.assertEquals(90, listedRotation(documentId, fileId, token));
        byte[] rasterAt90 = fetchWebRasterBytes(fileId, token);

        // Replace the encrypted _web derivative on disk with a NON-EMPTY DIRECTORY so the next
        // rotation's generation SUCCEEDS (temps written) but the _web move FAILS at the swap. The real
        // encrypted _web file is stashed so it can be restored for the byte-for-byte read-back.
        Path storageDir = DirectoryUtil.getStorageDirectory();
        Path webFile = storageDir.resolve(fileId + "_web");
        Path stashedWeb = storageDir.resolve(fileId + "_web.stashed");
        Files.move(webFile, stashedWeb);
        Files.createDirectories(webFile);
        Files.write(webFile.resolve("blocker"), new byte[]{1});
        try {
            // Attempt to rotate to 180 — generation succeeds, the _web swap fails, endpoint returns 500.
            Response failed = target().path("/file/" + fileId + "/rotation").request()
                    .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                    .post(Entity.form(new Form().param("rotation", "180")));
            Assertions.assertEquals(Status.INTERNAL_SERVER_ERROR, Status.fromStatusCode(failed.getStatus()),
                    "a rotation whose raster swap fails must return a server error");

            // (b) The DB rotation did NOT advance to 180 — it is still the committed 90 (DB-last: the
            // persist/commit runs only AFTER the swap succeeds).
            Assertions.assertEquals(90, listedRotation(documentId, fileId, token),
                    "DB rotation must NOT advance when the raster swap fails");

            // (c) No <uuid> temp raster leaked from the failed attempt.
            try (Stream<Path> entries = Files.list(storageDir)) {
                long tmpCount = entries
                        .filter(p -> p.getFileName().toString().startsWith(fileId))
                        .filter(p -> p.getFileName().toString().endsWith(".tmp"))
                        .count();
                Assertions.assertEquals(0, tmpCount, "no temp raster must leak from a failed swap");
            }
        } finally {
            // Restore the real encrypted _web derivative (remove the blocker directory first).
            Files.deleteIfExists(webFile.resolve("blocker"));
            Files.deleteIfExists(webFile);
            if (Files.exists(stashedWeb) && !Files.exists(webFile)) {
                Files.move(stashedWeb, webFile);
            }
        }

        // (a) The served _web raster is byte-for-byte the pre-failure 90° raster — the failed swap did
        // not install a new/partial derivative.
        byte[] rasterAfter = fetchWebRasterBytes(fileId, token);
        Assertions.assertArrayEquals(rasterAt90, rasterAfter,
                "the served _web raster must be byte-for-byte the pre-failure 90° raster");
    }
}
