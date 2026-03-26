package com.sismics.util;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Test of the resource utils.
 *
 * @author jtremeaux 
 */
public class TestResourceUtil {

    @Test
    public void listFilesTest() throws Exception {
        List<String> fileList = ResourceUtil.list(Assertions.class, "/org/junit/jupiter/api");
        Assertions.assertTrue(fileList.contains("Assertions.class"));

        fileList = ResourceUtil.list(Assertions.class, "/org/junit/jupiter/api/");
        Assertions.assertTrue(fileList.contains("Assertions.class"));

        fileList = ResourceUtil.list(Assertions.class, "org/junit/jupiter/api/");
        Assertions.assertTrue(fileList.contains("Assertions.class"));

        fileList = ResourceUtil.list(Assertions.class, "org/junit/jupiter/api/");
        Assertions.assertTrue(fileList.contains("Assertions.class"));
    }
}
