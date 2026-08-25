package com.sismics.rest.util;

import com.google.common.base.Strings;
import com.sismics.docs.core.util.TagNameNormalizer;
import com.sismics.rest.exception.ClientException;
import org.apache.commons.lang3.StringUtils;

import java.text.MessageFormat;
import java.time.Instant;
import java.util.Date;
import java.util.regex.Pattern;

/**
 * Utility class to validate parameters.
 *
 * @author jtremeaux
 */
public class ValidationUtil {
    private static Pattern EMAIL_PATTERN = Pattern.compile(".+@.+");
    
    private static Pattern HTTP_URL_PATTERN = Pattern.compile("https?://.+");
    
    private static Pattern ALPHANUMERIC_PATTERN = Pattern.compile("[a-zA-Z0-9_]+");
    
    private static Pattern USERNAME_PATTERN = Pattern.compile("[a-zA-Z0-9_@.-]+");

    private static Pattern HEX_COLOR_PATTERN = Pattern.compile("#[0-9a-fA-F]{6}");

    /**
     * The one refusal message for a tag name carrying a character the search grammar owns. Held as a
     * constant rather than written twice so the guarantee is structural: an exotic space (#305) gets
     * the SAME answer as an ordinary one, and the two cannot drift apart in a later edit.
     */
    private static final String ILLEGAL_TAG_NAME_MESSAGE =
            "Spaces, colons and asterisks are not allowed in tag name";
    
    /**
     * Checks that the argument is not null.
     * 
     * @param s Object tu validate
     * @param name Name of the parameter
     * @throws ClientException
     */
    public static void validateRequired(Object s, String name) throws ClientException {
        if (s == null) {
            throw new ClientException("ValidationError", MessageFormat.format("{0} must be set", name));
        }
    }
    
    /**
     * Validate a string length.
     * 
     * @param s String to validate
     * @param name Name of the parameter
     * @param lengthMin Minimum length (or null)
     * @param lengthMax Maximum length (or null)
     * @param nullable True if the string can be empty or null
     * @return String without white spaces
     * @throws ClientException
     */
    public static String validateLength(String s, String name, Integer lengthMin, Integer lengthMax, boolean nullable) throws ClientException {
        s = StringUtils.strip(s);
        if (nullable && StringUtils.isEmpty(s)) {
            return s;
        }
        if (s == null) {
            throw new ClientException("ValidationError", MessageFormat.format("{0} must be set", name));
        }
        if (lengthMin != null && s.length() < lengthMin) {
            throw new ClientException("ValidationError", MessageFormat.format("{0} must be more than {1} characters", name, lengthMin));
        }
        if (lengthMax != null && s.length() > lengthMax) {
            throw new ClientException("ValidationError", MessageFormat.format("{0} must be less than {1} characters", name, lengthMax));
        }
        return s;
    }
    
    /**
     * Validate a string length. The string mustn't be empty.
     * 
     * @param s String to validate
     * @param name Name of the parameter
     * @param lengthMin Minimum length (or null)
     * @param lengthMax Maximum length (or null)
     * @return String without white spaces
     * @throws ClientException
     */
    public static String validateLength(String s, String name, Integer lengthMin, Integer lengthMax) throws ClientException {
        return validateLength(s, name, lengthMin, lengthMax, false);
    }
    
    /**
     * Checks if the string is not null and is not only whitespaces.
     * 
     * @param s String to validate
     * @param name Name of the parameter
     * @return String without white spaces
     * @throws ClientException
     */
    public static String validateStringNotBlank(String s, String name) throws ClientException {
        return validateLength(s, name, 1, null, false);
    }
    
    /**
     * Checks if the string is an email.
     * 
     * @param s String to validate
     * @param name Name of the parameter
     * @throws ClientException
     */
    public static void validateEmail(String s, String name) throws ClientException {
        if (!EMAIL_PATTERN.matcher(s).matches()) {
            throw new ClientException("ValidationError", MessageFormat.format("{0} must be an email", name));
        }
    }
    
    /**
     * Checks if the string is a hexadecimal color (#rrggbb).
     *
     * The length check alone is not the contract: every caller stores this value verbatim and it is
     * then rendered as a CSS colour (the tag chip's background, the theme's navbar rule) or derived
     * into a palette. "#gggggg" is seven characters, so a length-only check accepted and persisted
     * it, leaving a configuration that claims a colour the UI cannot render.
     *
     * The pattern is matched against the RAW argument rather than the stripped one: callers keep
     * the value they passed in, so accepting " #ff0000 " here would persist the padding.
     *
     * @param s String to validate
     * @param name Name of the parameter
     * @param nullable True if the string can be empty or null
     */
    public static void validateHexColor(String s, String name, boolean nullable) throws ClientException {
        String value = ValidationUtil.validateLength(s, name, 7, 7, nullable);
        if (Strings.isNullOrEmpty(value)) {
            return;
        }
        if (!isHexColor(s)) {
            throw new ClientException("ValidationError",
                    MessageFormat.format("{0} must be a hexadecimal color, for example #336699", name));
        }
    }

