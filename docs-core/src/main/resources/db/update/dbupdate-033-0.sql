alter table T_AUTHENTICATION_TOKEN add column AUT_OIDC_IDTOKEN_C varchar(4000);
update T_CONFIG set CFG_VALUE_C = '33' where CFG_ID_C = 'DB_VERSION';
