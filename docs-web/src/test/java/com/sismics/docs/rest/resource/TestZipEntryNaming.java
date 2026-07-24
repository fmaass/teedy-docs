package com.sismics.docs.rest.resource;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;

/**
 * Unit coverage for the download-zip entry naming allocator ({@link FileResource#deduplicateEntryNames}).
 * Entries carry the file's real (sanitized) name; duplicates get the smallest free " (N)" suffix inserted
 * before the extension, and a generated suffix must never collide with a real file already named like one.
 * These assert the entry-naming contract directly, independent of the REST wiring.
 */
public class TestZipEntryNaming {

    /**
     * Distinct names are emitted verbatim, in order.
     */
    @Test
    public void uniqueNamesKeptVerbatim() {
        Assertions.assertEquals(
                List.of("a.pdf", "b.pdf", "c.txt"),
                FileResource.deduplicateEntryNames(List.of("a.pdf", "b.pdf", "c.txt")));
    }

    /**
     * A repeated name gets " (1)" before the extension; the first occurrence stays verbatim.
     */
    @Test
    public void duplicateGetsSuffixBeforeExtension() {
        Assertions.assertEquals(
                List.of("a.pdf", "a (1).pdf", "b.pdf"),
                FileResource.deduplicateEntryNames(List.of("a.pdf", "a.pdf", "b.pdf")));
    }

    /**
     * Adversarial case: a real file already named "a (1).pdf" is reserved, so the duplicate of "a.pdf" must
     * skip to "a (2).pdf" and the real "a (1).pdf" keeps its verbatim name — no entry name is ever repeated.
     */
    @Test
    public void generatedSuffixNeverStealsAReservedRealName() {
        List<String> result = FileResource.deduplicateEntryNames(List.of("a.pdf", "a.pdf", "a (1).pdf"));
        Assertions.assertEquals(List.of("a.pdf", "a (2).pdf", "a (1).pdf"), result);
        // No two entries share a name.
        Assertions.assertEquals(result.size(), new HashSet<>(result).size());
    }

    /**
     * Extensionless names get the suffix appended at the end.
     */
    @Test
    public void extensionlessNamesSuffixAtEnd() {
        Assertions.assertEquals(
                List.of("a", "a (1)", "b"),
                FileResource.deduplicateEntryNames(List.of("a", "a", "b")));
    }

    /**
     * A leading dot is not an extension separator: a dotfile like ".env" is treated as extensionless.
     */
    @Test
    public void dotLeadingNamesTreatedAsExtensionless() {
        Assertions.assertEquals(
                List.of(".env", ".env (1)", ".env (2)"),
                FileResource.deduplicateEntryNames(List.of(".env", ".env", ".env")));
    }

    /**
     * A multi-dot name splits only at its last dot (the real extension).
     */
    @Test
    public void multiDotNameSplitsAtLastDot() {
        Assertions.assertEquals(
                List.of("archive.tar.gz", "archive.tar (1).gz"),
                FileResource.deduplicateEntryNames(List.of("archive.tar.gz", "archive.tar.gz")));
    }

    /**
     * Three identical names produce a monotonic suffix run.
     */
    @Test
    public void repeatedNameProducesMonotonicSuffixes() {
        Assertions.assertEquals(
                List.of("report.pdf", "report (1).pdf", "report (2).pdf"),
                FileResource.deduplicateEntryNames(
                        List.of("report.pdf", "report.pdf", "report.pdf")));
    }
}
