package com.sismics.docs.core.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.mail.Flags;
import javax.mail.Folder;
import javax.mail.FolderClosedException;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.Store;
import javax.mail.UIDFolder;
import javax.mail.internet.InternetAddress;
import javax.mail.search.FlagTerm;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.util.concurrent.AbstractScheduledService;
import com.sismics.docs.core.constant.ConfigType;
import com.sismics.docs.core.dao.ConfigDao;
import com.sismics.docs.core.dao.InboxImportReceiptDao;
import com.sismics.docs.core.dao.TagDao;
import com.sismics.docs.core.dao.UserDao;
import com.sismics.docs.core.dao.criteria.TagCriteria;
import com.sismics.docs.core.dao.dto.TagDto;
import com.sismics.docs.core.event.DocumentCreatedAsyncEvent;
import com.sismics.docs.core.model.jpa.Config;
import com.sismics.docs.core.model.jpa.Document;
import com.sismics.docs.core.model.jpa.InboxImportReceipt;
import com.sismics.docs.core.model.jpa.Tag;
import com.sismics.docs.core.util.ConfigUtil;
import com.sismics.docs.core.util.DescriptionSanitizer;
import com.sismics.docs.core.util.DocumentUtil;
import com.sismics.docs.core.util.FileUtil;
import com.sismics.docs.core.util.ImapSourceIdentity;
import com.sismics.docs.core.util.TransactionUtil;
import com.sismics.docs.core.util.jpa.SortCriteria;
import com.sismics.util.EmailUtil;
import com.sismics.util.context.ThreadLocalContext;
import com.sun.mail.imap.IMAPFolder;
import com.sun.mail.imap.IMAPMessage;
import com.sun.mail.imap.IMAPStore;

/**
 * Inbox scanning service.
 *
 * <p>The import is crash-safe and exactly-once. Each UNSEEN message is materialized OUTSIDE any
 * database transaction using non-mutating IMAP PEEK (reading a body must not set {@code \Seen}, so the
 * UNSEEN set stays a durable retry queue), then imported in its OWN transaction that inserts an
 * idempotency receipt FIRST (claim-first) so a duplicate is rejected by the receipt's unique index
 * before any document or encrypted blob exists. The IMAP flag ack ({@code \Seen}, or {@code \Deleted}
 * + expunge) is set ONLY after that transaction durably commits: a crash between commit and ack leaves
 * the message UNSEEN, and the next cycle re-sees it, the receipt dedups it, and only the ack retries.
 *
 * @author bgamard
 */
public class InboxService extends AbstractScheduledService {
    /**
     * Logger.
     */
    private static final Logger log = LoggerFactory.getLogger(InboxService.class);

    /**
     * IMAP protocol identifier folded into the source identity digest.
     */
    private static final String PROTOCOL = "imap";

    /**
     * Last synchronization data.
     */
    private Date lastSyncDate;
    private int lastSyncMessageCount = 0;
    private String lastSyncError;

    /**
     * Fault-injection seam for tests only. Invoked INSIDE a per-message import transaction, just before
     * it commits: a test can force a chosen message's transaction to fail (exercising the commit-fails /
     * poison-isolation windows against a real IMAP server + real database, which a single local
     * datasource cannot deterministically produce). Null in production.
     */
    private volatile InboxFaultInjector importFaultInjector;

    /**
     * Fault-injection seam for tests only. Invoked AFTER a per-message import durably commits but BEFORE
     * the IMAP flag ack, to force the commit-then-ack-fails window. Null in production.
     */
    private volatile InboxFaultInjector ackFaultInjector;

    /**
     * Fault-injection seam for tests only. Invoked AFTER a per-message import durably commits, to model
     * an AMBIGUOUS commit — commit() threw after the database actually persisted the row (IN_DOUBT). On
     * a throw the message is treated as not-durably-acked: it is NOT acked and stays UNSEEN, and the next
     * cycle's receipt (which the DB did commit) dedups it, so there is no duplicate document and no lost
     * mail. Distinct from a pre-commit work failure (which rolls back and persists nothing). Null in
     * production.
     */
    private volatile InboxFaultInjector commitInDoubtFaultInjector;

