package com.sismics.docs.core.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.apache.commons.lang3.StringUtils;

import com.sismics.util.EnvironmentUtil;

/**
 * Utilities to gain access to the storage directories used by the application.
 * 
 * @author jtremeaux
 */
public class DirectoryUtil {
    /**
     * Returns the base data directory.
     * 
     * @return Base data directory
     */
    public static Path getBaseDataDirectory() {
        Path baseDataDir = resolveBaseDataDirectory();

        if (baseDataDir != null && !Files.isDirectory(baseDataDir)) {
            try {
                Files.createDirectories(baseDataDir);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        return baseDataDir;
    }

    /**
     * Resolves the base data directory path WITHOUT creating it. Used by strictly read-only callers
     * (e.g. the clean_storage dry-run) that must not touch the filesystem.
     *
     * @return Base data directory path (may not exist)
     */
    private static Path resolveBaseDataDirectory() {
        if (StringUtils.isNotBlank(EnvironmentUtil.getTeedyHome())) {
            // If the docs.home property is set then use it
            return Paths.get(EnvironmentUtil.getTeedyHome());
        } else if (EnvironmentUtil.isUnitTest()) {
            // For unit testing, use a temporary directory
            return Paths.get(System.getProperty("java.io.tmpdir"));
        } else {
            // We are in a webapp environment and nothing is specified, use the default directory for this OS
            Path baseDataDir = null;
            if (EnvironmentUtil.isUnix()) {
                baseDataDir = Paths.get("/var/docs");
            } if (EnvironmentUtil.isWindows()) {
                baseDataDir = Paths.get(EnvironmentUtil.getWindowsAppData() + "\\Sismics\\Docs");
            } else if (EnvironmentUtil.isMacOs()) {
                baseDataDir = Paths.get(EnvironmentUtil.getMacOsUserHome() + "/Library/Sismics/Docs");
            }
            return baseDataDir;
        }
    }

    /**
     * Resolves the storage directory path WITHOUT creating the base or storage directory — a strictly
     * read-only variant of {@link #getStorageDirectory()}. The returned path may not exist; callers
     * must check {@link Files#isDirectory(Path, java.nio.file.LinkOption...)} before listing it. Used
     * by the clean_storage dry-run, which must be free of any filesystem side effect.
     *
     * @return Storage directory path (may not exist), or null if no base directory resolves
     */
    public static Path getStorageDirectoryReadOnly() {
        Path baseDataDir = resolveBaseDataDirectory();
        return baseDataDir == null ? null : baseDataDir.resolve("storage");
    }
    
    /**
     * Returns the database directory.
     * 
     * @return Database directory.
     */
    public static Path getDbDirectory() {
        return getDataSubDirectory("db");
    }

    /**
     * Returns the lucene indexes directory.
     * 
     * @return Lucene indexes directory.
     */
    public static Path getLuceneDirectory() {
        return getDataSubDirectory("lucene");
    }
    
    /**
     * Returns the storage directory.
     * 
     * @return Storage directory.
     */
    public static Path getStorageDirectory() {
        return getDataSubDirectory("storage");
    }
    
    /**
     * Returns the log directory.
     * 
     * @return Log directory.
     */
    public static Path getLogDirectory() {
        return getDataSubDirectory("log");
    }

    /**
     * Returns the theme directory.
     *
     * @return Theme directory.
     */
    public static Path getThemeDirectory() {
        return getDataSubDirectory("theme");
    }

    /**
     * Returns a subdirectory of the base data directory
     * 
     * @return Subdirectory
     */
    private static Path getDataSubDirectory(String subdirectory) {
        Path baseDataDir = getBaseDataDirectory();
        Path directory = baseDataDir.resolve(subdirectory);
        if (!Files.isDirectory(directory)) {
            try {
                Files.createDirectories(directory);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        return directory;
    }
}
