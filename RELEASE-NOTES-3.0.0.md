# Teedy 3.0.0

> **Historical note:** The features these v3.0.0 notes describe as removed were
> later **reinstated** — LDAP authentication in v3.1, and workflows and vocabulary
> in v3.2. These notes are preserved as the v3.0.0 release record; for current
> behavior see [CHANGELOG.md](CHANGELOG.md) and the [`docs/`](docs/README.md) tree.

This is a **breaking release**. It permanently removes three features from
the database (workflow/routes, vocabulary, LDAP authentication) and retires
a legacy authentication filter. Read the full migration guide —
[`UPGRADING-3.0.md`](https://github.com/fmaass/teedy-docs/blob/v3.0.0/UPGRADING-3.0.md)
— before upgrading. **Take a `pg_dump` backup first**; the schema migrations
in this release cannot be reversed.

## Breaking changes

- **Workflow/routes feature removed.** `dbupdate-037-0.sql` drops
  `T_ROUTE`, `T_ROUTE_MODEL`, and `T_ROUTE_STEP`, and deletes the
  associated `ROUTING`-type and route-model ACLs. The Vue UI for this
  feature had already been removed in an earlier release; this finishes the
  removal end to end (REST resources, DAOs, JPA entities).
- **Vocabulary feature removed.** `dbupdate-038-0.sql` drops
  `T_VOCABULARY`. Any custom vocabulary entries are permanently deleted.
- **LDAP authentication removed.** `dbupdate-039-0.sql` deletes all
  `LDAP_*` rows from `T_CONFIG`. The LDAP authentication handler and its
  `META-INF/services` registration have been deleted from the codebase —
  only internal (username/password) authentication remains as a
  `ServiceLoader`-discovered handler besides OIDC.
  **Deployments using LDAP must migrate to internal authentication or OIDC
  before upgrading**, or affected users will be unable to log in.
- **Bearer-JWT authentication filter retired.** `JwtBasedSecurityFilter`
  and the `docs.jwt_authentication=true` system property are gone. Any
  deployment relying on that flag must move to OIDC or API-key
  authentication (`Authorization: Bearer tdapi_<hex>`, unaffected).
- **Header authentication now requires an explicit trusted-proxy allowlist.**
  If `docs.header_authentication=true` is set without a `trusted_proxies`
  init-param or `docs.header_authentication_trusted_proxies` system
  property, header-based authentication now fails closed (all such requests
  are refused) instead of trusting any request bearing the
  `X-Authenticated-User` header. Configure the allowlist (comma-separated
  IPs/CIDRs) before upgrading if you use header-based proxy auth (Authelia,
  Authentik, etc.).

See [`UPGRADING-3.0.md`](https://github.com/fmaass/teedy-docs/blob/v3.0.0/UPGRADING-3.0.md)
for exact SQL, config keys, and a fork-maintainer checklist — including why
the three retirement migrations must be applied together, not
cherry-picked individually.

## Also in this release

- **Version scheme unified to `3.0.0`** across all Maven modules, the
  Docker image tag, and the frontend package — now enforced in CI by
  `scripts/check-version-consistency.sh`.
- **Webhook SSRF protection**: webhook URLs are validated against loopback,
  link-local, and private address ranges (including IPv6-embedded IPv4,
  6to4, and Teredo forms) before being saved and again before each outbound
  call. Opt out for trusted internal networks with
  `docs.webhook_allow_private=true` / `DOCS_WEBHOOK_ALLOW_PRIVATE=true`.
- **`GET /api/document/export` is now resource-guarded**: a document-count
  cap (`docs.export_max_documents`, default `10000`), a concurrency limit
  (`docs.export_max_concurrent`, default `2`), and a kill switch
  (`docs.export_enabled`) prevent a very large account or concurrent
  exports from exhausting server memory.
- **OIDC hardening**: RP-initiated logout wiring (via the discovered
  `end_session_endpoint`) and stricter ID-token expiry validation (a token
  with no `exp` claim is now rejected instead of treated as
  non-expiring).
- **Password-recovery tokens** now generated with `SecureRandom` (128-bit,
  matching the existing API-key generation scheme).
- **Frontend test harness added** (Vitest) plus new CI guardrails:
  `scripts/check-db-version.sh` (fails the build if `db.version` doesn't
  cover the latest migration), and all GitHub Actions pinned to commit SHAs
  rather than mutable tags.

## Upgrade checklist

1. `pg_dump` your database.
2. If you use LDAP: migrate affected users to internal auth or OIDC.
3. If you use header-based proxy auth: set `trusted_proxies` /
   `docs.header_authentication_trusted_proxies`.
4. If you set `docs.jwt_authentication=true`: switch to OIDC or API keys.
5. Deploy `ghcr.io/fmaass/teedy-docs:v3.0.0` — the app applies
   `dbupdate-037-0.sql` → `dbupdate-039-0.sql` automatically on startup
   (`db.version` moves to `39`).
6. Confirm login works for every authentication method you use before
   considering the upgrade complete.

Full details: [`UPGRADING-3.0.md`](https://github.com/fmaass/teedy-docs/blob/v3.0.0/UPGRADING-3.0.md).
