-- #1 IMAP exactly-once import receipt. One row records that a message (identity digest, UIDVALIDITY,
-- UID) has been durably imported, so a crash between commit and the IMAP flag-ack cannot re-import it.
-- The document FK is nullable (the receipt is inserted claim-first, before the document exists, then
-- linked before the same transaction commits) and is 'on delete set null' — never cascade-deleted —
-- so purging the imported document keeps the receipt and the message is never re-imported. The unique
-- key is the identity DIGEST (not the raw account/folder columns), so it behaves identically on H2
-- (SET IGNORECASE) and PostgreSQL. Retry-safe DDL (IF NOT EXISTS): on H2 a partially-applied migration
-- leaves the auto-committed DDL behind, and a re-run must skip it rather than fail.
create cached table if not exists T_INBOX_RECEIPT ( INR_ID_C varchar(36) not null, INR_IDENTITY_C varchar(64) not null, INR_UIDVALIDITY_N bigint not null, INR_UID_N bigint not null, INR_ACCOUNT_C varchar(500), INR_FOLDER_C varchar(500), INR_IDDOCUMENT_C varchar(36), INR_CREATEDATE_D datetime not null, primary key (INR_ID_C), constraint FK_INR_IDDOCUMENT_C foreign key (INR_IDDOCUMENT_C) references T_DOCUMENT (DOC_ID_C) on delete set null on update restrict );
create unique index if not exists IDX_INR_IDENTITY on T_INBOX_RECEIPT (INR_IDENTITY_C, INR_UIDVALIDITY_N, INR_UID_N);
-- #6 GLOBAL_QUOTA_LOCK sentinel: a single shared row Phase D locks (PESSIMISTIC_WRITE) to serialize the
-- cross-user global-quota check. Created + seeded here so it exists on fresh installs AND upgraded
-- production databases. Conditional insert so a re-run after a partial 053 never double-seeds it.
insert into T_CONFIG (CFG_ID_C, CFG_VALUE_C) select 'GLOBAL_QUOTA_LOCK', '1' where not exists (select 1 from T_CONFIG where CFG_ID_C = 'GLOBAL_QUOTA_LOCK');
update T_CONFIG set CFG_VALUE_C = '53' where CFG_ID_C = 'DB_VERSION';
