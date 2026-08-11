package com.sismics.docs.core.util;

import com.sismics.BaseTest;
import com.sismics.docs.core.dao.dto.DocumentDto;
import com.sismics.docs.core.model.jpa.File;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Contract for the creation date {@link PdfUtil#convertToPdf} prints on the export's metadata page.
 *
 * <p>The <em>calendar system</em> and <em>numbering</em> read the same no matter which host ran the
 * export: the pre-fix code built a {@code SimpleDateFormat} with no {@link Locale}, so a {@code th-TH}
 * host printed the Buddhist era (2569 instead of 2026) and {@code -u-nu-thai} printed Thai digits.
 * Those stay pinned to ISO Gregorian, ASCII digits.</p>
 *
 * <p>The calendar <em>day</em>, by contrast, is deliberately the SERVER's local day (#265): a UTC+14
 * host prints its own calendar day, matching the day the date-range/{@code at:} search and the
 * statistics buckets resolve, so a document created near midnight reads one date everywhere rather
 * than two. The accepted trade is that the exported day now depends on the exporting host's zone.</p>
 *
 * <p>Each test renders a real PDF through the production path and reads the date back out of the
 * generated page, rather than asserting on the formatter in isolation.</p>
 */
public class TestPdfUtilMetadataDate extends BaseTest {

    /**
     * 2026-08-03T23:30:00Z. Deliberately late in the UTC day: a host east of UTC has already rolled over
     * to the 4th at this instant, so the same instant distinguishes a UTC rendering from a host-zone one.
     */
    private static final Instant CREATED_AT = Instant.parse("2026-08-03T23:30:00Z");

    /**
     * The creation day, ISO-8601 proleptic Gregorian with ASCII digits. The two host-locale tests
     * below pin the zone to UTC, so the server-local day equals the UTC day (2026-08-03) for them.
     */
    private static final String EXPECTED_LINE = "Created by test on 2026-08-03";

    /**
     * The creation day on a UTC+14 host: 23:30 UTC on the 3rd is already 13:30 on the 4th there, so
     * the export prints 2026-08-04 — the SERVER-local day (#265), not the UTC day.
     */
    private static final String EXPECTED_LINE_LOCAL_PLUS14 = "Created by test on 2026-08-04";

    private Locale hostLocale;
    private Locale hostDisplayLocale;
    private Locale hostFormatLocale;
    private TimeZone hostTimeZone;

    @BeforeEach
    public void captureHostDefaults() {
        hostLocale = Locale.getDefault();
        hostDisplayLocale = Locale.getDefault(Locale.Category.DISPLAY);
        hostFormatLocale = Locale.getDefault(Locale.Category.FORMAT);
        hostTimeZone = TimeZone.getDefault();
    }

    @AfterEach
    public void restoreHostDefaults() {
        // Runs after a failed assertion too, so a hostile locale or zone can never leak into a sibling
        // test in this JVM. setDefault(Locale) resets all three category slots, hence the explicit
        // per-category restore afterwards.
        Locale.setDefault(hostLocale);
        Locale.setDefault(Locale.Category.DISPLAY, hostDisplayLocale);
        Locale.setDefault(Locale.Category.FORMAT, hostFormatLocale);
        TimeZone.setDefault(hostTimeZone);
    }

    @Test
    public void metadataDateUsesTheGregorianCalendarUnderABuddhistHostLocale() throws Exception {
        // th-TH resolves to a BuddhistCalendar in the JDK, so an unpinned formatter prints year 2569.
        Locale.setDefault(Locale.forLanguageTag("th-TH"));
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

        String text = renderMetadataPage();

        Assertions.assertTrue(text.contains(EXPECTED_LINE),
                "the metadata page must print the ISO Gregorian creation date regardless of the host's"
                        + " default calendar, expected \"" + EXPECTED_LINE + "\" in: " + text);
    }

    @Test
    public void metadataDateUsesAsciiDigitsUnderAThaiNumberingHostLocale() throws Exception {
        // The -u-nu-thai extension switches the locale's digits to U+0E50..U+0E59, and -u-ca-buddhist
        // its calendar, so an unpinned formatter prints "๒๕๖๙-๐๘-๐๓".
        Locale.setDefault(Locale.forLanguageTag("th-TH-u-ca-buddhist-nu-thai"));
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

        String text = renderMetadataPage();

        Assertions.assertTrue(text.contains(EXPECTED_LINE),
                "the metadata page must print ASCII digits regardless of the host's default numbering"
                        + " system, expected \"" + EXPECTED_LINE + "\" in: " + text);
    }

    @Test
    public void metadataDateIsRenderedInTheServerLocalZoneNotUtc() throws Exception {
        // Pacific/Kiritimati is UTC+14: at CREATED_AT (23:30Z on the 3rd) the host calendar already
        // reads 2026-08-04, so the export prints the local day — matching the statistics and search.
        Locale.setDefault(Locale.US);
        TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati"));

        String text = renderMetadataPage();

        Assertions.assertTrue(text.contains(EXPECTED_LINE_LOCAL_PLUS14),
                "the metadata page must render the creation instant as its SERVER-local calendar day"
                        + " (#265) so it matches the statistics and search, expected \""
                        + EXPECTED_LINE_LOCAL_PLUS14 + "\" in: " + text);
    }

    /**
     * Render a metadata-only export through the production path and extract its text.
     */
    private static String renderMetadataPage() throws Exception {
        DocumentDto documentDto = new DocumentDto();
        documentDto.setTitle("locale probe");
        documentDto.setLanguage("eng");
        documentDto.setCreator("test");
        documentDto.setCreateTimestamp(CREATED_AT.toEpochMilli());

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        PdfUtil.convertToPdf(documentDto, List.<File>of(), false, true, 10, outputStream);
        try (PDDocument doc = Loader.loadPDF(outputStream.toByteArray())) {
            // The extractor emits its own line breaks; collapse all whitespace so the assertion targets
            // the rendered text rather than the stripper's layout heuristics.
            return new PDFTextStripper().getText(doc).replaceAll("\\s+", " ").trim();
        }
    }
}
