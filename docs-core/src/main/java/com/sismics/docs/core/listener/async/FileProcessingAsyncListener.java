package com.sismics.docs.core.listener.async;

import com.google.common.eventbus.AllowConcurrentEvents;
import com.google.common.eventbus.Subscribe;
import com.sismics.docs.core.dao.DocumentDao;
import com.sismics.docs.core.dao.FileDao;
import com.sismics.docs.core.dao.TagMatchRuleDao;
import com.sismics.docs.core.dao.UserDao;
import com.sismics.docs.core.event.FileCreatedAsyncEvent;
import com.sismics.docs.core.event.FileEvent;
import com.sismics.docs.core.event.FileUpdatedAsyncEvent;
import com.sismics.docs.core.model.context.AppContext;
import com.sismics.docs.core.model.jpa.Document;
import com.sismics.docs.core.model.jpa.DocumentTag;
import com.sismics.docs.core.model.jpa.File;
import com.sismics.docs.core.model.jpa.TagMatchRule;
import com.sismics.docs.core.model.jpa.User;
import com.sismics.docs.core.util.FileUtil;
import com.sismics.docs.core.util.RasterGenerationUtil;
import com.sismics.docs.core.util.RegexRulePolicy;
import com.sismics.docs.core.util.TransactionUtil;
import com.sismics.docs.core.util.format.FormatHandler;
import com.sismics.docs.core.util.format.FormatHandlerUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.MessageFormat;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Listener on file processing.
 * 
 * @author bgamard
 */
public class FileProcessingAsyncListener {
    /**
     * Logger.
     */
    private static final Logger log = LoggerFactory.getLogger(FileProcessingAsyncListener.class);

    /**
     * File created.
     *
     * @param event File created event
     */
    @Subscribe
    @AllowConcurrentEvents
    public void on(final FileCreatedAsyncEvent event) {
        processEvent(event, true);
    }

    /**
     * File updated.
     *
     * @param event File updated event
     */
    @Subscribe
    @AllowConcurrentEvents
    public void on(final FileUpdatedAsyncEvent event) {
        processEvent(event, false);
    }

    /**
     * Process a file event, guaranteeing the in-memory processing marker is ALWAYS released and the
     * plaintext temp file deleted, whatever the outcome — including an {@link Error}, and including a
     * failure in the subscriber-entry logging (which is why the logging lives inside the protected body,
     * not before it). Without this guarantee a processing throw would strand the file's processing marker
     * until the JVM restarts, blocking any later rotation or reprocessing of that file.
     *
     * <p>The two releases are NESTED so a temp-delete failure cannot skip the marker release. On the
     * durable-commit path this listener is the sole owner of the release; on a producer rollback the
     * event is discarded and never delivered here, and the producer's registered rollback compensation
     * ({@link FileUtil#markProcessingWithRollbackCleanup}) owns the release instead — the two paths are
     * mutually exclusive, so the marker is released exactly once.</p>
     *
     * <p><b>Known limitation:</b> on the commit path the release depends on this listener actually
     * running. The async event bus is backed by a {@code ThreadPoolExecutor} with a
     * {@code CallerRunsPolicy}, so a full queue applies backpressure rather than rejecting; a transient
     * rejection under a running JVM does not occur (rejection only happens once the executor is shut
     * down at JVM stop). If delivery is nonetheless lost at shutdown, the in-memory marker lingers — this
     * is acceptable because it is process-local and cleared on the next JVM start.</p>
     *
     * @param event File event
     * @param isFileCreated True if the file was just created
     */
    private void processEvent(FileEvent event, boolean isFileCreated) {
        try {
            if (log.isInfoEnabled()) {
                log.info((isFileCreated ? "File created event: " : "File updated event: ") + event.toString());
            }
            processFile(event, isFileCreated);
        } finally {
            // Deterministically delete the plaintext temp file this event carried, then release the
            // processing marker. All producers (upload, attach, manual re-process, EML import) hand
            // ownership of the decrypted/unencrypted file to this event; this is the single point where
            // its lifetime ends on the commit path. The releases are nested so a temp-delete failure
            // cannot skip the marker release. The PhantomReference sweep in FileService remains only as a
            // backstop.
            try {
                deleteUnencryptedFile(event);
            } finally {
                FileUtil.endProcessingFile(event.getFileId());
            }
        }
    }

