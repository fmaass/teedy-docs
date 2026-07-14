-- =============================================================================
-- Teedy — remediation for a duplicate ACTIVE (case-insensitive) username anomaly
-- blocking migration 050 (the active, case-insensitive unique-username constraint).
-- Engine: PostgreSQL (production). For the embedded H2 database use the -h2.sql script.
-- =============================================================================
--
-- WHEN TO USE THIS
--   Teedy v3.6.0+ refuses to start with a message like:
--     "Database upgrade blocked (migration 050): N active username(s) collide
--      case-insensitively ... Colliding groups: ..."
--   That means two or more ACTIVE users have usernames that differ only in case
--   (e.g. 'MaxMuster' and 'maxmuster'). Migration 050 cannot add the unique index
--   until each such group is reduced to one active user (case-insensitively).
--
-- !!!  MANDATORY BACKUP  !!!
--   Take a full database backup BEFORE running anything here. Example:
--     pg_dump -U <user> -d teedy -F c -f teedy-before-remediation.dump
--   Do NOT proceed without a verified, restorable backup.
--
-- WHAT THIS DOES / DOES NOT DO
--   - It does NOT auto-rename or auto-merge anyone. YOU choose, per colliding
--     group, which user keeps the name and which user (by USE_ID_C) is renamed.
--   - Renaming changes ONLY the login username. Documents, ACLs, tags, files,
--     shares etc. are keyed by USE_ID_C (a uuid), so they are unaffected.
--   - USERNAME-BASED REFERENCES that DO move with the name (handle these too):
--       * T_PASSWORD_RECOVERY.PWR_USERNAME_C — pending password-recovery rows are
--         matched by username. This script soft-deletes any pending recovery rows
--         for the OLD name so a stale reset link cannot resolve to the renamed user.
--       * Workflow route models (T_ROUTE_MODEL.RTM_STEPS_C) may embed a USER step
--         target BY NAME in JSON. If you use workflows, after renaming, re-check any
--         route model whose step targeted the old username and update it in the UI.
--
-- RESOLVING A GROUP OF MORE THAN TWO (N-way collision)
--   For a group like 'Foo' / 'FOO' / 'foo', rename ALL BUT ONE member, each to a
--   distinct new name that does not collide with any OTHER active user. Run Section 2
--   once per user you rename. You do NOT have to resolve the whole group in one pass:
--   each rename only requires the NEW name to be collision-free — the remaining
--   still-colliding members do not block a correct rename. After all groups are done,
--   Section 3 (global) confirms zero collisions remain.
--
-- USERNAME RULES the new name MUST satisfy (enforced by the app):
--   - length 3..50 characters
--   - characters only from [a-zA-Z0-9_@.-]
--   - must NOT case-insensitively equal any OTHER active username (else you just
--     move the collision). The pre-checks below enforce this.
--
-- HOW TO USE
--   1. Run the READ-ONLY listing (Section 1) to see every colliding group with
--      each user's USE_ID_C, username, email and create date.
--   1b. (Optional, if you use workflows) Run Section 1b with :old_name set to each
--      username you will rename, to list route models that reference it by name.
--   2. For EACH user to rename, fill in the two psql variables below and run Section 2
--      (wrapped in a transaction). Section 2 validates the NEW name is collision-free
--      (it does NOT require the rest of the group to be resolved first), so you resolve
--      an N-way group one member at a time.
--   3. Repeat Section 2 for every user you need to rename.
--   4. Run the final validation (Section 3). It must return ZERO rows across ALL groups.
--   5. If Section 1b listed any route models, repair each in the UI (edit the workflow
--      step whose USER target is the old name to point at the new name).
--   6. Restart Teedy; migration 050 now proceeds.
--
-- ROLLBACK
--   Section 2 is a single transaction. ALL validation runs as pre-checks BEFORE any
--   data change; each RAISEs on failure (target missing / not matching the old name, or
--   an invalid or colliding new name), which aborts the transaction so nothing is changed.
--   If you have already COMMITted and want to undo, restore the backup taken above.
-- =============================================================================


-- ---------------------------------------------------------------------------
-- SECTION 1 — READ-ONLY: list all colliding active-username groups.
-- ---------------------------------------------------------------------------
SELECT lower(u.USE_USERNAME_C) AS folded_username,
       u.USE_ID_C,
       u.USE_USERNAME_C,
       u.USE_EMAIL_C,
       u.USE_CREATEDATE_D
FROM T_USER u
JOIN (
    SELECT lower(USE_USERNAME_C) AS un
    FROM T_USER
    WHERE USE_DELETEDATE_D IS NULL
    GROUP BY lower(USE_USERNAME_C)
    HAVING count(*) > 1
) d ON lower(u.USE_USERNAME_C) = d.un
WHERE u.USE_DELETEDATE_D IS NULL
ORDER BY folded_username, u.USE_CREATEDATE_D;


-- ---------------------------------------------------------------------------
-- SECTION 1b — READ-ONLY: workflow route models referencing a username by name.
--
-- Route models (T_ROUTE_MODEL.RTM_STEPS_C) embed USER step targets BY NAME in JSON
-- ({"target":{"name":"<username>","type":"USER"}}). A rename leaves such a step
-- pointing at the OLD name, which makes the route unstartable until an admin repairs
-- it in the UI. This query lists every route model whose steps JSON mentions a name
-- you are about to rename, so you can see and fix them. Set :old_name to the CURRENT
-- (pre-rename) username of the user you will rename, then run this:
--   \set old_name 'MaxMuster'
--
-- The name match is WHITESPACE-TOLERANT (a POSIX regex, not a fixed LIKE pattern): it
-- matches "name":"X", "name": "X", and any spacing around the colon, so a pretty-printed
-- steps blob is not missed. It intentionally OVER-lists rather than risk missing a
-- reference — this is a read-only detection the admin reviews before repairing in the UI.
-- (Rows returned = route models to repair manually AFTER the rename; see the README.)
-- ---------------------------------------------------------------------------
SELECT RTM_ID_C, RTM_NAME_C, RTM_STEPS_C
FROM T_ROUTE_MODEL
WHERE RTM_DELETEDATE_D IS NULL
  AND RTM_STEPS_C ~ ('"name"\s*:\s*"' || :'old_name' || '"')
  AND RTM_STEPS_C ~* '"type"\s*:\s*"user"';


