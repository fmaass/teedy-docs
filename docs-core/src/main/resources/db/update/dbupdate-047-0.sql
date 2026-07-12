create cached table T_FAVORITE ( FAV_ID_C varchar(36) not null, FAV_IDUSER_C varchar(36) not null, FAV_IDDOCUMENT_C varchar(36) not null, FAV_CREATEDATE_D datetime not null, primary key (FAV_ID_C) );
alter table T_FAVORITE add constraint FK_FAV_IDUSER_C foreign key (FAV_IDUSER_C) references T_USER (USE_ID_C) on delete restrict on update restrict;
alter table T_FAVORITE add constraint FK_FAV_IDDOCUMENT_C foreign key (FAV_IDDOCUMENT_C) references T_DOCUMENT (DOC_ID_C) on delete restrict on update restrict;
create unique index IDX_FAV_USER_DOCUMENT on T_FAVORITE (FAV_IDUSER_C, FAV_IDDOCUMENT_C);
update T_CONFIG set CFG_VALUE_C = '47' where CFG_ID_C = 'DB_VERSION';
