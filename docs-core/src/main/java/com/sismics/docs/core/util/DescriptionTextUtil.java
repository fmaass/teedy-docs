package com.sismics.docs.core.util;

import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;

/**
 * Projects a rich-HTML document description down to plain text.
 *
 * <p>Descriptions are stored as sanitized HTML (see {@link DescriptionSanitizer}). Two
 * consumers need the text without markup and share this one projection so they never
 * diverge: the Lucene index (markup must not pollute search — a word inside a tag must be
 * findable, an element name like {@code script} must not be) and the PDF export (the
 * metadata page renders literal text, not HTML).
 */
public class DescriptionTextUtil {
    private DescriptionTextUtil() {
    }

    /**
     * Strip all markup from a description, returning collapsed plain text.
     *
     * @param html Description HTML (or plain text, or null)
     * @return Tag-free, whitespace-collapsed text; empty string for null/blank input
     */
    public static String toPlainText(String html) {
        if (StringUtils.isBlank(html)) {
            return "";
        }
        // Jsoup.parse(...).text() drops every element and attribute, leaving only the
        // readable text content with runs of whitespace collapsed to single spaces.
        return Jsoup.parse(html).text();
    }

    /**
     * Strip markup and cap the result at a character bound, appending an ellipsis when
     * truncated. Used by the PDF export, whose metadata page does not paginate and would
     * overflow on a long rich description.
     *
     * @param html Description HTML (or plain text, or null)
     * @param maxLength Maximum length of the returned string including the ellipsis
     * @return Capped plain text
     */
    public static String toPlainText(String html, int maxLength) {
        String text = toPlainText(html);
        if (text.length() <= maxLength) {
            return text;
        }
        String ellipsis = "…";
        if (maxLength <= ellipsis.length()) {
            return ellipsis.substring(0, maxLength);
        }
        return text.substring(0, maxLength - ellipsis.length()) + ellipsis;
    }
}
