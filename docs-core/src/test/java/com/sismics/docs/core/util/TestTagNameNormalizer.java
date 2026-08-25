package com.sismics.docs.core.util;

import com.sismics.docs.core.util.TagNameNormalizer.Result;
import com.sismics.docs.core.util.TagNameNormalizer.Verdict;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * #305 — the character rule for tag names, one class of character at a time.
 *
 * <p>Every value is built from CODE POINTS rather than written as a literal: a raw zero-width space
 * or thin space in the source is invisible or indistinguishable from an ordinary space, so a literal
 * would make the test unreadable and unreviewable — the same property that makes these characters a
 * defect in the first place.
 */
public class TestTagNameNormalizer {

    private static String cp(int codePoint) {
        return new String(Character.toChars(codePoint));
    }

    /**
     * Each invisible format character (Unicode category Cf) is removed silently, one by one. A user
     * cannot see them, so refusing the name would be asking them to delete something invisible.
     */
    @Test
    public void invisibleFormatCharactersAreStripped() {
        int[] invisible = {
                0x00AD, // SOFT HYPHEN
                0x200B, // ZERO WIDTH SPACE
                0x200C, // ZERO WIDTH NON-JOINER
                0x200D, // ZERO WIDTH JOINER
                0x200E, // LEFT-TO-RIGHT MARK
                0x202E, // RIGHT-TO-LEFT OVERRIDE
                0x2060, // WORD JOINER
                0xFEFF, // ZERO WIDTH NO-BREAK SPACE (BOM)
        };
        for (int codePoint : invisible) {
            Result result = TagNameNormalizer.normalize("Rech" + cp(codePoint) + "nung");
            Assertions.assertEquals(Verdict.OK, result.verdict(),
                    () -> String.format("U+%04X must be stripped, not refused", codePoint));
            Assertions.assertEquals("Rechnung", result.name(),
                    () -> String.format("U+%04X must be removed from the stored name", codePoint));
        }
    }

    /**
     * A thin space is a space: refused with {@link Verdict#VISIBLE_WHITESPACE}, never deleted.
     * Deleting it would turn "Test 123" into "Test123" while the same name typed with an ordinary
     * space fails.
     */
    @Test
    public void thinSpaceIsRefused() {
        assertRefusedAsVisibleWhitespace(0x2009);
    }

    /** A no-break space is refused: it renders as a gap, so it carries the user's visible intent. */
    @Test
    public void noBreakSpaceIsRefused() {
        assertRefusedAsVisibleWhitespace(0x00A0);
    }

    /** An ideographic space is refused — a full-width gap is still a gap. */
    @Test
    public void ideographicSpaceIsRefused() {
        assertRefusedAsVisibleWhitespace(0x3000);
    }

    /** A tab is refused. It is control whitespace, not a category-Z space, so it needs its own test. */
    @Test
    public void tabIsRefused() {
        assertRefusedAsVisibleWhitespace(0x0009);
    }

    /** The ordinary ASCII space keeps behaving exactly as it always has: refused. */
    @Test
    public void ordinaryAsciiSpaceIsStillRefused() {
        assertRefusedAsVisibleWhitespace(0x0020);
    }

    /**
     * The remaining exotic spaces the issue's "whitespace generator" can emit, as one sweep: the
     * rule is a Unicode category, so every member of it has to behave the same way.
     */
    @Test
    public void everyOtherVisibleWhitespaceIsRefused() {
        int[] visible = {
                0x000A, // LINE FEED
                0x000B, // LINE TABULATION
                0x000C, // FORM FEED
                0x000D, // CARRIAGE RETURN
                0x1680, // OGHAM SPACE MARK
                0x2000, // EN QUAD
                0x2001, // EM QUAD
                0x2002, // EN SPACE
                0x2003, // EM SPACE
                0x2004, // THREE-PER-EM SPACE
                0x2005, // FOUR-PER-EM SPACE
                0x2006, // SIX-PER-EM SPACE
                0x2007, // FIGURE SPACE
                0x2008, // PUNCTUATION SPACE
                0x200A, // HAIR SPACE
                0x2028, // LINE SEPARATOR
                0x2029, // PARAGRAPH SEPARATOR
                0x202F, // NARROW NO-BREAK SPACE
                0x205F, // MEDIUM MATHEMATICAL SPACE
        };
        for (int codePoint : visible) {
            assertRefusedAsVisibleWhitespace(codePoint);
        }
    }

    /**
     * An invisible character next to a visible one does not launder it: the strip happens first, and
     * the space that is left is still refused.
     */
    @Test
    public void anInvisibleCharacterDoesNotHideAVisibleSpace() {
        Result result = TagNameNormalizer.normalize("Test" + cp(0x200B) + cp(0x2009) + cp(0x200B) + "123");
        Assertions.assertEquals(Verdict.VISIBLE_WHITESPACE, result.verdict());
    }

