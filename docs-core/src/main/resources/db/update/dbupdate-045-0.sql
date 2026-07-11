!H2!alter table T_CONFIG alter column CFG_VALUE_C varchar(4000) not null;
!PGSQL!alter table T_CONFIG alter column CFG_VALUE_C type varchar(4000);
update T_CONFIG set CFG_VALUE_C = '45' where CFG_ID_C = 'DB_VERSION';
