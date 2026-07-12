package com.sismics.docs.core.util;

import com.google.common.util.concurrent.Striped;
import com.sismics.docs.core.dao.FileDao;
import com.sismics.docs.core.model.jpa.File;
import com.sismics.docs.core.util.format.FormatHandler;
import com.sismics.docs.core.util.format.FormatHandlerUtil;
import com.sismics.util.ImageUtil;
import org.imgscalr.Scalr;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.CipherOutputStream;
import java.awt.image.BufferedImage;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.concurrent.locks.Lock;

/**
 * Shared, rotation-aware generation of a file's derived {@code _web} and {@code _thumb} rasters.
 *
 * <p>Every writer that (re)generates these derivatives — the upload/attach processor, the manual
 * reprocess, and the rotate endpoint — routes through {@link #regenerateRasters} so a single code
 * path always bakes the file's currently stored rotation into the rasters. This is what keeps a
 * reprocess or a new upload from silently reverting a previously applied rotation.
 *
 * <p><b>Atomicity / concurrency (single-instance deployment).</b> Teedy is deployed as a single
 * container, so a JVM-level per-file lock is sufficient; multi-instance is not a supported topology.
 * The whole read-rotation / generate / swap / persist sequence runs UNDER the per-file lock so a
 * rotate and a reprocess on the same file cannot interleave: the rotation is read FRESH from inside
 * the lock (a racing rotate cannot install a stale value), the rasters are baked with that value,
 * both rasters are moved into place FIRST, and the DB mutation runs LAST (the DB is the source of
 * truth and must advance only after its rasters are in place). Temp rasters use UNIQUE per-operation
 * names so two concurrent generations never collide on a shared temp path. On any failure before the
 * DB update the prior rasters and the DB row are left untouched and every temp is cleaned up.
 *
 * <p><b>Accepted residual risk.</b> A process crash in the sub-millisecond window between the raster
 * swap and the DB commit could leave a cosmetically-stale thumbnail (the original bytes and OCR
 * content are never touched, so this is never data loss). It self-heals on the next rotate/reprocess.
 * Full crash-recovery machinery is deliberately out of scope for a cosmetic thumbnail rotation.
 */
public class RasterGenerationUtil {
    private static final Logger log = LoggerFactory.getLogger(RasterGenerationUtil.class);

    /**
     * Per-file critical section, keyed on the file id. All writers that touch a file's derivatives
     * or its processing/rotation state acquire the same stripe before doing so, and read a fresh
     * rotation value inside it.
     */
    private static final Striped<Lock> FILE_LOCKS = Striped.lock(64);

    private RasterGenerationUtil() {
        // Utility class
    }

    /**
     * Acquire the per-file critical section for the given file id. The returned lock is already
     * held; the caller MUST release it in a finally block.
     *
     * @param fileId File ID
     * @return The held lock for this file
     */
    public static Lock lockFile(String fileId) {
        Lock lock = FILE_LOCKS.get(fileId);
        lock.lock();
        return lock;
    }

    /**
     * Persist a file's rotation and COMMIT it within the current request transaction, intended to run
     * as the last step inside the per-file lock (as the {@link PersistStep} of a rotate operation).
     * Committing here — rather than letting the request transaction commit later, outside the lock —
     * is what makes the new rotation visible to the next lock holder (a reprocess reading the rotation
     * fresh inside its own lock), closing the stale-read window. This is the single production
     * definition of "apply rotation under the lock and commit", shared by {@code FileResource.rotation}
     * and the concurrency guard test so the test exercises the real path.
     *
     * @param file File whose rotation is being set (already loaded in the current transaction)
     * @param rotation Absolute clockwise rotation to persist ({0,90,180,270})
     */
    public static void commitRotation(File file, int rotation) {
        file.setRotation(rotation);
        new FileDao().update(file);
        TransactionUtil.commit();
    }

    /**
     * Supplies the rotation to bake, read FRESH from the database inside the per-file lock so a
     * concurrent rotate/reprocess cannot capture a stale value.
     */
    @FunctionalInterface
    public interface RotationSupplier {
        int get() throws Exception;
    }

    /**
     * A persistence step run as the LAST action inside the per-file lock, after both temp rasters
     * have been moved into place. The DB is the source of truth and must advance only once its
     * rasters exist.
     */
    @FunctionalInterface
    public interface PersistStep {
        void run() throws Exception;
    }

