package com.sismics.docs.core.listener.async;

import com.sismics.docs.core.event.FileCreatedAsyncEvent;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Unit tests for deterministic plaintext temp-file cleanup in
 * {@link FileProcessingAsyncListener}.
 */
public class TestFileProcessingAsyncListener {

    @Test
    public void deletesUnencryptedTempFile() throws Exception {
        Path temp = Files.createTempFile("sismics_docs_test", ".bin");
        Files.writeString(temp, "plaintext");
        Assertions.assertTrue(Files.exists(temp));

        FileCreatedAsyncEvent event = new FileCreatedAsyncEvent();
        event.setFileId("file-1");
        event.setUnencryptedFile(temp);

        new FileProcessingAsyncListener().deleteUnencryptedFile(event);

        Assertions.assertFalse(Files.exists(temp),
                "Plaintext temp file must be deleted deterministically after processing");
    }

    @Test
    public void toleratesNullTempFile() {
        FileCreatedAsyncEvent event = new FileCreatedAsyncEvent();
        event.setFileId("file-2");
        // No unencrypted file set
        Assertions.assertDoesNotThrow(
                () -> new FileProcessingAsyncListener().deleteUnencryptedFile(event));
    }

    @Test
    public void toleratesAlreadyDeletedFile() throws Exception {
        Path temp = Files.createTempFile("sismics_docs_test", ".bin");
        Files.delete(temp);
        Assertions.assertFalse(Files.exists(temp));

        FileCreatedAsyncEvent event = new FileCreatedAsyncEvent();
        event.setFileId("file-3");
        event.setUnencryptedFile(temp);

        Assertions.assertDoesNotThrow(
                () -> new FileProcessingAsyncListener().deleteUnencryptedFile(event));
    }
}
