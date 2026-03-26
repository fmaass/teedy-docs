package com.sismics.docs.core.util;

import com.google.common.base.Strings;
import com.google.common.io.ByteStreams;
import com.sismics.BaseTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import java.io.InputStream;

/**
 * Test of the encryption utilities.
 * 
 * @author bgamard
 */
public class TestEncryptUtil extends BaseTest {
    @Test
    public void generatePrivateKeyTest() {
        String key = EncryptionUtil.generatePrivateKey();
        System.out.println(key);
        Assertions.assertFalse(Strings.isNullOrEmpty(key));
    }
    
    @Test
    public void encryptStreamTest() throws Exception {
        try {
            EncryptionUtil.getEncryptionCipher("");
            Assertions.fail();
        } catch (IllegalArgumentException e) {
            // NOP
        }
        Cipher cipher = EncryptionUtil.getEncryptionCipher("OnceUponATime");
        InputStream inputStream = new CipherInputStream(getSystemResourceAsStream(FILE_PDF), cipher);
        byte[] encryptedData = ByteStreams.toByteArray(inputStream);
        byte[] assertData = ByteStreams.toByteArray(getSystemResourceAsStream(FILE_PDF_ENCRYPTED));

        Assertions.assertEquals(encryptedData.length, assertData.length);
    }
    
    @Test
    public void decryptStreamTest() throws Exception {
        InputStream inputStream = EncryptionUtil.decryptInputStream(
                getSystemResourceAsStream(FILE_PDF_ENCRYPTED), "OnceUponATime");
        byte[] encryptedData = ByteStreams.toByteArray(inputStream);
        byte[] assertData = ByteStreams.toByteArray(getSystemResourceAsStream(FILE_PDF));
        
        Assertions.assertEquals(encryptedData.length, assertData.length);
    }
}
