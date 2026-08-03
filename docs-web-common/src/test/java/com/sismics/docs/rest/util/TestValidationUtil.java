package com.sismics.docs.rest.util;

import com.sismics.rest.exception.ClientException;
import com.sismics.rest.util.ValidationUtil;
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
        Assertions.assertDoesNotThrow(() -> ValidationUtil.validateTagName("Report-2026"));
        Assertions.assertDoesNotThrow(() -> ValidationUtil.validateTagName("Rechnung"));
    }

    /**
     * An absent name means "leave the name as it is" on a tag update (a colour- or parent-only
     * edit), so it must pass validation instead of blowing up.
     */
    @Test
    public void validateTagNameAcceptsAnAbsentName() {
        Assertions.assertDoesNotThrow(() -> ValidationUtil.validateTagName(null));
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