    public InboxService() {
    }

    @Override
    protected void startUp() {
        log.info("Inbox service starting up");
    }

    @Override
    protected void shutDown() {
        log.info("Inbox service shutting down");
    }

    @Override
    protected void runOneIteration() {
        try {
            syncInbox();
        } catch (Throwable e) {
            log.error("Exception during inbox synching", e);
        }
    }

    /**
     * Synchronize the inbox.
     */
    public void syncInbox() {
        InboxConfig config = readConfig();
        if (config == null || !config.enabled) {
            return;
        }

        log.info("Synchronizing IMAP inbox...");
        lastSyncError = null;
        lastSyncDate = new Date();
        lastSyncMessageCount = 0;

        Folder inbox = null;
        try {
            inbox = openInbox(config);

            // Fail closed if the folder cannot give us a stable UID identity: no import, no flag change.
            if (!(inbox instanceof UIDFolder)) {
                lastSyncError = "The inbox folder does not support UIDs; import is disabled (fail-closed).";
                log.error(lastSyncError);
                return;
            }
            long uidValidity = ((UIDFolder) inbox).getUIDValidity();
            if (uidValidity <= 0) {
                lastSyncError = "The inbox folder reported a non-positive UIDVALIDITY (" + uidValidity
                        + "); import is disabled (fail-closed).";
                log.error(lastSyncError);
                return;
            }

            String identityDigest = config.identityDigest();

            // Fail closed persistently on a UIDVALIDITY reset (a recreated mailbox renumbers every UID).
            if (isEpochBlocked(config, identityDigest, uidValidity)) {
                return;
            }

            Message[] messages = inbox.search(new FlagTerm(new Flags(Flags.Flag.SEEN), false));
            log.info(messages.length + " messages found");

            // Materialize each message's bytes + metadata into a detached work item OUTSIDE any DB
            // transaction, under PEEK, so a rollback/crash never loses a message from the UNSEEN queue.
            List<WorkItem> workItems = materialize(inbox, messages, config, identityDigest, uidValidity);

            // Import each work item in its own transaction; ack only after a durable commit.
            List<WorkItem> ackedForExpunge = new ArrayList<>();
            for (WorkItem item : workItems) {
                boolean acked = importWorkItem(item, config, inbox);
                if (acked) {
                    lastSyncMessageCount++;
                    if (config.deleteImported) {
                        ackedForExpunge.add(item);
                    }
                }
            }

            // Expunge the imported+deleted messages by UID (once per cycle).
            if (config.deleteImported && !ackedForExpunge.isEmpty()) {
                expungeImported(inbox, ackedForExpunge, uidValidity);
            }
        } catch (FolderClosedException e) {
            // Ignore this, we will just continue importing on the next cycle
        } catch (Exception e) {
            log.error("Error syncing the inbox", e);
            lastSyncError = e.getMessage();
        } finally {
            closeInbox(inbox);
        }
    }

    /**
     * Test the inbox configuration.
     *
     * @return Number of messages currently in the remote inbox
     */
    public int testInbox() {
        InboxConfig config = readConfig();
        if (config == null || !config.enabled) {
            return -1;
        }

        Folder inbox = null;
        try {
            inbox = openInbox(config);
            return inbox.search(new FlagTerm(new Flags(Flags.Flag.SEEN), false)).length;
        } catch (Exception e) {
            log.error("Error testing inbox", e);
            return -1;
        } finally {
            closeInbox(inbox);
        }
    }

    @Override
    protected Scheduler scheduler() {
        return Scheduler.newFixedDelaySchedule(0, 1, TimeUnit.MINUTES);
    }

