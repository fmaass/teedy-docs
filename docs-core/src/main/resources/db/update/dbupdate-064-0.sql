-- #197 raw .eml attachment for the IMAP inbox import: seed the toggle OFF for EXISTING installations.
--
-- The asymmetry is deliberate (design decision 4): a fresh installation gets the feature ON, seeded by
-- the base install script dbupdate-000-0.sql, while an upgrade keeps today's behaviour until an operator
-- turns it on. A code-level default cannot express that -- neither case has a row, so both would read the
-- same value; the signal is WHICH script ran. dbupdate-000-0.sql runs only when the database is created,
-- and every numbered script (this one included) runs afterwards on a fresh database too.
--
-- Hence the conditional insert (dbupdate-053-0.sql:14 precedent, portable on H2 and PostgreSQL): on a
-- fresh database the row already exists as 'true' and this statement is a no-op; on an upgrade it is
-- absent and inserted as 'false'. Only-when-absent also means a re-run after a partially applied migration
-- never clobbers a value an operator has since changed.
insert into T_CONFIG (CFG_ID_C, CFG_VALUE_C) select 'INBOX_EML_ATTACH', 'false' where not exists (select 1 from T_CONFIG where CFG_ID_C = 'INBOX_EML_ATTACH');
update T_CONFIG set CFG_VALUE_C = '64' where CFG_ID_C = 'DB_VERSION';
