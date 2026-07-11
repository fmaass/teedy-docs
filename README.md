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
- **LDAP authentication, hardened beyond upstream** — empty-bind rejection, RFC-4515 filter escaping, account-hijack guard, and bounded connect/response timeouts
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

# Documentation

Full self-hoster documentation lives in [`docs/`](docs/README.md):

- [Getting started](docs/getting-started.md) — Docker Compose quick start and first login
- [Configuration](docs/configuration.md) — complete environment-variable and `-D` property reference
- [Authentication](docs/authentication.md) — OIDC/SSO, LDAP, reverse-proxy header auth, and troubleshooting
- [Documents](docs/documents.md), [Tags & filtering](docs/tags-and-filtering.md), [Workflows](docs/workflows.md), [Vocabulary](docs/vocabulary.md)
- [Sharing & permissions](docs/sharing-and-permissions.md), [Admin guide](docs/admin-guide.md), [REST API](docs/api.md), [FAQ & troubleshooting](docs/faq-troubleshooting.md)

Related root documents: [RECOVERY.md](RECOVERY.md) (account/admin recovery),
[SECURITY.md](SECURITY.md) (reporting a vulnerability), and [CHANGELOG.md](CHANGELOG.md).

# Install with Docker

A preconfigured Docker image (with OCR and media-conversion tools) listens on port
`8080`. With no `DATABASE_URL`, Teedy uses an embedded H2 database — **for testing
only**; use the PostgreSQL configuration in production.

- Latest stable version: `ghcr.io/fmaass/teedy-docs:v3.4.0`
- Development (main branch, may be unstable): `ghcr.io/fmaass/teedy-docs:latest`

The data directory is `/data` — mount a volume on it — and `DOCS_BASE_URL` must be
set to your public URL. A ready-to-run Compose stack and the minimal steps are in
[Getting started](docs/getting-started.md); the [Examples](#examples) below are a
quick reference.

**The default admin password is "admin". Change it before going to production.**

## Configuration

Teedy is configured through `DOCS_*` / `DATABASE_*` environment variables and a few
JVM `-D` system properties. The complete reference — every variable, its default,
the data directory, and backup guidance — is in
[Configuration](docs/configuration.md).

## Authentication

Teedy supports local login, OpenID Connect (OIDC) SSO, reverse-proxy header auth,
and LDAP, and they can be combined. OIDC uses the Authorization Code flow with PKCE
and a confidential client, auto-provisions users on first login, and binds each
account to its `(issuer, sub)` pair (never username/email, to prevent account
takeover). LDAP is hardened beyond upstream (empty-bind rejection, RFC-4515 filter
escaping, an account-hijack guard, and bounded timeouts). The built-in `admin`
account always authenticates locally, so a down SSO provider can never lock you out.

Full setup for every flow — including Authelia and generic-provider OIDC, Docker
split-endpoint networking, oauth2-proxy header wiring, LDAP fields, the provisioning
contract, and a login-failure troubleshooting table — is in
[Authentication](docs/authentication.md).

## API key authentication

Create API keys in **Settings → API Keys** and send them in the `Authorization`
header (`Bearer tdapi_<your-key>`). A key acts as its creating user with the same
permissions, and the raw key is shown only once. See [REST API](docs/api.md).

## Examples

In the following examples some passwords are exposed in cleartext. This was done in order to keep the examples simple. We strongly encourage you to use variables with an `.env` file or other means to securely store your passwords.

### Default, using PostgreSQL

```yaml
services:
  teedy-server:
    image: ghcr.io/fmaass/teedy-docs:v3.4.0
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
    image: ghcr.io/fmaass/teedy-docs:v3.4.0
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
