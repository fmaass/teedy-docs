package com.sismics.docs.core.util;

import java.io.IOException;
import java.nio.file.Path;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sismics.util.mime.MimeType;

/**
 * (#197) Attaching the RAW RFC822 message of an imported email to its document.
 *
 * <p>Shared by both ingresses — the IMAP importer and {@code PUT /document/eml} — so the file name, the
 * MIME typing and the quota behaviour of the raw copy are identical whichever way the mail arrived. The
 * CAPTURE of those bytes is deliberately NOT shared: the importer streams them off the IMAP server,
 * while the upload ingress attaches the exact bytes the user submitted (re-serializing them would change
 * header folding and line endings, breaking DKIM verification and any external hash of the message).</p>
 *
 * @author teedy
 */
public class EmlAttachmentUtil {
    /**
     * Logger.
     */
    private static final Logger log = LoggerFactory.getLogger(EmlAttachmentUtil.class);

    /**
     * The message {@link IOException} {@code reserveStorage} signals an exceeded per-user or global quota
     * with. Matching on it keeps the catch below narrow: any OTHER failure of the raw attach is a real
     * error and still fails the import.
     */
    private static final String QUOTA_REACHED = "QuotaReached";

    /**
     * File name for a message with no usable subject.
     */
    private static final String FALLBACK_NAME = "message";

    /**
     * Maximum length of the subject-derived part of the file name.
     */
    private static final int SUBJECT_MAX_LENGTH = 60;

    /**
     * Build the file name for a raw message: the mail's subject, sanitized, plus the {@code .eml}
     * extension. Deterministic — the same subject always yields the same name — and duplicates are
     * allowed (two mails may legitimately share a subject; the ZIP export de-collides names on the way
     * out).
     *
     * <p>Sanitizing is a boundary guard, not cosmetics: the value comes from an unauthenticated mail
     * header, and it is stored, echoed to clients and used as the download file name. Path separators
     * would let a subject suggest a directory traversal, and control characters (including the CR/LF a
     * crafted subject can carry) would let it inject line breaks into headers and logs. Both classes
     * collapse to {@code _}. A subject that sanitizes away to nothing falls back to {@code message}.</p>
     *
     * @param subject Mail subject, may be null
     * @return File name ending in {@code .eml}
     */
    public static String fileName(String subject) {
        String sanitized = sanitize(subject);
        if (StringUtils.isBlank(sanitized)) {
            sanitized = FALLBACK_NAME;
        }
        return StringUtils.abbreviate(sanitized, SUBJECT_MAX_LENGTH) + ".eml";
    }

    /**
     * Attach the raw message to a document as the LAST file, typed {@code message/rfc822}.
     *
     * <p>Attaching last, and catching ONLY this file's quota rejection, is what makes the raw copy
     * strictly additive: every mail that imported before #197 still imports, because the extracted
     * attachments claim their headroom first and unchanged. When the raw copy does not fit, the import
     * completes without it — for the IMAP ingress that is the difference between a mail that is acked
     * once and a mail that fails, stays UNSEEN and re-drives the poller every minute forever.</p>
     *
     * <p>A quota rejection is an application-level comparison made AFTER a successful {@code SELECT ...
     * FOR UPDATE}, not a failed statement, so catching it leaves the transaction usable on PostgreSQL
     * too (unlike the constraint violations the importer deliberately lets escape).</p>
     *
     * @param subject Mail subject the file name is derived from, may be null
     * @param rawFile Plaintext temp file holding the raw message
     * @param rawSize Size of the raw message in bytes
     * @param language Language of the document
     * @param userId User the document belongs to (and whose quota is charged)
     * @param documentId Document to attach to
     * @return true if the file was created — ownership of {@code rawFile} has passed to the queued
     *         processing event; false if it was skipped for quota, leaving the caller its owner
     * @throws Exception any non-quota failure of the attach
     */
    public static boolean attachRawMessage(String subject, Path rawFile, long rawSize, String language,
                                           String userId, String documentId) throws Exception {
        try {
            FileUtil.createFileWithMimeType(fileName(subject), rawFile, rawSize, language, userId, documentId,
                    MimeType.MESSAGE_RFC822);
            return true;
        } catch (IOException e) {
            if (!QUOTA_REACHED.equals(e.getMessage())) {
                throw e;
            }
            log.warn("The raw message of document " + documentId + " (" + rawSize
                    + " bytes) does not fit in the storage quota; importing it without the .eml file");
            return false;
        }
    }

    /**
     * Replace every path separator and control character with an underscore.
     *
     * @param value Value to sanitize, may be null
     * @return Sanitized value, trimmed; null becomes an empty string
     */
    private static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '/' || c == '\\' || Character.isISOControl(c)) {
                builder.append('_');
            } else {
                builder.append(c);
            }
        }
        // A name of "." or ".." is a directory reference rather than a file name.
        String trimmed = builder.toString().trim();
        return StringUtils.containsOnly(trimmed, '.') ? "" : trimmed;
    }
}
