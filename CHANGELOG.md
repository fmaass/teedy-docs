# Changelog

All notable changes to this fork are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project follows
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Per-release detail lives in the [GitHub releases](https://github.com/fmaass/teedy-docs/releases).

## [3.4.0] - 2026-07-11

Two database migrations this release: db.version moves from 43 to 45 (`dbupdate-044` adds the saved-filters table; `dbupdate-045` widens the config value column).

### Added
- `/apidoc` serves an interactive API reference again — a hand-authored OpenAPI 3 specification covering every endpoint with a vendored Swagger UI, no runtime dependency added to the WAR, and a CI check that fails the build if the spec drifts from the resources (#15).
- Tag chips in the document list, overflow popovers, and the document view filter the list when clicked (#34).
- View-only rotation controls for PDF and image previews; rotation composes with a page's intrinsic orientation and is not persisted (#35).
- A "Related documents" section on the document view to add and remove document-to-document relations (#36).
- Per-user saved filters: name a tag/text/workflow filter combination and re-apply it from the search bar (#42).
- Configurable footer links (imprint, privacy, terms, documentation) shown in the app shell and on the login screen, managed by an admin (#43).
- An admin screen to manage OIDC provider and claim settings, backed by the database with system-property fallback so existing deployments are unaffected until saved (#44).

### Changed
- Storage quota is editable after user creation and shown as an explicit field in both the create and edit dialogs (#37).
- OIDC configuration resolves from the database first, then system properties, then built-in defaults, through a single accessor; a change is picked up by the next login without a restart. See ADR-0016.
- ESLint now covers TypeScript files, which previously matched no configuration and went unlinted (#45).

### Fixed
- Editing a document no longer re-submits incoming relations as outgoing ones, which had created a spurious reverse relation on every save (#36).
- Assorted test-infrastructure papercuts from the v3.3 cycle: temp-file leak checks, an embedded-LDAP port race, GreenMail teardown, and a UserInfo response byte cap (#45).

### Security
- OIDC configuration is read as a single consistent per-request snapshot on login, callback, and logout, so a concurrent settings change cannot produce a mixed configuration or send the logout token hint to the wrong provider. The client secret is write-only through the admin API. Identity binding, username derivation, the eligibility check, and fail-closed rules remain non-editable behavior, per ADR-0015.
- Footer-link URLs are validated as http(s) only, rejecting `javascript:`, `data:`, and scheme-relative values.

### Documentation
- A full `docs/` tree covering installation, configuration, all authentication flows, workflows, vocabulary, tags and filtering, documents, sharing and permissions, the admin surface, and the REST API — illustrated with real screenshots and a cookbook of end-to-end recipes, and mirrored to the GitHub wiki on release.

## [3.3.0] - 2026-07-11

No database migration this release: db.version stays at 43.

### Added
- Configurable OIDC claim names via the `docs.oidc_username_claim` and `docs.oidc_email_claim` system properties, plus an optional `docs.oidc_userinfo_endpoint` with UserInfo fallback for providers whose ID tokens carry only minimal claims, such as Authelia 4.38+ (#21).
- Renaming or deleting a vocabulary entry now warns with the number of documents referencing it (#29).

### Changed
- Loading placeholders are now content-shaped skeletons instead of generic spinners; the text-processing status keeps an accessible live label (#32).

### Fixed
- The facet view no longer freezes the browser on trees with hundreds of nested tags: child arrays are capped at 20 entries — the top co-occurrences plus an overflow node when truncated — and the tree build is memoized (#12).
- Navigating back from a document view no longer loses the "Assigned to me" filter, including when the document view is the cold-load entry point (#28).
- The route-model validator no longer accepts SHARE step targets (#30).
- Renaming a group no longer leaves stale names in route-model step targets: existing models are repaired under symmetric row locking, and route-model create and update now share one locked write path (#31).
- Eliminated a port race in the test SMTP server by switching GreenMail to dynamic ports (#33).

### Security
- OIDC logins without a `sub` claim are rejected; previously each such login provisioned a new unbound user.
- An attempted-but-failed UserInfo fetch (including a `sub` mismatch) rejects the OIDC login.
- Concurrent first OIDC logins for the same identity converge on a single account.
- A temporarily absent email claim can no longer overwrite a user's stored email address.

### Documentation
- Rewrote the README's OIDC identity and provisioning section (identity is issuer + `sub` only) and added an oauth2-proxy integration guide.

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

[3.4.0]: https://github.com/fmaass/teedy-docs/compare/v3.3.0...HEAD
[3.3.0]: https://github.com/fmaass/teedy-docs/releases/tag/v3.3.0
[3.2.2]: https://github.com/fmaass/teedy-docs/releases/tag/v3.2.2
[3.2.1]: https://github.com/fmaass/teedy-docs/releases/tag/v3.2.1
[3.1.0]: https://github.com/fmaass/teedy-docs/releases/tag/v3.1.0
[3.0.0]: https://github.com/fmaass/teedy-docs/releases/tag/v3.0.0
[2.9.0]: https://github.com/fmaass/teedy-docs/releases/tag/v2.9.0
