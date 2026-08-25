package com.sismics.docs.rest.util;

import com.sismics.rest.exception.ClientException;
import com.sismics.rest.util.ValidationUtil;
import jakarta.json.JsonObject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Test the validations.
 *
 * @author jtremeaux
 */
public class TestValidationUtil {
    @Test
    public void testValidateHttpUrlFail() throws Exception {
        ValidationUtil.validateHttpUrl("http://www.google.com", "url");
        ValidationUtil.validateHttpUrl("https://www.google.com", "url");
        ValidationUtil.validateHttpUrl(" https://www.google.com ", "url");
        try {
            ValidationUtil.validateHttpUrl("ftp://www.google.com", "url");
            Assertions.fail();
        } catch (ClientException e) {
            // NOP
        }
        try {
            ValidationUtil.validateHttpUrl("http://", "url");
            Assertions.fail();
        } catch (ClientException e) {
            // NOP
        }
    }

    /**
     * A clean file name (no separators or control characters) is accepted unchanged.
     */
    @Test
    public void validateFileNameAcceptsCleanName() {
        Assertions.assertDoesNotThrow(() -> ValidationUtil.validateFileName("report 2026.pdf", "name"));
        Assertions.assertDoesNotThrow(() -> ValidationUtil.validateFileName("resume-final.docx", "name"));
        // Null passes here - length/required is a separate concern.
        Assertions.assertDoesNotThrow(() -> ValidationUtil.validateFileName(null, "name"));
    }

    /**
     * A negative value is rejected (used to reject a negative storage quota at the input boundary so it
     * cannot reach the quota comparison and underflow); zero and positive values pass.
     */
    @Test
    public void validateNonNegativeRejectsNegatives() {
        Assertions.assertThrows(ClientException.class,
                () -> ValidationUtil.validateNonNegative(-1L, "storage_quota"));
        Assertions.assertThrows(ClientException.class,
                () -> ValidationUtil.validateNonNegative(Long.MIN_VALUE, "storage_quota"));
        Assertions.assertDoesNotThrow(() -> ValidationUtil.validateNonNegative(0L, "storage_quota"));
        Assertions.assertDoesNotThrow(() -> ValidationUtil.validateNonNegative(1_000L, "storage_quota"));
    }

    /**
     * A file name carrying a path separator, a backslash, a NUL, or a control character is REJECTED
     * (not silently rewritten), so a rename can never store a name that would later escape an archive
     * extraction directory or inject a traversal.
     */
    @Test
    public void validateFileNameRejectsSeparatorsAndControlChars() {
        String[] bad = {
                "../../etc/passwd",
                "a/b.txt",
                "a\\b.txt",
                "nul\u0000name.txt",
"line\nbreak.txt",
                "tab\tname.txt"};
        for (String value : bad) {
            Assertions.assertThrows(ClientException.class,
                    () -> ValidationUtil.validateFileName(value, "name"),
                    "must reject a name containing a separator/NUL/control char: " + value);
        }
    }

    /**
     * An asterisk is rejected in a tag name alongside spaces and colons: the search grammar splits
     * segments on spaces, uses the colon as the field separator, and reads an asterisk in a tag
     * term as a wildcard, so a name carrying one could not be searched for unambiguously.
     */
    @Test
    public void validateTagNameRejectsSearchGrammarCharacters() {
        Assertions.assertThrows(ClientException.class, () -> ValidationUtil.validateTagName("Report*"));
        Assertions.assertThrows(ClientException.class, () -> ValidationUtil.validateTagName("Re*port"));
        Assertions.assertThrows(ClientException.class, () -> ValidationUtil.validateTagName("Report 2026"));
        Assertions.assertThrows(ClientException.class, () -> ValidationUtil.validateTagName("Report:2026"));
        Assertions.assertEquals("Report-2026", ValidationUtil.validateTagName("Report-2026"));
        Assertions.assertEquals("Rechnung", ValidationUtil.validateTagName("Rechnung"));
    }

    /**
     * An absent name means "leave the name as it is" on a tag update (a colour- or parent-only
     * edit), so it must pass validation instead of blowing up — and come back absent, or the caller
     * would store the absence as a rename to nothing. An empty name means the same thing.
     */
    @Test
    public void validateTagNameAcceptsAnAbsentName() {
        Assertions.assertNull(ValidationUtil.validateTagName(null));
        Assertions.assertEquals("", ValidationUtil.validateTagName(""));
    }

