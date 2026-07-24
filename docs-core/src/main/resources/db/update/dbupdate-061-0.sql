-- #169 self-service TOTP enrollment. USE_TOTPKEYPENDING_C holds a TOTP secret generated for enrollment
-- but not yet confirmed with a valid code. It is promoted to the active key (USE_TOTPKEY_C) only by
-- POST /user/totp/activate; login never challenges against it, so an un-activated enrollment cannot lock
-- an account. A plain nullable varchar is unchanged by the H2->PostgreSQL dialect transform (DialectUtil
-- rewrites only datetime/longvarchar/bit), so no !H2!/!PGSQL! split is needed; ADD COLUMN IF NOT EXISTS
-- lets a partially-applied re-run skip an already-created column rather than failing.
alter table T_USER add column if not exists USE_TOTPKEYPENDING_C varchar(100);
-- Clear any active TOTP key on an OIDC-subject account. Such an account authenticates only through its
-- identity provider and can never satisfy the native-login TOTP challenge, so a stored key is dead state
-- that only stands in the way of admin-free recovery. Keyed on USE_OIDC_SUBJECT_C (the definitive OIDC
-- marker; the dbupdate-055 all-or-none constraint ties it to the issuer). LDAP accounts have a null
-- USE_OIDC_SUBJECT_C, DO pass native login, and keep their keys; internal accounts are untouched.
update T_USER set USE_TOTPKEY_C = null where USE_OIDC_SUBJECT_C is not null;
update T_CONFIG set CFG_VALUE_C = '61' where CFG_ID_C = 'DB_VERSION';
