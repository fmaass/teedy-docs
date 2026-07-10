alter table T_METADATA add column MET_VOCABULARY_C varchar(50);
update T_CONFIG set CFG_VALUE_C = '43' where CFG_ID_C = 'DB_VERSION';
