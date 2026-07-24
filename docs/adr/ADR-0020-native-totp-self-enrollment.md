# ADR-0020 — Native self-service TOTP enrollment (proxy-less second factor)

- **Status:** accepted
- **Date:** 2026-07-24
- **Issue:** #169 ("Let users set up two-factor authentication themselves")
- **Shipped in:** (pending release) — migration `dbupdate-061-0.sql`, db.version 60 → 61
- **Supersedes:** ADR-0011 in part. ADR-0011 recorded Authelia as the recommended MFA front door and
  treated in-app TOTP as legacy/discouraged. This ADR keeps Authelia as the recommended posture for
  reverse-proxied deployments but restores a first-class, self-service native TOTP path for
  proxy-less installs. ADR-0011's guidance is otherwise unchanged.

## Context

Before this change, a native TOTP key could be set (`POST /user/enable_totp`) but the flow was
minimal and, per ADR-0011, positioned as legacy: operators were told to put Authelia (or an
equivalent proxy) in front for MFA. That leaves proxy-less self-hosters — a large part of the
audience — without a supported in-app second factor. The old `enable_totp` also *activated* the key
immediately (the returned secret became the live login factor before the user proved they could
generate a code), so a fat-fingered enrollment could lock a user out with no confirmation step.

Two account classes cannot use a native second factor at all: **OIDC** accounts authenticate only
through their identity provider and never traverse the native password+TOTP login path, so a stored
TOTP key on them is dead state; **LDAP** accounts, by contrast, *do* authenticate through the native
login path (password verified against the directory, then the TOTP challenge), so their keys are
live and must be preserved.

## Decision

1. **Native self-enrollment is a first-class, proxy-less path.** Authelia remains the recommended
   front door where a proxy is deployed; for deployments without one, users enroll a TOTP factor
   themselves from Settings › Account.

2. **Two-phase enroll → activate.** `POST /user/enable_totp` now generates the secret into a new
   *pending* column (`USE_TOTPKEYPENDING_C`) and returns the fields for a client-built `otpauth://`
   URI (secret, issuer = application name, account = username; the URI declares SHA1/6-digit/30-second
   to match the server verifier). The secret becomes the active login factor only after
   `POST /user/totp/activate` verifies a code generated from the pending secret. Login enforces the
   **active** key only; a pending enrollment never challenges login. Re-calling `enable_totp` while
   pending replaces the pending secret; calling it while a key is already active is rejected (disable
   first). Activation attempts reuse the login throttle's blocked-check and failure accounting (but
   not the bcrypt bulkhead — no password hashing runs there).

3. **External-origin accounts delegate MFA enrollment to their IdP.** Both `enable_totp` and
   `totp/activate` are rejected server-side for OIDC- or LDAP-origin accounts
   (`AuthenticationUtil.isExternalOrigin`); the SPA additionally hides the section (defence in depth).
   The current-user response gains an `external_origin` flag for the UI to key on.

4. **Existing LDAP active keys are deliberately preserved and native-login-enforced.** LDAP accounts
   pass native login and are challenged for TOTP, so any key they already hold stays valid; they
   simply cannot self-enroll a *new* one (that is delegated to the directory/IdP posture).

5. **Migration 061 clears unreachable OIDC-account keys.** It adds the nullable pending column and
   clears the active key on any account with a non-null OIDC subject (dead state that only blocks
   admin-free recovery). LDAP and internal accounts are untouched.

6. **The TOTP columns leave the generic update path.** `UserDao.update` no longer copies either TOTP
   column; both are writable only through dedicated compare-and-swap DAO writers (affected-row-count
   contract, modelled on `FileDao.demoteCurrentLatestVersion`/`setContentMacIfNull`) that preserve the
   user-update audit event. Activation promotes only `WHERE pending = :expected AND totp_key IS NULL`,
   so a concurrent admin recovery (which clears both columns) makes activation match 0 rows and fail
   closed — a cleared key can never be resurrected by a straggling activation or a stale generic
   profile update.

7. **Admin disable stays the recovery path.** No recovery codes, no admin-enrolls-other-user, no
   WebAuthn — out of scope. A locked-out user is recovered by an admin clearing their key
   (`POST /user/:username/disable_totp`), which now clears both columns atomically.

## Consequences / trade-offs

- Users on proxy-less installs get a supported, confirmation-gated second factor; a mistyped
  enrollment can no longer lock them out (login enforces only the confirmed active key).
- OIDC accounts lose any previously stored (unusable) TOTP key on upgrade — intended, since it could
  never satisfy native login and only impeded recovery.
- The guarantee is scoped to native login. External-origin MFA remains the IdP's responsibility.
- The two-phase flow adds one endpoint and one column; the CAS-only writers add a small amount of DAO
  surface in exchange for closing the resurrection/race classes.

## Alternatives rejected

- **Keep single-phase `enable_totp` (immediate activation)** — no confirmation step; a bad enrollment
  locks the user out. Rejected for enroll → activate.
- **Copy the TOTP columns in `UserDao.update` (status quo)** — a stale detached entity from a
  concurrent profile/OIDC update could resurrect a CAS-cleared key. Rejected for CAS-only writers.
- **Allow external-origin self-enrollment** — an OIDC key is unusable at native login; an LDAP/OIDC
  account's MFA belongs to its provider. Rejected; enrollment is internal-origin only.
- **Recovery codes / WebAuthn / admin-enrolls-other-user** — out of scope; admin disable is the
  recovery path.
- **Drop native TOTP entirely in favour of Authelia (strict ADR-0011 reading)** — abandons proxy-less
  self-hosters. Rejected; Authelia stays recommended where a proxy exists, native enrollment serves
  the rest.
