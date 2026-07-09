alter table T_USER add column USE_LDAP_B bit not null default 0;
update T_CONFIG set CFG_VALUE_C = '41' where CFG_ID_C = 'DB_VERSION';
