!H2!alter table T_DOCUMENT alter column DOC_DESCRIPTION_C varchar(50000);
!PGSQL!alter table T_DOCUMENT alter column DOC_DESCRIPTION_C type varchar(50000);
update T_CONFIG set CFG_VALUE_C = '48' where CFG_ID_C = 'DB_VERSION';
