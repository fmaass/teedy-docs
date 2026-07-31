package com.sismics.docs.core.util;

import com.sismics.util.mime.MimeType;
import com.sismics.util.mime.MimeTypeUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * (#197) The file name a raw message is stored under is derived from an UNAUTHENTICATED mail header, so
 * its derivation is a boundary guard: deterministic, bounded, and free of path separators and control
 * characters whatever the sender puts in the subject.
 */
public class TestEmlAttachmentNaming {
    @Test
    public void subjectBecomesTheFileName() {
        Assertions.assertEquals("Invoice 42.eml", EmlAttachmentUtil.fileName("Invoice 42"));
    }

    @Test
    public void sameSubjectAlwaysYieldsTheSameName() {
        Assertions.assertEquals(EmlAttachmentUtil.fileName("Invoice 42"), EmlAttachmentUtil.fileName("Invoice 42"));
    }

    @Test
    public void missingSubjectFallsBackToMessage() {
        Assertions.assertEquals("message.eml", EmlAttachmentUtil.fileName(null));
        Assertions.assertEquals("message.eml", EmlAttachmentUtil.fileName(""));
        Assertions.assertEquals("message.eml", EmlAttachmentUtil.fileName("   "));
    }

    /** A subject that is nothing but dots is a directory reference, not a name. */
    @Test
    public void dotOnlySubjectFallsBackToMessage() {
        Assertions.assertEquals("message.eml", EmlAttachmentUtil.fileName(".."));
        Assertions.assertEquals("message.eml", EmlAttachmentUtil.fileName("."));
    }

    @Test
    public void pathSeparatorsAreRemoved() {
        String name = EmlAttachmentUtil.fileName("../../etc/passwd");
        Assertions.assertFalse(name.contains("/"), "a forward slash must not survive: " + name);
        Assertions.assertTrue(name.endsWith(".eml"), name);

        String windows = EmlAttachmentUtil.fileName("C:\\Windows\\system32\\evil");
        Assertions.assertFalse(windows.contains("\\"), "a backslash must not survive: " + windows);
        Assertions.assertTrue(windows.endsWith(".eml"), windows);
    }

    /** A crafted subject must not be able to inject line breaks into a name that is echoed and logged. */
    @Test
    public void controlCharactersAreRemoved() {
        String name = EmlAttachmentUtil.fileName("Invoice\r\nBcc: attacker@example.com\u0000\u007f");
        Assertions.assertFalse(name.contains("\r"), "a carriage return must not survive: " + name);
        Assertions.assertFalse(name.contains("\n"), "a line feed must not survive: " + name);
        Assertions.assertFalse(name.contains("\u0000"), "a NUL must not survive: " + name);
        Assertions.assertFalse(name.contains("\u007f"), "a DEL must not survive: " + name);
        Assertions.assertTrue(name.endsWith(".eml"), name);
    }

    /** Bounded well under the 200-character column the file name is stored in. */
    @Test
    public void longSubjectIsTruncated() {
        String name = EmlAttachmentUtil.fileName("s".repeat(500));
        Assertions.assertTrue(name.length() <= 64, "the name must stay bounded: " + name.length());
        Assertions.assertTrue(name.endsWith(".eml"), name);
    }

    /** The explicit typing the raw attach uses must round-trip to the .eml extension. */
    @Test
    public void rawMessageMimeTypeMapsToTheEmlExtension() {
        Assertions.assertEquals("eml", MimeTypeUtil.getFileExtension(MimeType.MESSAGE_RFC822));
    }
}
