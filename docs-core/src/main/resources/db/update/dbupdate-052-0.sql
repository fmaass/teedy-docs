-- #74 clean_storage single-run guard: seed a sentinel T_CONFIG row that the clean_storage endpoint
-- locks (PESSIMISTIC_WRITE / SELECT ... FOR UPDATE) at the start of every run, so two concurrent runs
-- are serialized (the second blocks until the first commits) and can never double-count / double-credit
-- the same file's storage quota. The value is never read; only the row's lock matters. Portable across
-- H2 and PostgreSQL (a plain INSERT + a PESSIMISTIC_WRITE row lock, both dialect-agnostic).
insert into T_CONFIG(CFG_ID_C, CFG_VALUE_C) values('CLEAN_STORAGE_LOCK', '1');
update T_CONFIG set CFG_VALUE_C = '52' where CFG_ID_C = 'DB_VERSION';
