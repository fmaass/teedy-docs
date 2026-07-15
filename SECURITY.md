# Security Policy

## Supported Versions

This fork tracks the 3.x line. Security fixes land on the latest minor; the immediately
preceding minor receives security fixes only.

| Version | Supported           |
| ------- | ------------------- |
| 3.6.x   | Yes                 |
| 3.5.x   | Security fixes only |
| < 3.5   | No                  |

## Reporting a Vulnerability

Please report vulnerabilities privately — **do not open a public GitHub issue.**

1. Preferred: use GitHub's **private vulnerability reporting** for this repository
   (repository **Security** tab → **Report a vulnerability**). This opens a private
   advisory visible only to the maintainers.
2. Alternatively, email the maintainer at the address listed on the
   [GitHub profile](https://github.com/fmaass).
3. Include a description of the vulnerability, steps to reproduce, affected version,
   and any potential impact.
4. You will receive an acknowledgement within 7 days.

We will work with you to understand the issue and coordinate a fix and disclosure.

## Data-at-Rest

Teedy does **not** encrypt document files or database contents at rest (see ADR-0012).
Uploaded files are stored on the configured storage volume and database rows in
PostgreSQL (or embedded H2) as-is. Protecting the host, the storage volume, and the
database — for example with full-disk / filesystem encryption and restricted access —
is the operator's responsibility. Secrets that Teedy manages (password hashes, API-key
hashes, OIDC client secret, the CSRF proxy key) are stored hashed or as opaque values,
but the underlying store itself is not encrypted by the application.

## Cross-Site Request Forgery (CSRF)

CSRF protection infrastructure is present but runs in **report-only mode** (off by
default) as of v3.6.5-rc.2. See **`docs/csrf.md`** for the mechanism, the credential
precedence model, and how enforcement is enabled in a future release.