    /**
     * #305: an invisible format character is STRIPPED and the caller is handed the cleaned name to
     * store. Returning it is the contract — a validator that only threw or passed would leave the
     * caller persisting exactly the characters this removed.
     */
    @Test
    public void validateTagNameStripsInvisibleFormatCharacters() {
        String zwsp = new String(Character.toChars(0x200B));
        String bom = new String(Character.toChars(0xFEFF));
        String softHyphen = new String(Character.toChars(0x00AD));
        Assertions.assertEquals("Rechnung",
                ValidationUtil.validateTagName("Rech" + zwsp + "nung" + bom));
        Assertions.assertEquals("Rechnung",
                ValidationUtil.validateTagName(softHyphen + "Rechnung"));
        // Stripping happens BEFORE the search-grammar characters are judged, so an invisible
        // character cannot smuggle a colon past the check.
        Assertions.assertThrows(ClientException.class,
                () -> ValidationUtil.validateTagName("Report" + zwsp + ":2026"));
    }

    /**
     * A name that is nothing but invisible characters has no name left once they are stripped. It is
     * refused rather than stored empty — an empty tag name is a row no list can render or select.
     */
    @Test
    public void validateTagNameRejectsANameOfOnlyInvisibleCharacters() {
        String onlyInvisible = new String(Character.toChars(0x200B)) + new String(Character.toChars(0x200D));
        ClientException e = Assertions.assertThrows(ClientException.class,
                () -> ValidationUtil.validateTagName(onlyInvisible));
        Assertions.assertEquals("IllegalTagName", errorType(e));
    }

    /**
     * The exotic whitespace refusal must be the SAME error the ordinary space has always produced,
     * so an existing client (and the SPA, which surfaces the server message verbatim) needs no new
     * handling — and so the two spellings of "Test 123" get the same answer.
     */
    @Test
    public void exoticWhitespaceIsRefusedWithTheSameErrorAsAnOrdinarySpace() {
        String thinSpace = new String(Character.toChars(0x2009));
        ClientException withSpace = Assertions.assertThrows(ClientException.class,
                () -> ValidationUtil.validateTagName("Test 123"));
        ClientException withThinSpace = Assertions.assertThrows(ClientException.class,
                () -> ValidationUtil.validateTagName("Test" + thinSpace + "123"));
        Assertions.assertEquals("IllegalTagName", errorType(withSpace));
        Assertions.assertEquals(errorType(withSpace), errorType(withThinSpace));
        Assertions.assertEquals(errorMessage(withSpace), errorMessage(withThinSpace));
    }

    /**
     * #305 ordering: the validator TRIMS edge whitespace of every class and returns the trimmed name,
     * so the caller's length bound is measured on what will be stored. A leading ordinary space has
     * always been trimmed rather than refused (validateLength stripped it), and the exotic spaces now
     * reach the same answer instead of depending on which validator happened to run first.
     */
    @Test
    public void validateTagNameTrimsEdgeWhitespaceOfEveryClass() {
        String nbsp = new String(Character.toChars(0x00A0));
        String thinSpace = new String(Character.toChars(0x2009));
        String ideographic = new String(Character.toChars(0x3000));
        Assertions.assertEquals("Report", ValidationUtil.validateTagName(" Report "));
        Assertions.assertEquals("Report", ValidationUtil.validateTagName("\tReport\t"));
        Assertions.assertEquals("Report", ValidationUtil.validateTagName(nbsp + "Report" + nbsp));
        Assertions.assertEquals("Report", ValidationUtil.validateTagName(thinSpace + "Report"));
        Assertions.assertEquals("Report", ValidationUtil.validateTagName("Report" + ideographic));
        // Trimming the edges must not rescue an interior gap.
        Assertions.assertThrows(ClientException.class,
                () -> ValidationUtil.validateTagName(nbsp + "Re" + thinSpace + "port" + nbsp));
    }

    /**
     * A name of nothing but whitespace — ordinary or exotic — has no name left. It is refused with
     * {@code IllegalTagName}, and the message has to be true of BOTH cases, so it talks about visible
     * characters rather than about invisible ones.
     */
    @Test
    public void validateTagNameRejectsANameOfOnlyWhitespace() {
        String ideographic = new String(Character.toChars(0x3000));
        for (String name : new String[] {"   ", "\t", ideographic, ideographic + " "}) {
            ClientException e = Assertions.assertThrows(ClientException.class,
                    () -> ValidationUtil.validateTagName(name));
            Assertions.assertEquals("IllegalTagName", errorType(e));
            Assertions.assertEquals("Tag name must contain at least one visible character", errorMessage(e));
        }
    }

