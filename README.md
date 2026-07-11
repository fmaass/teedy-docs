<h3 align="center">
  <img src="https://teedy.io/img/github-title.png" alt="Teedy" width=500 />
</h3>

[![License: GPL v2](https://img.shields.io/badge/License-GPL%20v2-blue.svg)](https://www.gnu.org/licenses/old-licenses/gpl-2.0.en.html)
[![Build and Publish](https://github.com/fmaass/teedy-docs/actions/workflows/build-deploy.yml/badge.svg)](https://github.com/fmaass/teedy-docs/actions/workflows/build-deploy.yml)

> **This is an actively maintained fork of [sismics/docs](https://github.com/sismics/docs) (Teedy).**
> It includes OIDC/SSO authentication, Java 21 + Jetty 12 modernization, security hardening, and multi-arch Docker images published to GHCR.

Teedy is an open source, lightweight document management system for individuals and businesses.

# What's different in this fork

- **OpenID Connect (OIDC) authentication** with PKCE, auto-provisioning, and stable subject binding
- **Header-based proxy authentication** (e.g., Authelia, Authentik) with auto-skip login
- **Java 21 + Jetty 12 + Jakarta EE 10** (upgraded from Java 11 / Jetty 9)
- **Multi-arch Docker images** (amd64 + arm64) published to GitHub Container Registry
- **Security hardening**: JWKS key validation, discovery issuer verification, nonce fail-closed
- **API key authentication** for programmatic access (`Authorization: Bearer tdapi_*`)
- **Trash / recycle bin** with soft-delete, restore, permanent delete, and auto-purge
- **Document workflows & controlled vocabularies** (v3.2): multi-step approval routes (validate / approve / reject-halts) and vocabulary-backed metadata, with a native Vue UI
- **Two-factor (TOTP) web login**: TOTP-enabled users can sign in through the Vue UI (an OTP field appears after the password step), with the login rate-limiter counting wrong OTP codes toward lockout
- **User account state management**: admins can disable and re-enable users from the settings UI (not just delete them); disabled users are denied at every auth path (login, session cookie, API key, OIDC)
- **Vue 3 frontend** replacing AngularJS (PrimeVue 4, Vite 7, Pinia 3, TypeScript 5.9, vue-i18n)
- **Full internationalization** with 12 languages and live language switching
- **Accessibility**: keyboard navigation, label associations, ARIA attributes, PrimeVue FileUpload
- **Document update safety**: partial-update semantics prevent data loss on concurrent edits
- **Log4j 1.x removed**, replaced with Log4j 2
- **Dependency updates**: Hibernate 6.6, Jersey 3.1, Lucene 10, Guava 33, OkHttp 4.12, PostgreSQL driver 42.7

# Features

- Responsive user interface
- Optical character recognition
- LDAP authentication
- OpenID Connect (OIDC) / SSO authentication
- Header-based proxy authentication
- Support image, PDF, ODT, DOCX, PPTX files
- Video file support
- Flexible search engine with suggestions and highlighting
- Full text search in all supported files
- All [Dublin Core](http://dublincore.org/) metadata
- Custom user-defined metadata (incl. vocabulary-backed dropdown fields)
- Controlled vocabularies (admin-managed value lists)
- Document approval workflows (multi-step routes: validate / approve / reject-halts, with per-document history)
- 256-bit AES encryption of stored files
- File versioning
- Tag system with nesting
- Import document from email (EML format)
- Automatic inbox scanning and importing
- User/group permission system
- 2-factor authentication
- Hierarchical groups
- Audit log
- Comments
- Storage quota per user
- Document sharing by URL
- RESTful Web API
- Webhooks to trigger external service
- [Bulk files importer](https://github.com/fmaass/teedy-docs/tree/main/docs-importer) (single or scan mode)
- Tested to one million documents

# Install with Docker

A preconfigured Docker image is available, including OCR and media conversion tools, listening on port 8080. If no PostgreSQL config is provided, the database is an embedded H2 database. The H2 embedded database should only be used for testing. For production usage use the provided PostgreSQL configuration (check the Docker Compose example).

**The default admin password is "admin". Don't forget to change it before going to production.**

- Latest stable version: `ghcr.io/fmaass/teedy-docs:v3.2.2`
- Development (main branch, may be unstable): `ghcr.io/fmaass/teedy-docs:latest`

The data directory is `/data`. Don't forget to mount a volume on it.

To build external URL, the server is expecting a `DOCS_BASE_URL` environment variable (for example https://teedy.mycompany.com)

## Available environment variables

- General
  - `DOCS_BASE_URL`: The base url used by the application. Generated url's will be using this as base.
  - `DOCS_GLOBAL_QUOTA`: Defines the default quota applying to all users.
  - `DOCS_MAX_UPLOAD_SIZE`: Maximum accepted upload size in bytes. Default: `524288000` (500 MB).
  - `DOCS_BCRYPT_WORK`: Defines the work factor which is used for password hashing. The default is `10`. This value may be `4...31` including `4` and `31`. The specified value will be used for all new users and users changing their password. Be aware that setting this factor to high can heavily impact login and user creation performance.
  - `DOCS_SESSION_LIFETIME_DAYS`: Number of days before login sessions expire. Default: `90`. Missing, malformed, or non-positive values fall back to the default.
  - `DOCS_LOGIN_MAX_ATTEMPTS`: Number of failed login attempts before temporary lockout starts. Default: `5`.
  - `DOCS_LOGIN_LOCKOUT_SECONDS`: Base login lockout duration in seconds. Default: `60`. Repeated failures apply exponential backoff capped at 15 minutes.
  - `APPDATA`: On Windows, the system AppData directory used to resolve the default data directory when `docs.home` is not set.

- Admin
  - `DOCS_ADMIN_EMAIL_INIT`: Defines the e-mail-address the admin user should have upon initialization.
  - `DOCS_ADMIN_PASSWORD_INIT`: Defines the password the admin user should have upon initialization.  Needs to be a bcrypt hash.  **Be aware that `$` within the hash have to be escaped with a second `$`.**

- Database
  - `DATABASE_URL`: The jdbc connection string to be used by `hibernate`.
  - `DATABASE_USER`: The user which should be used for the database connection.
  - `DATABASE_PASSWORD`: The password to be used for the database connection.
  - `DATABASE_POOL_SIZE`: The pool size to be used for the database connection.

- Language
  - `DOCS_DEFAULT_LANGUAGE`: The language which will be used as default. Currently supported values are:
    - `eng`, `fra`, `ita`, `deu`, `spa`, `por`, `pol`, `rus`, `ukr`, `ara`, `hin`, `chi_sim`, `chi_tra`, `jpn`, `tha`, `kor`, `nld`, `tur`, `heb`, `hun`, `fin`, `swe`, `lav`, `dan`, `nor`, `vie`, `ces`, `sqi`

- E-Mail
  - `DOCS_SMTP_HOSTNAME`: Hostname of the SMTP-Server to be used by Teedy.
  - `DOCS_SMTP_PORT`: The port which should be used.
  - `DOCS_SMTP_USERNAME`: The username to be used.
  - `DOCS_SMTP_PASSWORD`: The password to be used.
  - `DOCS_SMTP_FROM`: Sender address used for outbound e-mail. If unset, the value configured in the application is used.

- Trash
  - `DOCS_TRASH_RETENTION_DAYS`: Number of days to keep deleted documents in the trash before auto-purging. Default: `30`. Set to `0` to disable auto-purge.

- Export
  - `DOCS_EXPORT_ENABLED`: Enables the full-account export endpoint. Default: `true`. Set to `false` to disable exports.
  - `DOCS_EXPORT_MAX_DOCUMENTS`: Maximum number of documents allowed in a single export. Default: `10000`. Values below `1` fall back to the default.
  - `DOCS_EXPORT_MAX_CONCURRENT`: Maximum number of simultaneous exports. Default: `2`. Values below `1` fall back to the default.

- Async processing
  - `DOCS_ASYNC_QUEUE_CAPACITY`: Maximum queued asynchronous events before the producing thread applies backpressure. Default: `1000`. Values below `1` fall back to the default.
  - `DOCS_ASYNC_RETRY_COUNT`: Number of times failed async listeners are retried. Default: `0`.
  - `DOCS_ASYNC_RETRY_BACKOFF_MS`: Backoff in milliseconds between async listener retries. Default: `200`.

- Webhooks
  - `DOCS_WEBHOOK_ALLOW_PRIVATE`: Allows webhook URLs targeting private, loopback, or link-local addresses when set to `true`. Default: `false`.

## System properties (JVM `-D`)

A few settings are only read as JVM system properties, passed via `JAVA_OPTIONS`
(or a `-D` flag), not as `DOCS_*` environment variables:

- `docs.logout_url`: External URL the browser is redirected to after logout. When set, it takes precedence over any OIDC `end_session_endpoint`. If unset and OIDC is active, Teedy composes an RP-initiated logout URL from the provider's `end_session_endpoint` instead. If neither applies, no external redirect is performed.
- `docs.header_authentication_trusted_proxies`: Comma-separated allowlist of IPs and/or CIDR ranges (e.g. `10.0.0.0/8, 192.168.1.5`) permitted to assert the `X-Authenticated-User` header when header-based proxy authentication (`docs.header_authentication=true`) is enabled. If header auth is enabled but this list is empty, all header-based authentication is refused (fail-closed). The built-in `admin` account can never be authenticated through this header.
- `application.mode`: Set to `dev` to run in development mode; any other value (or unset) is production mode.
- `docs.home`: Absolute path to the base data directory (documents, index, database when using H2). If unset, Teedy falls back to `/var/docs` on Linux, `%APPDATA%\Sismics\Docs` on Windows, and `~/Library/Sismics/Docs` on macOS. In the Docker image this is the `/data` volume.

## API Key Authentication

Teedy supports API key authentication for programmatic access. Create API keys in Settings > API Keys. Use the key in the `Authorization` header:

```
Authorization: Bearer tdapi_<your-key>
```

API keys act as the creating user and have the same permissions. The raw key is shown only once at creation -- store it securely.

## OIDC / SSO Authentication

Teedy supports OpenID Connect (OIDC) authentication via the Authorization Code flow with PKCE and a confidential client. This allows integration with identity providers like Authelia, Keycloak, or any standard OIDC provider.

### System Properties

Configure via `JAVA_TOOL_OPTIONS` environment variable (e.g., `-Ddocs.oidc_enabled=true`):

| Property | Required | Description |
|----------|----------|-------------|
| `docs.oidc_enabled` | Yes | Set to `true` to enable OIDC |
| `docs.oidc_issuer` | Yes | Issuer URL (e.g., `https://auth.example.com`) |
| `docs.oidc_client_id` | Yes | OIDC client ID |
| `docs.oidc_client_secret` | Yes | OIDC client secret (plaintext) |
| `docs.oidc_redirect_uri` | Yes | Callback URL (e.g., `https://teedy.example.com/api/oidc/callback`) |
| `docs.oidc_scope` | No | Scopes to request (default: `openid profile email`) |
| `docs.oidc_authorization_endpoint` | No | Override the authorization endpoint (see Docker networking below) |
| `docs.oidc_token_endpoint` | No | Override the token endpoint (see Docker networking below) |
| `docs.oidc_jwks_uri` | No | Override the JWKS URI (see Docker networking below) |
| `docs.oidc_userinfo_endpoint` | No | Override the UserInfo endpoint (see Docker networking below). If unset, it is taken from the discovery document. Consulted only when a configured claim is missing from the ID token. |
| `docs.oidc_username_claim` | No | Claim used to derive the local username at provisioning time (default: `preferred_username`). |
| `docs.oidc_email_claim` | No | Claim used for the user's email at provisioning and on profile refresh (default: `email`). |

### How It Works

1. User navigates to `/api/oidc/login` (or clicks "Login with SSO" on the login page)
2. Teedy redirects to the OIDC provider's authorization endpoint (with PKCE challenge)
3. After authentication, the provider redirects back to `/api/oidc/callback`
4. Teedy exchanges the authorization code for tokens (with PKCE verifier), verifies the ID token signature (RSA via JWKS), and validates issuer, audience, and nonce claims
5. The user is identified **solely** by the OIDC `(issuer, sub)` pair (stable subject binding). Teedy does **not** match or auto-link to an existing account by username or email — that would allow account takeover, so the first login for a given `sub` always provisions a brand-new user with the `user` role. Claims (`docs.oidc_username_claim`, `docs.oidc_email_claim`) supply the *provisioning* username/email and refresh the stored email on later logins; they never change which account you are logged into.
6. A session cookie is set and the user is redirected to the application (preserving the original URL if the user followed a deep link)

#### Identity, claims, and provisioning (important)

- **`sub` is the only identity key.** Login requires a `sub` claim; a token without one is rejected. The stored binding is `(issuer, sub)`, enforced unique in the database (`IDX_USER_OIDC`), so repeated logins with the same identity always converge on the same single account — even under concurrent first logins.
- **Claims affect profile data, never account linking.** `docs.oidc_username_claim` (default `preferred_username`) is sanitized to Teedy's username charset and suffixed with a deterministic hash of the identity, so two providers/subjects that share a display name never collide. `docs.oidc_email_claim` (default `email`) sets the address at provisioning and refreshes it on later logins — but a temporarily absent or invalid email claim never overwrites a previously stored address.
- **UserInfo is the supported claim source for minimal-ID-token providers.** When a configured claim is absent from the ID token (default Authelia ≥ 4.38 ships a minimal ID token), Teedy fetches the provider's UserInfo endpoint with the access token and verifies the returned `sub` exactly matches the ID-token `sub` before consuming any claim (a mismatched response is rejected). The endpoint is `docs.oidc_userinfo_endpoint` if set, else the discovery document's `userinfo_endpoint`.

### Security Features

- **PKCE (S256)**: Protects against authorization code interception
- **Stable subject binding**: Users are identified only by `(issuer, sub)` — never by username or email — preventing account takeover. A token with no `sub` is rejected, and the binding is enforced unique in the database so re-provisioning is idempotent
- **Sub-verified UserInfo**: When claims are read from the UserInfo endpoint, its `sub` must exactly match the ID-token `sub` or the response is discarded (fail closed)
- **JWKS validation**: Keys are filtered by kty/use/alg; cache refreshes automatically on key rotation
- **Discovery issuer verification**: The OIDC discovery document's issuer is cross-checked against configuration
- **Nonce verification**: Fail-closed — missing nonce always rejects the login
- **Persistent state**: CSRF state and nonce are stored in the database, surviving restarts

### Docker Networking

When Teedy runs in a Docker container, it often cannot resolve the external OIDC issuer URL (e.g., `https://auth.example.com`). In this case, use the explicit endpoint overrides to split browser-facing and server-to-server URLs:

```yaml
JAVA_TOOL_OPTIONS: >-
  -Ddocs.oidc_enabled=true
  -Ddocs.oidc_issuer=https://auth.example.com
  -Ddocs.oidc_client_id=teedy
  -Ddocs.oidc_client_secret=your-secret-here
  -Ddocs.oidc_redirect_uri=https://teedy.example.com/api/oidc/callback
  -Ddocs.oidc_authorization_endpoint=https://auth.example.com/api/oidc/authorization
  -Ddocs.oidc_token_endpoint=http://authelia:9091/api/oidc/token
  -Ddocs.oidc_jwks_uri=http://authelia:9091/jwks.json
  -Ddocs.oidc_userinfo_endpoint=http://authelia:9091/api/oidc/userinfo
```

The authorization endpoint uses the external URL (browser redirect), while the token, JWKS, and UserInfo endpoints use internal Docker DNS (server-to-server). The UserInfo endpoint is only contacted when a configured claim is missing from the ID token.

### Authelia Setup

When using Authelia as the OIDC provider, you must add a `claims_policy` to include `preferred_username` and `email` in the ID token (they are not included by default):

```yaml
identity_providers:
  oidc:
    claims_policies:
      teedy:
        id_token:
          - 'preferred_username'
          - 'email'
          - 'name'
    clients:
      - client_id: 'teedy'
        client_name: 'Teedy'
        client_secret: '$pbkdf2-sha512$...'
        public: false
        authorization_policy: 'two_factor'
        consent_mode: 'implicit'
        redirect_uris:
          - 'https://teedy.example.com/api/oidc/callback'
        scopes:
          - 'openid'
          - 'profile'
          - 'email'
        response_types:
          - 'code'
        grant_types:
          - 'authorization_code'
        userinfo_signed_response_alg: 'none'
        token_endpoint_auth_method: 'client_secret_post'
        claims_policy: 'teedy'
```

The `claims_policy` above puts `preferred_username`/`email` directly in the ID token, which is the simplest setup. If you omit it, the ID token carries only the `sub` claim (an opaque UUID); Teedy then falls back to the **UserInfo endpoint** to read the configured claims (see *Identity, claims, and provisioning* above). Either way, login works and the account is bound to `(issuer, sub)` — the claims only determine the provisioning username and the stored email, never which account you sign in to. To use a claim other than `preferred_username`/`email` (for example a Keycloak protocol-mapper claim), set `docs.oidc_username_claim` / `docs.oidc_email_claim` accordingly.

### Coexistence with Header Auth

OIDC and header-based proxy auth (`-Ddocs.header_authentication=true`) can both be active simultaneously. Header auth is useful as a fallback for API access from the local network, while OIDC provides proper per-user identity for browser sessions.

### Running behind oauth2-proxy

If you put [oauth2-proxy](https://oauth2-proxy.github.io/oauth2-proxy/) in front of Teedy instead of (or in addition to) using the native OIDC client, four details are commonly misconfigured:

- **Header modes are not interchangeable.** In **nginx `auth_request` mode**, the authenticated identity comes back as *response* headers `X-Auth-Request-User` / `X-Auth-Request-Email` — these require `--set-xauthrequest` (default **false**) and must be surfaced to the upstream via `auth_request_set` in your nginx config. In **reverse-proxy mode**, oauth2-proxy forwards the request itself and injects *request* headers `X-Forwarded-User` / `X-Forwarded-Email` via `--pass-user-headers` (default **true**). Pick one model and wire the matching header names; expecting `X-Auth-Request-*` without `--set-xauthrequest` is the usual cause of "the proxy authenticates but the app sees no user". To feed Teedy's header auth, map the chosen header onto `X-Authenticated-User` and configure `docs.header_authentication_trusted_proxies`.
- **Uploads.** On the nginx `/oauth2/auth` subrequest location, use the vendor-documented pattern `proxy_pass_request_body off;` + `proxy_set_header Content-Length "";` so request bodies never hit the auth endpoint. Keep `client_max_body_size` on the *application* location, sized to match `DOCS_MAX_UPLOAD_SIZE`.
- **Logout.** `/oauth2/sign_out?rd=<url-encoded provider logout URL>` clears **only the proxy cookie**; the IdP session ends only if `rd=` redirects on to the provider's logout URL. The redirect target's domain must be listed in `--whitelist-domain` — entries are `domain[:port]` values (not URLs); a leading `.` (e.g. `.example.com`) matches subdomains.
- **Local-admin escape hatch.** Keep a path to the login form that does not depend on the proxy: the built-in `admin` account authenticates against the local database and is deliberately never authenticated through the proxy header path, so a misconfigured or down IdP/proxy cannot lock you out.

## Examples

In the following examples some passwords are exposed in cleartext. This was done in order to keep the examples simple. We strongly encourage you to use variables with an `.env` file or other means to securely store your passwords.

### Default, using PostgreSQL

```yaml
services:
  teedy-server:
    image: ghcr.io/fmaass/teedy-docs:v3.2.2
    restart: unless-stopped
    ports:
      - 8080:8080
    environment:
      DOCS_BASE_URL: "https://docs.example.com"
      DOCS_ADMIN_EMAIL_INIT: "admin@example.com"
      DOCS_ADMIN_PASSWORD_INIT: "$$2a$$05$$PcMNUbJvsk7QHFSfEIDaIOjk1VI9/E7IPjTKx.jkjPxkx2EOKSoPS"
      DOCS_MAX_UPLOAD_SIZE: "524288000"
      DOCS_SESSION_LIFETIME_DAYS: "90"
      DOCS_LOGIN_MAX_ATTEMPTS: "5"
      DOCS_LOGIN_LOCKOUT_SECONDS: "60"
      DOCS_SMTP_FROM: "teedy@example.com"
      DOCS_EXPORT_ENABLED: "true"
      DOCS_EXPORT_MAX_DOCUMENTS: "10000"
      DOCS_EXPORT_MAX_CONCURRENT: "2"
      DOCS_ASYNC_QUEUE_CAPACITY: "1000"
      DOCS_ASYNC_RETRY_COUNT: "0"
      DOCS_ASYNC_RETRY_BACKOFF_MS: "200"
      DOCS_WEBHOOK_ALLOW_PRIVATE: "false"
      DATABASE_URL: "jdbc:postgresql://teedy-db:5432/teedy"
      DATABASE_USER: "teedy_db_user"
      DATABASE_PASSWORD: "teedy_db_password"
      DATABASE_POOL_SIZE: "10"
    volumes:
      - ./docs/data:/data
    networks:
      - docker-internal
      - internet
    depends_on:
      - teedy-db

  teedy-db:
    image: postgres:17-alpine
    restart: unless-stopped
    expose:
      - 5432
    environment:
      POSTGRES_USER: "teedy_db_user"
      POSTGRES_PASSWORD: "teedy_db_password"
      POSTGRES_DB: "teedy"
    volumes:
      - ./docs/db:/var/lib/postgresql/data
    networks:
      - docker-internal

networks:
  docker-internal:
    driver: bridge
    internal: true
  internet:
    driver: bridge
```

### Using the internal database (only for testing)

```yaml
services:
  teedy-server:
    image: ghcr.io/fmaass/teedy-docs:v3.2.2
    restart: unless-stopped
    ports:
      - 8080:8080
    environment:
      DOCS_BASE_URL: "https://docs.example.com"
      DOCS_ADMIN_EMAIL_INIT: "admin@example.com"
      DOCS_ADMIN_PASSWORD_INIT: "$$2a$$05$$PcMNUbJvsk7QHFSfEIDaIOjk1VI9/E7IPjTKx.jkjPxkx2EOKSoPS"
      DOCS_MAX_UPLOAD_SIZE: "524288000"
    volumes:
      - ./docs/data:/data
```

# Manual installation

## Requirements

- Java 21
- Tesseract 4+ for OCR
- ffmpeg for video thumbnails
- mediainfo for video metadata extraction
- A webapp server like [Jetty](http://eclipse.org/jetty/) or [Tomcat](http://tomcat.apache.org/)

## Download

The latest release is downloadable here: <https://github.com/fmaass/teedy-docs/releases> in WAR format.
**The default admin password is "admin". Don't forget to change it before going to production.**

## How to build Teedy from the sources

Prerequisites: JDK 21, Maven 3.9+ (or use the included `./mvnw` wrapper), NPM, Tesseract 4+

Teedy is organized in several Maven modules:

- docs-core
- docs-web
- docs-web-common

First off, clone the repository: `git clone https://github.com/fmaass/teedy-docs.git`
or download the sources from GitHub.

### Launch the build

From the root directory:

```console
./mvnw clean -DskipTests install
```

### Run a stand-alone version

From the `docs-web` directory:

```console
../mvnw jetty:run
```

### Build a .war to deploy to your servlet container

From the root directory:

```console
./mvnw -Pprod -DskipTests clean install
```

You will get your deployable WAR in the `docs-web/target` directory.

### End-to-end tests

End-to-end tests use [Playwright](https://playwright.dev/) to drive a **real
running Teedy instance** — the production Docker image on port 8080 (embedded H2,
default `admin`/`admin`). They log in through Teedy's **native form login**, not
Authelia (Authelia only fronts the production deployment).

Run them locally from the repository root:

```console
scripts/e2e-run.sh
```

The script builds the production WAR + Docker image (if not already built), boots
the container, waits for `/api/user` to serve, runs the suite, and tears the
container down. First run only: install the browser once with
`cd docs-web/src/main/webapp && npx playwright install chromium`.

To reuse an already-built image and skip the WAR/image build:

```console
E2E_IMAGE=teedy-e2e:local scripts/e2e-run.sh
```

The Playwright project lives in `docs-web/src/main/webapp` (`playwright.config.ts`,
`e2e/`). `@playwright/test` is a dev dependency, so the browser is only downloaded
in the e2e job — `npm ci` for the unit-test/build pipeline is unaffected. In CI the
`e2e` job runs after `build`, reusing the WAR artifact, and the image publish gates
on it.

# Roadmap

See the [GitHub releases and tags](https://github.com/fmaass/teedy-docs/releases) for shipped changes.

# Backing up

Back up both the PostgreSQL database with `pg_dump` and the document storage directory (`docs.home`, typically the `/data` volume) together. Stored files are encrypted and cannot be used without the database metadata, and the database cannot restore usable documents without the matching files.

Verify the backup by restoring it into a scratch database and storage directory before relying on it.

# Contributing

All contributions are more than welcomed. Contributions may close an issue, fix a bug (reported or not reported), improve the existing code, add new feature, and so on.

The `main` branch is the default and base branch for the project. It is used for development and all Pull Requests should go there.

# License

Teedy is released under the terms of the GPL license. See `COPYING` for more
information or see <http://opensource.org/licenses/GPL-2.0>.
