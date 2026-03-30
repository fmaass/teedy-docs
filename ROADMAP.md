# Teedy Roadmap

This document tracks planned work, deferred features, and ideas for future releases.
It is maintained alongside the code and updated with each release.

---

## v2.2.0 (Released)

**Theme:** Test Infrastructure + Dependency Modernization

- Jetty 12.0.21 (EE10, Servlet 6.0)
- 74 integration tests running in CI
- Lucene 9.12.3, BouncyCastle 1.83, java-jwt 4.5.1, Node 20
- External logout support (docs.logout_url + OIDC RP-Initiated Logout)
- Guest user privacy fix
- Docker hardening (HEALTHCHECK, non-root user)

See [release notes](https://github.com/fmaass/teedy-docs/releases/tag/v2.2.0) for details.

---

## v2.3.0 (Released)

**Theme:** Developer Experience + Backend Modernization

- Java 21 LTS, Hibernate 6.6.18, Lucene 10.4.0, Jetty 12
- Full migration to Jakarta EE 10 namespace
- All dependencies bumped to current stable versions
- JUnit 5 migration (55 tests)
- Maven Wrapper (3.9.9) for reproducible builds
- Docker: Ubuntu 24.04 + JRE 21 headless
- JWKS caching with 10-minute TTL
- Dependabot configuration, SECURITY.md, issue/PR templates, README refresh
- Android module removed (unmaintained)

See [release notes](https://github.com/fmaass/teedy-docs/releases/tag/v2.3.0) for details.

---

## v2.4.0 (Released)

**Theme:** Modern UI + Smart Document Handling

### Frontend rewrite

- Complete rewrite from AngularJS 1.6.6 + Bootstrap 3 + Grunt to Vue 3 (Composition API) + PrimeVue (Lara theme) + Vite + TypeScript
- Sidebar navigation layout with Documents, Tags, Users & Groups, Settings
- Full-width document list with DataTable, search, and collapsible hierarchical tag tree filter
- Document view with Files, Extracted Text, Permissions, and Activity tabs
- Hierarchical tag management with Tree view and parent selector
- Theme picker (Aura, Lara, Material, Nora) with dark mode support, persisted to localStorage
- OIDC "Login with SSO" and guest login buttons on login page
- Language picker (28 OCR languages matching Tesseract), respects server default_language
- OCR toggle, per-file reprocess button, search index rebuild in admin settings
- User management (list, add, edit, delete) for admins
- Password reset flow (forgot password + reset page)
- Extracted Text tab showing OCR output per file with status indicators
- Pinia state management, TanStack Vue Query for data fetching, vue-i18n (12 locales)
- All colors use PrimeVue semantic tokens for automatic dark mode support

### Auto-tagging via regex matchers

- New `TagMatchRule` entity: match document title, filename, or extracted content against regex patterns to automatically apply tags
- REST API for CRUD on rules, plus a regex test endpoint
- Hook into `FileProcessingAsyncListener` after content extraction

### Configurable tag search mode

- Setting to switch between prefix matching (default) and exact matching
- Exposed in admin configuration UI

### Technical debt cleanup

- Replace joda-time with java.time across all modules
- Refactor DbOpenHelper/EMF to plain JDBC (remove Hibernate ServiceRegistry dependency for migrations)
- Fix `TestPdfFormatHandler.testIssue373` (disabled since test PDF was never committed)

### Infrastructure

- Vite build replaces Grunt; legacy AngularJS assets no longer shipped in production WAR
- DB migrations 32-34 (OIDC state table, auth token ID token column, tag match rules table)
- Fully backward-compatible upgrade from v2.3.0 (additive schema, same storage, same Lucene version)

---

## v2.5.0 (Released)

**Theme:** Automation + Integration

### Multi-tag filtering

- Checkbox-based tag tree with AND logic for progressive narrowing
- Selected tags shown as removable filter chips above the document list
- Backend already supported multiple `tag:` tokens; frontend now leverages it

### Trash / recycle bin

- `DELETE /document/{id}` now soft-deletes (files preserved on disk)
- Dedicated trash view with restore and permanent delete options
- Auto-purge via `TrashPurgeService` (configurable via `DOCS_TRASH_RETENTION_DAYS`, default 30)
- New endpoints: `GET /document/trash`, `POST /document/{id}/restore`, `DELETE /document/{id}/permanent`, `DELETE /document/trash`

### API key authentication

- Bearer token authentication (`Authorization: Bearer tdapi_<hex>`) for external integrations
- Keys stored as SHA-256 hashes; raw key shown only at creation
- API key management UI in Settings
- DB migration 035: `T_API_KEY` table

### Webhook management UI

- Ported webhook management from legacy AngularJS to Vue 3 Settings page
- Added `DOCUMENT_TRASHED` and `DOCUMENT_RESTORED` webhook event types

### Tag browser (faceted navigation)

- New Browse view with faceted tag navigation
- Select any combination of tags; remaining co-occurring tags update dynamically with counts
- `GET /tag/facets?tags=id1,id2` endpoint for co-occurring tag counts
- `GET /tag/stats` endpoint for document counts per tag

### Security hardening

- Auth cookie: added `Secure` + `HttpOnly` flags
- Response headers: `X-Content-Type-Options`, `X-Frame-Options`, `Referrer-Policy`
- Lucene: removed `NoLockFactory`, commit-only-on-success, synchronized reader access
- AsyncEventBus: exceptions now routed to SLF4J
- Fixed soft-delete bugs in `TagDao.delete()`, `GroupDao.delete()`, `ShareDao.delete()`
- Ownership checks on trash restore/permanent-delete endpoints

### Infrastructure

- GitHub Actions bumped to Node.js 24 (`checkout@v5`, `setup-java@v5`, `upload-artifact@v6`, `download-artifact@v7`)
- Removed 180 dead AngularJS files (`src-legacy/`)
- DB migration 036: cleanup of pre-existing orphan soft-deleted documents

See [release notes](https://github.com/fmaass/teedy-docs/releases/tag/v2.5.0) for details.

---

## v2.6.0 (Planned)

**Theme:** Security Hardening + Unified Navigation

### Unified document view

- Merge Documents and Browse into a single view with three zones: tag tree sidebar (left), address bar with active/related tags and search (top), document list (main)
- Tag tree always visible alongside documents, with facet-driven counts and auto-expand to active branches
- Document slide-over panel (side peek) to preview documents without leaving the list
- AND/OR toggle for tag intersection vs union mode
- Replaces the separate Documents and Browse navigation items with a single "Documents" view

### Login brute force protection

- Per-IP and per-username rate limiting with exponential backoff after failed attempts
- Configurable thresholds via env vars

### Password change verification

- Require current password when changing password via self-update endpoint
- Frontend form update

### File upload size limits

- Configurable maximum upload size (env var, default 500MB)
- Enforce at stream level before writing to disk

### OIDC linking security

- Prevent auto-linking OIDC accounts to existing local accounts without explicit authorization
- Require local login or admin approval for first-time binding

### Session token lifetime

- Reduce "remember me" token lifetime from 20 years to 90 days
- Token rotation on authenticated requests

### Password complexity

- Enforce mixed-case + digit or zxcvbn-based strength check
- Reject passwords matching the username

### Folder ingestion

- Watch a filesystem directory for new files and auto-import as documents
- Configurable polling interval, post-processing (delete or move), error handling

### Improved search syntax

- Expose Lucene's query capabilities in the UI (AND/OR/NOT operators, date ranges, field-specific queries)
- Search syntax help/documentation in the UI

---

## Ideas / Wishlist

Lower-priority ideas that may be worth exploring:

- **S3-compatible storage backend**: store files in object storage instead of local filesystem
- **Admin-only tag management** (upstream [sismics/docs#323](https://github.com/sismics/docs/issues/323)): RBAC for tag creation
- **Bulk operations UI**: select multiple documents for tagging, moving, or deleting
- **Document templates**: pre-filled metadata for common document types
- **Webhook / event system enhancements**: document lifecycle events for external automation
- **Improved email integration** (upstream [sismics/docs#352](https://github.com/sismics/docs/issues/352)): IMAP monitoring, attachment extraction
- **Document deduplication**: content-hash-based detection of duplicate uploads
- **Custom properties on documents**: user-defined metadata fields beyond Dublin Core
- **Organizations / multi-tenancy**: scope documents, tags, and settings per organization
- **Command palette (Ctrl+K)**: keyboard-driven navigation and actions
- **CLI tool**: manage documents from the command line
- **Mobile app**: upload and browse documents on mobile devices
