-- #51: a saved filter can be PUBLISHED to every user of the instance. SFL_PUBLISHDATE_D is the whole
-- of that state: NULL means "private to its owner" — which is what every pre-067 row is, and there is
-- no other truthful answer for one — and a timestamp means "published, then". The date is deliberately
-- not a boolean: it also answers "shared since when", which the shared list orders and explains by, and
-- it follows the schema's own NULL-means-not idiom (every *_DELETEDATE_D column).
--
-- Additive and nullable, so the upgrade needs no backfill and no rewrite of existing rows — and,
-- because NULL is the private state, an upgrade cannot silently expose anybody's filters.
-- `timestamp` is spelled the same on H2 and PostgreSQL and is left untouched by the H2->PG dialect
-- transform (DialectUtil rewrites only cached/memory table, datetime, longvarchar and bit), so no
-- !H2!/!PGSQL! split is needed; ADD COLUMN IF NOT EXISTS is accepted by both engines, so a partially
-- applied re-run skips an already-created column rather than failing.
--
-- No index on the new column: the shared list reads `where SFL_PUBLISHDATE_D is not null` over
-- T_SAVED_FILTER, a table that holds one row per saved filter per user — tens of rows in this
-- deployment class. An index there would cost a write on every publish and buy nothing measurable.
alter table T_SAVED_FILTER add column if not exists SFL_PUBLISHDATE_D timestamp;
update T_CONFIG set CFG_VALUE_C = '67' where CFG_ID_C = 'DB_VERSION';