-- ---------------------------------------------------------------------------
-- SECTION 2 — RENAME one user (repeat per colliding group). Transaction-wrapped.
--
-- Set these two variables from the Section 1 output, then run this whole block.
--   :target_id   = the USE_ID_C of the ACTIVE user to rename
--   :new_name    = the new username (must satisfy the rules above)
--
-- In psql:
--   \set target_id 'the-uuid-here'
--   \set new_name  'maxmuster2'
--
-- DESIGN: ALL validation runs as PRE-CHECKS *before* any data change; the rename UPDATE
-- is the only mutation and comes after every guard; COMMIT is last. Each guard RAISEs on
-- failure, which aborts the whole transaction — so nothing is committed and a failed
-- remediation leaves the DB unchanged. (Postgres would already abort on a RAISE mid-txn;
-- doing all validation first is for clarity and parity with the H2 script.)
--
-- NOTE ON psql VARIABLES: :'var' is substituted only in PLAIN statements, NOT inside
-- a dollar-quoted DO $$...$$ body. We therefore capture the admin-supplied values into
-- transaction-local GUCs with set_config() (plain statements) and read them back with
-- current_setting() inside the DO guard.
-- ---------------------------------------------------------------------------
BEGIN;

-- Capture admin-supplied values as transaction-local settings (plain statements interpolate :'var').
SELECT set_config('teedy.target_id', :'target_id', true);
SELECT set_config('teedy.new_name',  :'new_name',  true);

-- Capture the OLD username NOW (before the rename) so reference cleanup below targets it.
SELECT set_config('teedy.old_name',
                  (SELECT USE_USERNAME_C FROM T_USER WHERE USE_ID_C = :'target_id'), true);

-- PRE-CHECK GUARD (runs BEFORE any mutation). Reads the captured GUCs, so no :'var'
-- appears inside the DO body. Validates:
--   (a) the target is exactly one active user whose CURRENT name is the captured old name;
--   (b) the new name is valid (length 3..50, chars [a-zA-Z0-9_@.-]);
--   (c) the new name does NOT case-insensitively collide with any OTHER active user.
-- It does NOT check the old name for remaining collisions — resolving an N-way group one
-- member at a time is intended; the Section-3 global check confirms zero remain afterwards.
-- Any failed condition RAISEs, aborting the whole transaction so nothing is committed.
DO $$
DECLARE
    v_target   text := current_setting('teedy.target_id');
    v_new_name text := current_setting('teedy.new_name');
    v_old_name text := current_setting('teedy.old_name');
    n int;
BEGIN
    -- (a) target must be exactly one active user whose current name is the captured old name.
    SELECT count(*) INTO n FROM T_USER
    WHERE USE_ID_C = v_target
      AND lower(USE_USERNAME_C) = lower(v_old_name)
      AND USE_DELETEDATE_D IS NULL;
    IF n <> 1 THEN
        RAISE EXCEPTION 'target USE_ID_C % is not exactly one active user matching the captured old name (found %)', v_target, n;
    END IF;
    -- (b) new name format + length.
    IF length(v_new_name) < 3 OR length(v_new_name) > 50 THEN
        RAISE EXCEPTION 'new username % must be 3..50 characters', v_new_name;
    END IF;
    IF v_new_name !~ '^[a-zA-Z0-9_@.-]+$' THEN
        RAISE EXCEPTION 'new username % may contain only [a-zA-Z0-9_@.-]', v_new_name;
    END IF;
    -- (c) new name must not case-insensitively equal any OTHER active user.
    SELECT count(*) INTO n FROM T_USER
    WHERE lower(USE_USERNAME_C) = lower(v_new_name)
      AND USE_DELETEDATE_D IS NULL
      AND USE_ID_C <> v_target;
    IF n <> 0 THEN
        RAISE EXCEPTION 'new username % collides with % other active user(s)', v_new_name, n;
    END IF;
END $$;

-- ===== ALL VALIDATION IS DONE. The only mutations follow. =====

-- Username-based reference: void pending password-recovery rows for the OLD name FIRST,
-- BEFORE the rename, so we match the still-current old username (a stale reset link must
-- not resolve to the renamed user).
UPDATE T_PASSWORD_RECOVERY
SET PWR_DELETEDATE_D = now()
WHERE PWR_DELETEDATE_D IS NULL
  AND lower(PWR_USERNAME_C) = lower(current_setting('teedy.old_name'));

-- Apply the rename (the ONLY change to T_USER).
UPDATE T_USER
SET USE_USERNAME_C = current_setting('teedy.new_name')
WHERE USE_ID_C = current_setting('teedy.target_id') AND USE_DELETEDATE_D IS NULL;

COMMIT;


-- ---------------------------------------------------------------------------
-- SECTION 3 — FINAL VALIDATION (read-only). Run AFTER resolving every group.
-- MUST return ZERO rows before restart (global: no active group may still collide).
-- ---------------------------------------------------------------------------
SELECT lower(USE_USERNAME_C) AS folded_username, count(*) AS active_count
FROM T_USER
WHERE USE_DELETEDATE_D IS NULL
GROUP BY lower(USE_USERNAME_C)
HAVING count(*) > 1;
