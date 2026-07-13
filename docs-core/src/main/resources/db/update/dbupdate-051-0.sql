-- #60 clean_storage durable protocol: record every REAL storage-cleanup run in a
-- store the NEXT cleanup does NOT purge. A naive audit-log entry is itself swept by
-- clean_storage's orphan-audit-log delete (its identity resolves to no live entity),
-- so a dedicated table is used. Rows here are never touched by clean_storage.
-- Columns mirror the run outcome surfaced by the dry-run manifest.
--   CLR_ID_C         run id (UUID)
--   CLR_FILECOUNT_N  number of files hard-deleted (DB soft-deleted + filesystem orphan) by the run
--   CLR_BYTES_N      reclaimable/reclaimed bytes attributed to those files
--   CLR_IDUSER_C     acting admin user id (a plain value, NOT an FK — the record must
--                    survive that admin's later deletion/purge so the protocol is durable)
--   CLR_USERNAME_C   acting admin username snapshot (readable protocol without a join)
--   CLR_CREATEDATE_D run timestamp
-- The whole DDL is on one physical line: DbOpenHelper executes one statement per line.
create cached table T_CLEANUP_RUN ( CLR_ID_C varchar(36) not null, CLR_FILECOUNT_N bigint not null, CLR_BYTES_N bigint not null, CLR_IDUSER_C varchar(36), CLR_USERNAME_C varchar(50), CLR_CREATEDATE_D datetime not null, primary key (CLR_ID_C) );
update T_CONFIG set CFG_VALUE_C = '51' where CFG_ID_C = 'DB_VERSION';
