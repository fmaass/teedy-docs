package com.sismics.util;

import com.sismics.docs.BaseTransactionalTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.activation.DataHandler;
import javax.activation.DataSource;
import javax.mail.Part;
import javax.mail.Session;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Proves the inbox parser does not leak a plaintext temp when reading an attachment fails mid-copy.
 */
public class TestEmailUtilTempLeak extends BaseTransactionalTest {

    @Test
    public void parseFailureMidCopyLeavesNoPlaintextTemp() throws Exception {
        // Warm up the app context first: its startup extracts its own sismics_docs temps (e.g. PDF
        // fonts). Snapshotting AFTER this keeps those out of the leak measurement, so the diff reflects
        // only the temp our attachment parse creates.
        com.sismics.docs.core.model.context.AppContext.getInstance().getFileService().createTemporaryFile();
        Path tmpDir = Path.of(System.getProperty("java.io.tmpdir"));
        Set<Path> before = sismicsTemps(tmpDir);

        // A message with one attachment whose content stream throws mid-read.
        DataSource failing = new DataSource() {
            @Override
            public InputStream getInputStream() {
                return new InputStream() {
                    @Override
                    public int read() throws IOException {
                        throw new IOException("simulated attachment read failure");
                    }
                };
            }

            @Override
            public OutputStream getOutputStream() {
                throw new UnsupportedOperationException();
            }

            @Override
            public String getContentType() {
                return "application/octet-stream";
            }

            @Override
            public String getName() {
                return "boom.bin";
            }
        };
        MimeBodyPart attachment = new MimeBodyPart();
        attachment.setDataHandler(new DataHandler(failing));
        attachment.setFileName("boom.bin");
        attachment.setDisposition(Part.ATTACHMENT);
        MimeMultipart multipart = new MimeMultipart();
        multipart.addBodyPart(attachment);
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        message.setContent(multipart);
        message.saveChanges();

        EmailUtil.MailContent mailContent = new EmailUtil.MailContent();
        Assertions.assertThrows(IOException.class,
                () -> EmailUtil.parseMailContent(message, mailContent),
                "a mid-copy read failure must propagate");

        Assertions.assertTrue(mailContent.getFileContentList().isEmpty(),
                "the failed attachment must not be recorded in the content list");

        // No NEW sismics_docs temp survives the failed parse.
        Set<Path> after = sismicsTemps(tmpDir);
        after.removeAll(before);
        Assertions.assertTrue(after.isEmpty(),
                "a mid-copy parse failure must leave no plaintext temp behind, but found: " + after);
    }

    private static Set<Path> sismicsTemps(Path dir) throws IOException {
        try (Stream<Path> stream = Files.list(dir)) {
            Set<Path> result = new HashSet<>();
            stream.filter(p -> p.getFileName().toString().startsWith("sismics_docs")).forEach(result::add);
            return result;
        }
    }
}