    /**
     * A name that had content and lost all of it is its own verdict — the user typed something, so
     * "must not be empty" is a different message from "contains a space".
     */
    @Test
    public void aNameOfOnlyInvisibleCharactersIsEmptyAfterNormalizing() {
        Result result = TagNameNormalizer.normalize(cp(0x200B) + cp(0x200D) + cp(0xFEFF));
        Assertions.assertEquals(Verdict.EMPTY_AFTER_NORMALIZE, result.verdict());
        Assertions.assertEquals("", result.name());
    }

    /**
     * Null and empty mean "the caller is not setting a name" (a colour- or parent-only tag update),
     * NOT "a name that became empty". They pass through untouched, or a colour edit would start
     * failing on the name.
     */
    @Test
    public void absentAndEmptyNamesPassThroughUnchanged() {
        Assertions.assertEquals(new Result(null, Verdict.OK), TagNameNormalizer.normalize(null));
        Assertions.assertEquals(new Result("", Verdict.OK), TagNameNormalizer.normalize(""));
    }

    /** A clean name is returned as the very same instance — normalization must not churn strings. */
    @Test
    public void aCleanNameIsUntouched() {
        String name = "Rechnung-2026";
        Result result = TagNameNormalizer.normalize(name);
        Assertions.assertEquals(Verdict.OK, result.verdict());
        Assertions.assertSame(name, result.name());
    }

    /**
     * Accents, umlauts, non-Latin scripts and emoji are ordinary characters here — the rule is about
     * whitespace, not about an allow-list of "safe" letters, and a tag named in Greek or Japanese is
     * a normal thing for this application to hold.
     */
    @Test
    public void ordinaryNonAsciiCharactersAreKept() {
        for (String name : new String[] { "Rechnungen-Ä", "Λογαριασμός", "請求書", "Facturé", "Steuer" + cp(0x1F4B0) }) {
            Result result = TagNameNormalizer.normalize(name);
            Assertions.assertEquals(Verdict.OK, result.verdict(), "must accept " + name);
            Assertions.assertEquals(name, result.name(), "must not alter " + name);
        }
    }

    /**
     * The repair rule for names already stored: every whitespace class goes, invisible and visible
     * alike, because an existing name cannot be handed back to its author for correction.
     */
    @Test
    public void stripAllWhitespaceRemovesBothClasses() {
        String stored = cp(0x00A0) + "Rech" + cp(0x200B) + "nung" + cp(0x2009) + "2026\t" + cp(0xFEFF);
        Assertions.assertEquals("Rechnung2026", TagNameNormalizer.stripAllWhitespace(stored));
    }

    /** Stripping an already-clean name changes nothing, which is what makes the repair idempotent. */
    @Test
    public void stripAllWhitespaceIsIdempotent() {
        String once = TagNameNormalizer.stripAllWhitespace("Rech" + cp(0x200B) + "nung" + cp(0x2009));
        Assertions.assertEquals("Rechnung", once);
        Assertions.assertEquals(once, TagNameNormalizer.stripAllWhitespace(once));
        Assertions.assertNull(TagNameNormalizer.stripAllWhitespace(null));
    }

    /**
     * The two classes must not overlap, or "strip the invisible ones, then judge what is left" would
     * depend on the order the two tests are applied in. Checked across the whole BMP rather than on
     * a sample, because the rule is a category membership, not a list.
     */
    @Test
    public void theInvisibleAndVisibleClassesAreDisjoint() {
        int invisible = 0;
        int whitespace = 0;
        int supplementaryInvisible = 0;
        for (int codePoint = Character.MIN_CODE_POINT; codePoint <= Character.MAX_CODE_POINT; codePoint++) {
            if (codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE) {
                // A lone surrogate is not a character; it can never reach these tests as a code point.
                continue;
            }
            boolean isInvisible = TagNameNormalizer.isInvisibleFormat(codePoint);
            boolean isWhitespace = TagNameNormalizer.isVisibleWhitespace(codePoint);
            if (isInvisible && isWhitespace) {
                Assertions.fail(String.format("U+%04X cannot be both an invisible format character"
                        + " and visible whitespace", codePoint));
            }
            if (isInvisible) {
                invisible++;
                if (codePoint > Character.MAX_VALUE) {
                    supplementaryInvisible++;
                }
            }
            if (isWhitespace) {
                whitespace++;
            }
        }
        // Positive control: a 0-hit sweep proves nothing unless the sweep actually classified
        // something. Both classes must be non-empty, and the LOOP itself must have counted
        // supplementary-plane Cf characters (the block starting at U+E0001, LANGUAGE TAG) — a
        // direct call outside the loop would keep passing if the upper bound ever shrank to the
        // basic plane again.
        Assertions.assertTrue(invisible > 0 && whitespace > 0,
                "the sweep must have classified both kinds of character");
        Assertions.assertTrue(supplementaryInvisible > 0,
                "the sweep must reach past the basic plane: U+E0001 LANGUAGE TAG and its block are category Cf");
    }

