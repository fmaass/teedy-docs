package com.sismics.docs.core.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Single regex policy for user-authored matching rules (tag-match rules).
 *
 * <p>Every place that compiles a rule pattern — the admin add/update/test endpoints AND the
 * runtime evaluation of persisted rules — must go through this class, and only this class, so the
 * accepted dialect and the evaluation bounds are decided in exactly one place.
 *
 * <p>The engine is deliberately {@code java.util.regex}: rules have always been authored and
 * evaluated in that dialect, so keeping it preserves historical semantics BY CONSTRUCTION — no
 * engine-translation layer, no dialect-divergence class (class intersection, {@code \Q..\E}
 * regions, bare-dot line-terminator behavior, case-folding tables, anchor placement) to chase.
 *
 * <p>The backtracking-DoS risk of that engine is contained by BOUNDING instead of substitution:
 * <ul>
 * <li><b>Admission</b> ({@link #validate}): the pattern must compile and is length-capped
 *     ({@value #MAX_PATTERN_LENGTH} chars).</li>
 * <li><b>Evaluation</b> ({@link #find}): the evaluated text is capped to the first
 *     {@value #MAX_EVALUATED_LENGTH} characters, and matching runs against a deadline of
 *     {@value #EVALUATION_DEADLINE_MS} ms enforced by a {@link CharSequence} wrapper that aborts
 *     the match from inside {@code charAt} — catastrophic backtracking cannot run away.</li>
 * <li><b>Fail safe</b>: a timeout ({@link EvaluationTimeoutException}) or an invalid persisted
 *     pattern surfaces as an exception; rule-evaluation callers treat both as "rule skipped"
 *     (see {@code FileProcessingAsyncListener}, which also bounds the skip logging).</li>
 * </ul>
 *
 * @author fmaass
 */
public final class RegexRulePolicy {

    /**
     * Maximum admitted pattern length. Bounds pattern-compilation cost and keeps the worst-case
     * state space of a single rule small; generous versus every documented rule example.
     */
    public static final int MAX_PATTERN_LENGTH = 1000;

    /**
     * Only this many leading characters of the target text are evaluated. Rule targets are titles,
     * filenames and extracted content; half a megabyte of leading text is far beyond any sane
     * matching prefix while bounding the per-rule work on huge OCR extractions.
     */
    public static final int MAX_EVALUATED_LENGTH = 512 * 1024;

    /**
     * Per-evaluation wall-clock deadline.
     */
    public static final long EVALUATION_DEADLINE_MS = 2_000;

    private RegexRulePolicy() {
    }

    /**
     * Thrown when a rule evaluation had to be aborted (deadline exceeded, or the backtracking
     * matcher exhausted its stack). Callers evaluating persisted rules must treat it as
     * "rule skipped"; the test endpoint reports it as a validation error.
     */
    public static class EvaluationAbortedException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public EvaluationAbortedException(String message) {
            super(message);
        }
    }

    /**
     * Thrown when a rule evaluation exceeds {@link #EVALUATION_DEADLINE_MS}.
     */
    public static class EvaluationTimeoutException extends EvaluationAbortedException {
        private static final long serialVersionUID = 1L;

        public EvaluationTimeoutException(String message) {
            super(message);
        }
    }

    /**
     * Validate a rule pattern for admission, returning a human-readable rejection reason.
     *
     * @param pattern Rule pattern
     * @return {@code null} when the pattern is accepted, otherwise the rejection description
     */
    public static String validate(String pattern) {
        if (pattern.length() > MAX_PATTERN_LENGTH) {
            return "pattern exceeds the maximum length of " + MAX_PATTERN_LENGTH + " characters";
        }
        try {
            Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);
            return null;
        } catch (PatternSyntaxException e) {
            return e.getDescription();
        }
    }

    /**
     * Evaluate a rule pattern against a target text (case-insensitive find, bounded).
     *
     * @param pattern Rule pattern
     * @param text Target text
     * @return True when the pattern occurs in the evaluated prefix of the text
     * @throws PatternSyntaxException When the pattern is invalid
     * @throws EvaluationAbortedException When evaluation exceeds the deadline or exhausts the
     *         matcher stack
     */
    public static boolean find(String pattern, String text) {
        CharSequence bounded = text.length() > MAX_EVALUATED_LENGTH
                ? text.subSequence(0, MAX_EVALUATED_LENGTH)
                : text;
        long deadlineNanos = System.nanoTime() + EVALUATION_DEADLINE_MS * 1_000_000;
        Matcher matcher = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE)
                .matcher(new DeadlineCharSequence(bounded, deadlineNanos));
        try {
            return matcher.find();
        } catch (StackOverflowError e) {
            // The recursive matcher can exhaust its stack in milliseconds on adversarial
            // pattern/input combinations — an Error that would sail past the callers'
            // catch(Exception) fail-safes. The catch is deliberately tight around the match
            // call: a genuine VM-level stack overflow anywhere else still propagates.
            throw new EvaluationAbortedException("Rule evaluation exhausted the matcher stack");
        }
    }

    /**
     * CharSequence that aborts an in-flight match once a deadline passes. The matcher reads every
     * character it examines through {@code charAt}, so a backtracking blow-up — which by nature
     * re-reads characters — hits the deadline check no matter how the engine explores the state
     * space. The check runs every 1024 reads to keep the fast path cheap.
     */
    private static final class DeadlineCharSequence implements CharSequence {
        private final CharSequence inner;
        private final long deadlineNanos;
        private int reads;

        private DeadlineCharSequence(CharSequence inner, long deadlineNanos) {
            this.inner = inner;
            this.deadlineNanos = deadlineNanos;
        }

        @Override
        public char charAt(int index) {
            if ((++reads & 0x3FF) == 0 && System.nanoTime() > deadlineNanos) {
                throw new EvaluationTimeoutException(
                        "Rule evaluation exceeded " + EVALUATION_DEADLINE_MS + " ms");
            }
            return inner.charAt(index);
        }

        @Override
        public int length() {
            return inner.length();
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new DeadlineCharSequence(inner.subSequence(start, end), deadlineNanos);
        }

        @Override
        public String toString() {
            return inner.toString();
        }
    }
}
