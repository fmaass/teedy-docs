package com.sismics.docs.rest;

import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetup;
import com.sismics.docs.core.constant.ConfigType;
import com.sismics.docs.core.dao.ConfigDao;
import com.sismics.docs.core.model.context.AppContext;
import com.sismics.docs.core.service.InboxService;
import com.sismics.docs.core.util.ConfigUtil;
import com.sismics.docs.core.util.ImapSourceIdentity;
import com.sismics.docs.core.util.TransactionUtil;
import com.sismics.util.filter.TokenBasedSecurityFilter;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Form;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.mail.Message;
import javax.mail.Session;
import javax.mail.internet.MimeMessage;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Crash-window and exactly-once tests for the IMAP inbox import, driven end-to-end against an embedded
 * GreenMail IMAP server and the real database. The import's transaction-boundary fault seams are used to
 * force the commit-then-ack-fails and commit-fails windows a single local datasource cannot otherwise
 * produce deterministically; the UNSEEN state is asserted via the app's own {@code testInbox()} count
 * (the number of UNSEEN messages), so no IMAP internals are poked.
 */
public class TestInboxSync extends BaseJerseyTest {

    private void createUser(String adminToken, String username, String email) {
        target().path("/user").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form()
                        .param("username", username)
                        .param("email", email)
                        .param("password", "Test1234")
                        .param("storage_quota", "1000000")), JsonObject.class);
    }

    private int configureInbox(String adminToken, int imapPort, String tagId, boolean deleteImported) {
        target().path("/app/config_inbox").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form()
                        .param("enabled", "true")
                        .param("starttls", "false")
                        .param("autoTagsEnabled", "false")
                        .param("deleteImported", Boolean.toString(deleteImported))
                        .param("hostname", "localhost")
                        .param("port", Integer.toString(imapPort))
                        .param("username", "test@sismics.com")
                        .param("password", "Test1234")
                        .param("folder", "INBOX")
                        .param("tag", tagId)), JsonObject.class);
        return imapPort;
    }

    private String createInboxTag(String adminToken, String name) {
        JsonObject json = target().path("/tag").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form().param("name", name).param("color", "#00ff00")), JsonObject.class);
        return json.getString("id");
    }

    private int inboxTaggedDocumentCount(String adminToken, String tagName) {
        JsonObject json = target().path("/document/list")
                .queryParam("search", "tag:" + tagName)
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class);
        return json.getJsonArray("documents").size();
    }

    /** The app's UNSEEN count for the configured inbox (0 once every message is acked). */
    private int unseenCount(String adminToken) {
        JsonObject json = target().path("/app/test_inbox").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form()), JsonObject.class);
        return json.getJsonNumber("count").intValue();
    }

    private void sync() {
        AppContext.getInstance().getInboxService().syncInbox();
    }

    /**
     * POST the inbox configuration. The four required fields are always sent; {@code dedicatedFolder} is
     * sent only when non-null, to model both an explicit set and the current SPA form (which omits it).
     */
    private void postInboxConfig(String adminToken, String dedicatedFolder) {
        Form form = new Form()
                .param("enabled", "false")
                .param("autoTagsEnabled", "false")
                .param("deleteImported", "false")
                .param("starttls", "false")
                .param("hostname", "localhost")
                .param("port", "993")
                .param("username", "test@sismics.com")
                .param("folder", "INBOX");
        if (dedicatedFolder != null) {
            form.param("dedicatedFolder", dedicatedFolder);
        }
        target().path("/app/config_inbox").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(form), JsonObject.class);
    }

    private boolean getDedicatedFolder(String adminToken) {
        JsonObject json = target().path("/app/config_inbox").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class);
        return json.getBoolean("dedicatedFolder");
    }

    /**
     * (#150/#160) The dedicated-folder acknowledgement must be operable through the inbox config endpoint:
     * POST persists it, GET returns it, and a POST that omits it (the current SPA form) PRESERVES the set
     * value instead of clobbering it. The persisted value is exactly what the expunge wiring reads.
     */
    @Test
    public void dedicatedFolderConfigRoundTripsAndPreserves() {
        String adminToken = adminToken();

        // POST WITH the acknowledgement persists it; GET returns it.
        postInboxConfig(adminToken, "true");
        Assertions.assertTrue(getDedicatedFolder(adminToken),
                "POST with dedicatedFolder=true must persist and be returned by GET");

        // POST WITHOUT the field (the current SPA form) must PRESERVE the set value, not clobber it.
        postInboxConfig(adminToken, null);
        Assertions.assertTrue(getDedicatedFolder(adminToken),
                "a POST omitting dedicatedFolder must preserve the prior value");

        // The persisted value is readable via the EXACT accessor InboxService.readConfig uses to populate
        // config.dedicatedFolder, which feeds expungeMode (whose branch table TestInboxExpungeMode covers).
        boolean[] wired = {false};
        TransactionUtil.handle(() ->
                wired[0] = ConfigUtil.getConfigBooleanValue(ConfigType.INBOX_DEDICATED_FOLDER, false));
        Assertions.assertTrue(wired[0], "the persisted acknowledgement must be readable by the expunge wiring");

        // POST WITH the acknowledgement cleared turns it back off.
        postInboxConfig(adminToken, "false");
        Assertions.assertFalse(getDedicatedFolder(adminToken),
                "POST with dedicatedFolder=false must clear the acknowledgement");
    }

    @Test
    public void ambiguousSenderEmailImportsAsAdmin() throws Exception {
        GreenMail greenMail = new GreenMail(new ServerSetup[] {
                ServerSetup.SMTP.dynamicPort(), ServerSetup.IMAP.dynamicPort() });
        greenMail.setUser("test@sismics.com", "Test1234");
        greenMail.start();
        InboxService inbox = AppContext.getInstance().getInboxService();
        try {
            String adminToken = adminToken();
            String suffix = Long.toUnsignedString(System.nanoTime());
            String senderEmail = "shared-" + suffix + "@example.com";
            createUser(adminToken, "inbox_shared_a_" + suffix, senderEmail);
            createUser(adminToken, "inbox_shared_b_" + suffix, senderEmail);
            String tagName = "InboxShared" + suffix;
            String tagId = createInboxTag(adminToken, tagName);
            configureInbox(adminToken, greenMail.getImap().getPort(), tagId, false);
            ServerSetup smtp = new ServerSetup(greenMail.getSmtp().getPort(), null, ServerSetup.PROTOCOL_SMTP);

            GreenMailUtil.sendTextEmail("test@sismics.com", senderEmail, "Ambiguous sender", "body", smtp);
            sync();

            Assertions.assertNull(inbox.getLastSyncError(), "an ambiguous sender must not fail the sync");
            JsonObject list = target().path("/document/list")
                    .queryParam("search", "tag:" + tagName)
                    .request()
                    .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                    .get(JsonObject.class);
            Assertions.assertEquals(1, list.getJsonArray("documents").size(),
                    "the message must be imported exactly once");
            String documentId = list.getJsonArray("documents").getJsonObject(0).getString("id");
            JsonObject document = target().path("/document/" + documentId).request()
                    .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                    .get(JsonObject.class);
            Assertions.assertEquals("admin", document.getString("creator"),
                    "an ambiguous sender email must fall back to admin ownership");
            Assertions.assertEquals(0, unseenCount(adminToken), "the imported message must be acknowledged");
        } finally {
            greenMail.stop();
        }
    }

    /**
     * (#150/#160) A message with NO From header must import successfully, attributed to the unknown-email
     * (admin) fallback, instead of NPEing the import. Both the materialization ({@code getFrom()[0]}) and
     * the create path (sender logging + senderEmail resolution) must tolerate a null sender.
     */
    @Test
    public void missingFromHeaderImportsAsAdminFallback() throws Exception {
        GreenMail greenMail = new GreenMail(new ServerSetup[] {
                ServerSetup.SMTP.dynamicPort(), ServerSetup.IMAP.dynamicPort() });
        GreenMailUser mailbox = greenMail.setUser("test@sismics.com", "Test1234");
        greenMail.start();
        InboxService inbox = AppContext.getInstance().getInboxService();
        try {
            String adminToken = adminToken();
            String tagName = "InboxNoFrom" + System.nanoTime();
            String tagId = createInboxTag(adminToken, tagName);
            configureInbox(adminToken, greenMail.getImap().getPort(), tagId, false);

            // Deliver a message with NO From header directly into the mailbox (SMTP would attach an
            // envelope sender; direct delivery preserves the absent From).
            MimeMessage message = new MimeMessage((Session) null);
            message.setRecipients(Message.RecipientType.TO, "test@sismics.com");
            message.setSubject("No sender");
            message.setText("body without a From header");
            message.saveChanges();
            mailbox.deliver(message);

            sync();

            Assertions.assertNull(inbox.getLastSyncError(), "a missing From header must not fail the sync");
            JsonObject list = target().path("/document/list")
                    .queryParam("search", "tag:" + tagName)
                    .request()
                    .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                    .get(JsonObject.class);
            Assertions.assertEquals(1, list.getJsonArray("documents").size(),
                    "the From-less message must be imported exactly once");
            String documentId = list.getJsonArray("documents").getJsonObject(0).getString("id");
            JsonObject document = target().path("/document/" + documentId).request()
                    .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                    .get(JsonObject.class);
            Assertions.assertEquals("admin", document.getString("creator"),
                    "a null sender must fall back to admin ownership");
            Assertions.assertEquals(0, unseenCount(adminToken), "the imported message must be acknowledged");
        } finally {
            greenMail.stop();
        }
    }

    /**
     * (a) A commit that succeeds followed by an ack that fails must NOT create a duplicate document on
     * the next cycle: the message is re-seen, the receipt dedups it, and only the ack retries.
     */
    @Test
    public void commitThenAckFailsDedupsWithoutDuplicate() throws Exception {
        GreenMail greenMail = new GreenMail(new ServerSetup[] {
                ServerSetup.SMTP.dynamicPort(), ServerSetup.IMAP.dynamicPort() });
        greenMail.setUser("test@sismics.com", "Test1234");
        greenMail.start();
        InboxService inbox = AppContext.getInstance().getInboxService();
        try {
            String adminToken = adminToken();
            String tagName = "InboxAck" + System.nanoTime();
            String tagId = createInboxTag(adminToken, tagName);
            int imapPort = configureInbox(adminToken, greenMail.getImap().getPort(), tagId, false);
            ServerSetup smtp = new ServerSetup(greenMail.getSmtp().getPort(), null, ServerSetup.PROTOCOL_SMTP);

            GreenMailUtil.sendTextEmail("test@sismics.com", "sender@example.com", "Ack window", "body", smtp);

            // Force the ack to fail after the durable commit.
            inbox.setAckFaultInjectorForTest(uid -> {
                throw new RuntimeException("simulated ack failure");
            });
            sync();

            // The document was created (committed), but the message was NOT acked → still UNSEEN.
            Assertions.assertEquals(1, inboxTaggedDocumentCount(adminToken, tagName),
                    "the message must have been imported despite the ack failure");
            Assertions.assertEquals(1, unseenCount(adminToken),
                    "a failed ack must leave the message UNSEEN so it retries");

            // Next cycle with the ack working: the receipt dedups (no second document) and the ack lands.
            inbox.setAckFaultInjectorForTest(null);
            sync();
            Assertions.assertEquals(1, inboxTaggedDocumentCount(adminToken, tagName),
                    "the receipt must dedup the re-seen message — no duplicate document");
            Assertions.assertEquals(0, unseenCount(adminToken),
                    "the retried ack must mark the message SEEN");
        } finally {
            inbox.setAckFaultInjectorForTest(null);
            greenMail.stop();
        }
    }

    /**
     * (b)+(d) A commit that fails must leave the message UNSEEN (not expunged), and — because the body
     * was read under PEEK during materialization — the message stays UNSEEN even though its body was
     * read before the rolled-back import. The next clean cycle imports it exactly once.
     */
    @Test
    public void commitFailsLeavesMessageUnseenAndPeekDoesNotMarkSeen() throws Exception {
        GreenMail greenMail = new GreenMail(new ServerSetup[] {
                ServerSetup.SMTP.dynamicPort(), ServerSetup.IMAP.dynamicPort() });
        greenMail.setUser("test@sismics.com", "Test1234");
        greenMail.start();
        InboxService inbox = AppContext.getInstance().getInboxService();
        try {
            String adminToken = adminToken();
            String tagName = "InboxCommit" + System.nanoTime();
            String tagId = createInboxTag(adminToken, tagName);
            configureInbox(adminToken, greenMail.getImap().getPort(), tagId, false);
            ServerSetup smtp = new ServerSetup(greenMail.getSmtp().getPort(), null, ServerSetup.PROTOCOL_SMTP);

            GreenMailUtil.sendTextEmail("test@sismics.com", "sender@example.com", "Commit window", "body", smtp);

            // Force the per-message import transaction to fail before commit.
            inbox.setImportFaultInjectorForTest(uid -> {
                throw new RuntimeException("simulated work failure");
            });
            sync();

            Assertions.assertEquals(0, inboxTaggedDocumentCount(adminToken, tagName),
                    "a failed commit must create no document");
            // The body WAS read during materialization; PEEK means the message is still UNSEEN, so it
            // remains a retry candidate. Without PEEK it would have been silently dropped from UNSEEN.
            Assertions.assertEquals(1, unseenCount(adminToken),
                    "PEEK must keep the message UNSEEN after a rolled-back import");

            // Clean cycle: exactly one import.
            inbox.setImportFaultInjectorForTest(null);
            sync();
            Assertions.assertEquals(1, inboxTaggedDocumentCount(adminToken, tagName),
                    "the retained message must import exactly once on the next clean cycle");
            Assertions.assertEquals(0, unseenCount(adminToken),
                    "after a clean import the message is SEEN");
        } finally {
            inbox.setImportFaultInjectorForTest(null);
            greenMail.stop();
        }
    }

    /**
     * (c) A mid-batch failure must keep the already-committed messages AND continue to the later ones —
     * one poison message does not unwind the whole cycle.
     */
    @Test
    public void midBatchFailureKeepsCommittedAndContinues() throws Exception {
        GreenMail greenMail = new GreenMail(new ServerSetup[] {
                ServerSetup.SMTP.dynamicPort(), ServerSetup.IMAP.dynamicPort() });
        greenMail.setUser("test@sismics.com", "Test1234");
        greenMail.start();
        InboxService inbox = AppContext.getInstance().getInboxService();
        try {
            String adminToken = adminToken();
            String tagName = "InboxPoison" + System.nanoTime();
            String tagId = createInboxTag(adminToken, tagName);
            configureInbox(adminToken, greenMail.getImap().getPort(), tagId, false);
            ServerSetup smtp = new ServerSetup(greenMail.getSmtp().getPort(), null, ServerSetup.PROTOCOL_SMTP);

            GreenMailUtil.sendTextEmail("test@sismics.com", "sender@example.com", "Msg 1", "b1", smtp);
            GreenMailUtil.sendTextEmail("test@sismics.com", "sender@example.com", "Msg 2", "b2", smtp);
            GreenMailUtil.sendTextEmail("test@sismics.com", "sender@example.com", "Msg 3", "b3", smtp);

            // Fail the SECOND message processed; the first and third must still import.
            AtomicInteger seq = new AtomicInteger(0);
            inbox.setImportFaultInjectorForTest(uid -> {
                if (seq.incrementAndGet() == 2) {
                    throw new RuntimeException("simulated poison message");
                }
            });
            sync();

            Assertions.assertEquals(2, inboxTaggedDocumentCount(adminToken, tagName),
                    "poison isolation: the two healthy messages must import despite the failed one");
            Assertions.assertEquals(1, unseenCount(adminToken),
                    "only the poison message stays UNSEEN");

            // Clean cycle imports the remaining message exactly once (receipts dedup the other two).
            inbox.setImportFaultInjectorForTest(null);
            sync();
            Assertions.assertEquals(3, inboxTaggedDocumentCount(adminToken, tagName),
                    "the retried poison message imports once; the others are receipt-deduped");
            Assertions.assertEquals(0, unseenCount(adminToken), "all messages eventually SEEN");
        } finally {
            inbox.setImportFaultInjectorForTest(null);
            greenMail.stop();
        }
    }

    /**
     * Ambiguous-commit ack-safety: commit() throwing AFTER the database durably persisted the row
     * (IN_DOUBT) must NOT ack the message — but the row IS committed, so the next cycle's receipt dedups
     * it. No duplicate document, no lost mail. This is distinct from a pre-commit work failure (which
     * rolls back and persists nothing): here the document DOES exist after the failed cycle.
     */
    @Test
    public void ambiguousCommitLeavesMessageUnseenThenReceiptDedups() throws Exception {
        GreenMail greenMail = new GreenMail(new ServerSetup[] {
                ServerSetup.SMTP.dynamicPort(), ServerSetup.IMAP.dynamicPort() });
        greenMail.setUser("test@sismics.com", "Test1234");
        greenMail.start();
        InboxService inbox = AppContext.getInstance().getInboxService();
        try {
            String adminToken = adminToken();
            String tagName = "InboxInDoubt" + System.nanoTime();
            String tagId = createInboxTag(adminToken, tagName);
            configureInbox(adminToken, greenMail.getImap().getPort(), tagId, false);
            ServerSetup smtp = new ServerSetup(greenMail.getSmtp().getPort(), null, ServerSetup.PROTOCOL_SMTP);

            GreenMailUtil.sendTextEmail("test@sismics.com", "sender@example.com", "In-doubt", "body", smtp);

            // Model commit() throwing after the DB durably persisted the row.
            inbox.setCommitInDoubtFaultInjectorForTest(uid -> {
                throw new RuntimeException("simulated in-doubt commit");
            });
            sync();

            // The row WAS committed (the document exists), unlike a pre-commit rollback.
            Assertions.assertEquals(1, inboxTaggedDocumentCount(adminToken, tagName),
                    "an in-doubt commit persisted the document");
            // But it was not acked, so it stays UNSEEN.
            Assertions.assertEquals(1, unseenCount(adminToken),
                    "an in-doubt commit must NOT ack the message");

            // Next cycle: the persisted receipt dedups — no duplicate document, no lost mail.
            inbox.setCommitInDoubtFaultInjectorForTest(null);
            sync();
            Assertions.assertEquals(1, inboxTaggedDocumentCount(adminToken, tagName),
                    "the persisted receipt must dedup — no duplicate document");
            Assertions.assertEquals(0, unseenCount(adminToken),
                    "the retried ack must mark the message SEEN");
        } finally {
            inbox.setCommitInDoubtFaultInjectorForTest(null);
            greenMail.stop();
        }
    }

    /**
     * (f) A UIDVALIDITY change must PERSISTENTLY block the source (no import, no flag change, error
     * surfaced) and never re-import, until the operator re-saves the inbox configuration.
     */
    @Test
    public void uidValidityChangeBlocksPersistentlyThenOperatorUnblocks() throws Exception {
        GreenMail greenMail = new GreenMail(new ServerSetup[] {
                ServerSetup.SMTP.dynamicPort(), ServerSetup.IMAP.dynamicPort() });
        greenMail.setUser("test@sismics.com", "Test1234");
        greenMail.start();
        InboxService inbox = AppContext.getInstance().getInboxService();
        try {
            String adminToken = adminToken();
            String tagName = "InboxEpoch" + System.nanoTime();
            String tagId = createInboxTag(adminToken, tagName);
            int imapPort = configureInbox(adminToken, greenMail.getImap().getPort(), tagId, false);
            ServerSetup smtp = new ServerSetup(greenMail.getSmtp().getPort(), null, ServerSetup.PROTOCOL_SMTP);

            // First message imports normally and establishes the UIDVALIDITY baseline.
            GreenMailUtil.sendTextEmail("test@sismics.com", "sender@example.com", "Epoch 1", "b1", smtp);
            sync();
            Assertions.assertEquals(1, inboxTaggedDocumentCount(adminToken, tagName), "baseline import");

            // Simulate a mailbox recreation: overwrite the stored baseline with the SAME source digest but
            // a different UIDVALIDITY. The next sync must detect the epoch change and block.
            String digest = ImapSourceIdentity.digest("localhost", Integer.toString(imapPort), "imap",
                    "test@sismics.com", "INBOX");
            TransactionUtil.handle(() ->
                    new ConfigDao().update(ConfigType.INBOX_UIDVALIDITY, digest + ":999999999"));

            // A NEW unseen message arrives; the blocked cycle must NOT import it.
            GreenMailUtil.sendTextEmail("test@sismics.com", "sender@example.com", "Epoch 2", "b2", smtp);
            sync();
            Assertions.assertEquals(1, inboxTaggedDocumentCount(adminToken, tagName),
                    "a UIDVALIDITY change must block import — no new document");
            Assertions.assertNotNull(inbox.getLastSyncError(), "the block must surface a sync error");
            Assertions.assertTrue(inbox.getLastSyncError().contains("UIDVALIDITY"),
                    "the surfaced error must name the UIDVALIDITY change; was: " + inbox.getLastSyncError());

            // A second cycle stays blocked (persistent, not resume-next-cycle).
            sync();
            Assertions.assertEquals(1, inboxTaggedDocumentCount(adminToken, tagName),
                    "the block must persist across cycles");

            // Operator unblock: re-save the inbox configuration (clears the baseline) and resume.
            configureInbox(adminToken, imapPort, tagId, false);
            sync();
            Assertions.assertEquals(2, inboxTaggedDocumentCount(adminToken, tagName),
                    "re-saving the configuration must lift the block and import the retained message");
        } finally {
            greenMail.stop();
        }
    }
}
