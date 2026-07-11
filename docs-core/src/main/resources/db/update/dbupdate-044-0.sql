create cached table T_SAVED_FILTER ( SFL_ID_C varchar(36) not null, SFL_IDUSER_C varchar(36) not null, SFL_NAME_C varchar(100) not null, SFL_QUERY_C varchar(2000) not null, SFL_CREATEDATE_D datetime not null, primary key (SFL_ID_C) );
alter table T_SAVED_FILTER add constraint FK_SFL_IDUSER_C foreign key (SFL_IDUSER_C) references T_USER (USE_ID_C) on delete restrict on update restrict;
create unique index IDX_SFL_USER_NAME on T_SAVED_FILTER (SFL_IDUSER_C, SFL_NAME_C);
update T_CONFIG set CFG_VALUE_C = '44' where CFG_ID_C = 'DB_VERSION';
