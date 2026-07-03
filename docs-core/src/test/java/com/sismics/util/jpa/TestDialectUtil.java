package com.sismics.util.jpa;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the explicit-dialect DialectUtil overloads used by the connection-derived
 * migration path. These need no database, so they cover the PostgreSQL transform branch that
 * the Testcontainers migration test would otherwise be the only thing to exercise.
 */
public class TestDialectUtil {
    @Test
    public void transformsHsqldbToPostgresql() {
        Assertions.assertEquals("create table T (D timestamp)",
                DialectUtil.transform("create cached table T (D datetime)", true));
        Assertions.assertEquals("C text",
                DialectUtil.transform("C longvarchar", true));
        Assertions.assertEquals("F bool not null default true",
                DialectUtil.transform("F bit not null default 1", true));
    }

    @Test
    public void leavesSqlUntouchedForH2() {
        String sql = "create cached table T (D datetime)";
        Assertions.assertEquals(sql, DialectUtil.transform(sql, false));
    }

    @Test
    public void appliesDialectPrefixes() {
        // !PGSQL! lines apply only to PostgreSQL; !H2! lines only to H2.
        Assertions.assertNull(DialectUtil.transform("!PGSQL!alter table T", false));
        Assertions.assertEquals("alter table T", DialectUtil.transform("!PGSQL!alter table T", true));
        Assertions.assertNull(DialectUtil.transform("!H2!alter table T", true));
        Assertions.assertEquals("alter table T", DialectUtil.transform("!H2!alter table T", false));
    }

    @Test
    public void detectsObjectNotFoundPerDialect() {
        Assertions.assertTrue(DialectUtil.isObjectNotFound("Table X not found", false));
        Assertions.assertFalse(DialectUtil.isObjectNotFound("Table X not found", true));
        Assertions.assertTrue(DialectUtil.isObjectNotFound("relation X does not exist", true));
        Assertions.assertFalse(DialectUtil.isObjectNotFound("relation X does not exist", false));
    }
}
