-- =============================================================================
-- Teedy — remediation for a duplicate ACTIVE (case-insensitive) username anomaly
-- blocking migration 050 (the active, case-insensitive unique-username constraint).
-- Engine: H2 (embedded dev/test database). For PostgreSQL use the -postgresql.sql script.
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
--   Take a full copy of the H2 database file BEFORE running anything here. Teedy
--   stores it under the configured data directory as docs.mv.db. With the app
--   STOPPED, copy that file (and any docs.trace.db) to a safe location. Do NOT
--   proceed without a restorable backup.
--
-- !!!  HOW TO RUN — STOP-ON-ERROR IS MANDATORY  !!!
--   Section 2 (the rename block) is SAFE ONLY when the runner HALTS on the first
--   error. On H2 a failed statement rolls back ONLY that statement, so a runner that
--   keeps going past a failed guard and reaches COMMIT would persist the rename. The
--   block is therefore structured so ALL validation runs as PRE-CHECKS *before* the
--   single rename UPDATE, and COMMIT is the last statement: a failed pre-check aborts
--   the run before anything is mutated, so nothing is committed.
--
--   Run the WHOLE Section-2 block through a stop-on-error runner. Two supported ways:
--     (A) H2 RunScript tool (stops on the first error by default), e.g.:
--           java -cp h2-*.jar org.h2.tools.RunScript \
--             -url "jdbc:h2:file:/path/to/docs" -user sa -script section2.sql
--         (put ONLY the Section-2 block, with your placeholders filled in, in section2.sql)
--     (B) H2 Shell (stops on error by default; do NOT use its "on error continue" mode):
--           java -cp h2-*.jar org.h2.tools.Shell \
--             -url "jdbc:h2:file:/path/to/docs" -user sa
--         then run the block AS A WHOLE.
--   Do NOT paste the statements one-by-one and continue past an error — that defeats
--   the stop-on-error guarantee and could persist a rename after a failed guard.
--   Sections 1, 1b and 3 are read-only and may be run any way you like.
--
-- WHAT THIS DOES / DOES NOT DO
--   - It does NOT auto-rename or auto-merge anyone. YOU choose, per colliding
--     group, which user keeps the name and which user (by USE_ID_C) is renamed.
--   - Renaming changes ONLY the login username. Documents, ACLs, tags, files,
--     shares etc. are keyed by USE_ID_C (a uuid), so they are unaffected.
--   - USERNAME-BASED REFERENCES that DO move with the name (handle these too):
--       * T_PASSWORD_RECOVERY.PWR_USERNAME_C — pending password-recovery rows are
--         matched by username; this script soft-deletes any for the OLD name (before
--         the rename, so it matches the still-current old username).
--       * Workflow route models (T_ROUTE_MODEL.RTM_STEPS_C) may embed a USER step
--         target BY NAME in JSON. Section 1b lists route models referencing a name you
--         are about to rename; after renaming, repair each in the UI (edit the workflow
--         step whose USER target is the old name to point at the new name).
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
-- =============================================================================


-- ---------------------------------------------------------------------------
-- SECTION 1 — READ-ONLY: list all colliding active-username groups.
-- ---------------------------------------------------------------------------
SELECT LOWER(u.USE_USERNAME_C) AS folded_username,
       u.USE_ID_C,
       u.USE_USERNAME_C,
       u.USE_EMAIL_C,
       u.USE_CREATEDATE_D
FROM T_USER u
JOIN (
    SELECT LOWER(USE_USERNAME_C) AS un
    FROM T_USER
    WHERE USE_DELETEDATE_D IS NULL
    GROUP BY LOWER(USE_USERNAME_C)
    HAVING COUNT(*) > 1
) d ON LOWER(u.USE_USERNAME_C) = d.un
WHERE u.USE_DELETEDATE_D IS NULL
ORDER BY folded_username, u.USE_CREATEDATE_D;


-- ---------------------------------------------------------------------------
-- SECTION 1b — READ-ONLY: workflow route models referencing a username by name.
--
-- Route models (T_ROUTE_MODEL.RTM_STEPS_C) embed USER step targets BY NAME in JSON
-- ({"target":{"name":"<username>","type":"USER"}}). A rename leaves such a step pointing
-- at the OLD name, which makes the route unstartable until an admin repairs it in the UI.
-- Edit '<OLD_NAME>' to the CURRENT (pre-rename) username you will rename, then run this to
-- list the route models to repair manually AFTER the rename (see README).
--
-- The name match is WHITESPACE-TOLERANT: it matches "name":"X", "name": "X", and any
-- spacing around the colon, so a pretty-printed steps blob is not missed. It intentionally
-- OVER-lists (any spacing/type context) rather than risk missing a reference — this is a
-- read-only detection the admin reviews before repairing in the UI.
-- ---------------------------------------------------------------------------
SELECT RTM_ID_C, RTM_NAME_C, RTM_STEPS_C
FROM T_ROUTE_MODEL
WHERE RTM_DELETEDATE_D IS NULL
  AND REGEXP_LIKE(RTM_STEPS_C, '"name"\s*:\s*"<OLD_NAME>"')
  AND REGEXP_LIKE(LOWER(RTM_STEPS_C), '"type"\s*:\s*"user"');


