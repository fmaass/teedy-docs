package com.sismics.docs.rest;

import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import com.sismics.docs.core.constant.ConfigType;
import com.sismics.docs.core.dao.ConfigDao;
import com.sismics.docs.core.model.context.AppContext;
import com.sismics.docs.core.service.InboxService;
import com.sismics.docs.core.util.ConfigUtil;
import com.sismics.docs.core.util.TransactionUtil;
import com.sismics.util.filter.TokenBasedSecurityFilter;
import com.sismics.util.mime.MimeType;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Form;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.mail.Message;
import javax.mail.Part;
import javax.mail.Session;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;

/**
 * (#197) The IMAP ingress must attach the RAW RFC822 message as a {@code .eml} file alongside the
 * extracted attachments, driven end-to-end against an embedded GreenMail IMAP server and the real
 * database (the {@link TestInboxSync} idiom).
 *
 * <p>Covered: the toggle in both states, explicit {@code message/rfc822} typing, the subject-derived
 * file name, and — the decision that keeps the poller out of a poison loop — an over-quota raw message
 * importing WITHOUT the {@code .eml} while the document, its attachments and the import receipt all
 * survive and the message is acked.</p>
 */
public class TestInboxEmlAttach extends BaseJerseyTest {
    /** Attachment payload size: fits comfortably under the small quota used by the over-quota test. */
    private static final int ATTACHMENT_SIZE = 20_000;