    /**
     * Process a file :
     * Generate thumbnails
     * Extract and save text content
     *
     * @param event File event
     * @param isFileCreated True if the file was just created
     */
    protected void processFile(FileEvent event, boolean isFileCreated) {
        AtomicReference<File> file = new AtomicReference<>();
        AtomicReference<User> user = new AtomicReference<>();

        // Open a first transaction to get what we need to start the processing
        TransactionUtil.handle(() -> {
            // Generate thumbnail, extract content
            file.set(new FileDao().getActiveById(event.getFileId()));
            if (file.get() == null) {
                // The file has been deleted since
                return;
            }

            // Get the creating user from the database for its private key
            UserDao userDao = new UserDao();
            user.set(userDao.getById(file.get().getUserId()));
        });

        // Process the file outside of a transaction
        if (user.get() == null || file.get() == null) {
            // The user or file has been deleted; the marker release + temp cleanup run in processEvent.
            return;
        }
        String content = extractContent(event, user.get(), file.get());

        // Open a new transaction to save the file content
        AtomicReference<String> documentId = new AtomicReference<>();
        AtomicReference<String> fileName = new AtomicReference<>();
        TransactionUtil.handle(() -> {
            FileDao fileDao = new FileDao();
            File freshFile = fileDao.getActiveById(event.getFileId());
            if (freshFile == null) {
                return;
            }

            freshFile.setContent(content);
            fileDao.update(freshFile);

            if (isFileCreated) {
                AppContext.getInstance().getIndexingHandler().createFile(freshFile);
            } else {
                AppContext.getInstance().getIndexingHandler().updateFile(freshFile);
            }

            documentId.set(freshFile.getDocumentId());
            fileName.set(freshFile.getName());
        });

        // Apply auto-tagging rules
        if (documentId.get() != null) {
            applyAutoTagRules(documentId.get(), fileName.get(), content);
        }
    }

    /**
     * Delete the plaintext temporary file carried by a file event, if any. Routes through the shared
     * guarded delete so the stored-file alias (a null-private-key decrypt, in unit-test mode) is never
     * deleted — the same guard the producers' rollback compensation uses.
     *
     * @param event File event
     */
    void deleteUnencryptedFile(FileEvent event) {
        FileUtil.deleteTempGuarded(event.getFileId(), event.getUnencryptedFile());
    }

    /**
     * Read a file's current rotation FRESH from the database, in its own short transaction. Called
     * from inside the raster helper's per-file lock so the value cannot be stale relative to a
     * concurrent rotate. A file deleted since (no row) contributes 0 (upright).
     *
     * @param fileId File ID
     * @return The stored rotation ({0,90,180,270}), or 0 if the file no longer exists
     */
    private int readFreshRotation(String fileId) {
        AtomicReference<Integer> rotation = new AtomicReference<>(0);
        TransactionUtil.handle(() -> {
            File fresh = new FileDao().getActiveById(fileId);
            if (fresh != null) {
                rotation.set(fresh.getRotation());
            }
        });
        return rotation.get();
    }

    /**
     * Extract text content from a file.
     * This is executed outside of a transaction.
     *
     * @param event File event
     * @param user User whom created the file
     * @param file Fresh file
     * @return Text content
     */
    private String extractContent(FileEvent event, User user, File file) {
        // Find a format handler
        FormatHandler formatHandler = FormatHandlerUtil.find(file.getMimeType());
        if (formatHandler == null) {
            log.info("Format unhandled: " + file.getMimeType());
            return null;
        }

        // Generate file variations through the single shared, rotation-aware raster helper so a
        // reprocess or re-upload always bakes the file's currently stored rotation (R11) rather than
        // reverting it. The rotation is read FRESH from the DB INSIDE the helper's per-file lock (not
        // from the file loaded in the opening transaction): a reprocess that races a rotate must not
        // capture a stale 0 and install upright rasters while the DB says 90. This writer does not
        // update the rotation itself (content is saved in a separate transaction), so its persist step
        // is a no-op.
        try {
            RasterGenerationUtil.regenerateRasters(file.getId(), event.getUnencryptedFile(),
                    file.getMimeType(), () -> readFreshRotation(file.getId()), user.getPrivateKey(),
                    () -> { });
        } catch (Throwable e) {
            log.error("Unable to generate thumbnails for: " + file, e);
        }

        // Extract text content from the file
        long startTime = System.currentTimeMillis();
        String content = null;
        log.info("Start extracting content from: " + file);
        try {
            content = formatHandler.extractContent(event.getLanguage(), event.getUnencryptedFile());
        } catch (Throwable e) {
            log.error("Error extracting content from: " + file, e);
        }
        log.info(MessageFormat.format("File content extracted in {0}ms: " + file.getId(), System.currentTimeMillis() - startTime));

        return content;
    }

