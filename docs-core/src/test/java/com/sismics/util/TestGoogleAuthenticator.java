package com.sismics.util;

import java.util.Date;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.sismics.util.totp.GoogleAuthenticator;
import com.sismics.util.totp.GoogleAuthenticatorKey;

/**
 * Test of {@link GoogleAuthenticator}
 * 
 * @author bgamard
 */
public class TestGoogleAuthenticator {
    @Test
    public void testGoogleAuthenticator() {
        GoogleAuthenticator gAuth = new GoogleAuthenticator();
        GoogleAuthenticatorKey key = gAuth.createCredentials();
        Assertions.assertNotNull(key.getVerificationCode());
        Assertions.assertEquals(5, key.getScratchCodes().size());
        int validationCode = gAuth.calculateCode(key.getKey(), new Date().getTime() / 30000);
        Assertions.assertTrue(gAuth.authorize(key.getKey(), validationCode));
    }
}
