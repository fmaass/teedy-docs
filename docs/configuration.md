# Configuration

Teedy is configured through environment variables (`DOCS_*`, `DATABASE_*`) and a
handful of JVM system properties (`-D` flags). This page is the complete reference;
[getting started](getting-started.md) covers the minimal set to get running.

The data directory is `/data` inside the container — mount a volume on it. To build
external URLs correctly, always set `DOCS_BASE_URL`.

## Environment variables

### General

| Variable | Description | Default |
|----------|-------------|---------|
| `DOCS_BASE_URL` | Public base URL; generated URLs use this as their base | — |
| `DOCS_GLOBAL_QUOTA` | Default storage quota applied to all users | — |
| `DOCS_MAX_UPLOAD_SIZE` | Maximum accepted upload size, in bytes | `524288000` (500 MB) |
| `DOCS_BCRYPT_WORK` | Bcrypt work factor for password hashing (`4`–`31`); applied to new users and password changes. High values slow login/user creation | `10` |
| `DOCS_SESSION_LIFETIME_DAYS` | Days before login sessions expire. Missing/malformed/non-positive values fall back to the default | `90` |
| `DOCS_LOGIN_MAX_ATTEMPTS` | Failed login attempts before temporary lockout starts | `5` |
| `DOCS_LOGIN_LOCKOUT_SECONDS` | Base lockout duration in seconds; repeated failures apply exponential backoff capped at 15 minutes | `60` |
| `APPDATA` | Windows AppData directory, used to resolve the default data dir when `docs.home` is unset | — |

### Admin bootstrap

| Variable | Description |
|----------|-------------|
| `DOCS_ADMIN_EMAIL_INIT` | Email for the built-in `admin` user at initialization |
| `DOCS_ADMIN_PASSWORD_INIT` | Initial password for `admin`, as a **bcrypt hash**. In compose YAML, escape each `$` as `$$`. Only applies while `admin` still holds the factory-default password (see [RECOVERY.md](../RECOVERY.md)) |

### Database

| Variable | Description |
|----------|-------------|
| `DATABASE_URL` | JDBC connection string for Hibernate. **If unset, Teedy uses an embedded H2 database** (testing only) |
| `DATABASE_USER` | Database user |
| `DATABASE_PASSWORD` | Database password |
| `DATABASE_POOL_SIZE` | Connection pool size |

### Language / OCR

| Variable | Description |
|----------|-------------|
| `DOCS_DEFAULT_LANGUAGE` | Default OCR language. Supported: `eng`, `fra`, `ita`, `deu`, `spa`, `por`, `pol`, `rus`, `ukr`, `ara`, `hin`, `chi_sim`, `chi_tra`, `jpn`, `tha`, `kor`, `nld`, `tur`, `heb`, `hun`, `fin`, `swe`, `lav`, `dan`, `nor`, `vie`, `ces`, `sqi` |

### E-mail (SMTP)

| Variable | Description |
|----------|-------------|
| `DOCS_SMTP_HOSTNAME` | SMTP server hostname |
| `DOCS_SMTP_PORT` | SMTP server port |
| `DOCS_SMTP_USERNAME` | SMTP username |
| `DOCS_SMTP_PASSWORD` | SMTP password |
| `DOCS_SMTP_FROM` | Sender address for outbound mail; falls back to the in-app configured value if unset |

SMTP is used for password-reset emails and workflow-rejection notifications. A
`250` from the relay is acceptance, not delivery — verify inbox receipt (see
[faq-troubleshooting.md](faq-troubleshooting.md)).

### Trash

| Variable | Description | Default |
|----------|-------------|---------|
| `DOCS_TRASH_RETENTION_DAYS` | Days to keep deleted documents before auto-purge. Set to `0` to disable auto-purge | `30` |

### Export

| Variable | Description | Default |
|----------|-------------|---------|
| `DOCS_EXPORT_ENABLED` | Enables the full-account export endpoint. `false` disables exports | `true` |
| `DOCS_EXPORT_MAX_DOCUMENTS` | Max documents in a single export. Values below `1` fall back to the default | `10000` |
| `DOCS_EXPORT_MAX_CONCURRENT` | Max simultaneous exports. Values below `1` fall back to the default | `2` |

### Async processing

| Variable | Description | Default |
|----------|-------------|---------|
| `DOCS_ASYNC_QUEUE_CAPACITY` | Max queued async events before the producer applies backpressure. Values below `1` fall back to the default | `1000` |
| `DOCS_ASYNC_RETRY_COUNT` | Times failed async listeners are retried | `0` |
| `DOCS_ASYNC_RETRY_BACKOFF_MS` | Backoff (ms) between async listener retries | `200` |

### Webhooks

| Variable | Description | Default |
|----------|-------------|---------|
| `DOCS_WEBHOOK_ALLOW_PRIVATE` | Allow webhook URLs targeting private, loopback, or link-local addresses | `false` |

## JVM system properties (`-D`)

A few settings are read only as JVM system properties, passed via
`JAVA_OPTIONS` / `JAVA_TOOL_OPTIONS` (or a `-D` flag) — not as `DOCS_*` variables:

| Property | Description |
|----------|-------------|
| `docs.home` | Absolute path to the data directory (documents, index, H2 database). Defaults: `/var/docs` on Linux, `%APPDATA%\Sismics\Docs` on Windows, `~/Library/Sismics/Docs` on macOS. In the Docker image this is the `/data` volume |
| `application.mode` | `dev` for development mode; any other value (or unset) is production mode |
| `docs.logout_url` | External URL the browser is redirected to after logout; takes precedence over any OIDC `end_session_endpoint` |
| `docs.header_authentication` | `true` to enable [header/proxy auth](authentication.md#3-header--reverse-proxy-authentication) |
| `docs.header_authentication_trusted_proxies` | Comma-separated allowlist of IPs/CIDRs permitted to assert `X-Authenticated-User`. Empty list = fail-closed (all header auth refused). The `admin` account is never authenticated via this header |

The full set of `docs.oidc_*` properties is documented on the
[authentication page](authentication.md#configuration).

## Data directory and backup

Stored files are AES-encrypted on disk and are useless without the database
metadata; the database cannot restore usable documents without the matching files.
**Back up both together:**

1. Dump the PostgreSQL database (`pg_dump`).
2. Back up the document storage directory (`docs.home`, typically the `/data`
   volume).
3. Verify by restoring both into a scratch database and storage directory before
   relying on the backup.

## See also

- [Getting started](getting-started.md) — the minimal compose setup
- [Authentication](authentication.md) — OIDC / LDAP / header-auth configuration
- [Admin guide](admin-guide.md) — webhooks, quotas, SMTP, OCR, theme
