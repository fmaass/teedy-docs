package com.sismics.docs.core.util;

/**
 * The one place that decides what a tag name may contain, character by character (#305).
 *
 * <p>A name pasted from a "whitespace generator" can carry characters that do not render at all.
 * Two classes of them exist and they are answered differently, because they mean different things
 * to the person who pasted the name:
 *
 * <ul>
 *   <li><b>Invisible format characters</b> — Unicode general category {@code Cf}: zero-width space
 *       (U+200B), zero-width non-joiner (U+200C), zero-width joiner (U+200D), word joiner (U+2060),
 *       byte-order mark / zero-width no-break space (U+FEFF), soft hyphen (U+00AD), and the bidi
 *       controls. They have no visual representation of their own, so a user cannot see them, cannot
 *       type them deliberately, and cannot be asked to remove them. They are STRIPPED silently.
 *       Category {@code Cf} is the definition rather than a hand-written list of six characters: a
 *       list would be a second, drifting rule for exactly the same idea, and a generator can emit
 *       any of them. That does sweep in the Arabic and Syriac number signs (U+0600..U+0605, U+070F),
 *       which are also invisible on their own; in a tag NAME they carry nothing a search or a label
 *       can use, so removing them is the same trade as the rest of the class.</li>
 *   <li><b>Visible whitespace</b> — everything {@link Character#isSpaceChar} or
 *       {@link Character#isWhitespace} accepts: the ordinary space, the tab, the line and paragraph
 *       separators, and the exotic Zs spaces (no-break U+00A0, en U+2002, em U+2003, figure U+2007,
 *       thin U+2009, hair U+200A, narrow no-break U+202F, ideographic U+3000, …). These render as a
 *       gap, so they carry the user's visible intent. INSIDE a name they are refused, exactly as an
 *       ordinary space already is — never deleted, because deleting them would turn "Test 123"
 *       pasted with a thin space into "Test123" while the same name typed with a normal space
 *       fails, which is two different answers to one intent. On the EDGES they are trimmed, again
 *       exactly as an ordinary space already is (tag names have always been stripped before
 *       storage), so " Report " and its no-break-space twin both store "Report".</li>
 * </ul>
 *
 * <p>The two sets are disjoint by construction: no code point is both {@code Cf} and a space
 * character, so "strip then judge" has no order-dependent overlap.
 *
 * <p><b>Why this lives in docs-core as one pure function.</b> Tag synonyms (#280) will accept names
 * on a second write path. A synonym that could carry a character its tag cannot would be a name the
 * user can search for but never see, which is the whole defect this fixes. So the rule is a pure,
 * dependency-free function here rather than logic inside the REST validator, and the synonym path is
 * expected to call {@link #normalize(String)} rather than re-derive it.
 *
 * @author fmaass
 */
public final class TagNameNormalizer {

    private TagNameNormalizer() {
    }

    /**
     * What a raw name turned out to be once it was normalized.
     */
    public enum Verdict {
        /** The normalized name is usable (it may be byte-identical to the raw one). */
        OK,

        /** The name carries whitespace INSIDE it, past the trimmed edges, and must be refused. */
        VISIBLE_WHITESPACE,

        /** The name had content but normalization left nothing of it. */
        EMPTY_AFTER_NORMALIZE
    }

    /**
     * A normalized name and the verdict on it.
     *
     * @param name The normalized name: invisible format characters removed and the edges trimmed.
     *             Never null unless the caller passed null. Carries a value for every verdict, so a
     *             caller building an error message can quote what was actually left.
     * @param verdict Whether that name is usable
     */
    public record Result(String name, Verdict verdict) {
    }

    /**
     * Normalize a tag name and classify what came out. This is the WHOLE name rule, in order:
     *
     * <ol>
     *   <li>remove every invisible format character;</li>
     *   <li>TRIM leading and trailing whitespace of any class;</li>
     *   <li>refuse what is left if it still carries whitespace — that whitespace is interior, and
     *       interior whitespace is the thing a tag name may not have;</li>
     *   <li>refuse a name that had content and has none left.</li>
     * </ol>
     *
     * <p>Trimming rather than refusing the EDGES is not leniency, it is the behaviour a leading or
     * trailing ordinary space has always had: {@code ValidationUtil.validateLength} ran
     * {@code StringUtils.strip} on every tag name long before this rule existed, so " Report " has
     * always been stored as "Report". A no-break space on the edge has to reach the same outcome, or
     * the answer to "is this name allowed" would depend on which invisible variant of a space the
     * paste happened to contain. Step 2 is exactly {@code StringUtils.strip}'s job widened from
     * {@link Character#isWhitespace} to the Unicode space classes it misses.
     *
     * <p>Because this owns the whole rule, it must run BEFORE any length bound: the length that
     * matters is the length of the name that will be stored, so a 36-character name carrying a
     * zero-width character is 36 characters, not 37.
     *
     * <p>A null name means "the caller is not setting a name" (a colour- or parent-only tag update),
     * and an empty one means the same on the update path — neither is a name that became empty, so
     * both pass through as {@link Verdict#OK}. {@link Verdict#EMPTY_AFTER_NORMALIZE} is reserved for
     * a name that HAD content and lost all of it, which is a different thing to tell the user.
     *
     * @param rawName The name as submitted, or null
     * @return The normalized name and its verdict
     */
    public static Result normalize(String rawName) {
        if (rawName == null) {
            return new Result(null, Verdict.OK);
        }
        String normalized = trimWhitespace(stripInvisibleFormat(rawName));
        if (containsVisibleWhitespace(normalized)) {
            return new Result(normalized, Verdict.VISIBLE_WHITESPACE);
        }
        if (normalized.isEmpty() && !rawName.isEmpty()) {
            return new Result(normalized, Verdict.EMPTY_AFTER_NORMALIZE);
        }
        return new Result(normalized, Verdict.OK);
    }

