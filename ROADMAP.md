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

## v2.3.0 (Planning)

**Working theme:** Developer Experience + Search Improvements

Items to consider for the next release. Not all will make the cut -- this is the input for planning, not the plan itself.

### Carried over from v2.2

- **Configurable tag search mode** ([#5](https://github.com/fmaass/teedy-docs/issues/5)): add a setting to switch between prefix matching (current default) and exact matching. Deferred from v2.2 because the exact-match change was too disruptive without a toggle.

### Infrastructure / DX

- **Technical API user for deployment testing**: set up a long-lived API token or a Traefik bypass route for smoke-testing endpoints behind Authelia. We hit integration issues in v2.2 that couldn't be verified without manual browser testing.
- **Synology non-root compatibility**: the `USER jetty` directive in the Dockerfile doesn't work on Synology NAS due to BTRFS/ACL restrictions. Investigate alternatives (matching UID to host, entrypoint script with `gosu`, or documenting the `user: "0:0"` workaround more prominently).
- **`TestPdfFormatHandler.testIssue373`**: currently `@Ignore`d because the test PDF was never committed. Either find/recreate the PDF or replace with a different OCR extraction test.

### Search & Indexing

- **Full-text search improvements**: stemming, synonyms, multi-language analyzers (Lucene 9 unlocks better analysis chains)
- **Lucene 10.x**: requires Java 21. If we bump the minimum Java version, this comes along for free. Assess Java 21 readiness of all dependencies first.

### Features (community interest)

- **Auto-tagging via regex matchers** (upstream [sismics/docs#234](https://github.com/sismics/docs/issues/234), 9 comments): the most requested community feature. Regex-based tag rules like Paperless-ngx.
- **Admin-only tag management** (upstream [sismics/docs#323](https://github.com/sismics/docs/issues/323), 10 comments): RBAC for tag creation.
- **Trash / recycle bin** (upstream [sismics/docs#328](https://github.com/sismics/docs/issues/328)): soft-delete with restore.

### Bigger bets (high effort, high impact)

- **Frontend modernization**: AngularJS 1.x is EOL. A rewrite to a modern framework would be the single highest-impact change but also the largest undertaking.
- **Webhook / event system**: document lifecycle events for external automation.
- **S3-compatible storage backend**: store files in object storage instead of local filesystem.

### Lessons from v2.2 to apply

- Always test logout/auth flows end-to-end behind the actual proxy setup, not just unit tests
- Synology NAS has filesystem quirks that break common Docker patterns (non-root user, chown)
- The Jetty 12 upgrade was less scary than expected -- don't over-estimate risk on well-documented migrations
- Having the full test suite running is transformative -- every subsequent change gets immediate feedback

---

## Ideas / Wishlist

Lower-priority ideas that may be worth exploring:

- **Bulk operations UI**: select multiple documents for tagging, moving, or deleting
- **Document templates**: pre-filled metadata for common document types
- **Audit log**: track who viewed/edited/shared documents
- **Improved email integration** (upstream [sismics/docs#352](https://github.com/sismics/docs/issues/352)): IMAP monitoring, attachment extraction