    /**
     * Evaluate all enabled tag match rules against a document's file and apply
     * matching tags.
     *
     * @param docId Document ID
     * @param fileName File name (may be null)
     * @param content Extracted text content (may be null)
     */
    private void applyAutoTagRules(String docId, String fileName, String content) {
        TransactionUtil.handle(() -> {
            List<TagMatchRule> rules;
            try {
                rules = new TagMatchRuleDao().findAllEnabled();
            } catch (Exception e) {
                log.debug("Unable to load tag match rules (table may not exist yet)", e);
                return;
            }
            if (rules.isEmpty()) {
                return;
            }

            DocumentDao documentDao = new DocumentDao();
            Document doc = documentDao.getById(docId);
            if (doc == null) {
                return;
            }
            String docTitle = doc.getTitle();

            Set<String> newTagIds = new LinkedHashSet<>();
            for (TagMatchRule rule : rules) {
                String target = switch (rule.getRuleType()) {
                    case "TITLE_REGEX" -> docTitle;
                    case "FILENAME_REGEX" -> fileName;
                    case "CONTENT_REGEX" -> content;
                    default -> null;
                };
                if (target == null) {
                    continue;
                }
                if (ruleMatches(rule, target)) {
                    newTagIds.add(rule.getTagId());
                }
            }

            if (!newTagIds.isEmpty()) {
                // Get existing tag links to avoid duplicates
                jakarta.persistence.EntityManager em = com.sismics.util.context.ThreadLocalContext.get().getEntityManager();
                jakarta.persistence.Query existingQuery = em.createQuery(
                        "select dt.tagId from DocumentTag dt where dt.documentId = :documentId and dt.deleteDate is null");
                existingQuery.setParameter("documentId", docId);
                @SuppressWarnings("unchecked")
                List<String> existingTagIds = existingQuery.getResultList();

                int added = 0;
                for (String tagId : newTagIds) {
                    if (!existingTagIds.contains(tagId)) {
                        DocumentTag dt = new DocumentTag();
                        dt.setId(UUID.randomUUID().toString());
                        dt.setDocumentId(docId);
                        dt.setTagId(tagId);
                        em.persist(dt);
                        added++;
                    }
                }
                if (added > 0) {
                    log.info("Auto-tagging applied {} new tag(s) to document {}", added, docId);
                }
            }
        });
    }

    /**
     * Evaluate one persisted rule against a target text under {@link RegexRulePolicy}.
     *
     * <p>FAIL SAFE by construction: an invalid persisted pattern (a legacy or directly-inserted
     * DB row that never went through REST validation) or an evaluation that exceeds the policy's
     * wall-clock deadline skips the rule with a bounded warning — a pathological rule cannot
     * stall file processing, and sibling rules are unaffected.
     *
     * <p>Package-private and static so rule evaluation can be exercised directly against
     * DAO-level rule objects in unit tests.
     *
     * @param rule Persisted tag match rule
     * @param target Target text
     * @return True when the rule pattern occurs in the target
     */
    static boolean ruleMatches(TagMatchRule rule, String target) {
        if (quarantinedRules.get(quarantineKey(rule.getId(), rule.getPattern())) != null) {
            return false;
        }
        try {
            return RegexRulePolicy.find(rule.getPattern(), target);
        } catch (Exception e) {
            if (shouldWarnSkip(rule.getId(), rule.getPattern())) {
                log.warn("Tag match rule {} skipped (pattern invalid or evaluation aborted): {}",
                        rule.getId(), rule.getPattern());
            }
            return false;
        }
    }

    /**
     * QUARANTINE for rule id + pattern combinations whose evaluation failed (invalid pattern,
     * deadline timeout, matcher stack exhaustion). Rule evaluation runs once per rule per
     * PROCESSED FILE, so without the quarantine every upload re-pays the full evaluation cost of
     * a bad rule (up to the 2 s deadline each) and re-logs the warning — any uploader can amplify
     * one bad persisted rule into unbounded work and log growth. Entries key on id + pattern, so
     * editing the rule's pattern releases it. Bounded LRU so a rotating pattern population cannot
     * grow it without limit; access-ordered so live entries survive.
     */
    private static final int QUARANTINE_CACHE_CAPACITY = 1024;
    private static final Map<String, Boolean> quarantinedRules =
            Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > QUARANTINE_CACHE_CAPACITY;
                }
            });

    private static String quarantineKey(String ruleId, String pattern) {
        return ruleId + ' ' + pattern;
    }

    /**
     * Quarantine a failed rule and decide whether its skip warning should be emitted: once per
     * rule id + pattern, re-warning (and re-evaluating) only when the rule's pattern changes.
     *
     * @param ruleId Rule id
     * @param pattern Rule pattern
     * @return True when the warning should be logged
     */
    static boolean shouldWarnSkip(String ruleId, String pattern) {
        return quarantinedRules.put(quarantineKey(ruleId, pattern), Boolean.TRUE) == null;
    }
}
