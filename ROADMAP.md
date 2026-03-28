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

## v2.5.0 (Planned)

**Theme:** Automation + Integration

### Trash / recycle bin

- Soft-delete documents with configurable retention period
- Dedicated trash view with restore and permanent delete options

### Folder ingestion

- Watch a filesystem directory for new files and auto-import as documents
- Configurable polling interval, post-processing (delete or move), error handling

### Webhooks

- Document lifecycle events (created, updated, deleted, tagged) sent to configurable HTTP endpoints
- Webhook management UI in admin settings

### API key authentication

- Bearer token authentication for external integrations and automation
- API key management UI with scoped permissions

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
