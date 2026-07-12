package com.sismics.docs.core.util;

import org.owasp.html.AttributePolicy;
import org.owasp.html.HtmlPolicyBuilder;
import org.owasp.html.PolicyFactory;

import java.util.regex.Pattern;

/**
 * The single server-side sanitization chokepoint for document descriptions.
 *
 * <p>Document descriptions became rich HTML in v3.5.0 (authored with a restricted Quill
 * editor). Every description writer routes its value through {@link #sanitize(String)}
 * before it reaches persistence, so stored descriptions are trusted HTML regardless of
 * the ingress (REST create/update, EML import, inbox import) or the client (the editor,
 * a raw form-urlencoded API call, or a hostile payload). Render-side DOMPurify remains a
 * permanent defence-in-depth layer, but the stored-content invariant lives here.
 *
 * <p>The allowlist is matched to the exact serializer output of the enabled Quill
 * formats (headings, bold/italic/underline/strike, ordered/unordered lists, links,
 * blockquote, code-block) — see docs-core/src/test/resources/description-fixtures, which
 * are generated from the real Quill build and round-tripped by TestDescriptionSanitizer.
 * Anything outside the allowlist (scripts, styles, iframes, event handlers, non
 * http/https/mailto URLs, protocol-relative {@code //host} links) is stripped. No inline
 * style attributes are permitted because the enabled formats emit none.
 */
public class DescriptionSanitizer {
    /**
     * Only these URL protocols are allowed on links; everything else (javascript:,
     * data:, vbscript:, file:, …) is dropped by the policy.
     */
    private static final String[] ALLOWED_URL_PROTOCOLS = {"http", "https", "mailto"};

    /**
     * Quill code blocks carry a {@code data-language} hint (e.g. {@code data-language="plain"}).
     * Restrict it to a conservative identifier token so it can never carry markup.
     */
    private static final Pattern DATA_LANGUAGE_PATTERN = Pattern.compile("[a-zA-Z0-9_+-]{1,32}");

    /**
     * Drops a protocol-relative href ({@code //host/path}). OWASP's default URL policy
     * treats a scheme-less URL as relative and allows it, but a protocol-relative URL is
     * an off-site navigation vector that the pinned contract (http/https/mailto only) does
     * not permit. Returning null removes the attribute; a non-{@code //} value passes
     * through to the protocol allowlist unchanged.
     */
    private static final AttributePolicy REJECT_PROTOCOL_RELATIVE = (elementName, attributeName, value) -> {
        if (value == null) {
            return null;
        }
        // Strip the leading control/whitespace the URL policy also ignores before checking.
        String trimmed = value.replaceFirst("^[\\s\\u0000-\\u0020]+", "");
        return trimmed.startsWith("//") ? null : value;
    };

    private static final PolicyFactory POLICY = new HtmlPolicyBuilder()
            // Block + inline structural elements the enabled Quill formats emit.
            .allowElements(
                    "p", "br",
                    "h1", "h2", "h3", "h4", "h5", "h6",
                    "strong", "b", "em", "i", "u", "s", "strike",
                    "ol", "ul", "li",
                    "blockquote",
                    "pre", "code",
                    "a")
            // Links: http/https/mailto only (protocol-relative // rejected), forced
            // rel=noopener noreferrer on target=_blank links.
            .allowAttributes("href").matching(REJECT_PROTOCOL_RELATIVE).onElements("a")
            .allowAttributes("target").matching(false, "_blank").onElements("a")
            .allowUrlProtocols(ALLOWED_URL_PROTOCOLS)
            .requireRelsOnLinks("noopener", "noreferrer")
            .allowAttributes("data-language").matching(DATA_LANGUAGE_PATTERN).onElements("pre")
            .toFactory();

    private DescriptionSanitizer() {
    }

    /**
     * Sanitize a description value to the allowlist. Idempotent: sanitizing already-clean
     * HTML returns equivalent HTML, so the entity-boundary guard can re-apply it safely.
     * A null input returns null (a document may legitimately have no description); a
     * plain-text (markup-free) description passes through unchanged apart from HTML
     * entity encoding of any bare {@code < > &}.
     *
     * @param html Raw description HTML (or plain text, or null)
     * @return Sanitized HTML safe to store and render, or null if the input was null
     */
    public static String sanitize(String html) {
        if (html == null) {
            return null;
        }
        return POLICY.sanitize(html);
    }
}
