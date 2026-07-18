package com.sismics.docs.core.util.pdf;

import com.sismics.docs.core.dao.UserDao;
import com.sismics.docs.core.model.context.AppContext;
import com.sismics.docs.core.model.jpa.File;
import com.sismics.docs.core.model.jpa.User;
import com.sismics.docs.core.service.FileService;
import com.sismics.docs.core.util.DirectoryUtil;
import com.sismics.docs.core.util.EncryptionUtil;
import com.sismics.docs.core.util.FileUtil;
import com.sismics.util.EnvironmentUtil;
import com.sismics.util.mime.MimeType;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;

/**
 * Applies a v1 page-operation manifest to a PDF file, saving the result as a new version on the
 * stale-base-safe file-version contract. It decrypts the stored source with the creator's key, rewrites the
 * page tree with PDFBox ({@link PdfPageOperationUtil}), reloads and validates the output, then hands it to
 * {@link FileUtil#createFile} whose affected-row compare-and-swap performs the concurrency-safe version flip.
 *
 * <p>All expensive work runs under {@link PdfPageOperationLimiter} (single-writer per file, bounded global
 * slots). The five resource ceilings are enforced here — max source bytes and max pages and the PDFBox
 * scratch bound reject before generation, and the wall-clock budget aborts cooperatively — every one
 * configurable via the standard property/env mechanism. No merge, split, page duplication, or new DB
 * migration.</p>
 */
public final class PdfPageOperationService {
    private static final Logger log = LoggerFactory.getLogger(PdfPageOperationService.class);

    private static final String MAX_PAGES_PROPERTY = "docs.pdf_page_ops_max_pages";
    private static final String MAX_PAGES_ENV = "DOCS_PDF_PAGE_OPS_MAX_PAGES";
    private static final int DEFAULT_MAX_PAGES = 500;

    private static final String MAX_SOURCE_BYTES_PROPERTY = "docs.pdf_page_ops_max_source_bytes";
    private static final String MAX_SOURCE_BYTES_ENV = "DOCS_PDF_PAGE_OPS_MAX_SOURCE_BYTES";
    private static final long DEFAULT_MAX_SOURCE_BYTES = 100L * 1024 * 1024;

    private static final String MAX_SCRATCH_BYTES_PROPERTY = "docs.pdf_page_ops_max_scratch_bytes";
    private static final String MAX_SCRATCH_BYTES_ENV = "DOCS_PDF_PAGE_OPS_MAX_SCRATCH_BYTES";
    private static final long DEFAULT_MAX_SCRATCH_BYTES = 1024L * 1024 * 1024;

    private static final String MAX_CONCURRENT_PROPERTY = "docs.pdf_page_ops_max_concurrent";
    private static final String MAX_CONCURRENT_ENV = "DOCS_PDF_PAGE_OPS_MAX_CONCURRENT";
    private static final int DEFAULT_MAX_CONCURRENT = 2;

    private static final String TIMEOUT_SECONDS_PROPERTY = "docs.pdf_page_ops_timeout_seconds";
    private static final String TIMEOUT_SECONDS_ENV = "DOCS_PDF_PAGE_OPS_TIMEOUT_SECONDS";
    private static final int DEFAULT_TIMEOUT_SECONDS = 120;

    /**
     * Small in-memory working set; anything larger spills to the bounded scratch file.
     */
    private static final long MAIN_MEMORY_BYTES = 1_000_000L;

    /**
     * The global concurrency ceiling is fixed for the process lifetime, so the limiter is sized once.
     */
    private static final PdfPageOperationLimiter LIMITER = new PdfPageOperationLimiter(resolveMaxConcurrent());

    private PdfPageOperationService() {
        // Static entry point
    }

    /**
     * Apply the manifest and create a new version of {@code file}.
     *
     * @param file The source file (already loaded and access-checked by the caller)
     * @param manifestJson The raw v1 manifest JSON
     * @param actingUserId The authenticated user creating the new version (charged the storage, owns the key)
     * @return The new file version's ID
     * @throws PdfPageOperationException for a client-attributable failure (non-PDF, over-ceiling, signed,
     *         encrypted, invalid manifest, or a saturated concurrency ceiling)
     * @throws Exception propagating the version-contract exceptions from {@link FileUtil#createFile}
     *         (a stale base or an unknown/cross-document previous version)
     */
    public static String applyPageOperations(File file, String manifestJson, String actingUserId)
            throws Exception {
        // Cheap structural checks before acquiring any concurrency slot.
        if (!MimeType.APPLICATION_PDF.equals(file.getMimeType())) {
            throw new PdfPageOperationException("NotPdf",
                    "Page operations are only supported on PDF files");
        }
        // The page ceiling bounds the OUTPUT page count (the ratified contract): a large source reduced
        // within the ceiling is legal, a manifest producing too many pages is rejected before generation.
        PdfPageManifest manifest = PdfPageManifest.parse(manifestJson, resolveMaxPages());
        if (manifest.getBaseVersion() != file.getVersion()) {
            throw new PdfPageOperationException("BaseVersionMismatch",
                    "The manifest base version does not match the current file version");
        }
        long maxSourceBytes = resolveMaxSourceBytes();
        // Early-out optimization only: a known, oversized size is rejected up front. This is NOT the
        // enforcement — a legacy row with an unknown/misreported size is still bounded by the byte-limited
        // decrypt copy below, so an unbounded decrypt+parse is impossible regardless of the recorded size.
        Long size = file.getSize();
        if (size != null && !File.UNKNOWN_SIZE.equals(size) && size > maxSourceBytes) {
            throw new PdfPageOperationException("SourceTooLarge",
                    "The source PDF exceeds the maximum of " + (maxSourceBytes / (1024 * 1024)) + " MB");
        }

        return LIMITER.runExclusive(file.getId(), () -> apply(file, manifest, actingUserId));
    }