    private void createUser(String adminToken, String username, String email, long quota) {
        target().path("/user").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form()
                        .param("username", username)
                        .param("email", email)
                        .param("password", "Test1234")
                        .param("storage_quota", Long.toString(quota))), JsonObject.class);
    }

    private void configureInbox(String adminToken, int imapPort, String tagId) {
        target().path("/app/config_inbox").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form()
                        .param("enabled", "true")
                        .param("starttls", "false")
                        .param("autoTagsEnabled", "false")
                        .param("deleteImported", "false")
                        .param("hostname", "localhost")
                        .param("port", Integer.toString(imapPort))
                        .param("username", "test@sismics.com")
                        .param("password", "Test1234")
                        .param("folder", "INBOX")
                        .param("tag", tagId)), JsonObject.class);
    }

    private String createInboxTag(String adminToken, String name) {
        return target().path("/tag").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form().param("name", name).param("color", "#00ff00")), JsonObject.class)
                .getString("id");
    }

    private void setAttachEml(boolean value) {
        TransactionUtil.handle(() -> new ConfigDao().update(ConfigType.INBOX_EML_ATTACH, Boolean.toString(value)));
    }

    private void sync() {
        AppContext.getInstance().getInboxService().syncInbox();
    }

    /** The app's UNSEEN count for the configured inbox (0 once every message is acked). */
    private int unseenCount(String adminToken) {
        return target().path("/app/test_inbox").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form()), JsonObject.class)
                .getJsonNumber("count").intValue();
    }

    /**
     * The importing user's own documents. Each test creates a fresh sender user, so this list is exactly
     * what THIS import produced — and, unlike a {@code tag:} search, it does not depend on the inbox tag
     * (created by admin) being readable by the sender.
     */
    private JsonArray documentsOf(String token) {
        return target().path("/document/list")
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .get(JsonObject.class)
                .getJsonArray("documents");
    }

    private JsonArray filesOf(String token, String documentId) {
        return target().path("/file/list")
                .queryParam("id", documentId)
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .get(JsonObject.class)
                .getJsonArray("files");
    }

    /**
     * Deliver a message carrying one text body part and one ATTACHMENT part straight into the mailbox
     * (direct delivery, so the From header is exactly what the test sets).
     */
    private void deliver(GreenMailUser mailbox, String from, String subject) throws Exception {
        MimeMessage message = new MimeMessage((Session) null);
        message.setFrom(new InternetAddress(from));
        message.setRecipients(Message.RecipientType.TO, "test@sismics.com");
        message.setSubject(subject);

        MimeBodyPart body = new MimeBodyPart();
        body.setText("invoice body text");
        MimeBodyPart attachment = new MimeBodyPart();
        attachment.setText("a".repeat(ATTACHMENT_SIZE));
        attachment.setFileName("payload.txt");
        attachment.setDisposition(Part.ATTACHMENT);
        MimeMultipart multipart = new MimeMultipart();
        multipart.addBodyPart(body);
        multipart.addBodyPart(attachment);
        message.setContent(multipart);
        message.saveChanges();

        mailbox.deliver(message);
    }

    /** The single file typed as a raw message, or null when the import attached none. */
    private JsonObject rawMessageFile(JsonArray files) {
        JsonObject found = null;
        for (int i = 0; i < files.size(); i++) {
            JsonObject file = files.getJsonObject(i);
            if (MimeType.MESSAGE_RFC822.equals(file.getString("mimetype", null))) {
                Assertions.assertNull(found, "at most one raw message file may be attached");
                found = file;
            }
        }
        return found;
    }

    /**
     * POST the inbox configuration for the round-trip test. The four server-required booleans are always
     * sent; {@code emlAttach} only when non-null, to model both an explicit set and a client that predates
     * the field.
     */
    private void postInboxConfig(String adminToken, String emlAttach) {
        Form form = new Form()
                .param("enabled", "false")
                .param("autoTagsEnabled", "false")
                .param("deleteImported", "false")
                .param("starttls", "false")
                .param("hostname", "localhost")
                .param("port", "993")
                .param("username", "test@sismics.com")
                .param("folder", "INBOX");
        if (emlAttach != null) {
            form.param("emlAttach", emlAttach);
        }
        target().path("/app/config_inbox").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(form), JsonObject.class);
    }

    private boolean getEmlAttach(String adminToken) {
        return target().path("/app/config_inbox").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class)
                .getBoolean("emlAttach");
    }

    /**
     * (#197) The toggle must be operable through the inbox configuration endpoint: POST persists it, GET
     * returns it, and a POST that OMITS it preserves the stored value rather than clobbering it — an API
     * client written before this field must not silently turn the feature on or off by saving the rest of
     * the inbox settings. The persisted value is read back through the exact accessor the importer uses.
     */
    @Test
    public void emlAttachConfigRoundTripsAndPreserves() {
        String adminToken = adminToken();
        try {
            postInboxConfig(adminToken, "true");
            Assertions.assertTrue(getEmlAttach(adminToken),
                    "POST with emlAttach=true must persist and be returned by GET");

            postInboxConfig(adminToken, null);
            Assertions.assertTrue(getEmlAttach(adminToken),
                    "a POST omitting emlAttach must preserve the prior value");

            // The persisted value must be visible to the importer's own read, not just to the endpoint.
            boolean[] wired = {false};
            TransactionUtil.handle(() ->
                    wired[0] = ConfigUtil.getConfigBooleanValue(ConfigType.INBOX_EML_ATTACH, false));
            Assertions.assertTrue(wired[0], "the persisted toggle must be readable by the import wiring");

            postInboxConfig(adminToken, "false");
            Assertions.assertFalse(getEmlAttach(adminToken),
                    "POST with emlAttach=false must turn the feature off");
        } finally {
            setAttachEml(false);
        }
    }

    /**
     * Toggle ON: the imported document carries the extracted attachment AND the raw message, typed
     * {@code message/rfc822} and named after the subject, with the description excerpt unchanged.
     */
    @Test
    public void toggleOnAttachesRawMessage() throws Exception {
        GreenMail greenMail = new GreenMail(new ServerSetup[] {
                ServerSetup.SMTP.dynamicPort(), ServerSetup.IMAP.dynamicPort() });
        GreenMailUser mailbox = greenMail.setUser("test@sismics.com", "Test1234");
        greenMail.start();
        InboxService inbox = AppContext.getInstance().getInboxService();
        try {
            String adminToken = adminToken();
            String suffix = Long.toUnsignedString(System.nanoTime());
            String senderEmail = "eml_on_" + suffix + "@example.com";
            String username = "inbox_eml_on_" + suffix;
            createUser(adminToken, username, senderEmail, 1_000_000L);
            String userToken = clientUtil.login(username);
            String tagName = "InboxEmlOn" + suffix;
            String tagId = createInboxTag(adminToken, tagName);
            configureInbox(adminToken, greenMail.getImap().getPort(), tagId);
            setAttachEml(true);

            deliver(mailbox, senderEmail, "Invoice " + suffix);
            sync();

            Assertions.assertNull(inbox.getLastSyncError(), "the import must not error");
            JsonArray documents = documentsOf(userToken);
            Assertions.assertEquals(1, documents.size(), "the message must be imported exactly once");
            String documentId = documents.getJsonObject(0).getString("id");

            JsonObject document = target().path("/document/" + documentId).request()
                    .cookie(TokenBasedSecurityFilter.COOKIE_NAME, userToken)
                    .get(JsonObject.class);
            Assertions.assertTrue(document.getString("description").contains("invoice body text"),
                    "the description excerpt must be unchanged by the raw attachment");

            JsonArray files = filesOf(userToken, documentId);
            Assertions.assertEquals(2, files.size(),
                    "the document must carry the extracted attachment AND the raw message: " + files);
            Assertions.assertEquals("payload.txt", files.getJsonObject(0).getString("name"),
                    "the extracted attachment must still be attached first");
            JsonObject raw = rawMessageFile(files);
            Assertions.assertNotNull(raw, "the raw message must be attached: " + files);
            Assertions.assertEquals("Invoice " + suffix + ".eml", raw.getString("name"),
                    "the raw message must be named after the subject");
            Assertions.assertTrue(raw.getJsonNumber("size").longValue() > ATTACHMENT_SIZE,
                    "the raw message must be stored at its own full size: " + raw);
            Assertions.assertEquals(0, unseenCount(adminToken), "the imported message must be acknowledged");
        } finally {
            setAttachEml(false);
            greenMail.stop();
        }
    }

    /**
     * Toggle OFF: today's behavior exactly — the extracted attachment only, no raw message anywhere.
     */
    @Test
    public void toggleOffKeepsTodaysBehaviour() throws Exception {
        GreenMail greenMail = new GreenMail(new ServerSetup[] {
                ServerSetup.SMTP.dynamicPort(), ServerSetup.IMAP.dynamicPort() });
        GreenMailUser mailbox = greenMail.setUser("test@sismics.com", "Test1234");
        greenMail.start();
        InboxService inbox = AppContext.getInstance().getInboxService();
        try {
            String adminToken = adminToken();
            String suffix = Long.toUnsignedString(System.nanoTime());
            String senderEmail = "eml_off_" + suffix + "@example.com";
            String username = "inbox_eml_off_" + suffix;
            createUser(adminToken, username, senderEmail, 1_000_000L);
            String userToken = clientUtil.login(username);
            String tagName = "InboxEmlOff" + suffix;
            String tagId = createInboxTag(adminToken, tagName);
            configureInbox(adminToken, greenMail.getImap().getPort(), tagId);
            setAttachEml(false);

            deliver(mailbox, senderEmail, "Invoice " + suffix);
            sync();

            Assertions.assertNull(inbox.getLastSyncError(), "the import must not error");
            JsonArray documents = documentsOf(userToken);
            Assertions.assertEquals(1, documents.size(), "the message must be imported exactly once");
            JsonArray files = filesOf(userToken, documents.getJsonObject(0).getString("id"));
            Assertions.assertEquals(1, files.size(),
                    "with the toggle off the document must carry only the extracted attachment: " + files);
            Assertions.assertNull(rawMessageFile(files), "no raw message may be attached: " + files);
            Assertions.assertEquals(0, unseenCount(adminToken), "the imported message must be acknowledged");
        } finally {
            greenMail.stop();
        }
    }

    /**
     * The raw capture is a full body fetch, and it must stay non-mutating: reading the message during
     * materialization must NOT set {@code \Seen}. If it did, a message whose import then failed would
     * have silently left the UNSEEN retry queue — mail loss, not a retry. Forced here with the import
     * fault seam: the transaction fails, the message must still be UNSEEN, no temp may survive the
     * cycle, and the next clean cycle must import it exactly once WITH its raw copy.
     */
    @Test
    public void capturingTheRawMessageNeitherMarksItSeenNorLeaksTemps() throws Exception {
        GreenMail greenMail = new GreenMail(new ServerSetup[] {
                ServerSetup.SMTP.dynamicPort(), ServerSetup.IMAP.dynamicPort() });
        GreenMailUser mailbox = greenMail.setUser("test@sismics.com", "Test1234");
        greenMail.start();
        InboxService inbox = AppContext.getInstance().getInboxService();
        try {
            String adminToken = adminToken();
            String suffix = Long.toUnsignedString(System.nanoTime());
            String senderEmail = "eml_peek_" + suffix + "@example.com";
            String username = "inbox_eml_peek_" + suffix;
            createUser(adminToken, username, senderEmail, 1_000_000L);
            String userToken = clientUtil.login(username);
            String tagId = createInboxTag(adminToken, "InboxEmlPeek" + suffix);
            configureInbox(adminToken, greenMail.getImap().getPort(), tagId);
            setAttachEml(true);

            deliver(mailbox, senderEmail, "Invoice " + suffix);

            // Fail the import transaction after the files were created.
            java.util.List<java.nio.file.Path> created =
                    java.util.Collections.synchronizedList(new java.util.ArrayList<>());
            com.sismics.docs.core.service.FileService.setTemporaryFileListener(created::add);
            inbox.setImportFaultInjectorForTest(uid -> {
                throw new RuntimeException("simulated commit failure");
            });
            try {
                sync();
            } finally {
                inbox.setImportFaultInjectorForTest(null);
                com.sismics.docs.core.service.FileService.setTemporaryFileListener(null);
            }

            Assertions.assertEquals(0, documentsOf(userToken).size(), "the failed import must create no document");
            Assertions.assertEquals(1, unseenCount(adminToken),
                    "reading the raw message must not mark it SEEN — it has to stay in the retry queue");
            Assertions.assertEquals(2, created.size(),
                    "the cycle must create exactly the raw message temp + 1 attachment temp: " + created);
            for (java.nio.file.Path temp : created) {
                Assertions.assertFalse(java.nio.file.Files.exists(temp),
                        "no captured temp may survive a failed cycle: " + temp);
            }

            // The next clean cycle imports it exactly once, raw copy included.
            sync();
            JsonArray documents = documentsOf(userToken);
            Assertions.assertEquals(1, documents.size(), "the retried cycle must import the message exactly once");
            JsonArray files = filesOf(userToken, documents.getJsonObject(0).getString("id"));
            Assertions.assertEquals(2, files.size(), "the retried import must carry both files: " + files);
            Assertions.assertNotNull(rawMessageFile(files), "the retried import must attach the raw message");
            Assertions.assertEquals(0, unseenCount(adminToken), "the retried import must be acknowledged");
        } finally {
            setAttachEml(false);
            greenMail.stop();
        }
    }

    /**
     * Decision 3 — the mail must always ack. With headroom for the attachment but not for the raw
     * message, the import completes WITHOUT the {@code .eml}: the document, its extracted attachment
     * and the import receipt all survive, the message is acked, and the next poll re-imports nothing
     * (the poison loop the raw-last ordering exists to prevent).
     */
    @Test
    public void overQuotaRawMessageImportsWithoutEmlAndAcks() throws Exception {
        GreenMail greenMail = new GreenMail(new ServerSetup[] {
                ServerSetup.SMTP.dynamicPort(), ServerSetup.IMAP.dynamicPort() });
        GreenMailUser mailbox = greenMail.setUser("test@sismics.com", "Test1234");
        greenMail.start();
        InboxService inbox = AppContext.getInstance().getInboxService();
        try {
            String adminToken = adminToken();
            String suffix = Long.toUnsignedString(System.nanoTime());
            String senderEmail = "eml_quota_" + suffix + "@example.com";
            String username = "inbox_eml_quota_" + suffix;
            // Room for the 20 KB attachment, but not for the raw message on top of it.
            createUser(adminToken, username, senderEmail, ATTACHMENT_SIZE + 5_000L);
            String userToken = clientUtil.login(username);
            String tagName = "InboxEmlQuota" + suffix;
            String tagId = createInboxTag(adminToken, tagName);
            configureInbox(adminToken, greenMail.getImap().getPort(), tagId);
            setAttachEml(true);

            deliver(mailbox, senderEmail, "Invoice " + suffix);
            sync();

            JsonArray documents = documentsOf(userToken);
            Assertions.assertEquals(1, documents.size(),
                    "an over-quota raw message must not prevent the import");
            String documentId = documents.getJsonObject(0).getString("id");
            JsonArray files = filesOf(userToken, documentId);
            Assertions.assertEquals(1, files.size(),
                    "the extracted attachment must survive; only the raw message is skipped: " + files);
            Assertions.assertEquals("payload.txt", files.getJsonObject(0).getString("name"));
            Assertions.assertNull(rawMessageFile(files), "the over-quota raw message must not be attached");
            Assertions.assertEquals(0, unseenCount(adminToken),
                    "the message must be acked even though the raw attachment was skipped");

            // The next poll must not re-import it: the receipt dedups and nothing new appears.
            sync();
            Assertions.assertEquals(1, documentsOf(userToken).size(),
                    "the next poll must not re-import the message (no poison loop)");
            Assertions.assertEquals(1, filesOf(userToken, documentId).size(),
                    "the next poll must not add a second copy of anything");
            Assertions.assertEquals(0, unseenCount(adminToken), "the message must stay acked");
        } finally {
            setAttachEml(false);
            greenMail.stop();
        }
    }
}
