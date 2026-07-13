# ADR-0018 — OIDC opt-in verbatim `preferred_username` + portable active-unique username constraint

- **Status:** accepted
- **Date:** 2026-07-13
- **Extends:** ADR-0015 (OIDC technical contract — NOT a feature freeze)
- **Issue:** #59
- **Durable record:** This file is the in-repo copy. The authoritative ARCHIMEDES record lives in
  BookStack under the `teedy-docs` shelf (tag `[archimedes:adr] [archimedes:project=teedy-docs]`);
  mirror any edits there.

## Context

Every OIDC-provisioned Teedy username is derived as `sanitizeUsernameStem(claim) + "_" +
first-12-hex(SHA-256(issuer + NUL + subject))`. The deterministic hash suffix guarantees a
collision-resistant, identity-stable username without any DB-level uniqueness guarantee — Teedy has
**no** unique constraint on `USE_USERNAME_C`; uniqueness is a race-prone application precheck
(`UserDao.getActiveByUsernameIgnoreCase`, case-insensitive, active-only). The only DB uniqueness on
`T_USER` is `IDX_USER_OIDC` over `(issuer, subject)`.

Operators asked (#59) for OIDC users to keep their real `preferred_username` (e.g. `alice`) rather
than `alice_ab12cd34ef56`. Doing so removes the hash disambiguator that today prevents two distinct
subjects from contending for one username, so a real uniqueness guarantee is required before the
suffix can be dropped.

## Decision

1. **Opt-in flag `docs.oidc_username_verbatim` (default OFF).** A new `OidcKey`/`ConfigType`
   (`OIDC_USERNAME_VERBATIM`) read through the single OIDC accessor chokepoint (DB → property →
   default). **OFF is byte-identical to today** (hash-suffix behavior unchanged). ON provisions the
   sanitized `preferred_username` **verbatim** at the FULL 50-char username budget (not the
   hash-reserved `MAX_LENGTH − 1 − HASH_LEN` truncation). Making verbatim the default would be a
   breaking contract change and would need its own ADR.

2. **DB-enforced active, case-insensitive username uniqueness (`dbupdate-050`, `db.version=50`).**
   Verbatim provisioning relies on a real constraint, not the app precheck. The constraint is
   **portable** across the two supported engines via the migration's `!H2!`/`!PGSQL!` dialect
   prefixes:
   - **PostgreSQL 17:** `CREATE UNIQUE INDEX IDX_USER_USERNAME_ACTIVE ON T_USER
     (lower(USE_USERNAME_C)) WHERE USE_DELETEDATE_D IS NULL;` — a partial (active-only),
     case-insensitive index.
   - **H2 2.3.232:** partial `CREATE UNIQUE INDEX … WHERE` is a syntax error, so the same invariant
     is expressed with a **generated column** `USE_USERNAME_UNIQUE_C = CASE WHEN USE_DELETEDATE_D IS
     NULL THEN lower(USE_USERNAME_C) ELSE NULL END` plus a plain unique index on it. NULLs never
     collide, so soft-deleted rows and post-delete username reuse are unaffected; H2 recomputes the
     generated value on every insert/update, so a soft-delete frees the name and a later reuse is
     accepted. (All four behaviors verified empirically on H2 2.3.232 and PostgreSQL 17.)

   The migration **aborts on a duplicate precondition** rather than auto-renaming. Adding the unique
   constraint over pre-existing duplicate active (case-insensitive) usernames would fail mid-migration
   and leave a partial schema; a silent auto-rename cannot be proven globally collision-free and would
   leave route-model USER-target blobs and pending password-recovery rows pointing at the old name.
   So the migration first attempts a dialect-agnostic statement (INSERT a NULL into `T_CONFIG`'s NOT
   NULL primary key, sourced from the duplicate-active-username groups) that is a **no-op when there
   are no duplicates** and a **hard, whole-transaction abort when any exist** — instructing the admin
   to deduplicate manually and re-run. Today's hash suffix + app precheck make real duplicates
   practically impossible; a controlled, documented abort on a should-never-happen precondition is the
   correct practice for adding a unique constraint (verified on H2 2.3.232 and PostgreSQL 17).

3. **The constraint is GLOBAL — every creation path is protected.** The index governs local
   (admin-created) and LDAP-provisioned user creation too, not only OIDC. So the shared
   `UserDao.create` forces a flush and **translates an `IDX_USER_USERNAME_ACTIVE` violation to the
   same clean `AlreadyExistingUsername`** the case-insensitive app precheck already throws — closing
   the race window between the precheck SELECT and the commit for ALL callers. No creation path can
   500 on the new constraint (any OTHER violation, e.g. `IDX_USER_OIDC`, still propagates so its own
   handler sees it).

4. **Collision-safe verbatim provisioning.** On a verbatim insert that hits the active-unique
   constraint (an existing active holder, or a true-race second insert), provisioning does NOT retry
   the deterministic verbatim name — it **falls back to the hash-suffix disambiguator** (bounded
   retry, extending the hash prefix on residual collision). The conflict is detected from the app
   precheck / `create` translation (`AlreadyExistingUsername`) AND the DB index name
   (`IDX_USER_USERNAME_ACTIVE`). Result: no duplicate active username and no HTTP 500, for two distinct
   subjects forced onto the same verbatim name.

## Consequences / Trade-off (collision vs hijack)

- **Hash-suffix mode (OFF, default):** identity-stable, collision-resistant, but ugly usernames.
- **Verbatim mode (ON):** human-readable usernames, at the cost that the FIRST subject to log in with
  a given `preferred_username` claims it; a LATER, distinct subject presenting the same
  `preferred_username` is disambiguated with a hash suffix (does not "hijack" the first user's name,
  and does not fail). Identity is always keyed on `(issuer, subject)` — the username is a display
  handle, never the security principal — so a differently-suffixed second account is a cosmetic
  surprise, not a takeover. Operators who enable verbatim mode accept that `preferred_username`
  values must be reasonably stable/unique in their IdP for the human-readable names to be predictable.
- The retained soft-delete-aware constraint also hardens the **local and LDAP** username paths (the app
  precheck gains a DB backstop against a true race), independent of OIDC — and `UserDao.create` surfaces
  that DB violation as the same clean `AlreadyExistingUsername`, never a 500.

## Alternatives rejected

- **Make verbatim the default** — breaking contract change; would itself need an ADR.
- **Plain / raw-column unique index** — not portable: case-SENSITIVE on PostgreSQL (violates Teedy's
  case-insensitive username contract) and would also constrain soft-deleted rows (blocking legitimate
  username reuse after account deletion).
- **App-precheck only (no DB constraint)** — race-prone; two concurrent first logins can both pass
  the precheck and mint duplicate active usernames.
- **Silently auto-rename pre-existing duplicates in the migration** — rejected: cannot be proven
  globally collision-free (a renamed value can collide with an unrelated active username, failing the
  index creation anyway) and mutates only `T_USER.USE_USERNAME_C`, leaving route-model USER-target
  blobs and pending password-recovery rows dangling at the old name. The controlled precondition abort
  is safer and is standard practice for adding a unique constraint.
