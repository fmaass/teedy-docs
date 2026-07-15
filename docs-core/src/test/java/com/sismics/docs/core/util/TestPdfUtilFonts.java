package com.sismics.docs.core.util;

import com.lowagie.text.FontFactory;
import com.sismics.BaseTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

/**
 * Verifies that {@link PdfUtil#registerFonts()} extracts the mono font at most once per JVM — it is
 * called on every AppContext start, so a per-start extraction would leak one temp per start — while
 * keeping the registered font lazily loadable (FontFactory stores the path and loads it on first use,
 * so a register-then-delete would be a silent font regression rather than a fix).
 */
public class TestPdfUtilFonts extends BaseTest {

    /** Count the mono-font extraction artifacts registerFonts drops in the system tmpdir. */
    private static long countFontArtifacts() throws IOException {
        Path tmpDir = Paths.get(System.getProperty("java.io.tmpdir"));
        try (Stream<Path> s = Files.list(tmpDir)) {
            return s.filter(p -> p.getFileName().toString().startsWith("sismics_docs_font_mono")).count();
        }
    }

    @Test
    public void registerFontsExtractsOncePerJvm() throws Exception {
        // A first registration establishes the single cached extraction (one may already exist from an
        // AppContext start earlier in this JVM — the invariant is idempotency, not an absolute count).
        PdfUtil.registerFonts();
        long afterFirst = countFontArtifacts();

        // A second registration must reuse the cached extraction, not drop another temp.
        PdfUtil.registerFonts();
        long afterSecond = countFontArtifacts();

        Assertions.assertEquals(afterFirst, afterSecond,
                "registerFonts must not extract a new font temp on each invocation");

        // The registered font must still be lazily loadable from its cached path.
        Assertions.assertNotNull(FontFactory.getFont("LiberationMono-Regular").getBaseFont(),
                "the registered mono font must remain loadable after re-registration");
    }

    /**
     * This JVM's cached extraction, read off PdfUtil's private static — the tmpdir is shared, so the
     * test must target exactly its own process-private cache, never a concurrent JVM's look-alike dir.
     */
    private static Path cachedMonoFontFile() throws Exception {
        Field field = PdfUtil.class.getDeclaredField("monoFontFile");
        field.setAccessible(true);
        return (Path) field.get(null);
    }

    /**
     * A tmp reaper (tmpwatch/systemd-tmpfiles) may delete the cached extraction mid-life. The next
     * registerFonts must re-extract instead of trusting the stale cache — and must not abort the
     * caller (AppContext startup) with FontFactory's unchecked ExceptionConverter on the missing file.
     * The recovery is asserted on the cache field itself (a fresh, existing extraction) because
     * FontFactory/BaseFont cache loaded fonts globally — a loadability probe alone could pass
     * vacuously off an earlier load even if no re-extraction happened.
     */
    @Test
    public void registerFontsReExtractsWhenCachedFileRemoved() throws Exception {
        // Establish the cached extraction, then simulate the reaper on THIS JVM's cache file only.
        PdfUtil.registerFonts();
        Path cached = cachedMonoFontFile();
        Assertions.assertNotNull(cached, "registerFonts must have populated the font cache");
        Assertions.assertTrue(Files.exists(cached), "the cached extraction exists before the reap");
        Files.delete(cached);

        // Must recover by re-extracting (and never throw out of the registration call).
        PdfUtil.registerFonts();
        Path reExtracted = cachedMonoFontFile();
        Assertions.assertNotNull(reExtracted, "the font cache must be repopulated after the reap");
        Assertions.assertNotEquals(cached, reExtracted,
                "recovery must be a FRESH extraction, not the stale path of the reaped file");
        Assertions.assertTrue(Files.exists(reExtracted), "the re-extracted font file must exist");
        Assertions.assertNotNull(FontFactory.getFont("LiberationMono-Regular").getBaseFont(),
                "the re-registered mono font must be loadable");
    }
}