    /**
     * The error type and message a {@link ClientException} carries live in its response entity, not
     * in {@link Exception#getMessage()} (which is the generic "HTTP 400 Bad Request").
     */
    private static String errorType(ClientException e) {
        return ((JsonObject) e.getResponse().getEntity()).getString("type");
    }

    private static String errorMessage(ClientException e) {
        return ((JsonObject) e.getResponse().getEntity()).getString("message");
    }

    /**
     * #305: every VISIBLE Unicode whitespace character is refused exactly like the ASCII space
     * already is. Silently deleting them instead would make "Test 123" pasted with a thin space
     * quietly become "Test123" while the same name typed with an ordinary space fails — two
     * different outcomes for one intent.
     */
    @Test
    public void validateTagNameRejectsExoticVisibleWhitespace() {
        // Spelled as CODE POINTS, never as literal characters: a raw NBSP or thin space in the
        // source is indistinguishable from an ordinary space when the test is read.
        int[] visibleWhitespace = {
                0x0009, // CHARACTER TABULATION (Cc, whitespace)
                0x000A, // LINE FEED (Cc, whitespace)
                0x00A0, // NO-BREAK SPACE (Zs)
                0x1680, // OGHAM SPACE MARK (Zs)
                0x2002, // EN SPACE (Zs)
                0x2003, // EM SPACE (Zs)
                0x2007, // FIGURE SPACE (Zs)
                0x2008, // PUNCTUATION SPACE (Zs)
                0x2009, // THIN SPACE (Zs)
                0x200A, // HAIR SPACE (Zs)
                0x2028, // LINE SEPARATOR (Zl)
                0x2029, // PARAGRAPH SEPARATOR (Zp)
                0x202F, // NARROW NO-BREAK SPACE (Zs)
                0x205F, // MEDIUM MATHEMATICAL SPACE (Zs)
                0x3000, // IDEOGRAPHIC SPACE (Zs)
        };
        for (int codePoint : visibleWhitespace) {
            String value = "Test" + new String(Character.toChars(codePoint)) + "123";
            Assertions.assertThrows(ClientException.class,
                    () -> ValidationUtil.validateTagName(value),
                    () -> String.format("must reject a tag name carrying U+%04X", codePoint));
        }
    }

    /**
     * A colour is stored verbatim and rendered as a CSS colour (the tag chip background, the theme
     * navbar rule) or derived into the UI palette, so "seven characters long" is not the contract.
     * A length-only check accepted "#gggggg" and persisted it.
     */
    @Test
    public void validateHexColorRejectsNonHexadecimalValues() {
        String[] bad = {
                "#gggggg",
                "#12345z",
                "#ff00 0",
                "1234567",
                "##12345",
                " #ff0000 ", // seven characters once trimmed, but the caller stores the padding
        };
        for (String value : bad) {
            Assertions.assertThrows(ClientException.class,
                    () -> ValidationUtil.validateHexColor(value, "color", true),
                    "must reject a non-hexadecimal colour: " + value);
        }
    }

    /**
     * The tightening must not narrow what the app already produces: real colours in either case,
     * and the nullable/empty forms every caller passes for "leave it unset".
     */
    @Test
    public void validateHexColorAcceptsRealColorsAndTheEmptyForms() throws Exception {
        for (String value : new String[] { "#000000", "#ffffff", "#AABBCC", "#0f0F0f" }) {
            ValidationUtil.validateHexColor(value, "color", true);
        }
        ValidationUtil.validateHexColor(null, "color", true);
        ValidationUtil.validateHexColor("", "color", true);
        Assertions.assertThrows(ClientException.class,
                () -> ValidationUtil.validateHexColor(null, "color", false),
                "a non-nullable colour must still be required");
    }

    /**
     * The predicate form answers the same question without throwing, for a caller that has to
     * decide whether an ALREADY STORED value is usable rather than reject an incoming one — a row
     * written before this validation was tightened can hold anything of the right length.
     */
    @Test
    public void isHexColorAcceptsOnlyWellFormedColors() {
        for (String value : new String[] { "#000000", "#ffffff", "#AABBCC", "#0f0F0f" }) {
            Assertions.assertTrue(ValidationUtil.isHexColor(value), "must accept: " + value);
        }
        String[] bad = {
                "#gggggg",
                "#12345z",
                "#ff00 0",
                "1234567",
                "##12345",
                " #ff0000 ",
                "",
                "red; } body { display: none",
        };
        for (String value : bad) {
            Assertions.assertFalse(ValidationUtil.isHexColor(value), "must reject: " + value);
        }
        Assertions.assertFalse(ValidationUtil.isHexColor(null), "must reject null");
    }
}