    /**
     * Whether a string IS a hexadecimal color (#rrggbb) — the same test {@link #validateHexColor}
     * rejects on, as a plain predicate.
     *
     * A caller that has to decide whether an ALREADY STORED value is usable needs the answer, not
     * a rejection: strict validation of a field is only ever as old as the release that added it,
     * so a row written by an earlier version can hold something this returns false for. Such a
     * caller cannot reuse the validator itself, because a {@link ClientException} carries a
     * BAD_REQUEST response — building and discarding one inside a request that is SUCCEEDING is
     * both wasteful and one stray rethrow away from failing that request.
     *
     * Matched against the RAW argument, exactly as the validator does: padding is not a color.
     *
     * @param s String to test
     * @return True if the string is a hexadecimal color
     */
    public static boolean isHexColor(String s) {
        return s != null && HEX_COLOR_PATTERN.matcher(s).matches();
    }

    /**
     * Validate a tag name and return the name to store.
     *
     * <p>The rejected characters are the ones the document search grammar owns: it splits a query
     * on spaces, separates a criteria from its value on a colon, and reads an asterisk in a tag
     * term as a wildcard standing for any run of characters. A name carrying one of them could not
     * be searched for unambiguously.
     *
     * <p>#305: "a space" is not only U+0020. A name can be pasted carrying any of Unicode's other
     * whitespace characters, and carrying invisible format characters that render as nothing at all.
     * {@link TagNameNormalizer} owns the whole name rule — strip the invisible characters, trim the
     * edges, refuse what is left if it still carries whitespace — and this method is the place that
     * turns its verdict into the {@code IllegalTagName} the ordinary space has always produced,
     * because a thin space is a space. The space check is not a separate condition any more: U+0020
     * is simply the most common member of the class the normalizer refuses.
     *
     * <p>Because names are rewritten rather than merely judged, this returns the name to store and
     * the caller MUST use the returned value — persisting the argument instead would store exactly
     * the characters this removed.
     *
     * <p><b>Call this BEFORE {@link #validateLength}, not after.</b> The length bound has to be
     * measured on the name that will be stored: a 36-character name carrying a zero-width character
     * is 36 characters, and checking the raw argument would refuse it as overlength over a character
     * the user cannot see. Running normalization first also means the edge-trimming is done here
     * rather than falling out of {@code validateLength}'s {@code StringUtils.strip} as a side effect
     * — one rule in one place, instead of a contract that depends on the order two validators happen
     * to be called in.
     *
     * <p>A null name means the caller is not changing the name (a colour- or parent-only tag
     * update), so there is nothing to validate; an empty one means the same on the update path. Both
     * come back unchanged.
     *
     * @param name Name of the tag, or null when the name is left unchanged
     * @return The name to store: the argument with its invisible format characters removed
     */
    public static String validateTagName(String name) throws ClientException {
        TagNameNormalizer.Result result = TagNameNormalizer.normalize(name);
        switch (result.verdict()) {
            case VISIBLE_WHITESPACE -> throw new ClientException("IllegalTagName", ILLEGAL_TAG_NAME_MESSAGE);
            case EMPTY_AFTER_NORMALIZE -> throw new ClientException("IllegalTagName",
                    "Tag name must contain at least one visible character");
            case OK -> {
                // Fall through to the search-grammar characters below.
            }
        }
        String normalized = result.name();
        if (normalized != null && (normalized.contains(":") || normalized.contains("*"))) {
            throw new ClientException("IllegalTagName", ILLEGAL_TAG_NAME_MESSAGE);
        }
        return normalized;
    }

    /**
     * Validates that the provided string matches an URL with HTTP or HTTPS scheme.
     * 
     * @param s String to validate
     * @param name Name of the parameter
     * @return Stripped URL
     * @throws ClientException
     */
    public static String validateHttpUrl(String s, String name) throws ClientException {
        s = StringUtils.strip(s);
        if (!HTTP_URL_PATTERN.matcher(s).matches()) {
            throw new ClientException("ValidationError", MessageFormat.format("{0} must be an HTTP(s) URL", name));
        }
        return s;
    }
    
