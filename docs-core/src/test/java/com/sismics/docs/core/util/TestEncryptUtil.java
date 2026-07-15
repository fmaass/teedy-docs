package com.sismics.docs.core.util;

import com.google.common.base.Strings;
import com.google.common.io.ByteStreams;
import com.sismics.BaseTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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

    // A decrypt failure AFTER the plaintext temp is created must not strand that temp on disk, and the
    // original failure must propagate unchanged. A non-existent source triggers the failure at
    // Files.newInputStream, which runs after createTemporaryFile has already produced the temp.
    @Test
    public void decryptFileDeletesTempOnFailureTest() throws Exception {
        Path tmpDir = Paths.get(System.getProperty("java.io.tmpdir"));
        Path missingSource = tmpDir.resolve("sismics_docs_missing_source_" + UUID.randomUUID());
        Assertions.assertFalse(Files.exists(missingSource));

        Set<Path> before = listPlaintextTemps(tmpDir);
        Assertions.assertThrows(NoSuchFileException.class,
                () -> EncryptionUtil.decryptFile(missingSource, "OnceUponATime"));

        // The tmpdir is shared: a straggler temp from an unrelated async worker may transiently land in
        // the delta but is deleted by its owner, so poll briefly until the delta drains. A temp decryptFile
        // itself stranded has no deleter, survives the whole window, and still fails loudly (the same
        // drain-poll mechanism as the docs-web temp-leak tests).
        Set<Path> leaked;
        long deadline = System.currentTimeMillis() + 10_000;
        do {
            leaked = listPlaintextTemps(tmpDir);
            leaked.removeAll(before);
            if (leaked.isEmpty()) {
                return;
            }
            Thread.sleep(250);
        } while (System.currentTimeMillis() < deadline);
        Assertions.fail("decryptFile must delete its plaintext temp when the decrypt fails: leaked " + leaked);
    }

    /** The {@code sismics_docs*.tmp} plaintext temps createTemporaryFile drops in the system tmpdir. */
    private static Set<Path> listPlaintextTemps(Path tmpDir) throws IOException {
        try (Stream<Path> s = Files.list(tmpDir)) {
            return s.filter(p -> p.getFileName().toString().startsWith("sismics_docs")
                            && p.getFileName().toString().endsWith(".tmp"))
                    .collect(Collectors.toSet());
        }
    }
}
