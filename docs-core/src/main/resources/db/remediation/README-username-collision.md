# Remediation: duplicate active username blocking migration 050

## Symptom

Teedy **v3.6.1+ refuses to start with a named diagnostic** (v3.6.0 fails with only the cryptic
SQL precondition abort — the named `UsernameCollisionPreflight` diagnostic below was added in
v3.6.1) and the log shows a message like:

```
Database schema update failed; aborting startup
  caused by: Database upgrade blocked (migration 050): 1 active username collides
  case-insensitively. ... No database changes were applied (the upgrade transaction
  was rolled back). Colliding groups:
    - lower(username)='maxmuster': username='MaxMuster' (USE_ID_C=abc...), username='maxmuster' (USE_ID_C=def...)
  Remediation: ... See docs-core/src/main/resources/db/remediation/README-username-collision.md
```

## Cause

v3.6.0 (migration `dbupdate-050-0.sql`, ADR-0018) adds a database-level unique index over
`lower(USE_USERNAME_C)` for **active** (not soft-deleted) users, so verbatim OIDC username
provisioning can rely on the constraint instead of a race-prone app precheck. If the database
already holds two or more **active** users whose usernames differ only in case
(e.g. `MaxMuster` and `maxmuster`), that index cannot be created.

This is a should-never-happen anomaly — the app precheck and the OIDC hash suffix normally
prevent it — but if it exists, the upgrade **fails closed with no schema change applied**. A Java
preflight (`UsernameCollisionPreflight`, added in v3.6.1) runs **before any migration script
executes** whenever the upgrade will cross version 50, and produces the named diagnostic above
(which usernames and user IDs collide). Running before the whole loop — not just before the 050
step — is what makes "no database changes were applied" true even on a multi-version jump (e.g. a
DB at version 46), where earlier scripts' DDL would otherwise auto-commit on H2 and survive the
rollback. The collision definition uses the database's own `lower()` (identical to the 050 unique
index), so the named list can never omit a colliding row through a JVM/DB case-folding mismatch.
The migration SQL also keeps a defence-in-depth precondition abort, so a duplicate can never slip
past even if the preflight is bypassed.

> Soft-deleted users are **not** counted: a soft-deleted case variant of an active name is not a
> collision (the constraint is active-only). Only two or more *active* rows in the same
> case-insensitive group block the upgrade.

## What the fix does (and does not do)

- The engine keeps the case-insensitivity policy. You must **reduce each colliding group to one
  active user**, case-insensitively — by renaming or soft-deleting one of each pair. The scripts
  **never auto-rename or auto-merge**; you choose per group.
- Renaming changes only the login username. Documents, ACLs, tags, files, shares, favorites, audit
  log, etc. are keyed by `USE_ID_C` (a uuid) and are unaffected.
- **Username-based references** that DO follow the name, handled by the scripts / called out here:
  - `T_PASSWORD_RECOVERY.PWR_USERNAME_C` — pending password-recovery rows are matched by username.
    The scripts soft-delete any pending rows for the **old** name **before** the rename (so they
    match the still-current old username) so a stale reset link cannot resolve to the renamed user.
  - Workflow route models (`T_ROUTE_MODEL.RTM_STEPS_C`) may embed a USER step target **by name** in
    JSON, which a rename leaves pointing at the old name (an unstartable route). The scripts cannot
    safely rewrite JSON, so **Section 1b** is a read-only detection query: set the old name and it
    lists every route model referencing it. After renaming, repair each in the UI (edit the workflow
    step whose USER target is the old name to point at the new name).

## Username rules the new name must satisfy

- length **3..50** characters
- characters only from `[a-zA-Z0-9_@.-]`
- must not case-insensitively equal any **other** active username (else you just move the collision)

## Resolving a group of more than two (N-way collision)

A group can have three or more members (e.g. `Foo` / `FOO` / `foo`). You do **not** resolve the
whole group in one statement. Instead **rename all but one** member — each to a distinct new name
that does not collide with any *other* active user — running Section 2 **once per user you rename**.
Each rename only requires the **new** name to be collision-free; the remaining still-colliding
members of the group do **not** block a correct rename. After you have renamed all-but-one member of
every group, the Section 3 **global** check confirms zero collisions remain.

## Procedure

1. **Back up the database first** (mandatory). PostgreSQL: `pg_dump`. H2: copy `docs.mv.db` with the
   app stopped. Do not proceed without a restorable backup.
2. Pick the engine-specific script:
   - PostgreSQL (production): [`username-collision-postgresql.sql`](username-collision-postgresql.sql)
   - H2 (embedded dev/test): [`username-collision-h2.sql`](username-collision-h2.sql)
   - **H2 only — stop-on-error is mandatory.** Section 2 is safe *only* when the runner halts on
     the first error. On H2 a failed statement rolls back only that one statement, so a runner that
     continues past a failed guard and reaches `COMMIT` would persist the rename. Run the Section-2
     block through the H2 **`RunScript`** tool (stops on the first error by default) or the H2
     **Shell** in its default stop-on-error mode — and run the block **as a whole**. Do **not** paste
     the statements one-by-one and continue past an error, and do not use an "on error continue"
     mode. (Section 2 is structured so all validation runs *before* the single rename, and `COMMIT`
     is last, so a halted run leaves the DB unchanged.) PostgreSQL aborts the transaction on any
     `RAISE`, so its Section 2 is safe run either way.
3. Run **Section 1** (read-only) to list every colliding group with each user's `USE_ID_C`,
   username, email, and create date.
4. (Optional, if you use workflows) Run **Section 1b** with the old (pre-rename) username set, to
   list route models that reference it by name — you will repair these in the UI in step 7. The
   match is whitespace-tolerant, so a pretty-printed steps blob (`"name": "X"`) is not missed.
5. For **each** colliding group, decide which user keeps the name and rename **all but one** member.
   For each member you rename, run **Section 2** (transaction-wrapped) with that user's `USE_ID_C`,
   its new username, and (H2) its old username. Section 2 validates only that the **new** name is
   collision-free (plus target-exists and format), so you resolve an N-way group one member at a time
   — the other still-colliding members do **not** block a correct rename. On any validation failure
   the transaction/run aborts and nothing changes.
6. Run **Section 3** (final validation, global). It must return **zero** rows across all groups.
7. If Section 1b listed any route models, repair each in the UI (edit the workflow step whose USER
   target is the old name to point at the new name).
8. Restart Teedy. Migration 050 now proceeds and the DB advances to version 52.

## Rollback

Section 2 runs **all validation as pre-checks before any data change**, then the single rename, then
`COMMIT`. A failed pre-check (target missing or not matching the old name, invalid new-name
format/length, or the new name colliding with another active user) aborts before anything is mutated,
so a bad rename is never committed and the DB is left unchanged. On PostgreSQL the guard `RAISE`s,
aborting the transaction; on H2 it forces a NOT-NULL-PK violation that a **stop-on-error** runner
halts on before the rename and before `COMMIT`. If you already committed and want to undo, restore
the backup from step 1.

## Why there is no offline admin command

A one-shot offline `admin` CLI subcommand would require new bootstrap/entrypoint wiring
(argument parsing, an EMF that can open the schema *before* it is migrated, packaging into the WAR
launcher) — disproportionate infrastructure for a narrow hotfix and a should-never-happen anomaly.
Transaction-wrapped, validated, engine-specific SQL scripts + this runbook are the supported
remediation for v3.6.1.
