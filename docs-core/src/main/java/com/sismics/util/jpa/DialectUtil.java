package com.sismics.util.jpa;

/**
 * Dialect utilities.
 *
 * @author jtremeaux
 */
public class DialectUtil {
    /**
     * Checks if the error from the drivers relates to an object not found.
     *
     * @param message Error message
     * @return Object not found
     */
    public static boolean isObjectNotFound(String message) {
        return isObjectNotFound(message, EMF.isDriverPostgresql());
    }

    /**
     * Checks if the error relates to an object not found, for an explicit dialect.
     *
     * @param message Error message
     * @param postgresql True if the target dialect is PostgreSQL (else H2)
     * @return Object not found
     */
    public static boolean isObjectNotFound(String message, boolean postgresql) {
        return !postgresql && message.contains("not found") ||
                postgresql && message.contains("does not exist");
    }


    /**
     * Transform SQL dialect to the driver's current dialect (derived from EMF).
     *
     * @param sql SQL to transform
     * @return Transformed SQL
     */
    public static String transform(String sql) {
        return transform(sql, EMF.isDriverPostgresql());
    }

    /**
     * Transform SQL from the HSQLDB/H2 source dialect to an explicit target dialect.
     * Deriving the dialect from the connection (rather than the global EMF) lets the
     * migration runner target a database independent of the app's configured driver.
     *
     * @param sql SQL to transform
     * @param postgresql True if the target dialect is PostgreSQL (else H2)
     * @return Transformed SQL, or null if the line does not apply to the target dialect
     */
    public static String transform(String sql, boolean postgresql) {
        if (sql.startsWith("!PGSQL!")) {
            return postgresql ? sql.substring(7) : null;
        }
        if (sql.startsWith("!H2!")) {
            return postgresql ? null : sql.substring(4);
        }

        if (postgresql) {
            sql = transformToPostgresql(sql);
        }
        return sql;
    }

    /**
     * Transform SQL from HSQLDB dialect to current dialect.
     *
     * @param sql SQL to transform
     * @return Transformed SQL
     */
    public static String transformToPostgresql(String sql) {
        sql = sql.replaceAll("(cached|memory) table", "table");
        sql = sql.replaceAll("datetime", "timestamp");
        sql = sql.replaceAll("longvarchar", "text");
        sql = sql.replaceAll("bit default 1", "bool default true");
        sql = sql.replaceAll("bit default 0", "bool default false");
        sql = sql.replaceAll("bit not null default 1", "bool not null default true");
        sql = sql.replaceAll("bit not null default 0", "bool not null default false");
        sql = sql.replaceAll("bit not null", "bool not null");
        return sql;
    }
}
