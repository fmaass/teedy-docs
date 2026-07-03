-- Test-only script: a successful transactional insert followed by a deliberately
-- failing statement. Lives OUTSIDE /db/update/ so it does not shadow the real
-- migration scripts. Used by TestDbOpenHelper to prove the runner fails fast AND
-- rolls back the successful insert instead of leaving it applied.
insert into T_ROLLBACK_CANARY (X_ID_C) values ('should-be-rolled-back');
insert into T_NONEXISTENT_TABLE_FAIL (X_ID_C) values ('canary');
