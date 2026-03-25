-- OIDC subject binding: stable IdP identity on T_USER
alter table T_USER add column USE_OIDC_ISSUER_C varchar(500);
alter table T_USER add column USE_OIDC_SUBJECT_C varchar(500);
create unique index IDX_USER_OIDC on T_USER (USE_OIDC_ISSUER_C, USE_OIDC_SUBJECT_C);

-- OIDC state: persistent CSRF state, nonce, PKCE verifier, and return URL
create memory table T_OIDC_STATE ( OIS_ID_C varchar(36) not null, OIS_NONCE_C varchar(36) not null, OIS_CODEVERIFIER_C varchar(128), OIS_RETURNURL_C varchar(2000), OIS_CREATEDATE_D datetime not null, primary key (OIS_ID_C) );

update T_CONFIG set CFG_VALUE_C = '32' where CFG_ID_C = 'DB_VERSION';
