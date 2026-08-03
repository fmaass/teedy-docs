package com.sismics.util;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.sismics.util.css.Selector;

/**
 * Test of CSS utilities.
 * 
 * @author bgamard
 */
public class TestCss {
    @Test
    public void testBuildCss() {
        Selector selector = new Selector(".test")
            .rule("background-color", "yellow")
            .rule("font-family", "Comic Sans");
        Assertions.assertEquals(".test {\n  background-color: yellow;\n  font-family: Comic Sans;\n}\n",
                selector.toString());
    }

    /**
     * A CUSTOM PROPERTY renders like any other declaration. The theme resource publishes the
     * Branding navbar colour this way, so the exact rendering is what the SPA's var() lookup
     * depends on.
     */
    @Test
    public void testBuildCustomProperty() {
        Assertions.assertEquals(":root {\n  --teedy-navbar-bg: #336699;\n}\n",
                new Selector(":root").rule("--teedy-navbar-bg", "#336699").toString());
    }
}