    /**
     * Read every configuration value the sync needs in one short transaction, so materialization and the
     * per-message imports run against detached data with no ambient transaction.
     *
     * @return the configuration, or a disabled marker
     */
    private InboxConfig readConfig() {
        InboxConfig[] holder = new InboxConfig[1];
        TransactionUtil.handle(() -> {
            InboxConfig config = new InboxConfig();
            config.enabled = ConfigUtil.getConfigBooleanValue(ConfigType.INBOX_ENABLED, false);
            if (!config.enabled) {
                holder[0] = config;
                return;
            }
            config.hostname = ConfigUtil.getConfigStringValue(ConfigType.INBOX_HOSTNAME);
            config.port = ConfigUtil.getConfigStringValue(ConfigType.INBOX_PORT);
            config.starttls = ConfigUtil.getConfigBooleanValue(ConfigType.INBOX_STARTTLS, false);
            config.username = ConfigUtil.getConfigStringValue(ConfigType.INBOX_USERNAME);
            config.password = ConfigUtil.getConfigStringValue(ConfigType.INBOX_PASSWORD);
            config.folder = ConfigUtil.getConfigStringValue(ConfigType.INBOX_FOLDER);
            config.deleteImported = ConfigUtil.getConfigBooleanValue(ConfigType.INBOX_DELETE_IMPORTED, false);
            config.defaultLanguage = ConfigUtil.getConfigStringValue(ConfigType.DEFAULT_LANGUAGE);
            config.inboxTagId = ConfigUtil.getConfigStringValue(ConfigType.INBOX_TAG);
            config.tagMap = getAllTags();
            Config storedUidValidity = new ConfigDao().getById(ConfigType.INBOX_UIDVALIDITY);
            config.storedUidValidityRaw = storedUidValidity == null ? null : storedUidValidity.getValue();
            holder[0] = config;
        });
        return holder[0];
    }

    /**
     * Determine whether the source is blocked by a UIDVALIDITY change, updating the persisted baseline on
     * a first sync or a source change.
     *
     * @return true if the source is blocked and must not be imported this cycle
     */
    private boolean isEpochBlocked(InboxConfig config, String identityDigest, long currentUidValidity) {
        String stored = config.storedUidValidityRaw;
        if (StringUtils.isEmpty(stored)) {
            storeBaseline(identityDigest, currentUidValidity);
            return false;
        }
        int sep = stored.lastIndexOf(':');
        if (sep <= 0) {
            storeBaseline(identityDigest, currentUidValidity);
            return false;
        }
        String storedDigest = stored.substring(0, sep);
        long storedUidValidity;
        try {
            storedUidValidity = Long.parseLong(stored.substring(sep + 1));
        } catch (NumberFormatException e) {
            storeBaseline(identityDigest, currentUidValidity);
            return false;
        }
        if (!storedDigest.equals(identityDigest)) {
            // A different configured source (account/folder changed): a new mailbox with its own digest.
            // Establish a fresh baseline and proceed — the receipt digests differ, so there is no
            // cross-source confusion.
            storeBaseline(identityDigest, currentUidValidity);
            return false;
        }
        if (storedUidValidity != currentUidValidity) {
            // Same source, new UIDVALIDITY epoch: every UID was renumbered, so no receipt matches and
            // auto-accepting would re-import every retained message. Fail closed PERSISTENTLY — do NOT
            // update the baseline, so every cycle stays blocked until an operator re-saves the inbox
            // configuration (which clears the stored baseline and re-establishes it).
            lastSyncError = "The inbox mailbox UIDVALIDITY changed from " + storedUidValidity + " to "
                    + currentUidValidity + " (the mailbox was recreated). Import is blocked to avoid"
                    + " re-importing every message; re-save the inbox configuration to accept the new"
                    + " mailbox and resume.";
            log.error(lastSyncError);
            return true;
        }
        return false;
    }

    /**
     * Persist the accepted UIDVALIDITY baseline for a source.
     */
    private void storeBaseline(String identityDigest, long uidValidity) {
        TransactionUtil.handle(() ->
                new ConfigDao().update(ConfigType.INBOX_UIDVALIDITY, identityDigest + ":" + uidValidity));
    }

