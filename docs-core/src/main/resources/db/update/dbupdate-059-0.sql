-- #159 durable per-file processing-completion marker + FENCED reprocess claim lease. Post-upload
-- derived data (OCR text, index entry, thumbnail, auto-tags) is produced asynchronously after the
-- upload commits; a hard JVM stop in the commit->process window loses that work with no backfill. These
-- columns give a durable "processing completed" signal a startup reconciliation service can act on.
-- `datetime` is rewritten to `timestamp` for PostgreSQL by the H2->PG dialect transform, and a plain
-- nullable varchar/int is unchanged, so no !H2!/!PGSQL! split is needed here. ADD COLUMN IF NOT EXISTS is
-- accepted by both engines, so a partially-applied re-run skips an already-created column.
alter table T_FILE add column if not exists FIL_PROCESSED_D datetime;
alter table T_FILE add column if not exists FIL_PROCESSINGAT_D datetime;
alter table T_FILE add column if not exists FIL_PROCESSINGTOKEN_C varchar(36);
alter table T_FILE add column if not exists FIL_PROCATTEMPTS_N int;
-- LEGACY LINE (bounded, one-time). Stamp every row that exists at upgrade time COMPLETE. There is no
-- durable way to know which pre-059 rows were actually processed (that is the marker we are adding), and
-- re-deriving the whole corpus on first boot is unacceptable (mass re-OCR/re-tag/re-index). So the marker
-- guarantees correctness GOING FORWARD only; pre-059 files are presumed handled (operators retain the
-- per-document process-files action + index rebuild for known-bad legacy files). FIL_CREATEDATE_D is
-- nullable in the base schema, so COALESCE it to avoid a NULL marker that would re-select the row forever.
-- This UPDATE runs inside the startup migration, before any request or service is serving, so it cannot
-- race live processing.
update T_FILE set FIL_PROCESSED_D = COALESCE(FIL_CREATEDATE_D, CURRENT_TIMESTAMP) where FIL_PROCESSED_D is null;
-- Accelerate the reconciler's hot predicate (marker IS NULL AND deleteDate IS NULL). After the legacy
-- stamp this selects ~0 rows, so a narrow index on the marker keeps the boot scan O(matches). CREATE INDEX
-- IF NOT EXISTS applies on both H2 and PostgreSQL.
create index if not exists IDX_FIL_PROCESSED on T_FILE (FIL_PROCESSED_D);
update T_CONFIG set CFG_VALUE_C = '59' where CFG_ID_C = 'DB_VERSION';
