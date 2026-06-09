package com.sismics.docs.core.util;

import com.sismics.docs.BaseTransactionalTest;
import com.sismics.docs.core.constant.ConfigType;
import com.sismics.docs.core.dao.ConfigDao;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Tests for ConfigUtil safe fallback overloads and ConfigDao null behavior.
 * Verifies that missing t_config rows do not throw, which is the fix for
 * EmailUtil SMTP env-based config and InboxService startup errors.
 */
public class TestConfigUtil extends BaseTransactionalTest {

    @Test
    public void testGetConfigStringValueThrowsWhenMissing() {
        Assertions.assertThrows(IllegalStateException.class, () ->
                ConfigUtil.getConfigStringValue(ConfigType.SMTP_PORT));
    }

    @Test
    public void testGetConfigStringValueWithDefault() {
        String value = ConfigUtil.getConfigStringValue(ConfigType.SMTP_PORT, "587");
        Assertions.assertEquals("587", value);
    }

    @Test
    public void testGetConfigIntegerValueWithDefault() {
        int value = ConfigUtil.getConfigIntegerValue(ConfigType.SMTP_PORT, 587);
        Assertions.assertEquals(587, value);
    }

    @Test
    public void testGetConfigBooleanValueWithDefault() {
        boolean value = ConfigUtil.getConfigBooleanValue(ConfigType.INBOX_ENABLED, false);
        Assertions.assertFalse(value);
    }

    @Test
    public void testConfigDaoGetByIdReturnsNullWhenMissing() {
        ConfigDao configDao = new ConfigDao();
        Assertions.assertNull(configDao.getById(ConfigType.SMTP_PORT));
        Assertions.assertNull(configDao.getById(ConfigType.SMTP_FROM));
        Assertions.assertNull(configDao.getById(ConfigType.SMTP_HOSTNAME));
    }
}
