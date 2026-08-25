-- #300 access recording: one row per RECORDED ACCESS EVENT (who, what, when). The counters the
-- issue asks for are an aggregation over this table, never a separately maintained tally, so the
-- same rows can later answer "who accessed what when" (the audit-trail slice) without a second
-- write path or a backfill that no longer has the history.
--
-- Deliberately an EVENT table and not a counter column: a counter cannot be scoped per user, cannot
-- be re-derived after a mistake, and cannot be filtered by time. Aggregating is affordable here —
-- this deployment class is a handful of users, so the row count grows by opens, not by traffic.
--   ACC_ID_C         event id (UUID)
--   ACC_IDUSER_C     acting user id (a plain value, NOT an FK — the event must outlive the user's
--                    deletion so the history stays truthful; the read path resolves the username
--                    by join and simply drops rows whose user is gone)
--   ACC_TYPE_C       'DOCUMENT' or 'FILE' — which kind of thing was accessed
--   ACC_IDTARGET_C   the accessed document id (DOCUMENT) or file id (FILE)
--   ACC_IDDOC_C      the owning document at access time (equals ACC_IDTARGET_C for DOCUMENT rows,
--                    nullable for a FILE row with no document). Stored rather than joined through
--                    T_FILE because a file can later be MOVED to another document, and the event
--                    must keep saying where it was read from.
--   ACC_CREATEDATE_D access timestamp
-- The whole DDL is on one physical line: DbOpenHelper executes one statement per line.
-- `timestamp`/`varchar` are spelled the same on H2 and PostgreSQL and are untouched by the H2->PG
-- transform (DialectUtil rewrites only cached/memory table, datetime, longvarchar and bit), so no
-- !H2!/!PGSQL! split is needed. Retry-safe DDL (IF NOT EXISTS): on H2 a partially-applied migration
-- leaves the auto-committed DDL behind and a re-run must skip it rather than fail.
create cached table if not exists T_ACCESS_EVENT ( ACC_ID_C varchar(36) not null, ACC_IDUSER_C varchar(36) not null, ACC_TYPE_C varchar(20) not null, ACC_IDTARGET_C varchar(36) not null, ACC_IDDOC_C varchar(36), ACC_CREATEDATE_D timestamp not null, primary key (ACC_ID_C) );
-- Personal count of one target for one user: the document view and the file panel read exactly this
-- (target, user) pair, so it is the leading composite.
create index if not exists IDX_ACC_TARGET_USER on T_ACCESS_EVENT (ACC_IDTARGET_C, ACC_IDUSER_C);
-- Admin aggregation: totals and the most-used ranking group by (type, target), and the per-user
-- breakdown groups by user within that.
create index if not exists IDX_ACC_TYPE_TARGET on T_ACCESS_EVENT (ACC_TYPE_C, ACC_IDTARGET_C, ACC_IDUSER_C);
-- Time-ordered per-user reads (the audit-trail slice this table is the base for).
create index if not exists IDX_ACC_USER_DATE on T_ACCESS_EVENT (ACC_IDUSER_C, ACC_CREATEDATE_D);
update T_CONFIG set CFG_VALUE_C = '66' where CFG_ID_C = 'DB_VERSION';
