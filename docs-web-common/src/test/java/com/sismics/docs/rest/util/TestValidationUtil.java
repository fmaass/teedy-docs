package com.sismics.docs.rest.util;

import com.sismics.rest.exception.ClientException;
import com.sismics.rest.util.ValidationUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Test the validations.
 *
 * @author jtremeaux 
 */
public class TestValidationUtil {
    @Test
    public void testValidateHttpUrlFail() throws Exception {
        ValidationUtil.validateHttpUrl("http://www.google.com", "url");
        ValidationUtil.validateHttpUrl("https://www.google.com", "url");
        ValidationUtil.validateHttpUrl(" https://www.google.com ", "url");
        try {
            ValidationUtil.validateHttpUrl("ftp://www.google.com", "url");
            Assertions.fail();
        } catch (ClientException e) {
            // NOP
        }
        try {
            ValidationUtil.validateHttpUrl("http://", "url");
            Assertions.fail();
        } catch (ClientException e) {
            // NOP
        }
    }
}
