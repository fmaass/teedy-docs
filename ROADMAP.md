# Teedy Roadmap

This document tracks planned work, deferred features, and ideas for future releases.
It is maintained alongside the code and updated with each release.

---

## v2.2.0 (In Progress)

**Theme:** Test Infrastructure + Dependency Modernization

- Jetty 11 -> 12 (EE 10) + Servlet 6.0 to align with Jersey 3.1.5
- Enable the full integration test suite (~50 REST tests, broken since Java 17 upgrade)
- Lucene 8.7 -> 9.12 (latest compatible with Java 17)
- BouncyCastle migration to jdk18on artifact line
- auth0 java-jwt bump to latest 4.x
- docs-importer Node.js 14 -> 20 LTS
- OIDC RP-Initiated Logout (end_session_endpoint)
- Fix tag search prefix matching (exact match instead of startsWith)
- Hide user/group lists from guest users
- Dockerfile: HEALTHCHECK, non-root user, pinned base image

Tracking: [GitHub Milestone v2.2.0](https://github.com/fmaass/teedy-docs/milestone/1)

---

## Future Consideration (v2.3+)

Items discussed and intentionally deferred. Roughly ordered by community interest.

### Auto-tagging via regex matchers
Upstream [sismics/docs#234](https://github.com/sismics/docs/issues/234) (9 comments).
Regex-based tag rules similar to Paperless-ngx: define patterns that automatically apply tags to newly imported documents. Significant feature, requires UI for rule management.

### Admin-only tag management
Upstream [sismics/docs#323](https://github.com/sismics/docs/issues/323) (10 comments).
RBAC for tag creation -- only admins can create/rename/delete tags, regular users can only apply existing ones. Medium effort.

### Trash / recycle bin
Upstream [sismics/docs#328](https://github.com/sismics/docs/issues/328).
Soft-delete with restore capability. Requires new DB table for deleted documents, UI for trash view, and scheduled permanent deletion.

### Lucene 10.x
Requires Java 21 (project is currently on Java 17). Revisit when the minimum Java version is bumped. The v2.2.0 Lucene 9.x upgrade keeps us on the latest version compatible with Java 17.

### Frontend modernization
AngularJS 1.x reached end-of-life in January 2022. A full rewrite to a modern framework (Angular, React, or Vue) would be a major project but would unlock modern tooling, better accessibility, and mobile responsiveness.

### Improved email integration
Upstream [sismics/docs#352](https://github.com/sismics/docs/issues/352).
Better IMAP inbox monitoring, attachment extraction, and email-to-document workflows.

### Webhook / event system
Allow external systems to subscribe to document events (created, tagged, shared). Would enable integration with automation platforms.

---

## Ideas / Wishlist

Lower-priority ideas that may be worth exploring:

- **Full-text search improvements**: stemming, synonyms, multi-language analyzers
- **Bulk operations UI**: select multiple documents for tagging, moving, or deleting
- **Document templates**: pre-filled metadata for common document types
- **Audit log**: track who viewed/edited/shared documents
- **S3-compatible storage backend**: store files in object storage instead of local filesystem
