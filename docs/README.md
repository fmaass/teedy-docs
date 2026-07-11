# Teedy documentation

Self-hoster documentation for this fork of Teedy. Start with
[getting started](getting-started.md), then dip into the reference pages as needed.

## Contents

| Page | What it covers |
|------|----------------|
| [Getting started](getting-started.md) | Docker Compose quick start (H2 for testing, PostgreSQL for production), first login, and changing the admin password |
| [Configuration](configuration.md) | The complete environment-variable and JVM `-D` property reference, plus the data directory and backup |
| [Authentication](authentication.md) | Local login, OIDC/SSO (Authelia & generic), reverse-proxy/oauth2-proxy header auth, LDAP, 2FA, the provisioning contract, and a login-failure troubleshooting table |
| [Documents](documents.md) | Files and versions, relations, viewer rotation, bulk operations, PDF / full-account export, custom metadata, the activity trail, and the trash |
| [Tags & filtering](tags-and-filtering.md) | Tags and nesting, clickable tag chips, auto-tagging rules, saved filters, and the full search-query syntax |
| [Inbox scanning](inbox.md) | Import email into Teedy over IMAP: connection setup, how mail becomes a document, auto-tagging, and delete-after-import |
| [Cookbook](cookbook.md) | End-to-end recipes wiring the features together — invoice intake, self-hosted setup, paper archive, team review, scripted ingestion, and lifecycle management |
| [Vocabulary](vocabulary.md) | Admin-managed controlled value lists that back dropdown metadata fields |
| [Workflows](workflows.md) | Multi-step approval routes: concepts, lifecycle, webhooks, and two worked sample workflows |
| [Sharing & permissions](sharing-and-permissions.md) | ACLs, hierarchical groups, guest access, public share links, and comments |
| [Admin guide](admin-guide.md) | Users and quotas, webhooks, tag rules, audit log, theming, and SMTP/OCR settings |
| [REST API](api.md) | Authentication methods (session vs API key), request format, key resources, and curl examples |
| [FAQ & troubleshooting](faq-troubleshooting.md) | Common failures and their fixes, with pointers to the deeper pages |

## Related documents at the repository root

- [RECOVERY.md](../RECOVERY.md) — account and admin recovery paths (with and without SMTP)
- [SECURITY.md](../SECURITY.md) — how to report a security vulnerability
- [CHANGELOG.md](../CHANGELOG.md) — release history
- [README.md](../README.md) — project overview and fork highlights
- [docs-importer](../docs-importer/README.md) — a standalone Node.js tool for a
  one-off or watched bulk import of files from a local folder (separate from the
  built-in [IMAP inbox scanning](inbox.md))
