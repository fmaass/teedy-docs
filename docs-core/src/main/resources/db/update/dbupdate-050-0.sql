-- #59 OIDC verbatim-username opt-in: enforce an ACTIVE (soft-delete-aware), CASE-INSENSITIVE
-- unique username at the DB level, so verbatim provisioning can rely on the constraint instead of
-- a race-prone app precheck. Portable across H2 2.3.232 and PostgreSQL 17 via dialect prefixes.
-- ADR-0018 (extends ADR-0015).

-- PRECONDITION ABORT FIRST (dialect-agnostic): adding a unique constraint over a table that already
-- holds duplicate ACTIVE (case-insensitive) usernames would fail mid-migration and leave a partial
-- schema. Today's app precheck + OIDC hash suffix already guarantee active-username uniqueness, so a
-- duplicate here is a should-never-happen data anomaly. Rather than silently auto-rename rows (which
-- cannot be proven globally collision-free and would leave route-model USER-target blobs and pending
-- password-recovery rows pointing at the old name), ABORT the upgrade with a clear failure so an
-- admin resolves the anomaly manually before retrying. Mechanism: attempt to INSERT a NULL into the
-- NOT NULL primary key of T_CONFIG, sourced from the duplicate-active-username groups — zero groups
-- means zero source rows (a harmless no-op), one or more groups raises a NOT NULL violation that
-- aborts the whole migration transaction. Portable (verified on H2 2.3.232 and PostgreSQL 17).
-- If this statement fails on upgrade, deduplicate active usernames (case-insensitively) manually,
-- then re-run the upgrade.
insert into T_CONFIG (CFG_ID_C, CFG_VALUE_C) select null, 'DUPLICATE_ACTIVE_USERNAME_ABORT' from (select lower(USE_USERNAME_C) un from T_USER where USE_DELETEDATE_D is null group by lower(USE_USERNAME_C) having count(*) > 1) dup;

-- PostgreSQL: a partial + case-insensitive unique index expresses the constraint directly.
!PGSQL!create unique index IDX_USER_USERNAME_ACTIVE on T_USER (lower(USE_USERNAME_C)) where USE_DELETEDATE_D is null;

-- H2 2.3.232 rejects a partial "create unique index ... where" clause, so express the same
-- invariant via a generated column that holds lower(username) ONLY for an active row (else NULL,
-- and NULLs never collide in a unique index), with a plain unique index on it. Soft-deleted rows
-- and post-delete username reuse are unaffected (their generated value is NULL). H2 recomputes the
-- generated value on every insert/update, so a soft-delete frees the name and a later reuse is
-- accepted (verified on H2 2.3.232).
!H2!alter table T_USER add column USE_USERNAME_UNIQUE_C varchar(50) generated always as (case when USE_DELETEDATE_D is null then lower(USE_USERNAME_C) else null end);
!H2!create unique index IDX_USER_USERNAME_ACTIVE on T_USER (USE_USERNAME_UNIQUE_C);

update T_CONFIG set CFG_VALUE_C = '50' where CFG_ID_C = 'DB_VERSION';
