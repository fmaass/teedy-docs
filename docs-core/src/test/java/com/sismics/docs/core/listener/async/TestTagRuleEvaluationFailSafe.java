package com.sismics.docs.core.listener.async;

import com.sismics.docs.core.model.jpa.TagMatchRule;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.Duration;

/**
 * Pins the runtime FAIL-SAFE of {@link FileProcessingAsyncListener#ruleMatches}: a persisted rule
 * whose pattern is invalid, or whose evaluation exceeds the policy deadline (a legacy or
 * directly-inserted DB row that never went through REST validation), is skipped — file processing
 * is never stalled — and the skip warning is bounded to once per rule id + pattern.
 *
 * @author fmaass
 */
public class TestTagRuleEvaluationFailSafe {

    @Test
    public void invalidPersistedRuleIsSkippedValidRuleStillMatches() {
        // A rule inserted below the REST validation layer (DAO/DB level) with a broken pattern
        // must be skipped without an exception.
        TagMatchRule broken = new TagMatchRule();
        broken.setId("broken-rule");
        broken.setPattern("[invalid");
        Assertions.assertFalse(FileProcessingAsyncListener.ruleMatches(broken, "anything"));

        TagMatchRule valid = new TagMatchRule();
        valid.setId("valid-rule");
        valid.setPattern("invoice.*\\d{4}");
        Assertions.assertTrue(FileProcessingAsyncListener.ruleMatches(valid, "Invoice 2024"));

        // Historical java.util.regex semantics are preserved: a legacy backreference rule keeps
        // matching exactly as it always did.
        TagMatchRule legacy = new TagMatchRule();
        legacy.setId("legacy-backref-rule");
        legacy.setPattern("(\\w+)\\s\\1");
        Assertions.assertTrue(FileProcessingAsyncListener.ruleMatches(legacy, "aa aa"));
    }

    @Test
    public void persistedCatastrophicRuleCannotStallEvaluation() {
        TagMatchRule hostile = new TagMatchRule();
        hostile.setId("hostile-rule");
        hostile.setPattern("(.*a){15}b");
        String target = "a".repeat(30);
        long start = System.nanoTime();
        boolean matches = Assertions.assertTimeoutPreemptively(Duration.ofSeconds(10),
                () -> FileProcessingAsyncListener.ruleMatches(hostile, target));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        Assertions.assertFalse(matches);
        Assertions.assertTrue(elapsedMs < 8_000,
                "rule evaluation must be deadline-bounded, took " + elapsedMs + " ms");
        // The timed-out rule is now skip-cached: re-evaluation stays a silent bounded skip.
        Assertions.assertFalse(FileProcessingAsyncListener.shouldWarnSkip(hostile.getId(), hostile.getPattern()));
    }

    /**
     * A stack-exhausting pattern throws StackOverflowError — an Error, not an Exception — from
     * inside the matcher. The policy converts it, so rule evaluation still degrades to a skip
     * instead of aborting the file-processing event and its sibling rules.
     */
    @Test
    public void stackExhaustingRuleIsSkippedNotFatal() {
        TagMatchRule soe = new TagMatchRule();
        soe.setId("soe-rule");
        soe.setPattern("(x|y)*");
        Assertions.assertFalse(FileProcessingAsyncListener.ruleMatches(soe, "x".repeat(5000)));
        // Sibling evaluation continues unaffected.
        TagMatchRule valid = new TagMatchRule();
        valid.setId("soe-sibling");
        valid.setPattern("invoice");
        Assertions.assertTrue(FileProcessingAsyncListener.ruleMatches(valid, "INVOICE 2024"));
    }

    /**
     * The quarantine must gate EVALUATION, not just the warning: a rule that timed out, blew the
     * stack, or failed to compile is skipped WITHOUT re-running the matcher until its pattern
     * changes — otherwise every uploaded file still pays the full deadline per bad rule.
     */
    @Test
    public void quarantinedRuleIsNotReEvaluated() {
        TagMatchRule hostile = new TagMatchRule();
        hostile.setId("quarantine-rule");
        hostile.setPattern("(.*a){15}b");
        String target = "a".repeat(30);
        Assertions.assertFalse(FileProcessingAsyncListener.ruleMatches(hostile, target));
        long start = System.nanoTime();
        Assertions.assertFalse(FileProcessingAsyncListener.ruleMatches(hostile, target));
        long secondMs = (System.nanoTime() - start) / 1_000_000;
        Assertions.assertTrue(secondMs < 500,
                "quarantined rule must be skipped without evaluation, second call took " + secondMs + " ms");
    }

    /**
     * The skip warning fires ONCE per rule id + pattern, not once per processed file — otherwise
     * an ordinary uploader drives log amplification through a single bad persisted rule. A changed
     * pattern on the same rule re-warns.
     */
    @Test
    public void incompatiblePatternWarnsOncePerRuleNotPerFile() {
        Assertions.assertTrue(FileProcessingAsyncListener.shouldWarnSkip("warn-r1", "(a)\\1"));
        Assertions.assertFalse(FileProcessingAsyncListener.shouldWarnSkip("warn-r1", "(a)\\1"));
        Assertions.assertFalse(FileProcessingAsyncListener.shouldWarnSkip("warn-r1", "(a)\\1"));
        // Pattern edited on the same rule: warn again once.
        Assertions.assertTrue(FileProcessingAsyncListener.shouldWarnSkip("warn-r1", "(b)\\1"));
        Assertions.assertFalse(FileProcessingAsyncListener.shouldWarnSkip("warn-r1", "(b)\\1"));
        // Independent rule with the same pattern: its own single warning.
        Assertions.assertTrue(FileProcessingAsyncListener.shouldWarnSkip("warn-r2", "(a)\\1"));

        // The evaluation outcome stays a silent fail-safe skip on repeats.
        TagMatchRule bad = new TagMatchRule();
        bad.setId("warn-r3");
        bad.setPattern("[broken");
        Assertions.assertFalse(FileProcessingAsyncListener.ruleMatches(bad, "xx"));
        Assertions.assertFalse(FileProcessingAsyncListener.ruleMatches(bad, "xx"));
    }
}
