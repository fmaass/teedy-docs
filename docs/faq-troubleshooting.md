# FAQ & troubleshooting

Common questions and failure modes, with pointers to the deeper pages.

## Authentication

### Why did my OIDC / SSO login fail?

OIDC is **fail-closed** — any missing or mismatched security value rejects the
login rather than silently downgrading. The most common causes:

| Symptom | Cause | Fix |
|---------|-------|-----|
| Rejected right after the provider redirect | ID token has no `sub` claim | `sub` is the only identity key and is mandatory — ensure the provider issues one |
| Rejected with a nonce error | Nonce missing or mismatched | Nonce verification is fail-closed; confirm the provider returns the nonce |
| Rejected after the UserInfo fallback | UserInfo `sub` did not match the ID-token `sub` | Align the provider's UserInfo `sub` with the ID token |
| Provisioned user but blank/wrong email | Email claim missing from ID token and UserInfo | Add `email` to the claims policy or set `docs.oidc_email_claim` |
| Account disabled | The account has a disable date | An admin must re-enable the user |

The full flow-by-flow table is on the
[authentication page](authentication.md#troubleshooting--why-did-my-login-fail).

### The proxy authenticates but Teedy sees no user

You are in the wrong oauth2-proxy header mode. `auth_request` mode returns
`X-Auth-Request-*` *response* headers (need `--set-xauthrequest`); reverse-proxy
mode injects `X-Forwarded-*` *request* headers. Wire the matching header and map it
onto `X-Authenticated-User`. See
[running behind oauth2-proxy](authentication.md#running-behind-oauth2-proxy).

### All header auth is refused

Header auth is enabled but `docs.header_authentication_trusted_proxies` is empty —
that is fail-closed by design. Set the allowlist of trusted proxy IPs/CIDRs.

### I'm locked out of admin after an SSO outage

The built-in `admin` account authenticates against the **local database** and is
never routed through OIDC, the proxy header, or LDAP. Use it on the login form.
For a lost admin password, see [RECOVERY.md](../RECOVERY.md).

## Email / SMTP

### Password-reset (or workflow) emails never arrive

An SMTP `250` from the relay is *acceptance*, not *delivery*. Verify by actually
receiving a message in an inbox — trigger a `password_lost` for a test account and
confirm the reset mail arrives. Check `DOCS_SMTP_HOSTNAME`, `DOCS_SMTP_PORT`,
`DOCS_SMTP_USERNAME`, `DOCS_SMTP_PASSWORD`, and `DOCS_SMTP_FROM` (see
[configuration](configuration.md#e-mail-smtp)).

### Can I run without SMTP?

Yes. Password recovery then goes through the admin (Settings → Users → edit →
set password), which needs no email. The full recovery matrix — local vs OIDC
accounts, with and without SMTP — is in [RECOVERY.md](../RECOVERY.md).

## Accounts & recovery

### A user lost their password

- **Local user, SMTP configured:** "Forgot password?" on the login form emails a
  reset link.
- **Local user, no SMTP:** an admin resets it in Settings → Users.
- **OIDC user:** recover at the identity provider — Teedy holds no usable password
  for them.

Full detail (including last-admin recovery and the SQL to reset the `admin` hash)
is in [RECOVERY.md](../RECOVERY.md).

### A user lost their TOTP device

An admin can disable their second factor (`POST /api/user/{username}/disable_totp`
with an admin session); the user can then log in and re-enrol. See
[authentication](authentication.md#5-two-factor-authentication-totp).

## Documents

### I deleted a document by mistake

Deleted documents go to the [trash / recycle bin](documents.md#trash--recycle-bin),
not oblivion. Restore them there until they are auto-purged
(`DOCS_TRASH_RETENTION_DAYS`, default 30 days; `0` disables auto-purge).

## Backups

### What do I need to back up?

Both the PostgreSQL database **and** the document storage directory (`docs.home` /
the `/data` volume), together. Stored files are AES-encrypted and useless without
the database metadata, and vice versa. Verify by restoring into a scratch
environment before relying on the backup. See
[configuration](configuration.md#data-directory-and-backup).

## See also

- [Authentication](authentication.md) — every auth flow and its failure modes
- [RECOVERY.md](../RECOVERY.md) — account and admin recovery
- [SECURITY.md](../SECURITY.md) — reporting a vulnerability
