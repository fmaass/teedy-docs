# Changelog

All notable changes to this fork are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project follows
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Per-release detail lives in the [GitHub releases](https://github.com/fmaass/teedy-docs/releases).

## [3.2.2] - 2026-07-10

### Added
- Admins can disable and re-enable user accounts from the settings UI, not just delete them (#17).
- Document-list rows open the full document view on double-click (#11).
- Overflow document tags (beyond the first three) collapse into a focusable "+N" popover (#24).
- Filter box on the document-edit and tag-edit tag pickers; selected document tags render as colored chips matching the badges used elsewhere (#14, #23).

### Changed
- Two-factor (TOTP) users can now sign in through the Vue web UI: an OTP field appears after the password step and the login is resubmitted with the code (#18).
- Admin and settings pages render wider (#25).
- Extracted the remaining hardcoded UI strings (tag-tree "Tree"/"Facets" toggle, tag-mode "AND"/"OR" toggle) into i18n keys with English and German translations (#16).

### Fixed
- Disabled users are now denied at every authentication path — login, session-cookie reuse, API key, and OIDC — closing a gap where a disabled account could still be issued a token (#17).

### Security
- The login rate-limiter now counts a wrong TOTP code toward lockout. Previously the counter was cleared before the TOTP step, letting an attacker who already knew the password brute-force the six-digit code (#18).

### Documentation
- Added a "System properties (JVM `-D`)" section to the README documenting `docs.logout_url`, `docs.header_authentication_trusted_proxies`, `application.mode`, and `docs.home` (#20).

## [3.2.1] - 2026-07-10

Reinstates the document workflow/routes and vocabulary features as first-class,
UI-complete capabilities (retired in 3.0), adds a VOCABULARY custom-metadata field
type, and surfaces six previously UI-less backend features. Supersedes ADR-0004/0005
(see ADR-0014). **3.2.0 was tagged but never deployed; 3.2.1 is the shipped release.**

### Added
- Workflow/routes: admin route-model editor with ACL sharing; a document workflow tab (start / validate / approve; reject halts the route; per-route history); `ROUTE_*` webhook events.
- Vocabulary: admin CRUD, Dublin Core document dropdowns, and a VOCABULARY custom-metadata type with value validation.
- Remnant UIs: SMTP and inbox-scan settings, self-service sessions, monitoring log viewer, clean-storage, EML import.

### Changed
- db.version 41 → 43 (migrations 042 + 043, additive, verified on populated PostgreSQL 17).

### Fixed
- Fixed a pre-existing ACL target-resolution fall-through.

### Security
- Route lifecycle hardening (reviewed as new attack surface): atomic start (document-row lock + trashed-doc rejection), complete ROUTING-ACL cleanup on principal/user deletion, single document-before-route lock order, null-transition standard for system-ended steps, HTML-escaped notification emails.

## [3.1.0] - 2026-07-09

### Added
- LDAP authentication reinstated (reverses the 3.0.0 retirement per ADR-0013) and hardened beyond upstream: case-collision provisioning guard (PostgreSQL), 5s connect / 10s response timeouts on the login path, new admin settings UI with app-wide admin route guards.
- Independent real-Chrome CDP e2e harness alongside the 13-spec Playwright suite in CI.

### Changed
- Config secrets are now write-only: the LDAP, SMTP, and inbox config GETs no longer echo stored passwords; all three POSTs treat an empty password as "keep existing" (LDAP returns `admin_password_set` instead of the secret).
- Additive DB migrations 040 (tag-facet index) and 041 (`USE_LDAP_B` column) run automatically on first boot; db.version 39 → 41.
- Packaging: single JCL provider in the WAR (commons-logging excluded at both leak paths), parsson aligned, README env-var docs completed.

### Fixed
- LDAP login-path cursor leak.
- Filter state survives navigation to Settings and back; transient tag-list failures can no longer rewrite deep-link URLs; the last tag is removable in single-document edit; What's-New refreshed for 3.1.

### Security
- Trash purge now reclaims storage quota for collaborator files stranded by owner deletion (data-loss fix).
- Test integrity: PostgreSQL reset-extension host allowlist (strict loopback-literal check), exact db-version CI gate, fully ephemeral test ports, falsifiable env-precedence tests.

## [3.0.0] - 2026-07-09

First release under the unified 3.0.0 version. **Breaking** — run `pg_dump` before
upgrading; see `RELEASE-NOTES-3.0.0.md` and `UPGRADING-3.0.md`.

### Added
- Frontend test harness (Vitest) and CI guardrails (db-version consistency, version consistency).

### Removed
- Retires workflow/routes, vocabulary, and LDAP authentication (destructive migrations 037–039) and the bearer-JWT filter.

### Security
- Removing the bearer-JWT filter closes an SSRF.
- Hardens webhook egress (SSRF), `/document/export` (DoS), OIDC, temp-file cleanup, and password-recovery tokens.

## [2.9.0] - 2026-07-09

Wave 1 fork remediation: launch-blocker security and integrity fixes.

### Fixed
- SEC-02: renaming or deleting a file requires WRITE on the parent document, not READ.
- SEC-04: editing tags on a shared document preserves tags the editor cannot see.

### Security
- SEC-01/NF-01: empty-trash is ownership-scoped — no cross-owner permanent delete; quota events attributed to the owner.
- SEC-03: tag stats/facets/co-occurrence scoped to the caller's ACL (no cross-user tag or count leak).
- SEC-05: database migrations fail fast (rollback + boot refusal) instead of booting on a partial schema.
- TST-07/08: PostgreSQL Testcontainers guardrail runs the real migrations on real PostgreSQL in CI.

[3.2.2]: https://github.com/fmaass/teedy-docs/compare/v3.2.1...HEAD
[3.2.1]: https://github.com/fmaass/teedy-docs/releases/tag/v3.2.1
[3.1.0]: https://github.com/fmaass/teedy-docs/releases/tag/v3.1.0
[3.0.0]: https://github.com/fmaass/teedy-docs/releases/tag/v3.0.0
[2.9.0]: https://github.com/fmaass/teedy-docs/releases/tag/v2.9.0