    /**
     * Materialize each message into a detached work item, reading bodies under PEEK. A per-message
     * materialization failure (including a parse failure) deletes that message's parsed temps and skips
     * it — one bad message does not abort the whole cycle.
     */
    private List<WorkItem> materialize(Folder inbox, Message[] messages, InboxConfig config,
                                       String identityDigest, long uidValidity) {
        UIDFolder uidFolder = (UIDFolder) inbox;
        List<WorkItem> items = new ArrayList<>();
        for (Message message : messages) {
            WorkItem item = new WorkItem();
            item.identityDigest = identityDigest;
            item.uidValidity = uidValidity;
            item.account = config.accountRaw();
            item.folder = config.folder;
            item.mailContent = new EmailUtil.MailContent();
            try {
                // Belt-and-suspenders on top of the mail.imap.peek session property: never set \Seen.
                if (message instanceof IMAPMessage) {
                    ((IMAPMessage) message).setPeek(true);
                }
                long uid = uidFolder.getUID(message);
                if (uid <= 0) {
                    lastSyncError = "An inbox message had no valid UID; skipping it (fail-closed).";
                    log.error(lastSyncError);
                    continue;
                }
                item.uid = uid;
                item.sender = (InternetAddress) message.getFrom()[0];
                item.mailContent.setSubject(message.getSubject());
                item.mailContent.setDate(message.getSentDate());
                EmailUtil.parseMailContent(message, item.mailContent);
                items.add(item);
            } catch (Exception e) {
                // Parser / read failure: delete the temps this message already wrote, log, skip it.
                deleteParsedTemps(item);
                log.error("Failed to materialize an inbox message; skipping it this cycle", e);
                lastSyncError = e.getMessage();
            }
        }
        return items;
    }

    /**
     * Import one materialized message in its own transaction and, on a durable commit, ack it. Returns
     * true iff the message was acked (freshly imported OR confirmed already-imported). Never throws — a
     * per-message failure is isolated so the loop continues with the remaining messages.
     */
    private boolean importWorkItem(WorkItem item, InboxConfig config, Folder inbox) {
        try {
            TransactionUtil.handle(() -> {
                InboxImportReceiptDao receiptDao = new InboxImportReceiptDao();
                // Claim FIRST: insert + flush the receipt so a duplicate is rejected atomically before any
                // document or encrypted blob is created. A unique violation ESCAPES this lambda (never
                // caught here — on PostgreSQL a caught violation would poison the transaction we then try
                // to commit).
                InboxImportReceipt receipt = receiptDao.claim(
                        item.identityDigest, item.uidValidity, item.uid, item.account, item.folder);
                String documentId;
                try {
                    documentId = createDocumentAndFiles(item, config);
                } catch (Exception e) {
                    // A work failure (not the claim's unique violation): wrap so handle() rolls back and
                    // propagates; the cause chain still exposes any constraint violation to the classifier.
                    throw new RuntimeException(e);
                }
                receiptDao.linkDocument(receipt, documentId);
                InboxFaultInjector fault = importFaultInjector;
                if (fault != null) {
                    fault.inject(item.uid);
                }
            });
        } catch (RuntimeException e) {
            // The document/files were never handed to FileUtil on the claim-lost path, and on a
            // work-failure rollback A2's after-rollback cleanup already removed the temps it owned;
            // deleting our parsed temps here is idempotent and covers the temps that never reached
            // FileUtil.
            deleteParsedTemps(item);
            if (isConstraintViolation(e)) {
                // Already claimed (a concurrent winner, or a prior cycle whose ack failed). Confirm the
                // WINNING receipt in a FRESH transaction before treating it as a duplicate — never ack on
                // an unrelated integrity error.
                if (confirmReceiptExists(item)) {
                    return ackAfterCommit(item, config, inbox);
                }
                log.error("A non-duplicate integrity error occurred importing inbox message uid=" + item.uid
                        + "; not acking (it stays unread)", e);
                lastSyncError = e.getMessage();
                return false;
            }
            // A work/commit failure: the transaction rolled back, so the message is NOT acked and stays
            // UNSEEN for the next cycle. Poison isolation: log and continue.
            log.error("Failed to import inbox message uid=" + item.uid + "; not acking (it stays unread)", e);
            lastSyncError = e.getMessage();
            return false;
        }

        // Durable commit. The parsed temps are now owned by A2 (deleted by the async listener on the
        // commit path).
        InboxFaultInjector inDoubt = commitInDoubtFaultInjector;
        if (inDoubt != null) {
            try {
                inDoubt.inject(item.uid);
            } catch (RuntimeException e) {
                // Models commit() throwing AFTER a durable persist (IN_DOUBT): the row is committed but
                // we cannot confirm it, so we MUST NOT ack. The message stays UNSEEN; the next cycle's
                // receipt dedups the persisted row (no duplicate document, no lost mail).
                log.error("Simulated in-doubt commit for inbox message uid=" + item.uid
                        + "; not acking (it stays unread and will be receipt-deduped next cycle)", e);
                return false;
            }
        }

        // Ack the message; a failure leaves it UNSEEN and receipt-deduped next cycle.
        return ackAfterCommit(item, config, inbox);
    }

