-- Canary table created by TestDbOpenHelper.onCreate. DDL auto-commits on H2, so the
-- table survives; the row inserted by dbupdate-fail-0.sql is the transactional write
-- whose rollback we assert.
create table T_ROLLBACK_CANARY (X_ID_C varchar(36));
