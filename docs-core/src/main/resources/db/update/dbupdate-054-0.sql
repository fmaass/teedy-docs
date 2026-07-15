-- #82 server-side per-user UI language: a nullable preferred-locale column on T_USER. NULL means
-- "no server-side preference" (fresh install / never chosen) and the SPA falls back to its on-device
-- or default locale. The value is an SPA locale code (e.g. 'de', 'zh_CN'), validated server-side
-- against the supported set on write. Dual-engine safe DDL (H2 + PostgreSQL both accept ADD COLUMN
-- IF NOT EXISTS, so a partially-applied migration re-run on H2 skips it rather than failing).
alter table T_USER add column if not exists USE_LOCALE_C varchar(10);

update T_CONFIG set CFG_VALUE_C = '54' where CFG_ID_C = 'DB_VERSION';