    /**
     * Ack a durably-imported message by setting its IMAP flag, honoring the ack fault seam. Returns true
     * iff the ack succeeded.
     */
    private boolean ackAfterCommit(WorkItem item, InboxConfig config, Folder inbox) {
        try {
            InboxFaultInjector fault = ackFaultInjector;
            if (fault != null) {
                fault.inject(item.uid);
            }
            ackMessage(item, config, inbox);
            return true;
        } catch (Exception e) {
            log.error("Failed to ack imported inbox message uid=" + item.uid
                    + "; it will retry (and receipt-dedup) next cycle", e);
            lastSyncError = e.getMessage();
            return false;
        }
    }

    /**
     * Set the imported message's IMAP flag, resolved BY UID under the captured UIDVALIDITY (never by a
     * sequence number). Sets {@code \Deleted} when delete-imported is enabled (the batch expunge removes
     * it), else {@code \Seen} so it leaves the UNSEEN queue.
     */
    private void ackMessage(WorkItem item, InboxConfig config, Folder inbox) throws MessagingException {
        UIDFolder uidFolder = (UIDFolder) inbox;
        // Guard: the epoch must not have changed under us mid-cycle, or a UID would target a different
        // message.
        long currentUidValidity = uidFolder.getUIDValidity();
        if (currentUidValidity != item.uidValidity) {
            throw new MessagingException("UIDVALIDITY changed during the sync cycle; refusing to ack by a stale UID");
        }
        Message message = uidFolder.getMessageByUID(item.uid);
        if (message == null) {
            // Already gone (expunged) — nothing to ack.
            return;
        }
        if (config.deleteImported) {
            message.setFlag(Flags.Flag.DELETED, true);
        } else {
            message.setFlag(Flags.Flag.SEEN, true);
        }
    }

