-- #177 global history view: order-matching indexes for the audit feed. Until #177 the only consumer of
-- T_AUDIT_LOG was the document-scoped Activity tab, served by IDX_LOG_IDENTITY_C (dbupdate-000-0.sql:34).
-- The GLOBAL feed has no identity predicate: the admin fetch is a single SELECT filtered only on
-- LOG_CLASSENTITY_C != 'Acl' and ordered by (LOG_CREATEDATE_D desc, LOG_ID_C desc) -- the keyset order
-- settled by #139 -- and the non-admin fetch adds LOG_IDUSER_C = :userId to that same order. With no
-- matching index PostgreSQL plans a full sequential scan plus an external merge sort for both
-- (measured on 1,000,000 rows: 174 ms, 8 MB of sort spilled to disk). These two indexes reproduce the
-- ORDER BY exactly, so the ordered fetch is served as an index scan with no sort step at all
-- (measured 0.099 ms). Index column order mirrors the query order, including the DESC direction: a
-- default-ASC index can still be walked backwards, but only when EVERY key inverts, which the mixed
-- LOG_IDUSER_C ASC + date/id DESC composite does not allow -- hence the explicit desc markers. H2
-- 2.3.232 accepts DESC index columns, and the H2->PostgreSQL dialect transform (DialectUtil rewrites
-- only cached/memory table, datetime, longvarchar and bit defaults) leaves these statements untouched,
-- so no !H2!/!PGSQL! split is needed.
--
-- This does NOT make the un-cursored total free: countSql runs count(*) over the same filtered scope on
-- every page request and remains a full scan of the matching rows (measured ~85-115 ms/1M rows). That
-- ceiling is a recorded known limitation of the paging contract, not something an index removes.
--
-- Retry-safe DDL (IF NOT EXISTS, as in 056/057/059): H2 auto-commits DDL, so a run that dies between the
-- two statements leaves DB_VERSION at 61 with the first index already created; the re-run must skip it
-- rather than fail on "index already exists". On PostgreSQL (transactional DDL) a failed run rolls back
-- cleanly and the guard is simply a no-op.
create index if not exists IDX_LOG_CREATEDATE_ID on T_AUDIT_LOG (LOG_CREATEDATE_D desc, LOG_ID_C desc);
create index if not exists IDX_LOG_USER_CREATEDATE_ID on T_AUDIT_LOG (LOG_IDUSER_C, LOG_CREATEDATE_D desc, LOG_ID_C desc);
update T_CONFIG set CFG_VALUE_C = '62' where CFG_ID_C = 'DB_VERSION';
