-- OIDC provider binding: pin the provider (issuer + client_id) that started the login on the
-- in-flight state, so the callback can reject a code minted for provider A being exchanged with
-- provider B after a live provider switch (#44).
alter table T_OIDC_STATE add column OIS_ISSUER_C varchar(500);
alter table T_OIDC_STATE add column OIS_CLIENTID_C varchar(500);

update T_CONFIG set CFG_VALUE_C = '46' where CFG_ID_C = 'DB_VERSION';
