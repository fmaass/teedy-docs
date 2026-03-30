create cached table T_API_KEY ( APK_ID_C varchar(36) not null, APK_IDUSER_C varchar(36) not null, APK_NAME_C varchar(100) not null, APK_KEYHASH_C varchar(100) not null, APK_PREFIX_C varchar(20) not null, APK_CREATEDATE_D datetime not null, APK_DELETEDATE_D datetime, APK_LASTUSEDDATE_D datetime, primary key (APK_ID_C) );
create index IDX_APK_KEYHASH on T_API_KEY (APK_KEYHASH_C);
update T_CONFIG set CFG_VALUE_C = '35' where CFG_ID_C = 'DB_VERSION';