    /**
     * Checks if the string uses only alphanumerical or underscore characters.
     * 
     * @param s String to validate
     * @param name Name of the parameter
     * @throws ClientException
     */
    public static void validateAlphanumeric(String s, String name) throws ClientException {
        if (!ALPHANUMERIC_PATTERN.matcher(s).matches()) {
            throw new ClientException("ValidationError", MessageFormat.format("{0} must have only alphanumeric or underscore characters", name));
        }
    }
    
    /**
     * Validates that a user-supplied file name is a single safe path segment: it must not contain a
     * path separator ({@code /} or {@code \}), a NUL, or any control character. Such characters are
     * REJECTED (not silently rewritten) so a rename can never introduce a traversal or a name that would
     * later escape an archive/extraction directory. Shared so any future rename endpoint validates
     * identically and cannot bypass the guard. The length bounds stay with the caller's own
     * {@link #validateLength} check.
     *
     * @param s File name to validate
     * @param name Name of the parameter (for the error message)
     * @throws ClientException if the name contains a separator, NUL, or control character
     */
    public static void validateFileName(String s, String name) throws ClientException {
        if (s == null) {
            return;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '/' || c == '\\' || c == '\0' || Character.isISOControl(c)) {
                throw new ClientException("ValidationError",
                        MessageFormat.format("{0} must not contain path separators or control characters", name));
            }
        }
    }

    public static void validateUsername(String s, String name) throws ClientException {
        if (!USERNAME_PATTERN.matcher(s).matches()) {
            throw new ClientException("ValidationError", MessageFormat.format("{0} must have only alphanumeric, underscore characters or @ and .", name));
        }
    }
    
    public static void validateRegex(String s, String name, String regex) throws ClientException {
        if (!Pattern.compile(regex).matcher(s).matches()) {
            throw new ClientException("ValidationError", MessageFormat.format("{0} must match {1}", name, regex));
        }
    }
    
    /**
     * Checks if the string is a number.
     * 
     * @param s String to validate
     * @param name Name of the parameter
     * @return Parsed number
     * @throws ClientException
     */
    public static Integer validateInteger(String s, String name) throws ClientException {
        try {
            return Integer.valueOf(s);
        } catch (NumberFormatException e) {
            throw new ClientException("ValidationError", MessageFormat.format("{0} is not a number", name));
        }
    }
    
    /**
     * Checks if the string is a number.
     * 
     * @param s String to validate
     * @param name Name of the parameter
     * @return Parsed number
     * @throws ClientException
     */
    public static Long validateLong(String s, String name) throws ClientException {
        try {
            return Long.valueOf(s);
        } catch (NumberFormatException e) {
            throw new ClientException("ValidationError", MessageFormat.format("{0} is not a number", name));
        }
    }
    
    /**
     * Validates that a parsed number is not negative.
     *
     * @param value Value to validate
     * @param name Name of the parameter
     * @throws ClientException if the value is negative
     */
    public static void validateNonNegative(long value, String name) throws ClientException {
        if (value < 0) {
            throw new ClientException("ValidationError", MessageFormat.format("{0} must not be negative", name));
        }
    }

    /**
     * Validates and parses a date.
     * 
     * @param s String to validate
     * @param name Name of the parameter
     * @param nullable True if the string can be empty or null
     * @return Parsed date
     * @throws ClientException
     */
    public static Date validateDate(String s, String name, boolean nullable) throws ClientException {
        if (Strings.isNullOrEmpty(s)) {
            if (!nullable) {
                throw new ClientException("ValidationError", MessageFormat.format("{0} must be set", name));
            } else {
                return null;
            }
        }
        try {
            return Date.from(Instant.ofEpochMilli(Long.parseLong(s)));
        } catch (NumberFormatException e) {
            throw new ClientException("ValidationError", MessageFormat.format("{0} must be a date", name));
        }
    }

    /**
     * Validates password strength: 8+ chars, at least one uppercase, one lowercase, one digit.
     * Rejects passwords matching the username (case-insensitive).
     *
     * @param password Password to validate
     * @param username Username to compare against
     */
    public static void validatePasswordStrength(String password, String username) {
        if (password == null || password.length() < 8) {
            throw new ClientException("ValidationError", "Password must be at least 8 characters");
        }
        boolean hasUpper = false, hasLower = false, hasDigit = false;
        for (char c : password.toCharArray()) {
            if (Character.isUpperCase(c)) hasUpper = true;
            else if (Character.isLowerCase(c)) hasLower = true;
            else if (Character.isDigit(c)) hasDigit = true;
        }
        if (!hasUpper || !hasLower || !hasDigit) {
            throw new ClientException("ValidationError", "Password must contain at least one uppercase letter, one lowercase letter, and one digit");
        }
        if (username != null && password.equalsIgnoreCase(username)) {
            throw new ClientException("ValidationError", "Password must not match the username");
        }
    }
}
