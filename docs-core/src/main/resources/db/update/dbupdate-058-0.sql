-- #147 server-side per-user dark-mode preference: a nullable dark-mode flag on T_USER. NULL means
-- "no server-side preference" (fresh install / never chosen) so a fresh device falls back to its own
-- on-device default; true/false is the user's persisted choice. The on-device toggle always wins — the
-- server value only seeds a device that has made no local choice. Mirrors the #82 preferred-locale column.
-- Dialect-split DDL: unlike a plain varchar, a BARE nullable `bit` is NOT rewritten to `bool` by the
-- H2->PostgreSQL transform (DialectUtil only maps `bit` when it carries NOT NULL / DEFAULT), so on
-- PostgreSQL a bare `bit` would create a bit-string column, not a boolean. Name each dialect's boolean
-- type explicitly — H2's `bit` (its boolean alias) and PostgreSQL's `bool` — one statement per line. ADD
-- COLUMN IF NOT EXISTS is accepted by both engines, so a partially-applied migration re-run skips the
-- already-created column rather than failing.
!H2! alter table T_USER add column if not exists USE_DARKMODE_B bit;
!PGSQL! alter table T_USER add column if not exists USE_DARKMODE_B bool;
update T_CONFIG set CFG_VALUE_C = '58' where CFG_ID_C = 'DB_VERSION';
