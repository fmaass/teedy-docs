package com.sismics.docs.core.constant;

/**
 * Configuration parameters.
 *
 * @author jtremeaux
 */
public enum ConfigType {
    /**
     * Lucene directory storage type.
     */
    LUCENE_DIRECTORY_STORAGE,
    /**
     * Theme configuration.
     */
    THEME,

    /**
     * Guest login.
     */
    GUEST_LOGIN,

    /**
     * OCR enabled.
     */
    OCR_ENABLED,

    /**
     * Default language.
     */
    DEFAULT_LANGUAGE,

    /**
     * SMTP server configuration.
     */
    SMTP_HOSTNAME,
    SMTP_PORT,
    SMTP_FROM,
    SMTP_USERNAME,
    SMTP_PASSWORD,

    /**
     * Inbox scanning configuration.
     */
    INBOX_ENABLED,
    INBOX_HOSTNAME,
    INBOX_PORT,
    INBOX_STARTTLS,
    INBOX_USERNAME,
    INBOX_PASSWORD,
    INBOX_FOLDER,
    INBOX_TAG,
    INBOX_AUTOMATIC_TAGS,
    INBOX_DELETE_IMPORTED,

    /**
     * Explicit operator acknowledgement that teedy EXCLUSIVELY owns the configured import folder (a
     * dedicated mailbox). Only when {@code true} may the non-UIDPLUS fallback issue a generic folder-wide
     * {@code EXPUNGE}, which finalizes EVERY {@code \Deleted} message in the folder — including one another
     * IMAP client marked. Default {@code false}: on a server without UIDPLUS the batch expunge is SKIPPED
     * (the imported+deleted messages are re-seen and receipt-deduped next cycle, no data loss) rather than
     * risk finalizing another client's deletions. Ignored when the server supports UIDPLUS — a targeted
     * {@code UID EXPUNGE} removes only teedy's messages regardless.
     */
    INBOX_DEDICATED_FOLDER,

    /**
     * Persisted baseline of the inbox source's accepted UIDVALIDITY epoch, stored as
     * {@code <sourceIdentityDigest>:<uidValidity>}. On each sync the current folder UIDVALIDITY is
     * compared with this baseline for the SAME source: a change means the mailbox was recreated and
     * every UID was renumbered, so the source is blocked (no import, no flag change) until an operator
     * re-saves the inbox configuration, which clears this value and re-establishes the baseline.
     */
    INBOX_UIDVALIDITY,

    /**
     * LDAP connection.
     */
    LDAP_ENABLED,
    LDAP_HOST,
    LDAP_PORT,
    LDAP_USESSL,
    LDAP_ADMIN_DN,
    LDAP_ADMIN_PASSWORD,
    LDAP_BASE_DN,
    LDAP_FILTER,
    LDAP_DEFAULT_EMAIL,
    LDAP_DEFAULT_STORAGE,

    /**
     * Tag search mode: "PREFIX" (default, case-insensitive startsWith)
     * or "EXACT" (case-insensitive equals).
     */
    TAG_SEARCH_MODE,

    /**
     * Configurable footer/imprint links, stored as a JSON array of
     * {label, url} objects (validated server-side: <= 5 entries, http(s) only).
     */
    FOOTER_LINKS,

    /**
     * OIDC (OpenID Connect) authentication configuration. Per-field entries mirror the
     * LDAP pattern so the client secret is a separately-stored, write-only value. A blank
     * or absent DB value means UNSET: the effective value falls through to the
     * {@code docs.oidc_*} system property, then to a built-in default. See
     * {@code OidcResource.oidcConfig} — the single accessor with that precedence.
     */
    OIDC_ENABLED,
    OIDC_ISSUER,
    OIDC_CLIENT_ID,
    OIDC_CLIENT_SECRET,
    OIDC_REDIRECT_URI,
    OIDC_SCOPE,
    OIDC_AUTHORIZATION_ENDPOINT,
    OIDC_TOKEN_ENDPOINT,
    OIDC_JWKS_URI,
    OIDC_USERINFO_ENDPOINT,
    OIDC_USERNAME_CLAIM,
    OIDC_EMAIL_CLAIM,

    /**
     * When true, an OIDC user is provisioned with the sanitized {@code preferred_username}
     * claim VERBATIM (no deterministic hash suffix), using the full username-length budget.
     * Default OFF preserves the safe hash-suffix behaviour. See {@code OidcResource} and
     * ADR-0018 (extends ADR-0015).
     */
    OIDC_USERNAME_VERBATIM,

    /**
     * Sentinel row used ONLY as a mutual-exclusion lock for {@code clean_storage} (#74). Its value is
     * never read; the clean_storage endpoint acquires a {@code PESSIMISTIC_WRITE} row lock on it so two
     * concurrent runs are serialized (a second run blocks until the first commits), preventing
     * double-count / double-credit of the same file. Seeded by {@code dbupdate-052}.
     */
    CLEAN_STORAGE_LOCK,

    /**
     * Sentinel row used ONLY as a mutual-exclusion lock for the GLOBAL storage-quota check. A global
     * quota is a cross-user {@code SUM} that no per-user row can serialize, so an upload that must
     * consult it acquires a {@code PESSIMISTIC_WRITE} row lock on this sentinel first. Its value is
     * never read; only the row's lock matters. Seeded by {@code dbupdate-053}.
     */
    GLOBAL_QUOTA_LOCK,

    /**
     * Server secret ({@code K_csrf_proxy}) used to MAC the structured CSRF proof token issued to
     * trusted-header ("proxy") authenticated sessions, which — unlike token-cookie sessions — carry no
     * per-session auth-token id to key a stateless token from. Runtime-seeded once at first use (a fresh
     * random value stored here; NO schema change) and NEVER echoed by any config endpoint; the single
     * container makes this row the shared secret store. A value-format version prefix leaves room for
     * future key rotation.
     */
    CSRF_PROXY_KEY
}
