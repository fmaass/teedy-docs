-- #174 explicit per-document cover file. DOC_IDFILECOVER_C records the file a user has explicitly
-- chosen as the document's cover; it is the INTENT, distinct from DOC_IDFILE_C, which stays the
-- derived SERVING pointer (the file whose thumbnail the list/gallery render). A post-update listener
-- reconciles the two: when the explicit cover is set and still attached, it becomes the serving
-- pointer; when it dangles (its file was deleted/detached) it is cleared and the serving pointer falls
-- back to the first file by order. NULL means "no explicit choice" (derive from order), so an old
-- install upgrades with no behaviour change and no backfill obligation.
--
-- DELIBERATELY NO FOREIGN KEY (unlike DOC_IDFILE_C's FK_DOC_IDFILE_C, which is ON DELETE RESTRICT and
-- must be null-cleared before its file rows can be hard-deleted — see DocumentDao.permanentDelete and
-- the clean-storage sweep). A plain, unconstrained pointer can never block a file/document purge: a
-- stale value is simply reconciled to null by the listener, or vanishes with the row on permanent
-- delete. A dangling value is harmless.
--
-- Dual-engine safe: a plain nullable varchar is unchanged by the H2->PostgreSQL dialect transform
-- (DialectUtil only rewrites datetime/longvarchar/bit), so no !H2!/!PGSQL! split is needed. ADD COLUMN
-- IF NOT EXISTS is accepted by both engines, so a partially-applied re-run skips an already-created
-- column rather than failing.
alter table T_DOCUMENT add column if not exists DOC_IDFILECOVER_C varchar(36);
update T_CONFIG set CFG_VALUE_C = '60' where CFG_ID_C = 'DB_VERSION';
