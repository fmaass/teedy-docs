package com.sismics.docs.core.util.pdf;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.io.ScratchFile;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Pure PDFBox page-tree transform for a v1 page-operation manifest: reorder / delete / per-page rotate,
 * with the input source guarded (not signed, not encrypted, parseable) and the output reloaded and
 * validated. No database, encryption, or configuration — the orchestration layer supplies the decrypted
 * input, the output path, the memory/scratch settings, and the deadline check. The output page ceiling is
 * enforced upstream (at manifest parse); source size is bounded by the byte-limited decrypt copy and the
 * scratch/deadline ceilings.
 */
public final class PdfPageOperationUtil {

    private PdfPageOperationUtil() {
        // Utility class
    }

    /**
     * Copy {@code in} to {@code output}, failing with a typed {@code SourceTooLarge} once more than
     * {@code maxBytes} have been read. Enforcing the byte ceiling on the stream — not on a recorded size —
     * bounds the decrypt+parse of a legacy row with an unknown/misreported size, so the stored size is only
     * an early-out optimization, never the enforcement.
     *
     * @param in Source stream (not closed here — the caller owns it)
     * @param output Destination path
     * @param maxBytes Maximum bytes permitted
     * @throws PdfPageOperationException {@code SourceTooLarge} if the stream exceeds {@code maxBytes}
     * @throws IOException on an I/O failure
     */
    public static void copyBounded(InputStream in, Path output, long maxBytes)
            throws PdfPageOperationException, IOException {
        long total = 0;
        byte[] buffer = new byte[8192];
        try (OutputStream out = Files.newOutputStream(output)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) {
                    throw new PdfPageOperationException("SourceTooLarge",
                            "The source PDF exceeds the maximum of " + (maxBytes / (1024 * 1024)) + " MB");
                }
                out.write(buffer, 0, read);
            }
        }
    }

    /**
     * Apply the manifest to the decrypted PDF at {@code input}, writing the result to {@code output}.
     *
     * @param input Decrypted source PDF
     * @param output Destination path for the generated PDF
     * @param manifest Parsed, structurally-valid manifest (its output page count already ceiling-checked)
     * @param memUsageSetting PDFBox memory/scratch settings (bounds in-memory + scratch usage)
     * @param deadlineExceeded Returns true once the wall-clock budget has elapsed; checked cooperatively
     * @throws PdfPageOperationException for a signed, encrypted, unparseable, or out-of-range source
     * @throws IOException on an I/O failure not attributable to the client
     */
    public static void transform(Path input, Path output, PdfPageManifest manifest,
                                 MemoryUsageSetting memUsageSetting, BooleanSupplier deadlineExceeded)
            throws PdfPageOperationException, IOException {
        try (PDDocument document = load(input, memUsageSetting)) {
            checkDeadline(deadlineExceeded);

            // Refuse sources we must not silently rewrite: any edit would invalidate a digital signature,
            // and an encrypted PDF cannot be safely rewritten. Both are preserved in history because the
            // caller never mutates the source row — it only creates a new version from a rejected attempt.
            if (document.isEncrypted()) {
                throw new PdfPageOperationException("EncryptedSource",
                        "Encrypted PDFs cannot have page operations applied");
            }
            if (!document.getSignatureDictionaries().isEmpty()) {
                throw new PdfPageOperationException("SignedSource",
                        "Digitally signed PDFs cannot have page operations applied");
            }

            int pageCount = document.getNumberOfPages();

            // Snapshot the original pages by index before mutating the tree.
            List<PDPage> original = new ArrayList<>(pageCount);
            for (PDPage page : document.getPages()) {
                original.add(page);
            }

            // Resolve the surviving pages in target order, validating each index and applying rotations.
            List<PDPage> target = new ArrayList<>(manifest.getPages().size());
            for (PdfPageManifest.PageOp op : manifest.getPages()) {
                if (op.getSource() >= pageCount) {
                    throw new PdfPageOperationException("InvalidManifest",
                            "Page index " + op.getSource() + " is out of range (" + pageCount + " pages)");
                }
                PDPage page = original.get(op.getSource());
                if (op.getRotate() != null) {
                    page.setRotation(op.getRotate());
                }
                target.add(page);
            }

            // Reorder + delete in place: detach every original page, then re-attach the survivors in the
            // requested order. The deadline is checked between page operations — PDFBox is synchronous and
            // is not guaranteed to stop on a thread interrupt, so the budget must be enforced cooperatively.
            for (PDPage page : original) {
                checkDeadline(deadlineExceeded);
                document.removePage(page);
            }
            for (PDPage page : target) {
                checkDeadline(deadlineExceeded);
                document.addPage(page);
            }

            checkDeadline(deadlineExceeded);
            document.save(output.toFile());
        }

        // Synchronously reload and validate the output, then re-check the budget: a validation that itself
        // crossed the deadline must NOT let the caller proceed to flip the latest-version pointer.
        checkDeadline(deadlineExceeded);
        validateOutput(output, memUsageSetting, manifest.getPages().size());
        checkDeadline(deadlineExceeded);
    }

    private static PDDocument load(Path input, MemoryUsageSetting memUsageSetting)
            throws PdfPageOperationException, IOException {
        try {
            return Loader.loadPDF(input.toFile(), () -> new ScratchFile(memUsageSetting));
        } catch (InvalidPasswordException e) {
            throw new PdfPageOperationException("EncryptedSource", "The PDF is password-protected");
        } catch (IOException e) {
            // A malformed / truncated stored PDF is unprocessable input, not a server fault: a typed 400.
            throw new PdfPageOperationException("InvalidPdf", "The file is not a readable PDF");
        }
    }

    /**
     * Reload the generated PDF and confirm it parses, is not encrypted, and has exactly the expected page
     * count — a corrupt or truncated write must be caught before it becomes the latest version.
     */
    private static void validateOutput(Path output, MemoryUsageSetting memUsageSetting, int expectedPages)
            throws PdfPageOperationException, IOException {
        try (PDDocument reloaded = Loader.loadPDF(output.toFile(), () -> new ScratchFile(memUsageSetting))) {
            if (reloaded.isEncrypted() || reloaded.getNumberOfPages() != expectedPages) {
                throw new PdfPageOperationException("OutputInvalid",
                        "The generated PDF failed post-generation validation");
            }
        }
    }

    /**
     * Fail with a timeout if the wall-clock budget has elapsed. Called at each cooperative checkpoint.
     */
    static void checkDeadline(BooleanSupplier deadlineExceeded) throws PdfPageOperationTimeoutException {
        if (deadlineExceeded.getAsBoolean()) {
            throw new PdfPageOperationTimeoutException("The page operation exceeded its time budget");
        }
    }
}
