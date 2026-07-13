# Changelog

All notable changes to this fork are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project follows
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Per-release detail lives in the [GitHub releases](https://github.com/fmaass/teedy-docs/releases).

## [Unreleased]

### Added

### Changed

### Fixed

### Security

## [3.6.0] - 2026-07-13

One database migration this release: db.version moves from 49 to 50 (`dbupdate-050` adds a case-insensitive unique index on active usernames, so verbatim OIDC provisioning can rely on a database-level constraint instead of a race-prone application precheck; the migration aborts with a clear failure if duplicate active usernames already exist). Portable across PostgreSQL 17 and H2 2.3.232.

### Added
- Reassign a deleted user's documents to a chosen user instead of trashing them: deleting a user can now hand their documents to another account rather than moving them to the trash (#55).
- A settings landing page with a grouped settings navigation, so settings open on an overview instead of jumping straight into the account page (#64, #61).
- Optional verbatim OIDC usernames: an admin can opt in to using the OIDC `preferred_username` claim verbatim as the account name (#59). See ADR-0018.
- Custom application title and favicon per instance, so each deployment can carry its own branding (#57).
- A configurable number of documents shown per page, plus a right-click menu to edit a document's tags directly from the gallery view (#52, #50).

### Changed
- Document search now matches partial words and tolerates small typos, so a partly-correct term still finds documents (#53).

### Fixed

### Security

## [3.5.2] - 2026-07-12

One database migration this release: db.version moves from 48 to 49 (`dbupdate-049` adds `FIL_ROTATION_N` to store per-file rotation).

### Added
- Persistent PDF and image rotation: rotation chosen in the document viewer is now stored per file and applied whenever the file is served or thumbnailed, so it survives reloads, sessions, and other viewers — extending the previous view-only rotation (#35). It is non-destructive: the original uploaded bytes are never rewritten and OCR is not re-run; only the derived web and thumbnail rasters are regenerated from the original and rotated.
- Real-browser (CDP) harness coverage for the 3.5 features — favorites, gallery view, the admin statistics dashboard (including a non-admin 403 check), rich-description sanitization, and persisted rotation — plus index-timing hardening for the existing document-search and open checks.

### Changed
- User storage quota is entered and displayed in gigabytes (on the same binary unit basis as the size shown elsewhere) instead of raw bytes (#49).

### Fixed
- Storage cleanup (`/app/batch/clean_storage`) no longer aborts wholesale: a soft-deleted file still referenced by a document's primary-file pointer (a RESTRICT foreign key) rolled back the entire purge and reclaimed nothing; the purge now clears those pointers before deleting the files, on both PostgreSQL and H2 (#54).
- The rich-text description editor's toolbar is now themed for dark mode: the toolbar icons, the format-picker dropdown, and the link-edit tooltip previously kept the editor library's hardcoded light-theme colors, rendering low-contrast or as bright popovers against a dark background (#38).
- The custom footer links are aligned (centered below the card) on the login/front page instead of floating beside it (#48).

## [3.5.0] - 2026-07-12

Two database migrations this release: db.version moves from 46 to 48 (`dbupdate-047` adds the personal-favorites table; `dbupdate-048` widens `DOC_DESCRIPTION_C` from 4000 to 50000 characters).

### Added
- Personal favorites: star and unstar documents, filter the document list to just your favorites, and star a document directly from its gallery card (#41).
- A gallery view mode for the document list that lays documents out as thumbnail cards (#39).
- Document descriptions are now authored in a rich-text editor with a formatting toolbar (headings, bold, italic, underline, strikethrough, ordered and unordered lists, links, blockquote, and code blocks). The stored length rises from 4000 to 50000 characters (#38).
- An admin statistics dashboard summarizing documents, storage, users, and activity (#40).

### Changed
- **API contract:** document descriptions are now stored as sanitized HTML — descriptions are sanitized server-side on every write. A `description` submitted to `PUT /api/document` or `POST /api/document/{id}` (or arriving via EML/inbox import) is reduced to an allowlist of formatting HTML before storage — any markup outside that set (scripts, styles, iframes, event handlers, non-`http(s)`/`mailto` URLs, arbitrary inline styles) is stripped. A raw description larger than 100000 characters is rejected; the stored sanitized result must be ≤ 50000 characters. API clients therefore always read back sanitized HTML.
- The About dialog's "What's new" heading now tracks the project's MAJOR.MINOR version rather than the exact patch version, so a patch release no longer needs to touch the curated highlights.

### Security
- Closed the latent stored-XSS surface on document descriptions: HTML was previously stored raw and sanitized only at render time (leaving third-party API consumers unprotected). Server-side HTML sanitization now happens at write time through a single chokepoint covering every description writer, with the entity-write boundary re-sanitizing as an intrinsic guard. Render-time DOMPurify is retained as a permanent defence-in-depth layer (#38).
- **Legacy data:** rows written before this release were stored raw and are NOT migrated; the write-time invariant covers descriptions written from this release onward, and an existing row is re-sanitized the next time its document is edited. The render-side DOMPurify layer therefore remains mandatory for all rows.

## [3.4.1] - 2026-07-12

No database migration this release: db.version stays at 46. A patch release folding in five fixes surfaced by the post-3.4.0 audit, plus extended end-to-end coverage and a hardened test harness.

### Added
- End-to-end coverage for four previously unexercised surfaces: API keys are proven to authenticate requests (bearer accepted; anonymous, garbage, and revoked rejected), webhook delivery is observed end-to-end (registration must succeed and the delivery is asserted as a POST carrying the created document id), the German language switch renders real `de.json` strings and persists, and the dark-mode toggle is verified by computed style rather than class name.
- Security overrides on six provably-anonymous API operations so the OpenAPI spec and Swagger UI stop misrepresenting the public surface.

### Changed
- The e2e harness rejects a reused WAR whose version mismatches the pom (`E2E_ALLOW_STALE_WAR=1` overrides) — a stale WAR had silently produced bogus failures under newer specs; screenshot regeneration is gated behind `E2E_UPDATE_SCREENSHOTS=1` so verification runs no longer dirty the tree.

### Fixed
- Storage purge no longer aborts wholesale: `T_SAVED_FILTER`'s `ON DELETE RESTRICT` foreign key made `/app/batch/clean_storage` stop all reclaim once any soft-deleted user had ever saved a filter; the purge now deletes those filters in the same transaction before the user hard-delete.
- The OIDC settings admin form clears the entered client secret after a successful save instead of keeping it revealable and resendable.
- The About dialog's "What's new" highlights, frozen at 3.1.0 while the app shipped 3.4.0, now reflect the 3.4 release in English and German, with a guard test pinning the highlights version to the pom so they can never silently go stale again.

### Security
- OIDC logout now binds the stored ID token to the configured client: `resolveLogoutUrl` requires the token's audience to contain the configured `client_id` in addition to matching the pinned issuer, falling back to local-only logout on a mismatch exactly like an issuer mismatch — closing the advertised provider-binding contract.

## [3.4.0] - 2026-07-11

Three database migrations this release: db.version moves from 43 to 46 (`dbupdate-044` adds the saved-filters table; `dbupdate-045` widens the config value column; `dbupdate-046` pins the OIDC provider on in-flight login state).

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
- OIDC configuration is read as a single consistent per-request snapshot on login, callback, and logout. The OIDC flow is bound to the provider that started it: logout only sends the stored ID token to a provider whose issuer matches the token, and a callback whose provider changed since login is rejected before the code is exchanged — so a live provider switch cannot disclose a token or authorization code to a different provider. The client secret is write-only through the admin API. Identity binding, username derivation, the eligibility check, and fail-closed rules remain non-editable behavior, per ADR-0015.
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

[Unreleased]: https://github.com/fmaass/teedy-docs/compare/v3.6.0...HEAD
[3.6.0]: https://github.com/fmaass/teedy-docs/compare/v3.5.2...v3.6.0
[3.5.2]: https://github.com/fmaass/teedy-docs/compare/v3.5.0...v3.5.2
[3.5.0]: https://github.com/fmaass/teedy-docs/compare/v3.4.1...v3.5.0
[3.4.1]: https://github.com/fmaass/teedy-docs/releases/tag/v3.4.1
[3.4.0]: https://github.com/fmaass/teedy-docs/releases/tag/v3.4.0
[3.3.0]: https://github.com/fmaass/teedy-docs/releases/tag/v3.3.0
[3.2.2]: https://github.com/fmaass/teedy-docs/releases/tag/v3.2.2
[3.2.1]: https://github.com/fmaass/teedy-docs/releases/tag/v3.2.1
[3.1.0]: https://github.com/fmaass/teedy-docs/releases/tag/v3.1.0
[3.0.0]: https://github.com/fmaass/teedy-docs/releases/tag/v3.0.0
[2.9.0]: https://github.com/fmaass/teedy-docs/releases/tag/v2.9.0
