# Account Recovery

How to regain access to a Teedy account. Teedy has two kinds of account, and the
recovery path depends on which kind — and on whether outbound email (SMTP) is
configured.

## Account types

| Type | Password | Identity owner | How they log in |
|------|----------|----------------|-----------------|
| **Local** | Stored in Teedy (bcrypt) | Teedy | Username + password on the login form |
| **OIDC** | Random UUID, never usable | The identity provider (Authelia) | "Login with SSO" → the IdP |

An OIDC account is provisioned on first SSO login with a random password it never
reveals, so it **cannot** log in locally. Its real credential lives in the IdP.

## Recovery matrix

| Situation | SMTP configured | Recovery path |
|-----------|-----------------|---------------|
| Local user forgot password | Yes | Self-service: **"Forgot password?"** on the login form → `POST /user/password_lost` emails a reset link → user sets a new password (`POST /user/password_reset`). |
| Local user forgot password | No | **Admin reset (no email):** Settings → Users → edit the user → set a new password → Save. Backed by `POST /user/{username}` (admin only); no SMTP involved. Tell the user the new password over a trusted channel and have them change it. |
| OIDC user cannot log in | n/a | Recover at the **identity provider** (Authelia) — Teedy holds no usable password for them. As a fallback, an admin can set a *local* password (as above) to enable local login, but the normal fix is IdP-side. |
| User locked out by 2FA / lost TOTP device | n/a | Admin disables their second factor: `POST /user/{username}/disable_totp` (no UI button yet — call the endpoint, e.g. via `curl` with an admin session). The user can then log in and re-enrol. |
| **Admin** password lost, another admin exists | n/a | The other admin resets it (admin reset row above). |
| **Admin** password lost, no other admin | n/a | See "Last-admin recovery" below. |

## SMTP-free admin reset (the common homelab case)

This deployment often runs without outbound email. The supported recovery is the
admin direct reset, which needs **no SMTP**:

1. Log in as an admin.
2. Settings → Users → edit the affected user.
3. Enter a new password and Save.
4. The old password immediately stops working; the new one logs in.

This path is covered by an automated test
(`TestUserResource#testAdminPasswordResetWithoutSmtp`) so it cannot silently regress.

## Last-admin recovery

`DOCS_ADMIN_PASSWORD_INIT` (a **bcrypt hash**, applied at container startup) only
resets the built-in `admin` account **while it still holds the factory-default
password**. Once the admin password has been changed, that env var is ignored — so
it is a first-boot bootstrap, not an ongoing reset lever.

If the only admin's password is lost and non-default, reset the `admin` row's
password hash in the database back to the factory default, then restart with
`DOCS_ADMIN_PASSWORD_INIT` set (or log in with the default and change it):

```sql
-- factory-default admin bcrypt (Constants.DEFAULT_ADMIN_PASSWORD)
UPDATE T_USER
   SET USE_PASSWORD_C = '$2y$10$xg0EEKVUehutDI1m6qQhVeFz7SMQMl1jQzjf2KkVsR2c7aV2vyyjK'
 WHERE USE_USERNAME_C = 'admin';
```

Take a database backup first, and change the password immediately after logging in.

## Verifying SMTP (when you do configure it)

If you enable email for self-service resets, verify delivery by **actually receiving
the message in an inbox** — an SMTP `250` from the relay is acceptance, not delivery.
Trigger a `password_lost` for a test account and confirm the reset mail arrives.
