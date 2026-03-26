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

## v2.4.0 (In Progress)

**Theme:** Modern UI + Smart Document Handling

### Frontend rewrite

- Replace AngularJS 1.6.6 + Bootstrap 3 + Grunt with Vue 3 (Composition API) + PrimeVue + Vite
- TypeScript, Pinia state management, Vue Router, vue-i18n (reusing existing 12 locale files)
- Phased migration: core document flow → tags → settings → share mini-app → polish

### Auto-tagging via regex matchers

- New `TagMatchRule` entity: match document title, filename, or extracted content against regex patterns to automatically apply tags
- REST API for CRUD on rules, plus a regex test endpoint
- Hook into `FileProcessingAsyncListener` after content extraction
- Built-in `#TagName` pattern support (extending existing IMAP inbox behavior to all documents)
- Community request: upstream [sismics/docs#234](https://github.com/sismics/docs/issues/234)

### Configurable tag search mode

- Setting to switch between prefix matching (current default) and exact matching ([#5](https://github.com/fmaass/teedy-docs/issues/5))
- Carried over from v2.2 and v2.3

### Technical debt cleanup

- Replace joda-time with java.time across all modules
- Refactor DbOpenHelper/EMF to plain JDBC (remove Hibernate ServiceRegistry dependency for migrations)
- Fix `TestPdfFormatHandler.testIssue373` (disabled since test PDF was never committed)

---

## Ideas / Wishlist

Lower-priority ideas that may be worth exploring:

- **Admin-only tag management** (upstream [sismics/docs#323](https://github.com/sismics/docs/issues/323)): RBAC for tag creation
- **Trash / recycle bin** (upstream [sismics/docs#328](https://github.com/sismics/docs/issues/328)): soft-delete with restore
- **Bulk operations UI**: select multiple documents for tagging, moving, or deleting
- **Document templates**: pre-filled metadata for common document types
- **S3-compatible storage backend**: store files in object storage instead of local filesystem
- **Webhook / event system enhancements**: document lifecycle events for external automation
- **Improved email integration** (upstream [sismics/docs#352](https://github.com/sismics/docs/issues/352)): IMAP monitoring, attachment extraction