    /**
     * Regenerate a file's {@code _web} and {@code _thumb} rasters from its decrypted original, baking
     * in the rotation returned by {@code rotationSupplier} (absolute, so the operation is idempotent —
     * re-applying the same value never compounds). The entire sequence runs UNDER the per-file lock:
     * <ol>
     *   <li>read the rotation FRESH via {@code rotationSupplier} (BLOCKING-3: a racing writer cannot
     *       hand us a stale value);</li>
     *   <li>generate both rasters, rotate them (imgscalr {@link Scalr.Rotation} enum), and write them
     *       to UNIQUE per-operation temp files encrypted with the OWNER's key (BLOCKING-1: two
     *       concurrent generations never share a temp path);</li>
     *   <li>atomically move BOTH temp rasters into place;</li>
     *   <li>run {@code persistStep} (the DB update) LAST (BLOCKING-2/R13: DB advances only after its
     *       rasters are in place).</li>
     * </ol>
     * On any failure before the DB update the prior rasters + DB row are left untouched, and every
     * temp file is cleaned up (the caller-owned decrypted original is NOT deleted here). When the
     * format handler produces no image (unsupported type) no raster is written and {@code persistStep}
     * still runs — a rotation value is recorded even for a file with no visual preview.
     *
     * @param fileId File ID
     * @param decryptedOriginal Path to the decrypted original file (caller owns its lifetime)
     * @param mimeType File MIME type (selects the format handler)
     * @param rotationSupplier Reads the absolute clockwise rotation ({0,90,180,270}) fresh inside the lock
     * @param ownerPrivateKey The file OWNER's private key (rasters are always encrypted with it)
     * @param persistStep The DB mutation, run last inside the lock
     * @throws Exception on any failure before the DB update (prior rasters + DB left intact)
     */
    public static void regenerateRasters(String fileId, Path decryptedOriginal, String mimeType,
                                         RotationSupplier rotationSupplier, String ownerPrivateKey,
                                         PersistStep persistStep) throws Exception {
        Path storageDir = DirectoryUtil.getStorageDirectory();
        Path webTarget = storageDir.resolve(fileId + "_web");
        Path thumbTarget = storageDir.resolve(fileId + "_thumb");

        Lock lock = lockFile(fileId);
        Path webTemp = null;
        Path thumbTemp = null;
        try {
            // (1) Read the rotation FRESH inside the lock so a racing rotate/reprocess cannot install
            // rasters that disagree with the DB value.
            int rotation = rotationSupplier.get();

            // (2) Generate + rotate + encrypt both rasters to UNIQUE temp paths.
            FormatHandler formatHandler = FormatHandlerUtil.find(mimeType);
            BufferedImage image = formatHandler == null ? null : formatHandler.generateThumbnail(decryptedOriginal);
            boolean hasRaster = false;
            if (image != null) {
                BufferedImage web = Scalr.resize(image, Scalr.Method.ULTRA_QUALITY, Scalr.Mode.AUTOMATIC, 1280);
                BufferedImage thumbnail = Scalr.resize(image, Scalr.Method.ULTRA_QUALITY, Scalr.Mode.AUTOMATIC, 256);
                image.flush();

                // Bake the rotation. The imgscalr Rotation-ENUM overload swaps width/height at 90/270,
                // so a rectangular image keeps its full content. The project's custom double-angle
                // Scalr.rotate must NOT be used here — it preserves the source canvas box and crops
                // rectangular images at 90/270.
                web = applyRotation(web, rotation);
                thumbnail = applyRotation(thumbnail, rotation);

                String token = UUID.randomUUID().toString();
                webTemp = storageDir.resolve(fileId + "_web." + token + ".tmp");
                thumbTemp = storageDir.resolve(fileId + "_thumb." + token + ".tmp");

                Cipher webCipher = EncryptionUtil.getEncryptionCipher(ownerPrivateKey);
                try (OutputStream outputStream = new CipherOutputStream(Files.newOutputStream(webTemp), webCipher)) {
                    ImageUtil.writeJpeg(web, outputStream);
                }
                Cipher thumbCipher = EncryptionUtil.getEncryptionCipher(ownerPrivateKey);
                try (OutputStream outputStream = new CipherOutputStream(Files.newOutputStream(thumbTemp), thumbCipher)) {
                    ImageUtil.writeJpeg(thumbnail, outputStream);
                }
                hasRaster = true;
            }

            // (3) Move BOTH rasters into place FIRST, then (4) persist LAST. If the second move fails
            // after the first succeeded, the DB is NOT advanced (it is step 4), so the DB keeps its
            // prior value and a subsequent rotate/reprocess regenerates BOTH from the untouched
            // original — the worst case is a transiently mismatched _web/_thumb pair (cosmetic,
            // self-healing per R13). Log loudly so the rare partial move is diagnosable.
            if (hasRaster) {
                move(webTemp, webTarget);
                webTemp = null;
                try {
                    move(thumbTemp, thumbTarget);
                } catch (Exception e) {
                    log.error("Partial raster swap for file {}: _web replaced but _thumb move failed; "
                            + "DB left at prior rotation, self-heals on next rotate/reprocess", fileId, e);
                    throw e;
                }
                thumbTemp = null;
            }

            // (4) DB update is the LAST step — the DB advances only after its rasters are in place.
            persistStep.run();
        } finally {
            lock.unlock();
            deleteQuietly(webTemp);
            deleteQuietly(thumbTemp);
        }
    }

    /**
     * Move a temp raster into its target path, preferring an atomic move and falling back to a
     * replace-existing move where the filesystem does not support atomic moves.
     */
    private static void move(Path source, Path target) throws java.io.IOException {
        try {
            // ATOMIC_MOVE + REPLACE_EXISTING: some filesystems reject an atomic move onto an existing
            // target unless replace is requested; asking for both keeps the atomic path reachable when
            // the derivative already exists. Only a filesystem that cannot do atomic moves at all falls
            // back to the plain replace.
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Apply an absolute clockwise rotation to a base image using the imgscalr {@link Scalr.Rotation}
     * enum overload (which swaps width/height at 90/270 — no crop). A rotation of 0 (or any value
     * that does not normalize to 90/180/270) returns the image unchanged.
     *
     * @param image Source image
     * @param degrees Absolute clockwise rotation
     * @return The rotated image (or the input unchanged for 0)
     */
    static BufferedImage applyRotation(BufferedImage image, int degrees) {
        int normalized = ((degrees % 360) + 360) % 360;
        Scalr.Rotation rotation = switch (normalized) {
            case 90 -> Scalr.Rotation.CW_90;
            case 180 -> Scalr.Rotation.CW_180;
            case 270 -> Scalr.Rotation.CW_270;
            default -> null;
        };
        if (rotation == null) {
            return image;
        }
        BufferedImage rotated = Scalr.rotate(image, rotation);
        image.flush();
        return rotated;
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (Exception e) {
            log.warn("Unable to delete temporary raster: " + path, e);
        }
    }
}
