<h3 align="center">
  <img src="https://teedy.io/img/github-title.png" alt="Teedy" width=500 />
</h3>

[![License: GPL v2](https://img.shields.io/badge/License-GPL%20v2-blue.svg)](https://www.gnu.org/licenses/old-licenses/gpl-2.0.en.html)
[![Build and Publish](https://github.com/fmaass/teedy-docs/actions/workflows/build-deploy.yml/badge.svg)](https://github.com/fmaass/teedy-docs/actions/workflows/build-deploy.yml)

> **This is an actively maintained fork of [sismics/docs](https://github.com/sismics/docs) (Teedy).**
> It includes OIDC/SSO authentication, Java 17 + Jetty 11 modernization, security hardening, and multi-arch Docker images published to GHCR.

Teedy is an open source, lightweight document management system for individuals and businesses.

# What's different in this fork

- **OpenID Connect (OIDC) authentication** with PKCE, auto-provisioning, and stable subject binding
- **Header-based proxy authentication** (e.g., Authelia, Authentik) with auto-skip login
- **Java 17 + Jetty 11 + Jakarta EE 9** (upgraded from Java 11 / Jetty 9)
- **Multi-arch Docker images** (amd64 + arm64) published to GitHub Container Registry
- **Security hardening**: JWKS key validation, discovery issuer verification, nonce fail-closed, JWT bearer filter with iss/aud checks
- **Log4j 1.x removed**, replaced with Logback
- **Dependency updates**: Hibernate 6.3, Jersey 3.1, Guava 33, OkHttp 4.12, PostgreSQL driver 42.6

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
- Custom user-defined metadata
- Workflow system
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
- Fully featured Android client
- [Bulk files importer](https://github.com/fmaass/teedy-docs/tree/main/docs-importer) (single or scan mode)
- Tested to one million documents

# Install with Docker

A preconfigured Docker image is available, including OCR and media conversion tools, listening on port 8080. If no PostgreSQL config is provided, the database is an embedded H2 database. The H2 embedded database should only be used for testing. For production usage use the provided PostgreSQL configuration (check the Docker Compose example).

**The default admin password is "admin". Don't forget to change it before going to production.**

- Latest stable version: `ghcr.io/fmaass/teedy-docs:v2.1.0`
- Development (main branch, may be unstable): `ghcr.io/fmaass/teedy-docs:latest`

The data directory is `/data`. Don't forget to mount a volume on it.

To build external URL, the server is expecting a `DOCS_BASE_URL` environment variable (for example https://teedy.mycompany.com)

## Available environment variables

- General
  - `DOCS_BASE_URL`: The base url used by the application. Generated url's will be using this as base.
  - `DOCS_GLOBAL_QUOTA`: Defines the default quota applying to all users.
  - `DOCS_BCRYPT_WORK`: Defines the work factor which is used for password hashing. The default is `10`. This value may be `4...31` including `4` and `31`. The specified value will be used for all new users and users changing their password. Be aware that setting this factor to high can heavily impact login and user creation performance.

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
    - `eng`, `fra`, `ita`, `deu`, `spa`, `por`, `pol`, `rus`, `ukr`, `ara`, `hin`, `chi_sim`, `chi_tra`, `jpn`, `tha`, `kor`, `nld`, `tur`, `heb`, `hun`, `fin`, `swe`, `lav`, `dan`

- E-Mail
  - `DOCS_SMTP_HOSTNAME`: Hostname of the SMTP-Server to be used by Teedy.
  - `DOCS_SMTP_PORT`: The port which should be used.
  - `DOCS_SMTP_USERNAME`: The username to be used.
  - `DOCS_SMTP_PASSWORD`: The password to be used.

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

### How It Works

1. User navigates to `/api/oidc/login` (or clicks "Login with SSO" on the login page)
2. Teedy redirects to the OIDC provider's authorization endpoint (with PKCE challenge)
3. After authentication, the provider redirects back to `/api/oidc/callback`
4. Teedy exchanges the authorization code for tokens (with PKCE verifier), verifies the ID token signature (RSA via JWKS), and validates issuer, audience, and nonce claims
5. The user is matched by OIDC subject (stable binding), then by `preferred_username`, then by `email`. If no match exists, a new user is auto-provisioned with the `user` role
6. A session cookie is set and the user is redirected to the application (preserving the original URL if the user followed a deep link)

### Security Features

- **PKCE (S256)**: Protects against authorization code interception
- **Stable subject binding**: After first login, users are bound to their IdP `sub` claim, preventing email-based account takeover
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
```

The authorization endpoint uses the external URL (browser redirect), while the token endpoint and JWKS URI use internal Docker DNS (server-to-server).

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

Without the `claims_policy`, the ID token will only contain the `sub` claim (an opaque UUID), and Teedy will be unable to match existing users.

### Coexistence with Header Auth

OIDC and header-based proxy auth (`-Ddocs.header_authentication=true`) can both be active simultaneously. Header auth is useful as a fallback for API access from the local network, while OIDC provides proper per-user identity for browser sessions.

## JWT Bearer Authentication

For API-to-API authentication using JWT bearer tokens (e.g., from Keycloak), configure:

| Property | Required | Description |
|----------|----------|-------------|
| `docs.jwt_authentication` | Yes | Set to `true` to enable |
| `docs.jwt_expected_issuer` | Yes | Expected `iss` claim (must match exactly) |
| `docs.jwt_expected_audience` | Yes | Expected `aud` claim |

If JWT authentication is enabled without both `issuer` and `audience` configured, it will be automatically disabled (fail closed).

## Examples

In the following examples some passwords are exposed in cleartext. This was done in order to keep the examples simple. We strongly encourage you to use variables with an `.env` file or other means to securely store your passwords.

### Default, using PostgreSQL

```yaml
version: '3'
services:
  teedy-server:
    image: ghcr.io/fmaass/teedy-docs:v2.1.0
    restart: unless-stopped
    ports:
      - 8080:8080
    environment:
      DOCS_BASE_URL: "https://docs.example.com"
      DOCS_ADMIN_EMAIL_INIT: "admin@example.com"
      DOCS_ADMIN_PASSWORD_INIT: "$$2a$$05$$PcMNUbJvsk7QHFSfEIDaIOjk1VI9/E7IPjTKx.jkjPxkx2EOKSoPS"
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
    image: postgres:16-alpine
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
version: '3'
services:
  teedy-server:
    image: ghcr.io/fmaass/teedy-docs:v2.1.0
    restart: unless-stopped
    ports:
      - 8080:8080
    environment:
      DOCS_BASE_URL: "https://docs.example.com"
      DOCS_ADMIN_EMAIL_INIT: "admin@example.com"
      DOCS_ADMIN_PASSWORD_INIT: "$$2a$$05$$PcMNUbJvsk7QHFSfEIDaIOjk1VI9/E7IPjTKx.jkjPxkx2EOKSoPS"
    volumes:
      - ./docs/data:/data
```

# Manual installation

## Requirements

- Java 17
- Tesseract 4 for OCR
- ffmpeg for video thumbnails
- mediainfo for video metadata extraction
- A webapp server like [Jetty](http://eclipse.org/jetty/) or [Tomcat](http://tomcat.apache.org/)

## Download

The latest release is downloadable here: <https://github.com/fmaass/teedy-docs/releases> in WAR format.
**The default admin password is "admin". Don't forget to change it before going to production.**

## How to build Teedy from the sources

Prerequisites: JDK 17, Maven 3, NPM, Grunt, Tesseract 4

Teedy is organized in several Maven modules:

- docs-core
- docs-web
- docs-web-common

First off, clone the repository: `git clone https://github.com/fmaass/teedy-docs.git`
or download the sources from GitHub.

### Launch the build

From the root directory:

```console
mvn clean -DskipTests install
```

### Run a stand-alone version

From the `docs-web` directory:

```console
mvn jetty:run
```

### Build a .war to deploy to your servlet container

From the `docs-web` directory:

```console
mvn -Pprod -DskipTests clean install
```

You will get your deployable WAR in the `docs-web/target` directory.

# Contributing

All contributions are more than welcomed. Contributions may close an issue, fix a bug (reported or not reported), improve the existing code, add new feature, and so on.

The `main` branch is the default and base branch for the project. It is used for development and all Pull Requests should go there.

# License

Teedy is released under the terms of the GPL license. See `COPYING` for more
information or see <http://opensource.org/licenses/GPL-2.0>.
