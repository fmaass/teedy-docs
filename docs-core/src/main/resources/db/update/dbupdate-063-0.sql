-- #190 duplicate group memberships: repair the duplicates already in the wild, then enforce at the DB
-- level that a (user, group) pair has AT MOST ONE ACTIVE row in T_USER_GROUP.
--
-- The defect: GroupDao.addMember was a blind insert and GroupResource's "avoid duplicates" read is
-- unlocked, so two concurrent adds of the same pair both saw "not a member" and both inserted. The
-- resulting duplicate ACTIVE rows are invisible to the group list (a join, deduped by the DTO set) but
-- make removeMember's single-result read ambiguous -- the user stays a member after a successful
-- removal. GroupDao now serializes the pair on the GROUP row and rechecks under that lock; this
-- migration is the DB-level backstop AND the repair for databases that already carry duplicates.
--
-- STATEMENT ORDER MATTERS. The dedup UPDATE runs BEFORE any enforcement DDL: the motivating defect
-- produces exactly the rows that would make the unique index creation fail mid-migration and leave a
-- partial schema. The keeper rule is deterministic -- the LOWEST UGP_ID_C of each duplicate active pair
-- stays active, every other active row of that pair is soft-deleted with the migration's timestamp.
-- Soft-deleted history is untouched: the predicate only ever reads and writes rows whose
-- UGP_DELETEDATE_D is null, so an earlier delete date is never overwritten and a historical duplicate
-- never resurfaces. A correlated subquery over the update target is portable (verified on H2 2.3.232
-- and PostgreSQL 17).
update T_USER_GROUP set UGP_DELETEDATE_D = CURRENT_TIMESTAMP where UGP_DELETEDATE_D is null and UGP_ID_C > (select min(dup.UGP_ID_C) from T_USER_GROUP dup where dup.UGP_DELETEDATE_D is null and dup.UGP_IDUSER_C = T_USER_GROUP.UGP_IDUSER_C and dup.UGP_IDGROUP_C = T_USER_GROUP.UGP_IDGROUP_C);

-- RESIDUAL PRECONDITION ABORT (dialect-agnostic), mirroring dbupdate-050-0.sql:6's discipline: whatever
-- the dedup could NOT resolve must abort the upgrade with a rollback rather than surface as an opaque
-- "could not create unique index" failure halfway through the DDL below. Mechanism: attempt to INSERT a
-- NULL into the NOT NULL primary key of T_CONFIG, sourced from the still-duplicated active pairs -- zero
-- groups means zero source rows (a harmless no-op), one or more groups raises a NOT NULL violation that
-- aborts the whole migration transaction. Defence in depth, not the expected path: after the statement
-- above there should be nothing left. If this statement fails on upgrade, deduplicate the active rows of
-- T_USER_GROUP manually (keep one row per UGP_IDUSER_C + UGP_IDGROUP_C pair, soft-delete the rest by
-- setting UGP_DELETEDATE_D), then re-run the upgrade.
insert into T_CONFIG (CFG_ID_C, CFG_VALUE_C) select null, 'DUPLICATE_ACTIVE_GROUP_MEMBERSHIP_ABORT' from (select UGP_IDUSER_C, UGP_IDGROUP_C from T_USER_GROUP where UGP_DELETEDATE_D is null group by UGP_IDUSER_C, UGP_IDGROUP_C having count(*) > 1) dup;

-- Retry-safe DDL (IF NOT EXISTS, as in 056/057/059/062): H2 auto-commits DDL, so a run that dies between
-- the two H2 statements leaves DB_VERSION at 62 with the generated column already added (and the dedup
-- above committed with it); the re-run must skip it rather than fail on "column already exists". On
-- PostgreSQL (transactional DDL) a failed run rolls back cleanly and the guard is simply a no-op.

-- PostgreSQL: a partial unique index expresses the active-only constraint directly.
!PGSQL!create unique index if not exists IDX_USER_GROUP_ACTIVE on T_USER_GROUP (UGP_IDUSER_C, UGP_IDGROUP_C) where UGP_DELETEDATE_D is null;

-- H2 2.3.232 rejects a partial "create unique index ... where" clause, so express the same invariant the
-- way dbupdate-050-0.sql:23-30 does: a generated ACTIVE-KEY column holding the user id ONLY for an active
-- row (else NULL), plus a composite unique index over that column and the group id. A unique index is
-- violated only when EVERY key column matches and none is NULL, so soft-deleted rows (NULL active key)
-- never collide -- with each other or with the surviving active row. H2 recomputes the generated value on
-- every insert/update, so a soft-delete frees the pair and a later re-add is accepted (verified on H2
-- 2.3.232, including over pre-existing soft-deleted duplicates of the same pair).
!H2!alter table T_USER_GROUP add column if not exists UGP_IDUSER_ACTIVE_C varchar(36) generated always as (case when UGP_DELETEDATE_D is null then UGP_IDUSER_C else null end);
!H2!create unique index if not exists IDX_USER_GROUP_ACTIVE on T_USER_GROUP (UGP_IDUSER_ACTIVE_C, UGP_IDGROUP_C);
update T_CONFIG set CFG_VALUE_C = '63' where CFG_ID_C = 'DB_VERSION';
