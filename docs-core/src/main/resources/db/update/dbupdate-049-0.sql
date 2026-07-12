alter table T_FILE add column FIL_ROTATION_N int default 0;
update T_CONFIG set CFG_VALUE_C = '49' where CFG_ID_C = 'DB_VERSION';
