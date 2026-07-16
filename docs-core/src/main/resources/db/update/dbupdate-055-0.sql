-- #96 credential-epoch mechanism. USE_CREDENTIALEPOCH_N is a monotonic per-user counter; every session
-- token and API key is stamped at mint with the epoch that authorized it, and a credential is honored only
-- while its stamp still equals the user's current epoch. Bumping the user's epoch therefore revokes every
-- previously-issued credential at once (the bump wiring lands in a later phase; this migration ships the
-- columns + the forced-logout seed). Dual-engine safe: ADD COLUMN IF NOT EXISTS applies on both H2 and
-- PostgreSQL, and bigint is unchanged by the H2->Postgres dialect transform. The whole run is one
-- transaction; on H2 (auto-commit DDL) IF NOT EXISTS makes a re-run after a partial failure skip the
-- already-created objects, while on PostgreSQL (transactional DDL) a failed run rolls back cleanly.
alter table T_USER add column if not exists USE_CREDENTIALEPOCH_N bigint not null default 0;
alter table T_AUTHENTICATION_TOKEN add column if not exists AUT_CREDENTIALEPOCH_N bigint not null default 0;
alter table T_API_KEY add column if not exists APK_CREDENTIALEPOCH_N bigint not null default 0;
-- Non-negativity invariants on the epoch + stamp columns. Split by dialect only for the IF NOT EXISTS
-- rerun-guard: H2 auto-commits DDL so a re-run must skip an already-added constraint, whereas PostgreSQL
-- has no ADD CONSTRAINT IF NOT EXISTS and does not need one (transactional DDL rolls a failed run back).
!H2! alter table T_USER add constraint if not exists CST_USE_CREDEPOCH_NONNEG check (USE_CREDENTIALEPOCH_N >= 0);
!PGSQL! alter table T_USER add constraint CST_USE_CREDEPOCH_NONNEG check (USE_CREDENTIALEPOCH_N >= 0);
!H2! alter table T_AUTHENTICATION_TOKEN add constraint if not exists CST_AUT_CREDEPOCH_NONNEG check (AUT_CREDENTIALEPOCH_N >= 0);
!PGSQL! alter table T_AUTHENTICATION_TOKEN add constraint CST_AUT_CREDEPOCH_NONNEG check (AUT_CREDENTIALEPOCH_N >= 0);
!H2! alter table T_API_KEY add constraint if not exists CST_APK_CREDEPOCH_NONNEG check (APK_CREDENTIALEPOCH_N >= 0);
!PGSQL! alter table T_API_KEY add constraint CST_APK_CREDEPOCH_NONNEG check (APK_CREDENTIALEPOCH_N >= 0);
-- Account-origin integrity: OIDC issuer/subject are all-or-none, and an account is at most one of
-- LDAP-origin or OIDC-origin (an OIDC user legitimately holds a local random-UUID password, so the
-- exclusion is expressed on the origin MARKERS, not on the password).
!H2! alter table T_USER add constraint if not exists CST_USE_OIDC_ALLORNONE check ((USE_OIDC_ISSUER_C is null and USE_OIDC_SUBJECT_C is null) or (USE_OIDC_ISSUER_C is not null and USE_OIDC_SUBJECT_C is not null));
!PGSQL! alter table T_USER add constraint CST_USE_OIDC_ALLORNONE check ((USE_OIDC_ISSUER_C is null and USE_OIDC_SUBJECT_C is null) or (USE_OIDC_ISSUER_C is not null and USE_OIDC_SUBJECT_C is not null));
!H2! alter table T_USER add constraint if not exists CST_USE_ORIGIN_EXCL check (not (USE_LDAP_B and USE_OIDC_ISSUER_C is not null));
!PGSQL! alter table T_USER add constraint CST_USE_ORIGIN_EXCL check (not (USE_LDAP_B and USE_OIDC_ISSUER_C is not null));
-- Forced-logout seed (Decision 2): every existing user advances to epoch 1 while all existing tokens and
-- keys stay at epoch 0, so every pre-migration credential is already epoch-dead (0 != 1) the instant this
-- lands. No WHERE special-casing: a disabled user's epoch-0 credentials are dead regardless of state, and
-- every existing key is dead too. Consequence: a one-time global re-login and integrations re-key.
update T_USER set USE_CREDENTIALEPOCH_N = 1;
update T_CONFIG set CFG_VALUE_C = '55' where CFG_ID_C = 'DB_VERSION';
