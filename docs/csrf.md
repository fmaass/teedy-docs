# CSRF Protection

Teedy has a Cross-Site Request Forgery (CSRF) protection subsystem that runs
**report-only and OFF by default** in this release. The filter fully evaluates
every state-changing request and logs a structured "would-block" line when the
proof is missing or wrong, but it **never rejects** a request unless enforcement
is explicitly turned on. This page documents the subsystem as it ships, the token
model, and how a future RC will flip it to enforcing.

> **Status: report-only (phase E1).** Enforcement is gated behind
> `DOCS_CSRF_ENFORCE` (default `false`). With enforcement off, the filter emits
> telemetry and continues. See [Enabling enforcement](#enabling-enforcement-future--e2).

## Threat model

CSRF attacks abuse **ambient credentials** — credentials the browser attaches
automatically on any request to the origin, regardless of who initiated it. In
Teedy the ambient credentials are the `auth_token` session cookie (from local
login, OIDC/SSO, or an LDAP bind — all of which mint a session cookie) and, when
Teedy sits behind a trusted authenticating proxy, the proxy-asserted
`X-Authenticated-User` header. A malicious cross-site page can cause the browser
to issue an authenticated state-changing request using those ambient credentials
without ever reading them. **API keys are exempt**: a `Authorization: Bearer
tdapi_<hex>` credential is non-ambient (a browser never attaches it on its own),
so an API client cannot be CSRF'd and is never evaluated.

## Where the filter runs

The `CsrfFilter` is mapped on `/api/*` **after** the security filters, so the
authenticated principal and the proven authentication mechanism are already
installed on the request. From `WEB-INF/web.xml`, the `/api/*` filter order is:

    corsFilter → requestContextFilter
      → apiKeyBasedSecurityFilter → tokenBasedSecurityFilter
      → headerBasedSecurityFilter → csrfFilter → Jersey

### Credential precedence (explicit-scheme-first)

The security filters resolve the principal with explicit-scheme-first precedence,
and the CSRF filter keys its proof off the resulting mechanism:

1. **API key** (`apiKeyBasedSecurityFilter`) runs first. A `Bearer tdapi_*`-shaped
   header that resolves to no key is an **invalid explicit credential**: it is
   rejected with `401` and terminates the chain — it never falls through to cookie
   auth. A valid key installs an api-key principal (CSRF-exempt).
2. **Session cookie** (`tokenBasedSecurityFilter`). An absent/unknown/expired
   cookie is not a rejection; it falls through to anonymous.
3. **Trusted header** (`headerBasedSecurityFilter`). Only honoured when the request
   comes from a configured trusted proxy (fail-closed otherwise); `admin` is never
   assumable via header.

Every filter still *probes* its own credential even when a principal is already
installed. Two **valid** credentials resolving to **different** principals is a
conflict: the request is rejected with `401` (`AuthenticationConflict`) and the
conflict is written to the audit log through an independent post-rollback
transaction (the 4xx rolls the ambient request transaction back, so the audit
insert cannot ride on it). A second credential for the *same* principal keeps the
installed context.

## The token model

The proof is a token submitted **only** as a request header (never a form field,
query parameter, or the cookie itself), compared in constant time
(`MessageDigest.isEqual`). There are two independent proofs, one per ambient
mechanism, derived statelessly in `CsrfTokenUtil`.

### Session (token-cookie) double-submit token

    token = base64url( HMAC-SHA-256( key = auth-token-id, data = "teedy.csrf.session.v1" ) )

The **auth-token id** — the unguessable server-side session identifier already
held only in the HttpOnly `auth_token` cookie — is the HMAC key, so no server key
and no schema change are needed. The token is placed in a **non-HttpOnly**
`csrf_token` cookie (host-only, `Secure`, `SameSite=Lax`, `Path=/`) and echoed by
the client in the `X-Csrf-Token` header. A cross-site page cannot read the
target-origin cookie to copy it into the header, and cannot recompute it (it never
sees the auth-token id). The filter recomputes the expected token from the proven
session (`AUTH_TOKEN_ID_ATTRIBUTE`, not the raw cookie) and constant-time-compares.

### Trusted-header ("proxy") token

Proxy-authenticated sessions carry no per-session auth-token id, so they use a
server-keyed structured token instead:

    <version>.<principalId-b64>.<nonce>.<expiryMs>.<mac>

MAC'd with the dedicated server secret `K_csrf_proxy`, stored in `T_CONFIG` under
`ConfigType.CSRF_PROXY_KEY` and seeded lazily on first use (idempotently and
durably, in its own committed transaction frame so a concurrent first-use race
just re-reads and a rolled-back request cannot lose the key). The MAC binds the
token to the authenticated principal and to a fixed domain-separation label
(`teedy.csrf.proxy`), and the token carries a 12h expiry (a fresh one is minted on
every proxy-authenticated request anyway). It rides a `__Host-csrf_proxy` cookie
and is echoed in the `X-Csrf-Proxy` header.

### Bootstrap and recovery

- **Bootstrap** — on *every* authenticated ambient-credential request, including
  one it would block, the filter issues-or-repairs the mechanism's CSRF cookie on
  the response when it is absent or stale. A pre-enforcement session that never
  re-logged-in therefore still obtains a token, and the retry path has a fresh
  cookie to read. Login/rotation sites also set the `csrf_token` cookie directly
  (`UserResource` returns `CsrfTokenUtil.buildSessionCookie(...)`).
- **Recovery (H3)** — the SPA client (`src/api/client.ts`) mirrors both CSRF
  cookies into their headers on every request. On a `403` whose body is
  `{"type":"CsrfError"}`, it re-reads the (re-set) cookie and retries the request
  **exactly once**. Because enforcement rejects *before* the handler runs, that
  single retry is safe even for non-idempotent requests. The `docs-importer`
  Node.js client does the same header-mirroring from its cookie jar; it sends no
  `Origin`/`Referer`, so the header token is its sole CSRF proof.

## What gets evaluated

A request is state-changing (and thus CSRF-evaluated) when its method is outside
`{GET, HEAD, OPTIONS}`, **or** when a `GET`/`HEAD` path matches the enumerated
**mutating-GET inventory** — the four routes whose handlers have a server side
effect:

| Route | Side effect |
|-------|-------------|
| `GET /oidc/login` | Writes OIDC state (in practice unauthenticated) |
| `GET /oidc/callback` | Consumes state + mints a session cookie — **exempt by route** |
| `GET /document/export` | Writes an audit row + acquires an export permit |
| `GET /user` | Refreshes `lastConnectionDate`, the short-session expiry clock |

Inventory entries are route *templates* (path params collapsed to `{}`) matched
segment-by-segment, so a future parameterized mutating GET (e.g.
`/document/{}/publish`) added to `MUTATING_GET_PATHS` is actually evaluated at
runtime, not merely accepted by the test.

Two build-gating tests keep this honest:

- `TestCsrfGetInventory` **reflectively discovers** every `@GET` JAX-RS route in
  the resource package and asserts the discovered set equals *(mutating-GET
  inventory + a known-safe read-only list)*. Adding a new `@GET` **fails the
  build** until a reviewer classifies it as mutating or read-only.
- `TestCsrfInventory` pins the inventory to exactly the four routes above and
  covers the state-changing classification and template matching.

## Origin / Referer validation

Alongside the header token, the filter runs a parsed-URI `Origin`/`Referer`
provenance check against the **configured base URL** (`DOCS_BASE_URL` /
`docs.base_url`) — never the request `Host`, `X-Forwarded-*`, or any other
forwarding header. Rules:

- `Origin` present → exact scheme+host+port match against the base URL (default
  ports normalized, host lower-cased). A single well-formed value that matches
  passes.
- No `Origin` → fall back to a single well-formed `Referer`, compared the same way.
- **`Origin: null`, malformed, multi-valued, or userinfo-bearing values always
  FAIL**, independent of the base URL.
- Neither header present → `UNAVAILABLE` (the header token is the sole proof).
- Base URL unset but a well-formed provenance header present → `UNAVAILABLE`
  (nothing to compare against). **A misconfigured/empty `DOCS_BASE_URL` therefore
  disables provenance matching** — set it correctly before enforcing.

Only a `FAIL` (reason `origin-mismatch`) or a bad token (`token-missing` /
`token-mismatch`) marks a request as would-block; `UNAVAILABLE` does not.

## Telemetry

On every would-block, report-only or not, the filter logs a single structured
WARN line — never a token or secret value:

    CSRF would-block: enforce=false mechanism=token-cookie method=POST path=/document reason=token-missing

Use these lines to measure readiness: for active clients (the SPA and the
importer already send the tokens) the would-block rate should trend to ~zero
before enforcement is enabled.

## Enabling enforcement (future / E2)

> This section describes the **not-yet-active** enforcement phase. In this release
> the filter is report-only regardless of client behavior.

To enforce, set `DOCS_CSRF_ENFORCE=true` (env var, or the `docs.csrf_enforce`
system property — the property wins if both are set). With enforcement on, a
would-block request is rejected with `403` and a `{"type":"CsrfError"}` body
*before* the handler runs; the bootstrap has already re-set the expected cookie so
the client's single retry can succeed.

Prerequisites before flipping enforcement in a future RC:

1. **Clients already send the tokens.** The Vue SPA (`src/api/client.ts`) and the
   `docs-importer` mirror both CSRF cookies into `X-Csrf-Token` / `X-Csrf-Proxy`
   on every request, and implement the retry-once recovery. No client change is
   needed to enable enforcement.
2. **Report-only telemetry should show ~zero would-block** for active clients
   first. A non-trivial would-block rate means a client (or an integration) is not
   sending the token — fix that before enforcing.
3. **Check `DOCS_BASE_URL`.** A misconfigured or empty base URL makes provenance
   `UNAVAILABLE` (no false rejects yet), but once set, a *wrong* base URL causes
   systematic `origin-mismatch` false rejects. Verify it matches the public origin
   users actually reach Teedy on before turning enforcement on.

## Development note

In dev mode only, `CorsFilter` echoes a loopback `Origin` and includes
`X-Csrf-Token, X-Csrf-Proxy` in `Access-Control-Allow-Headers` so the Vite dev
server can drive the API cross-origin. Production emits no CORS headers.

## Source map

- `docs-web-common/.../util/csrf/CsrfFilter.java` — the report-only filter, classification, provenance
- `docs-web-common/.../util/csrf/CsrfTokenUtil.java` — token derivation/verification, key seeding
- `docs-web-common/.../util/filter/SecurityFilter.java` (+ `ApiKey`/`Token`/`HeaderBasedSecurityFilter`) — credential precedence + conflict audit
- `docs-web/.../WEB-INF/web.xml` — filter order
- `docs-web/src/main/webapp/src/api/client.ts` — SPA header-mirroring + retry-once
- `docs-importer/main.js`, `docs-importer/cookies.js` — importer token-mirroring
- `docs-web-common/.../csrf/TestCsrfInventory.java`, `docs-web/.../rest/TestCsrfGetInventory.java` — inventory gates