    /**
     * Expunge the imported+deleted messages BY UID. Prefers UIDPLUS {@code UID EXPUNGE} so only our
     * messages are removed; falls back to a generic expunge only under the precondition that teedy
     * exclusively owns this configured import folder (a generic expunge removes every {@code \Deleted}
     * message in the folder).
     */
    private void expungeImported(Folder inbox, List<WorkItem> acked, long uidValidity) {
        try {
            UIDFolder uidFolder = (UIDFolder) inbox;
            if (uidFolder.getUIDValidity() != uidValidity) {
                log.error("UIDVALIDITY changed during the sync cycle; skipping expunge to avoid removing the wrong messages");
                return;
            }
            List<Message> messages = new ArrayList<>();
            for (WorkItem item : acked) {
                Message message = uidFolder.getMessageByUID(item.uid);
                if (message != null) {
                    messages.add(message);
                }
            }
            if (messages.isEmpty()) {
                return;
            }
            Store store = inbox.getStore();
            if (inbox instanceof IMAPFolder && store instanceof IMAPStore
                    && ((IMAPStore) store).hasCapability("UIDPLUS")) {
                // UID EXPUNGE: removes only the listed messages, never another client's \Deleted message.
                ((IMAPFolder) inbox).expunge(messages.toArray(new Message[0]));
            } else {
                // Precondition: teedy exclusively owns this configured import folder (a dedicated mailbox),
                // so every \Deleted message in it is one we imported.
                inbox.expunge();
            }
        } catch (Exception e) {
            log.error("Failed to expunge imported inbox messages; they will be re-seen and receipt-deduped next cycle", e);
        }
    }

    /**
     * Open the remote inbox. Transfers store ownership to the caller ONLY once the folder is fully open;
     * on any failure after connecting, closes the store so a socket does not leak every scheduled cycle.
     *
     * @return Opened inbox folder
     * @throws Exception e
     */
    private Folder openInbox(InboxConfig config) throws Exception {
        Properties properties = new Properties();
        String port = config.port;
        properties.put("mail.imap.host", config.hostname);
        properties.put("mail.imap.port", port);
        properties.setProperty("mail.imap.starttls.enable", String.valueOf(config.starttls));
        // Non-mutating fetch: reading a body must issue BODY.PEEK, not BODY, so it never sets \Seen. The
        // UNSEEN set is the durable retry queue; \Seen is set only as the deliberate post-commit ack.
        properties.setProperty("mail.imap.peek", "true");
        boolean isSsl = "993".equals(port);
        properties.put("mail.imap.ssl.enable", String.valueOf(isSsl));
        properties.setProperty("mail.imap.socketFactory.class",
                isSsl ? "javax.net.ssl.SSLSocketFactory" : "javax.net.DefaultSocketFactory");
        properties.setProperty("mail.imap.socketFactory.fallback", "true");
        properties.setProperty("mail.imap.socketFactory.port", port);
        // Set the timeouts on the ACTUAL provider prefix used. The store is always opened as protocol
        // "imap" (SSL is requested via mail.imap.ssl.enable, not by switching to the "imaps" provider),
        // so mail.imaps.* timeouts would be ignored and an SSL sync could hang the single scheduled
        // thread indefinitely. Set them on mail.imap.* for both SSL and plaintext.
        properties.put("mail.imap.connectiontimeout", 30000);
        properties.put("mail.imap.timeout", 30000);
        properties.put("mail.imap.writetimeout", 30000);

        Session session = Session.getInstance(properties);

        Store store = session.getStore("imap");
        try {
            // connect() is INSIDE this try: a connect that fails after partial provider/socket setup must
            // also close the store, so EVERY unsuccessful return releases the socket.
            store.connect(config.username, config.password);
            Folder inbox = store.getFolder(config.folder);
            inbox.open(Folder.READ_WRITE);
            return inbox;
        } catch (Exception e) {
            // Could not connect / open the folder: close the store so the socket does not leak every cycle.
            try {
                store.close();
            } catch (Exception closeError) {
                // NOP
            }
            throw e;
        }
    }

    /**
     * Close the inbox folder (without expunging — acks and the batch expunge already ran) and its store.
     */
    private void closeInbox(Folder inbox) {
        try {
            if (inbox != null) {
                if (inbox.isOpen()) {
                    inbox.close(false);
                }
                inbox.getStore().close();
            }
        } catch (Exception e) {
            // NOP
        }
    }

