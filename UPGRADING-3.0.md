# Upgrading to Teedy 3.0.0

Teedy 3.0.0 is a **breaking release**. It removes three features end-to-end
(database tables included) and retires one authentication method. If you run
a downstream fork of `fmaass/teedy-docs`, or maintain patches against a
release before 3.0.0, read this whole document before upgrading — the schema
migrations in this release are **irreversible**.

## 0. Back up first — no exceptions

```console
pg_dump -Fc -d <your-database> -f teedy-pre-3.0.dump
```

`db.version` moves from an earlier value to **39** via `dbupdate-037-0.sql`,
`dbupdate-038-0.sql`, and `dbupdate-039-0.sql`. These migrations `DROP TABLE`
and `DELETE` rows with no down-migration and no soft-delete. Once they run,
the removed data cannot be recovered from the running database — only from a
backup taken beforehand.

## 1. Breaking changes

### 1.1 Workflow / routes feature removed (dbupdate-037-0.sql)

The document workflow ("routes") feature is gone: the REST resources, DAOs,
JPA entities, and UI were already dead in this fork (the Vue UI had been
removed earlier); this release finishes the job in the database.

`docs-core/src/main/resources/db/update/dbupdate-037-0.sql` does exactly this,
in order:

```sql
delete from T_ACL where ACL_TYPE_C = 'ROUTING';
delete from T_ACL where ACL_SOURCEID_C in (select RTM_ID_C from T_ROUTE_MODEL);
drop table T_ROUTE_STEP;
drop table T_ROUTE;
drop table T_ROUTE_MODEL;
```

If you have any documents mid-workflow (an in-progress route step), that
state is deleted along with the tables — there is no export step. If you
depend on workflow/routes, **do not upgrade to 3.0.0** until you have
migrated off it or archived the relevant data yourself (query `T_ROUTE`,
`T_ROUTE_MODEL`, `T_ROUTE_STEP` before upgrading).

### 1.2 Vocabulary feature removed (dbupdate-038-0.sql)

```sql
drop table T_VOCABULARY;
```

Any custom vocabulary entries are deleted. There is no replacement feature in
3.0.0.

### 1.3 LDAP authentication removed (dbupdate-039-0.sql)

```sql
delete from T_CONFIG where CFG_ID_C in ('LDAP_ENABLED', 'LDAP_HOST', 'LDAP_PORT',
  'LDAP_USESSL', 'LDAP_ADMIN_DN', 'LDAP_ADMIN_PASSWORD', 'LDAP_BASE_DN',
  'LDAP_FILTER', 'LDAP_DEFAULT_EMAIL', 'LDAP_DEFAULT_STORAGE');
```

The LDAP authentication handler class has been deleted from the codebase, and
its `META-INF/services` entry
(`docs-core/src/main/resources/META-INF/services/com.sismics.docs.core.util.authentication.AuthenticationHandler`)
now lists only `com.sismics.docs.core.util.authentication.InternalAuthenticationHandler`.

**If your deployment authenticates users via LDAP, they will not be able to
log in after this upgrade.** Before upgrading:

