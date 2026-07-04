delete from T_ACL where ACL_TYPE_C = 'ROUTING';
delete from T_ACL where ACL_SOURCEID_C in (select RTM_ID_C from T_ROUTE_MODEL);
drop table T_ROUTE_STEP;
drop table T_ROUTE;
drop table T_ROUTE_MODEL;
update T_CONFIG set CFG_VALUE_C = '37' where CFG_ID_C = 'DB_VERSION';