    /**
     * Create the document and its files for a materialized message, returning the document ID. Runs
     * inside the caller's per-message transaction.
     */
    private String createDocumentAndFiles(WorkItem item, InboxConfig config) throws Exception {
        EmailUtil.MailContent mailContent = item.mailContent;
        InternetAddress sender = item.sender;
        log.info("Importing message: " + mailContent.getSubject() + ",sender=" + sender.getAddress());

        // Create the document
        Document document = new Document();
        String subject = mailContent.getSubject();
        if (subject == null) {
            subject = "Imported email from EML file";
        }

        HashSet<String> tagsFound = new HashSet<>();
        if (config.tagMap != null) {
            Pattern pattern = Pattern.compile("#([^\\s:#]+)");
            Matcher matcher = pattern.matcher(subject);
            while (matcher.find()) {
                if (config.tagMap.containsKey(matcher.group(1)) && config.tagMap.get(matcher.group(1)) != null) {
                    tagsFound.add(config.tagMap.get(matcher.group(1)));
                    subject = subject.replaceFirst("#" + matcher.group(1), "");
                }
            }
            log.debug("Tags found: " + String.join(", ", tagsFound));
            subject = subject.trim().replaceAll(" +", " ");
        }
        UserDao userDao = new UserDao();
        com.sismics.docs.core.model.jpa.User user = userDao.getByEmail(sender.getAddress());
        String userId = user != null ? user.getId() : "admin";
        if (user != null) {
            document.setUserId(user.getId());
        } else {
            document.setUserId("admin");
        }
        document.setTitle(StringUtils.abbreviate(subject, 100));
        document.setDescription(DescriptionSanitizer.sanitize(StringUtils.abbreviate(mailContent.getMessage(), 4000)));
        document.setSubject(StringUtils.abbreviate(mailContent.getSubject(), 500));
        document.setFormat("EML");
        document.setSource("Inbox");
        document.setLanguage(config.defaultLanguage);
        if (mailContent.getDate() == null) {
            document.setCreateDate(new Date());
        } else {
            document.setCreateDate(mailContent.getDate());
        }
        DocumentUtil.createDocument(document, userId);

        // Add the tag
        if (config.inboxTagId != null) {
            TagDao tagDao = new TagDao();
            Tag tag = tagDao.getById(config.inboxTagId);
            if (tag != null) {
                tagsFound.add(config.inboxTagId);
            }
        }

        // Update tags
        if (!tagsFound.isEmpty()) {
            new TagDao().updateTagList(document.getId(), tagsFound);
        }

        // Raise a document created event
        DocumentCreatedAsyncEvent documentCreatedAsyncEvent = new DocumentCreatedAsyncEvent();
        documentCreatedAsyncEvent.setUserId(userId);
        documentCreatedAsyncEvent.setDocumentId(document.getId());
        ThreadLocalContext.get().addAsyncEvent(documentCreatedAsyncEvent);

        // Add files to the document. These parsed temps are now owned by FileUtil (marker + rollback
        // cleanup registered there); this service no longer deletes them on the success path.
        for (EmailUtil.FileContent fileContent : mailContent.getFileContentList()) {
            FileUtil.createFile(fileContent.getName(), null, fileContent.getFile(), fileContent.getSize(),
                    document.getLanguage(), userId, document.getId());
        }

        return document.getId();
    }

    /**
     * Confirm, in a FRESH transaction, that a winning receipt exists for a message — the only signal
     * that a claim violation was a genuine duplicate rather than an unrelated integrity error.
     */
    private boolean confirmReceiptExists(WorkItem item) {
        boolean[] found = {false};
        try {
            TransactionUtil.handle(() ->
                    found[0] = new InboxImportReceiptDao()
                            .findByIdentity(item.identityDigest, item.uidValidity, item.uid) != null);
        } catch (RuntimeException e) {
            log.error("Failed to confirm the winning receipt for inbox message uid=" + item.uid, e);
            return false;
        }
        return found[0];
    }

