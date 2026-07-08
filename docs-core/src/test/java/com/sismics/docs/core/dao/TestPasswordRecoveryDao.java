package com.sismics.docs.core.dao;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Unit tests for {@link PasswordRecoveryDao} token generation.
 */
public class TestPasswordRecoveryDao {

    /** 128 bits = 16 bytes = 32 lowercase hex chars (fits varchar(36) PWR_ID_C). */
    private static final Pattern HEX_TOKEN = Pattern.compile("^[0-9a-f]{32}$");

    @Test
    public void tokenIsSecureRandomHexNotUuid() {
        String token = PasswordRecoveryDao.generateToken();

        // SecureRandom hex: exactly 32 lowercase hex characters, no dashes
        Assertions.assertTrue(HEX_TOKEN.matcher(token).matches(),
                "Recovery token must be 32 lowercase hex chars, got: " + token);

        // It must NOT be a UUID (which contains dashes and is only 122 random bits)
        Assertions.assertFalse(token.contains("-"), "Recovery token must not be a UUID");
        Assertions.assertThrows(IllegalArgumentException.class, () -> UUID.fromString(token),
                "Recovery token must not parse as a UUID");
    }

    @Test
    public void tokensAreUnique() {
        Set<String> tokens = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            tokens.add(PasswordRecoveryDao.generateToken());
        }
        Assertions.assertEquals(1000, tokens.size(), "Generated tokens must be unique");
    }
}
