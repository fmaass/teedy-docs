-- #285 slice 1: a comment's author may edit it, and every reader must be able to tell that they did.
-- COM_UPDATEDATE_D is the durable half of that audit trail: NULL means "never edited" (which is what
-- every pre-existing row is, and there is no way to know otherwise), a timestamp means "last edited
-- then". COM_CREATEDATE_D is deliberately left alone by an edit, so the two dates together say when the
-- comment was written AND when it was last changed.
--
-- Additive and nullable, so the upgrade needs no backfill and no rewrite of existing rows. `timestamp`
-- is spelled the same on H2 and PostgreSQL and is left untouched by the H2->PG dialect transform
-- (DialectUtil rewrites only datetime/longvarchar/bit), so no !H2!/!PGSQL! split is needed;
-- ADD COLUMN IF NOT EXISTS is accepted by both engines, so a partially-applied re-run skips an
-- already-created column rather than failing.
alter table T_COMMENT add column if not exists COM_UPDATEDATE_D timestamp;
update T_CONFIG set CFG_VALUE_C = '65' where CFG_ID_C = 'DB_VERSION';