    /**
     * Remove leading and trailing whitespace of every class — {@code StringUtils.strip} widened from
     * {@link Character#isWhitespace} to the Unicode space classes it does not accept (the no-break
     * spaces U+00A0, U+2007 and U+202F above all, which are precisely the ones a paste carries).
     * Iterates by code point so a supplementary character on the edge is never half-cut.
     *
     * @param value Value to trim, or null
     * @return The value without edge whitespace, or null for null
     */
    public static String trimWhitespace(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        int start = 0;
        int end = value.length();
        while (start < end) {
            int codePoint = value.codePointAt(start);
            if (!isVisibleWhitespace(codePoint)) {
                break;
            }
            start += Character.charCount(codePoint);
        }
        while (end > start) {
            int codePoint = value.codePointBefore(end);
            if (!isVisibleWhitespace(codePoint)) {
                break;
            }
            end -= Character.charCount(codePoint);
        }
        return start == 0 && end == value.length() ? value : value.substring(start, end);
    }

    /**
     * Remove every invisible format character (Unicode category {@code Cf}), leaving visible
     * whitespace in place for {@link #normalize(String)} to judge.
     *
     * @param value Value to strip, or null
     * @return The value without its invisible format characters, or null for null
     */
    public static String stripInvisibleFormat(String value) {
        return strip(value, TagNameNormalizer::isInvisibleFormat);
    }

    /**
     * Remove EVERY whitespace class — the invisible format characters AND the visible whitespace.
     *
     * <p>This is the repair rule for names ALREADY STORED, not the rule for new input: an existing
     * name cannot be handed back to its author for correction, and refusing it at rest is not an
     * option, so the only truthful outcome is the name without its whitespace. New input goes
     * through {@link #normalize(String)}, which refuses visible whitespace instead of deleting it.
     *
     * @param value Value to strip, or null
     * @return The value without any whitespace or invisible format character, or null for null
     */
    public static String stripAllWhitespace(String value) {
        return strip(value, codePoint -> isInvisibleFormat(codePoint) || isVisibleWhitespace(codePoint));
    }

    /**
     * Whether a code point is an invisible format character (Unicode general category {@code Cf}).
     *
     * @param codePoint Code point to test
     * @return True if it renders as nothing and is stripped silently
     */
    public static boolean isInvisibleFormat(int codePoint) {
        return Character.getType(codePoint) == Character.FORMAT;
    }

    /**
     * Whether a code point is a whitespace character that occupies visible width — the ordinary
     * space, the tab, the line/paragraph separators, and every exotic Unicode space.
     *
     * <p>Both tests are needed and neither subsumes the other: {@link Character#isWhitespace} rejects
     * the no-break spaces (U+00A0, U+2007, U+202F) because they are non-breaking, while
     * {@link Character#isSpaceChar} rejects the tab and the other control whitespace because they
     * are not category Z.
     *
     * @param codePoint Code point to test
     * @return True if it is whitespace a user can see the effect of
     */
    public static boolean isVisibleWhitespace(int codePoint) {
        return Character.isSpaceChar(codePoint) || Character.isWhitespace(codePoint);
    }

    /**
     * Whether the value carries any visible whitespace.
     *
     * @param value Value to test, or null
     * @return True if at least one code point is visible whitespace
     */
    public static boolean containsVisibleWhitespace(String value) {
        return value != null && value.codePoints().anyMatch(TagNameNormalizer::isVisibleWhitespace);
    }

    /**
     * Remove every code point the predicate accepts. Iterates by CODE POINT, not by char, so a
     * character outside the basic plane is never split into a lone surrogate.
     */
    private static String strip(String value, java.util.function.IntPredicate removable) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        StringBuilder sb = new StringBuilder(value.length());
        value.codePoints().forEach(codePoint -> {
            if (!removable.test(codePoint)) {
                sb.appendCodePoint(codePoint);
            }
        });
        return sb.length() == value.length() ? value : sb.toString();
    }
}
