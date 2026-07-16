package com.sismics.docs.core.util;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.regex.Pattern;

/**
 * Pins the shared rule-regex policy: semantics are EXACTLY {@code java.util.regex} (the dialect
 * every rule was historically authored in — pinned byte-identically against direct evaluation),
 * admission bounds the pattern (syntax + length), and evaluation is bounded (content cap +
 * wall-clock deadline) so a catastrophic backtracking pattern cannot stall processing.
 * The runtime FAIL-SAFE for persisted rules is pinned in {@code TestTagRuleEvaluationFailSafe}
 * next to the listener.
 *
 * @author fmaass
 */
public class TestRegexRulePolicy {

    @Test
    public void documentedRulePatternsKeepTheirSemantics() {
        // The patterns exercised by the REST test suite / documentation examples.
        Assertions.assertTrue(RegexRulePolicy.find("(?i)invoice.*\\d{4}", "This is an invoice for 2024"));
        Assertions.assertTrue(RegexRulePolicy.find("invoice.*\\d{4}", "This is an invoice for 2024"));
        Assertions.assertTrue(RegexRulePolicy.find("receipt.*", "RECEIPT for purchase"));
        Assertions.assertFalse(RegexRulePolicy.find("receipt", "This is an invoice"));
        // Anchors, alternation, classes, bounded quantifiers — the ordinary dialect.
        Assertions.assertTrue(RegexRulePolicy.find("^(invoice|rechnung)\\s+[0-9]{2,6}$", "Rechnung 4711"));
    }

    @Test
    public void matchingIsCaseInsensitiveWithoutInlineFlag() {
        Assertions.assertTrue(RegexRulePolicy.find("invoice", "INVOICE 2024"));
        Assertions.assertTrue(RegexRulePolicy.find("INVOICE", "invoice 2024"));
    }

    /**
     * The policy engine IS java.util.regex — every dialect corner (quoted regions, bare-dot
     * line-terminator behavior, case folding, class intersection, backreferences, end anchors)
     * behaves byte-identically to direct evaluation. Pinned against regression toward any
     * translating/substituting engine.
     */
    @Test
    public void semanticsAreExactlyJavaUtilRegex() {
        String[][] cases = {
                {"\\Qa.b*c\\E", "xa.b*cy"},
                {"\\Qa.b*c\\E", "xaXbYcz"},
                {"a.c", "a\nc"},
                {"a.c", "abc"},
                {"invoice", "INVOICE 2024"},
                {"straße", "STRASSE"},
                {"k", "K"},
                {"foo$", "foo\n"},
                {"foo$", "foo\nbar"},
                {"foo\\Z", "foo\n"},
                {"foo\\z", "foo\n"},
                {"[a-z&&[^aeiou]]", "e"},
                {"[a-z&&[^aeiou]]", "b"},
                {"[a-z[0-9]]", "7"},
                {"(a+)\\1", "aaaa"},
                {"(?m)^line2$", "line1\nline2"},
        };
        for (String[] c : cases) {
            Assertions.assertEquals(
                    Pattern.compile(c[0], Pattern.CASE_INSENSITIVE).matcher(c[1]).find(),
                    RegexRulePolicy.find(c[0], c[1]),
                    "policy diverged from java.util.regex for " + c[0] + " on " + escape(c[1]));
        }
    }

    @Test
    public void admissionBoundsSyntaxAndLength() {
        Assertions.assertNotNull(RegexRulePolicy.validate("[invalid"));
        Assertions.assertNull(RegexRulePolicy.validate("a".repeat(RegexRulePolicy.MAX_PATTERN_LENGTH)));
        Assertions.assertNotNull(RegexRulePolicy.validate("a".repeat(RegexRulePolicy.MAX_PATTERN_LENGTH + 1)));
        // Valid java.util.regex constructs are admitted — the dialect is not narrowed.
        Assertions.assertNull(RegexRulePolicy.validate("(a+)\\1$"));
        Assertions.assertNull(RegexRulePolicy.validate("(?=a)a+b"));
        Assertions.assertNull(RegexRulePolicy.validate("[a-z&&[^aeiou]]"));
    }

    /**
     * "(.*a){15}b" against a 30-char a-run explodes combinatorially under unbounded backtracking —
     * verified to run beyond any acceptable bound on JDK 21, whose Pattern optimizations already
     * defuse the simpler classics like "(a+)+$". The deadline must abort the evaluation from
     * inside the match loop.
     */
    @Test
    public void catastrophicPatternCannotStallEvaluation() {
        String target = "a".repeat(30);
        long start = System.nanoTime();
        Assertions.assertTimeoutPreemptively(Duration.ofSeconds(10), () ->
                Assertions.assertThrows(RegexRulePolicy.EvaluationTimeoutException.class,
                        () -> RegexRulePolicy.find("(.*a){15}b", target)));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        Assertions.assertTrue(elapsedMs < 8_000,
                "evaluation must be deadline-bounded, took " + elapsedMs + " ms");
    }

    /**
     * "(x|y)*" against a long x-run exhausts the recursive matcher's stack in milliseconds on
     * JDK 21 (verified: StackOverflowError after ~4 ms) — an Error, which escapes ordinary
     * catch(Exception) fail-safes. The policy must convert it to its own abort exception at the
     * match call so every caller's skip path handles it like a timeout.
     */
    @Test
    public void stackExhaustionIsConvertedToThePolicyException() {
        Assertions.assertThrows(RegexRulePolicy.EvaluationAbortedException.class,
                () -> RegexRulePolicy.find("(x|y)*", "x".repeat(5000)));
    }

    @Test
    public void evaluatedContentIsCappedToTheLeadingPrefix() {
        String beyondCap = "x".repeat(RegexRulePolicy.MAX_EVALUATED_LENGTH + 100) + "needle";
        Assertions.assertFalse(RegexRulePolicy.find("needle", beyondCap));
        String withinCap = "needle" + "x".repeat(RegexRulePolicy.MAX_EVALUATED_LENGTH + 100);
        Assertions.assertTrue(RegexRulePolicy.find("needle", withinCap));
    }

    private static String escape(String s) {
        return s.replace("\n", "\\n").replace("\r", "\\r");
    }
}