-- ---------------------------------------------------------------------------
-- SECTION 2 — RENAME one user (repeat per colliding group). Transaction-wrapped.
--             RUN THIS BLOCK THROUGH A STOP-ON-ERROR RUNNER (see the header).
--
-- H2 has no psql-style variables, so EDIT the three placeholders below inline before
-- running this block, then run it AS A WHOLE through a stop-on-error runner:
--   '<TARGET_ID>'  -> the USE_ID_C of the ACTIVE user to rename
--   '<NEW_NAME>'   -> the new username (must satisfy the rules above)
--   '<OLD_NAME>'   -> the CURRENT (pre-rename) username of that user (from Section 1)
--
-- DESIGN (why this cannot persist a bad rename on H2):
--   * autocommit is OFF, so nothing commits until the final COMMIT;
--   * ALL validation happens as PRE-CHECKS *before* any data change;
--   * a failed pre-check raises an error (it tries to INSERT a NULL into T_CONFIG's
--     NOT NULL primary key, sourced from the failing condition), which a stop-on-error
--     runner reports and HALTS on — before the rename and before COMMIT;
--   * the rename UPDATE is the ONLY data mutation and comes AFTER every guard;
--   * COMMIT is the LAST statement.
--   So on bad input the run stops before the rename and the connection is discarded
--   without committing => the DB is left completely unchanged.
-- ---------------------------------------------------------------------------
SET AUTOCOMMIT OFF;

-- Informational pre-checks (read them; the structural guard below enforces the same rules).
-- (2a) target is exactly one active user  -> expect exactly 1 row:
SELECT USE_ID_C, USE_USERNAME_C FROM T_USER
WHERE USE_ID_C = '<TARGET_ID>' AND USE_DELETEDATE_D IS NULL;
-- (2b) new name does not collide with any OTHER active user -> expect 0:
SELECT COUNT(*) AS would_collide FROM T_USER
WHERE LOWER(USE_USERNAME_C) = LOWER('<NEW_NAME>')
  AND USE_DELETEDATE_D IS NULL
  AND USE_ID_C <> '<TARGET_ID>';

-- STRUCTURAL PRE-CHECK GUARD (runs BEFORE any mutation). All conditions must hold:
--   * the target USE_ID_C is exactly one active user whose CURRENT name is '<OLD_NAME>';
--   * the new name is valid: length 3..50 and characters only from [a-zA-Z0-9_@.-];
--   * the new name does NOT case-insensitively collide with any OTHER active user.
-- It does NOT check the old name for remaining collisions — resolving an N-way group one
-- member at a time is intended; the Section-3 global check confirms zero remain afterwards.
-- Anchored on the always-present DB_VERSION config row so a missing target still trips the
-- guard. The subquery yields a row ONLY when a condition FAILS; inserting its NULL into the
-- NOT NULL PK then errors and halts the stop-on-error runner BEFORE the rename below. When
-- every condition holds the subquery is empty (a harmless no-op insert of zero rows).
INSERT INTO T_CONFIG (CFG_ID_C, CFG_VALUE_C)
SELECT NULL, 'USERNAME_REMEDIATION_GUARD'
FROM T_CONFIG anchor
WHERE anchor.CFG_ID_C = 'DB_VERSION'
  AND NOT (
       (SELECT COUNT(*) FROM T_USER WHERE USE_ID_C = '<TARGET_ID>'
          AND LOWER(USE_USERNAME_C) = LOWER('<OLD_NAME>') AND USE_DELETEDATE_D IS NULL) = 1
   AND LENGTH('<NEW_NAME>') BETWEEN 3 AND 50
   AND REGEXP_LIKE('<NEW_NAME>', '^[a-zA-Z0-9_@.-]+$')
   AND (SELECT COUNT(*) FROM T_USER WHERE LOWER(USE_USERNAME_C) = LOWER('<NEW_NAME>')
          AND USE_DELETEDATE_D IS NULL AND USE_ID_C <> '<TARGET_ID>') = 0
  );

-- ===== ALL VALIDATION IS DONE. The only mutations follow. =====

-- Username-based reference: void pending password-recovery rows for the OLD name FIRST,
-- BEFORE the rename, so we match the still-current old username (a stale reset link must
-- not resolve to the renamed user).
UPDATE T_PASSWORD_RECOVERY
SET PWR_DELETEDATE_D = NOW()
WHERE PWR_DELETEDATE_D IS NULL
  AND LOWER(PWR_USERNAME_C) = LOWER('<OLD_NAME>');

-- Apply the rename (the ONLY change to T_USER).
UPDATE T_USER
SET USE_USERNAME_C = '<NEW_NAME>'
WHERE USE_ID_C = '<TARGET_ID>' AND USE_DELETEDATE_D IS NULL;

-- Informational post-rename check for the affected name -> expect NO row: the new name
-- now resolves to exactly one active user. (This is a read-only confirmation; the safety
-- guarantee comes from the pre-checks above, not from here.)
SELECT LOWER(USE_USERNAME_C) AS folded_username, COUNT(*) AS active_count
FROM T_USER
WHERE USE_DELETEDATE_D IS NULL
  AND LOWER(USE_USERNAME_C) = LOWER('<NEW_NAME>')
GROUP BY LOWER(USE_USERNAME_C)
HAVING COUNT(*) > 1;

-- Every pre-check passed (the run reached here without halting), so keep the change:
COMMIT;


-- ---------------------------------------------------------------------------
-- SECTION 3 — FINAL VALIDATION (read-only). Run AFTER resolving every group.
-- MUST return ZERO rows before restart (global: no active group may still collide).
-- ---------------------------------------------------------------------------
SELECT LOWER(USE_USERNAME_C) AS folded_username, COUNT(*) AS active_count
FROM T_USER
WHERE USE_DELETEDATE_D IS NULL
GROUP BY LOWER(USE_USERNAME_C)
HAVING COUNT(*) > 1;
