package com.sismics.util;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Test of the image utilities.
 * 
 * @author bgamard
 */
public class TestImageUtil {

    @Test
    public void computeGravatarTest() {
        Assertions.assertEquals("0bc83cb571cd1c50ba6f3e8a78ef1346", ImageUtil.computeGravatar("MyEmailAddress@example.com "));
    }
}