    /**
     * Delete a work item's parsed attachment temps. Called on every path that does NOT hand them to
     * FileUtil (parser failure, duplicate fast-path, lost-claim race). Idempotent and guarded so a
     * failure never aborts the cycle.
     */
    private void deleteParsedTemps(WorkItem item) {
        if (item == null || item.mailContent == null) {
            return;
        }
        for (EmailUtil.FileContent fileContent : item.mailContent.getFileContentList()) {
            Path file = fileContent.getFile();
            if (file == null) {
                continue;
            }
            try {
                Files.deleteIfExists(file);
            } catch (Exception e) {
                log.warn("Unable to delete a parsed inbox attachment temp: " + file, e);
            }
        }
    }

    /**
     * Fetches a HashMap with all tag names as keys and their respective ids as values.
     *
     * @return Map with all tags or null if not enabled
     */
    private Map<String, String> getAllTags() {
        if (!ConfigUtil.getConfigBooleanValue(ConfigType.INBOX_AUTOMATIC_TAGS, false)) {
            return null;
        }
        TagDao tagDao = new TagDao();
        List<TagDto> tags = tagDao.findByCriteria(new TagCriteria().setTargetIdList(null), new SortCriteria(1, true));

        Map<String, String> tagsNameToId = new HashMap<>();
        for (TagDto tagDto : tags) {
            tagsNameToId.put(tagDto.getName(), tagDto.getId());
        }
        return tagsNameToId;
    }

    public Date getLastSyncDate() {
        return lastSyncDate;
    }

    public int getLastSyncMessageCount() {
        return lastSyncMessageCount;
    }

    public String getLastSyncError() {
        return lastSyncError;
    }

    /**
     * Install a per-message import fault injector (tests only).
     */
    public void setImportFaultInjectorForTest(InboxFaultInjector importFaultInjector) {
        this.importFaultInjector = importFaultInjector;
    }

    /**
     * Install a post-commit ack fault injector (tests only).
     */
    public void setAckFaultInjectorForTest(InboxFaultInjector ackFaultInjector) {
        this.ackFaultInjector = ackFaultInjector;
    }

    /**
     * Install a post-commit in-doubt fault injector (tests only), modelling an ambiguous commit.
     */
    public void setCommitInDoubtFaultInjectorForTest(InboxFaultInjector commitInDoubtFaultInjector) {
        this.commitInDoubtFaultInjector = commitInDoubtFaultInjector;
    }

    /**
     * Detects whether an exception was caused by a DB integrity/constraint violation, dialect-agnostically
     * (SQLState class "23" on both H2 and PostgreSQL, or a Hibernate ConstraintViolationException).
     */
    private static boolean isConstraintViolation(Throwable t) {
        for (Throwable cause = t; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException) {
                String state = sqlException.getSQLState();
                if (state != null && state.startsWith("23")) {
                    return true;
                }
            }
            if (cause instanceof org.hibernate.exception.ConstraintViolationException) {
                return true;
            }
            if (cause == cause.getCause()) {
                break;
            }
        }
        return false;
    }

    /**
     * Fault-injection seam for tests (dependency boundary), never wired in production.
     */
    public interface InboxFaultInjector {
        void inject(long uid);
    }

    /**
     * Detached snapshot of everything the sync needs from configuration, read once per cycle.
     */
    private static final class InboxConfig {
        private boolean enabled;
        private String hostname;
        private String port;
        private boolean starttls;
        private String username;
        private String password;
        private String folder;
        private boolean deleteImported;
        private String defaultLanguage;
        private String inboxTagId;
        private Map<String, String> tagMap;
        private String storedUidValidityRaw;

        private String accountRaw() {
            return hostname + ":" + port + ":" + PROTOCOL + ":" + username;
        }

        private String identityDigest() {
            return ImapSourceIdentity.digest(hostname, port, PROTOCOL, username, folder);
        }
    }

    /**
     * A detached, immutable-enough materialized message: its identity and its parsed content (with
     * attachment temp paths). Never holds a lazy IMAP {@link Message}.
     */
    private static final class WorkItem {
        private long uid;
        private long uidValidity;
        private String identityDigest;
        private String account;
        private String folder;
        private InternetAddress sender;
        private EmailUtil.MailContent mailContent;
    }
}
