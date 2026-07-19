-- #106 Covering index for the global-quota ghost-file scan. UserDao.getGlobalStorageCurrent's
-- ghost arms join T_FILE on FIL_IDUSER_C and filter on FIL_DELETEDATE_D / FIL_SIZE_N while
-- holding GLOBAL_QUOTA_LOCK on every upload; T_FILE's only index (FIL_IDDOC_C) leaves them as
-- full scans. Retry-safe DDL (IF NOT EXISTS): a partially-applied migration's auto-committed
-- DDL must be skipped on re-run, not fail.
create index if not exists IDX_FIL_GHOST_C on T_FILE (FIL_IDUSER_C, FIL_DELETEDATE_D, FIL_SIZE_N);
update T_CONFIG set CFG_VALUE_C = '56' where CFG_ID_C = 'DB_VERSION';
