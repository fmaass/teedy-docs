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

## [3.7.1] - 2026-07-21

A patch release with one additive database migration (`db.version` 58 → 59) that re-runs cleanly.

### Added

- In-app file preview: opening a document or file now shows it inside the app, using web-size rendering for images, the built-in PDF viewer, extracted text where available, and an explicit "preview unavailable" state otherwise. Only controls labelled Download fetch the original file (#144).
- Crash-safe file processing: a durable per-file completion marker and startup reconciliation service re-derive a file's text extraction, search-index entry, thumbnail and automatic tags if a hard shutdown interrupts processing after an upload is saved. Replays never send a duplicate webhook. Recovery covers first-time post-upload processing; migration 059 (#159).
- The local login path is now documented, including the `?local=1` parameter, the "Use a local account" link, and behavior when the identity provider is unreachable (#157).
- The optional `docs.oidc_end_session_endpoint` system property pins the OIDC logout endpoint for deployments that avoid outbound discovery (#156).
- The inbox settings endpoint now accepts an optional `dedicatedFolder` acknowledgement, required before the import will issue a folder-wide expunge on servers without UIDPLUS (#150, #160).

### Changed

- The German interface now uses natural, informal German across the settings, admin and dialog prose (about 75 strings). The i18n parity check now also enforces balanced HTML markup in every locale (#149).
- The duplicate-detection master key now requires an explicit encoding prefix: `hex:<hex>` or `base64:<base64>`. A bare value is ambiguous and is rejected, leaving the feature off (#160).
- CodeQL baseline lifecycle: triaged alerts are dismissed with the baseline's own reasons, a nightly job warns before triage expiry, the refresh tool degrades per entry instead of refusing the whole baseline, and a pre-push gate detects when a code change moves a triaged line (#138, #158).

### Fixed

- Uploads with an RFC 5987 `filename*` header no longer have their already-decoded name corrupted by the filename repair introduced for mojibake plain filenames (#148).
- OIDC logout now reaches the provider's `end_session_endpoint` even when all four endpoints are configured explicitly. The discovery document is fetched on demand with a bounded timeout, falling back to local-only logout on any failure (#156, reported in #155).
- Inbox import no longer fails on a message without a `From` header; it uses the unknown-sender fallback (#150, #160).
- A standalone file attached to a document now gets its duplicate-detection MAC computed immediately on attach instead of waiting for the periodic backfill (#150, #160).
- A guard test now reports a verdict instead of crashing with a NullPointerException when a javadoc ends in a parenthesized construct (#161).

### Security

- The destructive browser test harness now refuses to run without an explicit opt-in acknowledging a disposable target, and purges everything it seeds — including from the recycle bin — even when interrupted (#154).
- Inbox import no longer issues a generic folder-wide IMAP `EXPUNGE` on servers without `UIDPLUS` unless the operator has acknowledged that the folder is dedicated (#150).
- Dependency updates: axios 1.18.1 (GHSA-gcfj-64vw-6mp9) and the importer's transitive js-yaml 3.15.0 (CVE-2026-59869).
- The two published security advisories now list 3.7.0 as the patched version (#153).

## [3.7.0] - 2026-07-20

A minor release. No database migration (db.version stays 58).

The version moves to 3.7.0 rather than a further 3.6.x patch to reflect the size of the line
it closes. Between 3.6.1 and 3.7.0 the fork gained server-side dark mode, a fully pageable
activity log, translation parity for every shipped locale, credential-epoch invalidation and
API-key revocation, the `?share=` access-control fix and CSRF enforcement — 109 commits across
six database migrations. That is a feature release under semantic versioning, and the number
now says so.

### Changed
- The About dialog's "What's new" highlights now describe the 3.7 line instead of the 3.6.0 one.
- Release tooling: a pre-push hook runs the four "mirror" gates — OpenAPI spec parity, locale key parity, db.version overlay parity, and version literals — that previously ran only in the CI build job. Each compares a hand-maintained file against its source of truth and so cannot be failed by a unit test; running them at push time catches drift before it becomes a failed release build.

### Fixed
- The dark-mode class is now cleared on logout, so a theme seeded from one account's server-side preference can no longer follow the next user to log in on a shared browser. A device's own explicit choice is preserved.
- Uploaded API documentation now lists the audit-log pagination parameters (`limit`, `before_date`, `before_id`) and the dark-mode preference field, which were added to the REST resources in 3.6.7 without being mirrored into the published OpenAPI spec.

### Security
- Matrix parameters are stripped before the CSRF filter matches its mutating-GET inventory. JAX-RS ignores matrix parameters when selecting a resource method, so `GET /api/user;x=1` reached the same handler as `/api/user` while failing the filter's literal path comparison — letting a mutating GET skip token and Origin evaluation even under enforcement. Only the GET inventory was affected: every non-safe method was already unconditionally evaluated, so the reachable impact was refreshing a victim's session clock or forcing an export permit, not data disclosure or modification.

## [3.6.7] - 2026-07-20

A bug-fix and enhancement release. Database migration to version 58 (a nullable per-user
dark-mode preference column on the users table; additive, so a rollback to 3.6.6 is safe).

### Added
- Dark mode is now a per-user preference persisted server-side: the choice seeds a fresh device on login, while the on-device toggle always wins locally (#147).
- The document activity log is fully pageable via a "load older" control (keyset pagination on a stable order), instead of only ever showing the newest 20 entries (#139).
- Full translation parity for every shipped locale — Greek, Spanish, French, Italian, Polish, Portuguese, Russian, Albanian, and Simplified & Traditional Chinese are now complete, and the file-upload "Choose" button is localized (#146). The ten machine-translated locales await native-speaker review.

### Fixed
- Uploaded file names containing non-ASCII characters (e.g. umlauts) or a literal `+` are now stored correctly instead of as mojibake; already-stored names are unaffected (#143).
- The right-click tag menu reflects the current tags immediately after an add/remove, instead of showing a stale snapshot from when the menu opened (#142).
- The reassign and clean_storage batch operations now acquire document and tag row locks in the same order as the tagging path, closing a latent deadlock class (#137).

## [3.6.6] - 2026-07-19

A security and bug-fix release. No database migration (db.version stays 57).

### Security
- Fixed an unauthenticated access-control bypass via the `?share=` request parameter (CVE-2026-50885, CVE-2025-11853). The share value was trusted as an ACL target without validation, so `?share=admin` matched the reserved administrator ACL name and skipped the permission check, returning any document — its files, PDF export, ZIP download, and comments — to an anonymous caller by id. The parameter is now honoured only when it resolves to a genuine active share (share ids are server-generated random UUIDs, so a reserved name or another account's id can never match); legitimate share links are unaffected.
- The related-documents list is now filtered by the caller's read permission, so a related document a user cannot read no longer leaks its title through another document's relations (#140).

### Fixed
- The inbox auto-import tag can now be cleared: re-saving the inbox configuration with an empty tag removes it, where previously an empty tag was ignored and the tag could never be removed once set (#141).

## [3.6.5] - 2026-07-19

The whole rc.1–rc.8 candidate train plus a final ACL-hardening batch, released as one version. Five database migrations this release: db.version moves from 52 to 57. `dbupdate-053` adds the IMAP exactly-once import-receipt table and seeds the global storage-quota lock sentinel; `dbupdate-054` adds a per-user preferred-locale column so the interface language can be remembered server-side; `dbupdate-055` adds the credential-epoch columns to users, session tokens, and API keys, adds account-origin integrity constraints, and forces every existing credential to re-authenticate once on upgrade; `dbupdate-056` adds a covering index for the global storage-quota scan; `dbupdate-057` adds a nullable per-file content-MAC column and index for duplicate detection. All migrations are additive and portable across PostgreSQL 17 and H2, and each re-runs cleanly after a partial apply. The content-MAC column stays empty (and duplicate detection stays off) unless a deploy-time master key is supplied, so an existing install upgrades with no behaviour change.

### Added
- A grid-or-list file panel for a document's files, with a remembered per-user view, inline rename, and drag-to-reorder; the file list APIs now also return each file's creator and creation date (#58).
- A PDF page organizer: reorder, rotate, and delete pages of a PDF and save the result as a new file version, through a server-side page-operations endpoint (#73).
- A tag permissions and ownership editor: manage who can read and write a tag and hand a tag's ownership to another account, with a race-safe guard that stops a tag from being left with no owner or no editor (#88).
- Document download and account export: download a multi-file document or a selection of documents as a single ZIP, and export all of your own documents from account settings as a backup archive (#89).
- Upload a new file revision from the UI: replace a file with an updated copy that is stored as the next version of that file instead of an unrelated new file, and dropping a same-named file now offers add-as-new-version, keep-both, or cancel (#117).
- Per-upload duplicate detection: an upload whose contents match an existing file in the same document is flagged as a likely duplicate. It is opt-in and privacy-preserving — it uses a per-document keyed MAC (never a raw hash), and it stays entirely off unless the operator supplies a `DOCS_DEDUP_MASTER_KEY` (or `DOCS_DEDUP_MASTER_KEY_FILE`) of at least 32 bytes at deploy time; a missing or too-weak key leaves uploads unchanged (#119).
- A readable, filterable document activity log: each entry shows a localized event-type label and its date at the normal text size, and the tab can be filtered by event type (#113).
- Tag colors now appear on the search filter chips, not only on tags already assigned to a document (#115).

### Changed
- The interface language is now remembered per user on the server, so it follows the account across devices and sessions instead of living only in the browser (#82).
- ODT and DOCX to-PDF conversion now uses OpenPDF 2.0.2 in place of the transitive iText 2.1.7, removing CVE-2017-9096 (XXE in iText's XML parsers, unfixed in that lineage) with no change to the conversion behaviour (#105).
- A hardened CI security and coverage gate stack: dependency and container-image CVE scanning, CodeQL static analysis against a governed baseline, coverage gates, and exact-digest image promotion so only the exact artifact that passed the gates is published — and the full real-browser regression suite is now required before every release tag (#76, #104, #105).

### Fixed
- Storage cleanup (`clean_storage`) is now correct under concurrent runs: the on-disk sweep runs entirely outside the database transaction, concurrent runs no longer double-count reclaimed files, and the global storage quota now counts the bytes still held by soft-deleted "ghost" users; a covering index keeps the global-quota scan fast while it holds its lock (#79, #99, #103, #106).
- ACL and tag ownership stay correct when a user is deleted: ACL grants are serialized so a tag keeps at least one editor through a deletion, every tag the departing user owns is reassigned (not only the ones currently linked to a document), and ACL rows left pointing at a soft-deleted user are excluded from the tag and holder counts so an orphaned grant can no longer mask a sole-editor condition (#121, #122, #134, #135).
- The PDF page-operations endpoint now reports infrastructure, quota, and error conditions with the correct HTTP status instead of a misleading one, and a page-operation new version keeps the document's OCR language instead of losing it (#124, #126, #129).
- A file uploaded with no filename is now stored with an empty name instead of failing with a server error, and the file panel and API clients handle a null file name throughout (#131, #132, #136).
- The LDAP settings form now populates its stored host, port, base DN, and filter even when LDAP is currently disabled, instead of appearing wiped (#83).
- Inbox import survives a batch that contains several messages from the same sender address instead of aborting the sync (#116).
- Clicking a tag in the sidebar now filters by that exact tag name when one matches, so the result no longer includes documents whose tags merely start with the clicked name and no longer exceeds the count shown next to the tag (#114).
- Gallery thumbnails recover on their own from a transient load error instead of staying blank (#80).
- Assorted mobile layout glitches: the tag-create and metadata buttons and the tag-overflow popover no longer collide or get clipped on narrow viewports (#86, #118).
- A broad test-stabilization sweep removed the timing-related flakiness in the backend and end-to-end suites (deterministic per-test application-context lifecycle, quiesced async processing before observations, per-run test isolation, and de-flaked mobile e2e specs), so the suite no longer fails on races rather than real regressions (#81, #85, #94, #101, #130).

### Security
- CSRF protection infrastructure is now present. It runs in report-only mode by default: the filter fully evaluates every state-changing request and logs a structured would-block record when the proof is missing or wrong, but it never rejects a request unless enforcement is switched on with `DOCS_CSRF_ENFORCE=true` (environment variable) or the `docs.csrf_enforce` system property. See `docs/csrf.md`.
- Authentication was substantially hardened. A per-user credential epoch stamps every session token and API key at creation and honours it only while it still matches the user's current epoch, so a single epoch bump revokes every credential that account holds at once; password recovery, an admin password reset, disabling an account, and deleting a user now all revoke sessions and API keys immediately instead of leaving them live (#96, #97, #108, #110). Authentication is partitioned by account origin: an OIDC- or LDAP-provisioned account can no longer sign in (or mint an API key) with a local password, closing a path where a recovered or admin-set password bypassed the identity provider and its MFA, and the TOTP-disable reauthentication now routes through the same origin-aware check (#98, #109, #111).
- Password recovery, login throttling, and session revocation were fixed: the forgot-password endpoint could mint a recovery key for an arbitrary account (now a generic, timing-matched response whether or not the account exists); the login rate-limiter was unbounded and spoofable through `X-Forwarded-For` (now a bounded, per-account/per-network throttle behind a single trusted-proxy-aware address resolver); and a lost-password or admin reset now revokes every session while a self-service change rotates the current session only.
- Concurrent uploads can no longer bypass the storage quota: the quota check and update were an unprotected read-modify-write that let two uploads both pass and undercount usage; reservations now take place under portable pessimistic locks in one canonical order before any file is written (#92).
- IMAP inbox import is crash-safe and exactly-once: each message is imported in its own transaction with a durable receipt and the source message is only expunged after commit, so a crash or a single bad message can no longer permanently lose mail or roll back the whole batch, and network I/O no longer runs inside an open transaction (#90, #91).
- A zip-slip path-traversal weakness in the document ZIP download was fixed.
- The above resolve several privately-reported advisories, including an OIDC local-credential authentication bypass, the download zip-slip, missing session revocation on password reset, login rate-limiter spoofing, and issuance of a recovery token for an unauthenticated arbitrary account.

### Documentation
- New `docs/csrf.md` documenting the CSRF mechanism and the credential-precedence model; corrected README build and release instructions (#87); and a documented CI build/publish pipeline (`docs/ci-pipeline.md`).

## [3.6.0] - 2026-07-13

Three database migrations this release: db.version moves from 49 to 52. `dbupdate-050` adds a case-insensitive unique index on active usernames, so verbatim OIDC provisioning relies on a database-level constraint instead of a race-prone application precheck (it aborts with a clear failure if duplicate active usernames already exist); `dbupdate-051` adds the storage-cleanup run-protocol table; `dbupdate-052` adds a single-run lock sentinel for storage cleanup. All portable across PostgreSQL 17 and H2 2.3.232.

### Added
- Reassign a deleted user's documents to a chosen user instead of trashing them: deleting a user can hand their documents to another account, and the files stay readable to the new owner without any re-encryption (#55).
- A storage-cleanup dry-run: admins preview exactly what would be deleted and how much space would be reclaimed before running it, every run is recorded, and genuine on-disk orphans (files with no database row, safely older than a day) are reclaimed as well (#60, #72).
- Optional verbatim OIDC usernames: an admin can opt in to using the OIDC `preferred_username` claim verbatim as the account name (#59). See ADR-0018.
- Custom application title and favicon per instance, so each deployment can carry its own branding (#57).
- A settings landing page with a grouped settings navigation, so settings open on an overview instead of jumping straight into the account page (#64, #61).
- A configurable number of documents shown per page, plus a right-click menu to edit a document's tags directly from the gallery view (#52, #50).
- The running application version is shown at the bottom of the settings navigation (#62).

### Changed
- Document search now matches partial words and tolerates small typos, so a partly-correct term still finds documents (#53).
- The application now builds and runs on Java 25 (LTS) (#56).
- Faster initial load: the frontend is code-split so the theme presets and the PDF viewer load on demand, cutting the initial bundle by roughly 70% (#77).
- The tag tree now shows a rolled-up document count that includes documents on nested tags (#66).
- Expanded automated testing: a full mobile-viewport end-to-end suite, English/German visual-regression checks, a real-browser smoke suite in CI, and a nightly regression monitor.

### Fixed
- Storage cleanup now completes reliably: it clears every restricting foreign-key reference into a soft-deleted document or user (a still-referenced file, comment, tag link, route step, or auth token) before deleting, so the purge no longer rolls back wholesale; it deletes files only after the database change is committed, so a concurrent upload or restore cannot lose its bytes; it reclaims the storage quota held by the purged files; and it serializes concurrent runs (#54, #69, #74).
- Deletion side-effect events (webhooks, search de-indexing) no longer fire when the request that triggered them rolls back (#63).
- The document count in the search bar no longer appears twice under German and several other locales, and a parity check now guards against duplicated translation placeholders (#19, #75).
- The rich-text editor no longer shows doubled list markers, and the right-click tag menu no longer gets cut off — it is now a compact popover with a search field and the most-used tags (#70, #71).
- Assorted mobile and slide-over layout glitches: the header action icons and the slide-over title no longer overlap on narrow screens (#67, #68).

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

[Unreleased]: https://github.com/fmaass/teedy-docs/compare/v3.7.1...HEAD
[3.7.1]: https://github.com/fmaass/teedy-docs/compare/v3.7.0...v3.7.1
[3.7.0]: https://github.com/fmaass/teedy-docs/compare/v3.6.7...v3.7.0
[3.6.7]: https://github.com/fmaass/teedy-docs/compare/v3.6.6...v3.6.7
[3.6.6]: https://github.com/fmaass/teedy-docs/compare/v3.6.5...v3.6.6
[3.6.5]: https://github.com/fmaass/teedy-docs/compare/v3.6.1...v3.6.5
[3.6.1]: https://github.com/fmaass/teedy-docs/compare/v3.6.0...v3.6.1
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