- Move affected users to internal (username/password) authentication, or
- Stand up OIDC (see [`README.md`](README.md#oidc--sso-authentication)) against
  your existing directory (most LDAP servers, and products like Authelia,
  Keycloak, or Authentik, can front LDAP with an OIDC provider), and
  auto-provision users on first SSO login.

There is no in-place migration path from LDAP-authenticated accounts to
OIDC-authenticated accounts — a user who logs in via OIDC for the first time
is provisioned as a **new** account (matched by stable OIDC `sub`, never by
username/email, to prevent account takeover). Plan for users to re-establish
document ownership/ACLs under their new account, or handle reassignment via
an admin.

### 1.4 Bearer-JWT authentication filter removed

The `JwtBasedSecurityFilter` and the `docs.jwt_authentication=true` system
property no longer exist anywhere in the codebase. If a prior deployment set
`-Ddocs.jwt_authentication=true` (or any downstream fork patch depended on
that filter), it is now a no-op — the JVM flag is simply unread. Move any
JWT-bearer integrations to:

- **OIDC** (browser sessions, `/api/oidc/login` → `/api/oidc/callback`), or
- **API keys** (`Authorization: Bearer tdapi_<hex>`, unaffected by this
  change — see [`README.md`](README.md#api-key-authentication)).

### 1.5 Header authentication: `trusted_proxies` is now required

`HeaderBasedSecurityFilter`
(`docs-web-common/src/main/java/com/sismics/util/filter/HeaderBasedSecurityFilter.java`)
now **fails closed** if header authentication is enabled
(`docs.header_authentication=true`) but no trusted-proxy allowlist is
configured:

- New setting: `trusted_proxies` filter init-param, or the
  `docs.header_authentication_trusted_proxies` system property — a
  comma-separated list of exact IPs and/or CIDR ranges (IPv4 or IPv6, e.g.
  `10.0.0.0/8,192.168.1.50`).
- The filter checks the allowlist against the **immediate TCP peer address**
  (`request.getRemoteAddr()`) — never the spoofable `X-Forwarded-For` header.
- If `trusted_proxies` is unset (or empty) while header auth is enabled, a
  warning is logged and **all** header-based authentication attempts are
  refused — previously any request with the right header and no proxy check
  would have been trusted.
- The `X-Authenticated-User` header must appear **exactly once** on the
  request; more than one value is treated as a client-injected value and
  refused (the proxy is expected to overwrite, not append, the header).
- The built-in `admin` account can never be authenticated through this
  header, regardless of proxy trust.

If you run header-based proxy auth (Authelia, Authentik, or similar) in
front of Teedy, **set `trusted_proxies` before upgrading**, or logins via
that path will silently stop working the moment 3.0.0 starts.

### 1.6 OIDC configuration keys (if new to this fork's OIDC support)

Configured via `-D` system properties (typically through `JAVA_TOOL_OPTIONS`)
or the matching `DOCS_OIDC_*`-style env var convention used elsewhere in this
fork. The authoritative property names, read directly from
`docs-web/src/main/java/com/sismics/docs/rest/resource/OidcResource.java`:

| Property | Required | Purpose |
|----------|----------|---------|
| `docs.oidc_enabled` | Yes | Enables the `/api/oidc/*` endpoints |
| `docs.oidc_issuer` | Yes | Issuer URL, cross-checked against the discovery document's `issuer` |
| `docs.oidc_client_id` | Yes | OIDC client ID |
| `docs.oidc_client_secret` | Yes | OIDC client secret |
| `docs.oidc_redirect_uri` | Yes | Callback URL (`/api/oidc/callback`) |
| `docs.oidc_scope` | No | Defaults to `openid profile email` |
| `docs.oidc_authorization_endpoint` | No | Override for Docker networking (browser-facing) |
| `docs.oidc_token_endpoint` | No | Override for Docker networking (server-to-server) |
| `docs.oidc_jwks_uri` | No | Override for Docker networking (server-to-server) |

This is not new in 3.0.0, but is included here because it is the migration
target for LDAP and JWT deployments (1.3 and 1.4 above). Full setup details,
including the Docker-networking split and the Authelia `claims_policy`
requirement, are in [`README.md`](README.md#oidc--sso-authentication).

## 2. Non-breaking but notable changes

- **Version scheme unified to `3.0.0`** across `pom.xml` modules, the Docker
  image tag, and the frontend `package.json` — enforced going forward by
  `scripts/check-version-consistency.sh` in CI.
- **Webhook SSRF protection**: webhook URLs are now validated in
  `docs-core/src/main/java/com/sismics/docs/core/util/WebhookUtil.java`
  before being saved (`WebhookResource`) and again immediately before each
  outbound call (`WebhookAsyncListener`). Only `http`/`https` URLs whose
  resolved host is not loopback, link-local, site-local/private, wildcard,
  or multicast are allowed by default (IPv6-embedded IPv4, 6to4, and Teredo
  addresses are unwrapped and checked too). If your webhook targets are on a
  trusted internal network, set `docs.webhook_allow_private=true` (system
  property) or `DOCS_WEBHOOK_ALLOW_PRIVATE=true` (env var) to opt back in —
  scheme validation still applies regardless.
- **`GET /api/document/export` resource guards**: added
  `docs-core/src/main/java/com/sismics/docs/core/util/ExportGuard.java` to
  bound the full-account export endpoint, which previously had no cap and
  could OOM the service on a very large account or concurrent exports.
  Three new toggles (system property, falling back to the env var form):
  - `docs.export_enabled` / `DOCS_EXPORT_ENABLED` — enable/disable the
    endpoint entirely (default: enabled).
  - `docs.export_max_documents` / `DOCS_EXPORT_MAX_DOCUMENTS` — max documents
    per export, checked as a preflight before any data is loaded (default:
    `10000`).
  - `docs.export_max_concurrent` / `DOCS_EXPORT_MAX_CONCURRENT` — max
    simultaneous in-flight exports server-wide (default: `2`).
- **OIDC hardening**: added RP-initiated logout wiring (the app now looks up
  `end_session_endpoint` from the OIDC discovery document) and stricter
  ID-token expiry handling — a token with no `exp` claim is now rejected
  outright rather than treated as non-expiring.
- **Password-recovery tokens** now use `SecureRandom` (16 bytes / 128 bits,
  lowercase hex — the same pattern already used by API key generation)
  instead of the prior generator.
- **Frontend test harness**: `docs-web/src/main/webapp/vitest.config.ts` /
  `vitest.setup.ts` added; the frontend now has an automated test suite.
- **CI guardrails added**: `scripts/check-db-version.sh` (fails the build if
  `db.version` doesn't cover the latest migration file),
  `scripts/check-version-consistency.sh` (fails on a version mismatch across
  modules), and all GitHub Actions in `.github/workflows/build-deploy.yml`
  are now pinned by commit SHA rather than a mutable tag.

## 3. Fork-maintainer checklist

If you maintain your own fork or cherry-pick selectively from
`fmaass/teedy-docs`, read this section before touching `release/2.12` or the
`3.0.0` tag.

1. **`pg_dump` before anything else.** Section 0 above. There is no undo for
   `dbupdate-037-0.sql` / `-038-0.sql` / `-039-0.sql` once applied.
2. **Take the three retirement migrations as a set — do not cherry-pick
   individually.** The workflow/routes retirement landed in commit
   `ebecc195` ("Retire the workflow/routes feature") and bumped
   `db.version` to `37` in the same commit as `dbupdate-037-0.sql`. If a
   downstream fork cherry-picks the Java-side removal without the SQL
   migration (or vice versa), the schema and the code fall out of sync —
   either the app expects tables that were never dropped (harmless but
   leaves orphaned tables and misses the ACL cleanup), or, worse, a
   partial cherry-pick that takes `dbupdate-039-0.sql` without the LDAP
   Java-side removal leaves `T_CONFIG` LDAP rows deleted while the LDAP
   handler class and its `META-INF/services` registration still expect
   them — undefined behavior, not a clean revert. Apply migrations
   037 → 038 → 039 in order, together with their matching Java changes, in
   one pass.
3. **Grep your patch set for `LDAP`, `T_ROUTE`, `T_VOCABULARY`, and
   `docs.jwt_authentication`** before merging 3.0.0 into a fork with local
   patches. Any patch touching those will silently no-op or fail to apply.
4. **If you run header-based proxy auth**, add `trusted_proxies` /
   `docs.header_authentication_trusted_proxies` to your deployment config
   as part of the same change that upgrades to 3.0.0 — not after. Section
   1.5.
5. **New config surface to carry into your deployment manifests**:
   `docs.webhook_allow_private` / `DOCS_WEBHOOK_ALLOW_PRIVATE`,
   `docs.export_enabled` / `DOCS_EXPORT_ENABLED`,
   `docs.export_max_documents` / `DOCS_EXPORT_MAX_DOCUMENTS`,
   `docs.export_max_concurrent` / `DOCS_EXPORT_MAX_CONCURRENT`. All four
   have safe defaults (SSRF-blocked, export enabled with caps) so no action
   is required unless your deployment needs different values.
6. **Verify `db.version=39`** in
   `docs-core/src/main/resources/config.properties` matches the highest
   `dbupdate-NNN-*.sql` file present before building — `scripts/check-db-version.sh`
   enforces this in CI, but if you've patched the migrations directory
   locally, run it yourself first.