    /**
     * The decrypt → transform → validate → createFile pipeline, run while holding the concurrency slots.
     */
    private static String apply(File file, PdfPageManifest manifest, String actingUserId) throws Exception {
        long deadlineNanoTime = System.nanoTime() + (long) resolveTimeoutSeconds() * 1_000_000_000L;
        BooleanSupplier deadlineExceeded = () -> System.nanoTime() >= deadlineNanoTime;
        long maxSourceBytes = resolveMaxSourceBytes();

        // Bound in-memory usage and cap scratch to the configured ceiling, pointing PDFBox scratch at the
        // private owner-only temp directory rather than the world-listable OS temp root.
        MemoryUsageSetting memUsageSetting = MemoryUsageSetting
                .setupMixed(MAIN_MEMORY_BYTES, resolveMaxScratchBytes())
                .setTempDir(FileService.getTemporaryDirectory().toFile());

        User owner = new UserDao().getById(file.getUserId());
        String privateKey = owner.getPrivateKey();
        Path storedFile = DirectoryUtil.getStorageDirectory().resolve(file.getId());

        Path decrypted = null;
        Path output = null;
        boolean ownershipTransferred = false;
        try {
            // Decrypt with the creator's key (bytes are encrypted under the version owner's key), enforcing
            // the source byte ceiling ON THE STREAM so an unknown/misreported recorded size cannot bypass it.
            decrypted = AppContext.getInstance().getFileService().createTemporaryFile();
            try (InputStream raw = Files.newInputStream(storedFile);
                 InputStream in = privateKey == null ? raw : EncryptionUtil.decryptInputStream(raw, privateKey)) {
                PdfPageOperationUtil.copyBounded(in, decrypted, maxSourceBytes);
            }

            output = AppContext.getInstance().getFileService().createTemporaryFile();
            PdfPageOperationUtil.transform(decrypted, output, manifest, memUsageSetting, deadlineExceeded);

            // Save as a new version on the Phase-0 contract: previousFileId is the operated file itself, so
            // the affected-row compare-and-swap rejects a stale base (409) and inherits the rotation.
            long outputSize = Files.size(output);
            String newFileId = FileUtil.createFile(file.getName(), file.getId(), output, outputSize,
                    null, actingUserId, file.getDocumentId());
            ownershipTransferred = true;
            return newFileId;
        } finally {
            // The decrypted plaintext temp is ours on every path (createFile never takes it); always delete.
            if (decrypted != null) {
                deleteQuietly(decrypted);
            }
            // The output temp's ownership passes to the async processing pipeline only once createFile has
            // accepted it. On any earlier failure (transform, quota, stale-base CAS) it is still ours.
            if (!ownershipTransferred && output != null) {
                deleteQuietly(output);
            }
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Unable to delete a page-operation temporary file: " + path, e);
        }
    }

    static int resolveMaxPages() {
        return EnvironmentUtil.getIntConfig(MAX_PAGES_PROPERTY, MAX_PAGES_ENV, DEFAULT_MAX_PAGES, 1);
    }

    static long resolveMaxSourceBytes() {
        return EnvironmentUtil.getLongConfig(MAX_SOURCE_BYTES_PROPERTY, MAX_SOURCE_BYTES_ENV,
                DEFAULT_MAX_SOURCE_BYTES);
    }

    static long resolveMaxScratchBytes() {
        return EnvironmentUtil.getLongConfig(MAX_SCRATCH_BYTES_PROPERTY, MAX_SCRATCH_BYTES_ENV,
                DEFAULT_MAX_SCRATCH_BYTES);
    }

    static int resolveMaxConcurrent() {
        return EnvironmentUtil.getIntConfig(MAX_CONCURRENT_PROPERTY, MAX_CONCURRENT_ENV,
                DEFAULT_MAX_CONCURRENT, 1);
    }

    static int resolveTimeoutSeconds() {
        return EnvironmentUtil.getIntConfig(TIMEOUT_SECONDS_PROPERTY, TIMEOUT_SECONDS_ENV,
                DEFAULT_TIMEOUT_SECONDS, 1);
    }
}