    /**
     * A code point outside the basic plane is one code point, not two chars: stripping must never
     * cut an emoji in half and leave a lone surrogate in a stored name.
     */
    @Test
    public void supplementaryCharactersSurviveIntact() {
        String name = "A" + cp(0x1F5C2) + "B"; // CARD INDEX DIVIDERS
        Assertions.assertEquals(name, TagNameNormalizer.stripAllWhitespace(name));
        Assertions.assertEquals(name, TagNameNormalizer.normalize(name).name());
    }

    /**
     * Refusal is about INTERIOR whitespace, so the probe puts the character between two words. The
     * same character on an edge is trimmed instead — see
     * {@link #edgeWhitespaceIsTrimmedForEveryClass()}.
     */
    private static void assertRefusedAsVisibleWhitespace(int codePoint) {
        Result result = TagNameNormalizer.normalize("Test" + cp(codePoint) + "123");
        Assertions.assertEquals(Verdict.VISIBLE_WHITESPACE, result.verdict(),
                () -> String.format("U+%04X must be refused inside a name, never silently deleted", codePoint));
    }

    /**
     * Leading and trailing whitespace is TRIMMED, of every class, because that is what a leading or
     * trailing ORDINARY space has always done to a tag name (validateLength stripped it long before
     * this rule existed). The no-break spaces matter most here: they are the ones a paste carries and
     * the ones {@link Character#isWhitespace} does not recognise, so a plain strip would have left
     * them behind and then refused the name.
     */
    @Test
    public void edgeWhitespaceIsTrimmedForEveryClass() {
        int[] everyClass = {0x0020, 0x0009, 0x000A, 0x00A0, 0x1680, 0x2002, 0x2003, 0x2007, 0x2009,
                0x200A, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000};
        for (int codePoint : everyClass) {
            String pad = cp(codePoint);
            for (String name : new String[] {pad + "Report", "Report" + pad, pad + pad + "Report" + pad}) {
                Result result = TagNameNormalizer.normalize(name);
                Assertions.assertEquals(Verdict.OK, result.verdict(),
                        () -> String.format("U+%04X on an edge must be trimmed, not refused", codePoint));
                Assertions.assertEquals("Report", result.name(),
                        () -> String.format("U+%04X on an edge must be trimmed off the stored name", codePoint));
            }
        }
    }

    /**
     * The ordinary space is the reference behaviour the exotic ones have to match: the two must give
     * byte-identical answers, on the edges and inside.
     */
    @Test
    public void exoticSpacesAnswerExactlyAsTheOrdinarySpaceDoes() {
        for (int codePoint : new int[] {0x00A0, 0x2009, 0x3000, 0x202F, 0x2007, 0x0009}) {
            String exotic = cp(codePoint);
            Assertions.assertEquals(TagNameNormalizer.normalize(" Report "),
                    TagNameNormalizer.normalize(exotic + "Report" + exotic),
                    () -> String.format("U+%04X on the edges must answer as an ordinary space does", codePoint));
            Assertions.assertEquals(TagNameNormalizer.normalize("Re port").verdict(),
                    TagNameNormalizer.normalize("Re" + exotic + "port").verdict(),
                    () -> String.format("U+%04X inside must answer as an ordinary space does", codePoint));
        }
    }

    /**
     * Trimming must not soften the interior rule: a name padded on BOTH sides that still has a gap in
     * the middle is refused, not trimmed into acceptance.
     */
    @Test
    public void trimmingDoesNotRescueInteriorWhitespace() {
        Result result = TagNameNormalizer.normalize(cp(0x00A0) + "Re" + cp(0x2009) + "port" + cp(0x00A0));
        Assertions.assertEquals(Verdict.VISIBLE_WHITESPACE, result.verdict());
    }

    /**
     * Normalization is what the length bound must measure, so it has to be able to SHORTEN a name to
     * exactly the limit: 36 real characters plus an invisible one is a 36-character name.
     */
    @Test
    public void normalizationShortensToTheStorableName() {
        String thirtySix = "R".repeat(36);
        Result result = TagNameNormalizer.normalize("R" + cp(0x200B) + thirtySix.substring(1));
        Assertions.assertEquals(Verdict.OK, result.verdict());
        Assertions.assertEquals(36, result.name().length(),
                "the normalized name is what a length bound has to be measured against");
        Assertions.assertEquals(thirtySix, result.name());
    }

    /** A name of nothing but ordinary spaces is empty after normalizing, not "too short". */
    @Test
    public void aNameOfOnlyWhitespaceIsEmptyAfterNormalizing() {
        for (String name : new String[] {"   ", cp(0x00A0) + cp(0x3000), "\t", cp(0x2009) + cp(0x200B)}) {
            Result result = TagNameNormalizer.normalize(name);
            Assertions.assertEquals(Verdict.EMPTY_AFTER_NORMALIZE, result.verdict(),
                    "a name of only whitespace has nothing left to store: " + name.length() + " chars");
            Assertions.assertEquals("", result.name());
        }
    }
}
