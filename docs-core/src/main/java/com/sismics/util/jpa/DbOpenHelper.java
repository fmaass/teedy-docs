package com.sismics.util.jpa;

import com.google.common.base.Strings;
import com.google.common.io.CharStreams;
import com.sismics.docs.core.util.ConfigUtil;
import com.sismics.util.ResourceUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.ResourceBundle;

/**
 * A helper to update the database incrementally.
 *
 * @author jtremeaux
 */
abstract class DbOpenHelper {
    private static final Logger log = LoggerFactory.getLogger(DbOpenHelper.class);

    private final Connection connection;

    private final List<Exception> exceptions = new ArrayList<>();

    private Statement stmt;

    /**
     * @param connection JDBC connection to use for migrations. The caller is
     *                   responsible for closing this connection after the EMF
     *                   is created (important for in-memory databases).
     */
    DbOpenHelper(Connection connection) {
        this.connection = connection;
    }

    public void open() {
        log.info("Opening database and executing incremental updates");

        exceptions.clear();

        try {
            Integer oldVersion = null;
            try {
                stmt = connection.createStatement();
                ResultSet result = stmt.executeQuery("select c.CFG_VALUE_C from T_CONFIG c where c.CFG_ID_C='DB_VERSION'");
                if (result.next()) {
                    String oldVersionStr = result.getString(1);
                    oldVersion = Integer.parseInt(oldVersionStr);
                }
            } catch (Exception e) {
                if (DialectUtil.isObjectNotFound(e.getMessage())) {
                    log.info("Unable to get database version: Table T_CONFIG not found");
                } else {
                    log.error("Unable to get database version", e);
                }
            } finally {
                connection.commit();
                if (stmt != null) {
                    stmt.close();
                    stmt = null;
                }
            }

            stmt = connection.createStatement();
            if (oldVersion == null) {
                log.info("Executing initial schema creation script");
                onCreate();
                oldVersion = 0;
            }

            ResourceBundle configBundle = ConfigUtil.getConfigBundle();
            Integer currentVersion = Integer.parseInt(configBundle.getString("db.version"));
            log.info(MessageFormat.format("Found database version {0}, new version is {1}, executing database incremental update scripts", oldVersion, currentVersion));
            onUpgrade(oldVersion, currentVersion);
            log.info("Database upgrade complete");

            connection.commit();
        } catch (Exception e) {
            exceptions.add(e);
            log.error("Unable to complete schema update", e);
        } finally {
            try {
                if (stmt != null) {
                    stmt.close();
                    stmt = null;
                }
            } catch (Exception e) {
                exceptions.add(e);
                log.error("Unable to close statement", e);
            }
        }
    }

    /**
     * Execute all upgrade scripts in ascending order for a given version.
     *
     * @param version Version number
     * @throws Exception e
     */
    void executeAllScript(final int version) throws Exception {
        List<String> fileNameList = ResourceUtil.list(getClass(), "/db/update/", (dir, name) -> {
            String versionString = String.format("%03d", version);
            return name.matches("dbupdate-" + versionString + "-\\d+\\.sql");
        });
        Collections.sort(fileNameList);

        for (String fileName : fileNameList) {
            if (log.isInfoEnabled()) {
                log.info(MessageFormat.format("Executing script: {0}", fileName));
            }
            InputStream is = getClass().getResourceAsStream("/db/update/" + fileName);
            executeScript(is);
        }
    }

    /**
     * Execute a SQL script. All statements must be one line only.
     *
     * @param inputScript Script to execute
     * @throws IOException e
     */
    private void executeScript(InputStream inputScript) throws IOException {
        List<String> lines = CharStreams.readLines(new InputStreamReader(inputScript));

        for (String sql : lines) {
            if (Strings.isNullOrEmpty(sql) || sql.startsWith("--")) {
                continue;
            }

            String transformed = DialectUtil.transform(sql);
            if (transformed != null) {
                try {
                    log.debug(transformed);
                    stmt.executeUpdate(transformed);
                } catch (SQLException e) {
                    exceptions.add(e);
                    if (log.isErrorEnabled()) {
                        log.error("Error executing SQL statement: {}", sql);
                        log.error(e.getMessage());
                    }
                }
            }
        }
    }

    public abstract void onCreate() throws Exception;

    public abstract void onUpgrade(int oldVersion, int newVersion) throws Exception;

    /**
     * Returns a List of all Exceptions which occurred during the export.
     *
     * @return A List containing the Exceptions occurred during the export
     */
    public List<?> getExceptions() {
        return exceptions;
    }
}
