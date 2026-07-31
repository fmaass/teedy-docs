package com.sismics.docs.core.util.indexing;

import com.google.common.eventbus.AllowConcurrentEvents;
import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import com.sismics.BaseTest;
import com.sismics.docs.core.constant.ConfigType;
import com.sismics.docs.core.dao.DocumentDao;
import com.sismics.docs.core.dao.UserDao;
import com.sismics.docs.core.dao.criteria.DocumentCriteria;
import com.sismics.docs.core.dao.dto.DocumentDto;
import com.sismics.docs.core.event.RebuildIndexAsyncEvent;
import com.sismics.docs.core.model.context.AppContext;
import com.sismics.docs.core.model.jpa.Document;
import com.sismics.docs.core.model.jpa.File;
import com.sismics.docs.core.model.jpa.User;
import com.sismics.docs.core.util.DirectoryUtil;
import com.sismics.docs.core.util.jpa.PaginatedList;
import com.sismics.docs.core.util.jpa.PaginatedLists;
import com.sismics.docs.core.util.jpa.SortCriteria;
import com.sismics.util.context.ThreadLocalContext;
import com.sismics.util.jpa.EMF;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityTransaction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Absent-index boot reconciliation (#208): an index that opens cleanly but is EMPTY while the database holds
 * documents is rebuilt automatically on the next boot, instead of leaving search silently empty until an
 * administrator runs {@code POST /app/batch/reindex}.
 *
 * <p>Every scenario drives the REAL entry point — {@link LuceneIndexingHandler#startUp()}, reached through a
 * real {@link AppContext} boot inside a transactional context, exactly as production reaches it. The rebuild
 * is observed on the event bus the handler actually posts to; the singleton is reset by reflection (the
 * idiom of {@code TestAppContextReentrancy}) so each scenario boots a fresh context and tears it down.</p>
 *
 * <p><b>On the corrupt-index scenario's bus.</b> The production bus is ASYNCHRONOUS, so when the
 * reconciliation check runs the recovery rebuild it must not duplicate has not executed yet and the index it
 * observes is still empty. Under a unit test the same bus is synchronous, which would hide that window
 * entirely (the inline rebuild would populate the index before the check looked). That scenario therefore
 * substitutes a counting-only bus — a stand-in for the handler's event-bus DEPENDENCY, not for the handler
 * under test — which reproduces the production instant faithfully: the rebuild has been scheduled, and has
 * not run.</p>
 */
public class TestIndexBootReconciliation extends BaseTest {

    private Field instanceField;
    private Field constructingField;
    private Field duringStartUpField;
    private Field asyncEventBusField;

    @BeforeEach
    public void resolveAppContextSeams() throws Exception {
        instanceField = appContextField("instance");
        constructingField = appContextField("constructing");
        duringStartUpField = appContextField("duringStartUp");
        asyncEventBusField = appContextField("asyncEventBus");
        shutDownAppContext();
    }

    @AfterEach
    public void tearDownAppContext() throws Exception {
        duringStartUpField.set(null, null);
        shutDownAppContext();
    }

    /**
     * A boot that finds an empty index while the database holds documents schedules exactly ONE rebuild, and
     * the corpus is searchable once it has run.
     */
    @Test
    public void emptyIndexOverAPopulatedDatabaseSchedulesOneRebuildAndSearchFindsTheDocument() throws Exception {
        String term = uniqueTerm();
        seedDocument(term);
        useLuceneStorage("FILE");
        emptyLuceneDirectory();

        AtomicInteger rebuilds = countRebuildsOnTheRealBus();
        bootAppContext();

        Assertions.assertEquals(1, rebuilds.get(),
                "an empty index over a populated database must schedule exactly one automatic rebuild");
        List<DocumentDto> found = search(term);
        Assertions.assertEquals(1, found.size(),
                "the scheduled rebuild must make the seeded document searchable again");
    }

    /**
     * The other half of the contract: an index that still holds the corpus schedules NOTHING. Without it the
     * reconciliation would re-index the whole corpus on every single restart.
     *
     * <p>This is the DIRECT branch test: it calls the check a second time against the index the boot's own
     * rebuild just produced, so the "index is not empty" predicate is exercised on its own, in isolation from
     * anything a boot does. {@link #restartOverTheSameIndexDirectoryRebuildsNothingAndKeepsServing()} covers
     * the same contract end to end across a real restart — it became reachable only once #222 moved the
     * index health check ahead of the index writer, so both are kept: this one pins the predicate, that one
     * pins the restart.</p>
     */
    @Test
    public void reconciliationOverAnIndexThatHoldsTheCorpusSchedulesNothing() throws Exception {
        String term = uniqueTerm();
        seedDocument(term);
        useLuceneStorage("RAM");

        AtomicInteger rebuilds = countRebuildsOnTheRealBus();
        bootAppContext();
        Assertions.assertEquals(1, rebuilds.get(), "precondition: the boot reconciled its empty index");
        Assertions.assertEquals(1, search(term).size(), "precondition: the rebuild populated the index");

        LuceneIndexingHandler handler = (LuceneIndexingHandler) AppContext.getInstance().getIndexingHandler();
        inTransaction(() -> {
            handler.reconcileAbsentIndex();
            return null;
        });

        Assertions.assertEquals(1, rebuilds.get(),
                "an index that still holds the corpus must not schedule another rebuild");
    }

    /**
     * The whole restart contract, end to end: an instance boots, its index fills, it stops, and it boots
     * AGAIN over the same on-disk index directory. The second boot must be a no-op — the index passes its
     * health check, so there is no recovery, no reconciliation, no rebuild event of any kind — and the
     * carried-over index must keep serving search.
     *
     * <p>Proven by BOTH observable consequences of a recovery, because a recovery is exactly what used to
     * happen here (#222): it posts a rebuild event, and before doing so it DELETES the index directory. So
     * the assertions are "no event at all" and "the commit point written by the first boot is still BYTE FOR
     * BYTE the file on disk".</p>
     *
     * <p>Byte-identity, not mere existence: a recovery's rebuild replays the same commit sequence, so it
     * regenerates a commit point with the SAME {@code segments_N} name (verified — a filename-only check
     * false-passes here). Content cannot be faked, because Lucene stamps every segment and commit with a
     * freshly generated random id, so a rebuilt index never reproduces the original bytes.</p>
     */
    @Test
    public void restartOverTheSameIndexDirectoryRebuildsNothingAndKeepsServing() throws Exception {
        String term = uniqueTerm();
        seedDocument(term);
        useLuceneStorage("FILE");
        emptyLuceneDirectory();

        // First boot: the empty directory is reconciled and the rebuild commits a real index.
        bootAppContext();
        Assertions.assertEquals(1, search(term).size(), "precondition: the first boot populated the index");
        shutDownAppContext();

        Path commitPoint = onlyCommitPoint();
        byte[] commitBytes = Files.readAllBytes(commitPoint);
        long commitSize = commitBytes.length;
        FileTime commitModified = Files.getLastModifiedTime(commitPoint);

        AtomicInteger rebuilds = countRebuildsOnTheRealBus();
        bootAppContext();

        Assertions.assertEquals(0, rebuilds.get(),
                "a restart over a healthy, populated index must schedule no rebuild — neither the"
                        + " corrupt-index recovery's nor the absent-index reconciliation's");
        Assertions.assertTrue(Files.exists(commitPoint),
                "the first boot's commit point must survive the restart: " + commitPoint.getFileName()
                        + " (a recovery would have deleted the index directory)");
        Assertions.assertEquals(commitSize, Files.size(commitPoint),
                "the first boot's commit point must not have been rewritten (size changed): "
                        + commitPoint.getFileName());
        Assertions.assertArrayEquals(commitBytes, Files.readAllBytes(commitPoint),
                () -> "the first boot's commit point must survive the restart BYTE FOR BYTE: "
                        + commitPoint.getFileName() + " — same name with different content means the index was"
                        + " deleted and rebuilt, since Lucene stamps each commit with a fresh random id"
                        + " (first boot wrote it at " + commitModified + ", now "
                        + lastModifiedQuietly(commitPoint) + ")");
        Assertions.assertEquals(1, search(term).size(),
                "and the carried-over index must still serve search after the restart");
    }

    /**
     * A fresh install (no documents) must not schedule a rebuild of an empty corpus.
     */
    @Test
    public void emptyIndexOverAnEmptyDatabaseSchedulesNothing() throws Exception {
        useLuceneStorage("FILE");
        emptyLuceneDirectory();
        List<String> hidden = softDeleteAllDocuments();
        try {
            Assertions.assertEquals(0L, documentCount(), "precondition: the database holds no active document");

            AtomicInteger rebuilds = countRebuildsOnTheRealBus();
            bootAppContext();

            Assertions.assertEquals(0, rebuilds.get(),
                    "a boot with nothing to index must not schedule a rebuild");
        } finally {
            restoreDocuments(hidden);
        }
    }

    /**
     * The single-flight guarantee: a corrupt index makes the recovery path delete the index, re-create it and
     * schedule its own rebuild. The reconciliation must then stand down — the index it would look at is
     * empty by construction, so an ungated check would schedule the very same full rebuild a second time.
     */
    @Test
    public void corruptIndexBootSchedulesTheRecoveryRebuildExactlyOnce() throws Exception {
        String term = uniqueTerm();
        seedDocument(term);
        useLuceneStorage("FILE");
        emptyLuceneDirectory();

        // Produce a real committed on-disk index, then break its commit point.
        bootAppContext();
        Assertions.assertEquals(1, search(term).size(), "precondition: the first boot wrote a real index");
        shutDownAppContext();
        corruptIndexCommitPoint();

        AtomicInteger rebuilds = countRebuildsOnACountingOnlyBus();
        bootAppContext();

        Assertions.assertEquals(1, rebuilds.get(),
                "the corrupt-index recovery already scheduled a rebuild: the reconciliation must not add a second");
    }

    /**
     * Containment: a reconciliation failure must not propagate out of {@code startUp()}. The check sits
     * OUTSIDE the corrupt-index recovery's try, so an escaping failure could never be misread as corruption
     * and delete a healthy index — but it would still abort the indexing handler's start. The event bus is
     * the reconciliation's outermost dependency; failing it fails the same guarded region as a failing count
     * or index read.
     */
    @Test
    public void aFailingReconciliationIsContainedAndStartUpCompletes() throws Exception {
        seedDocument(uniqueTerm());
        // RAM storage: the context's own handler and the handler under test then hold private in-memory
        // indexes instead of contending for the write lock of the single on-disk index directory.
        useLuceneStorage("RAM");
        bootAppContext();

        AtomicInteger attempts = new AtomicInteger();
        AppContext appContext = AppContext.peekInstance();
        EventBus realBus = (EventBus) asyncEventBusField.get(appContext);
        asyncEventBusField.set(appContext, new EventBus() {
            @Override
            public void post(Object event) {
                attempts.incrementAndGet();
                throw new IllegalStateException("the event bus is unavailable");
            }
        });

        LuceneIndexingHandler handler = new LuceneIndexingHandler();
        try {
            inTransaction(() -> {
                handler.startUp();
                return null;
            });

            Assertions.assertEquals(1, attempts.get(),
                    "the reconciliation must have reached the bus — and failed there");

            // The handler is fully started despite the failed reconciliation.
            File file = new File();
            file.setId("containment-" + UUID.randomUUID());
            file.setName("containment.txt");
            file.setMimeType("text/plain");
            Assertions.assertTrue(handler.updateFile(file), "the index writer is alive after the failure");
            Assertions.assertEquals(1, handler.countIndexedDocuments(file.getId()),
                    "and the index it writes to is usable");
        } finally {
            asyncEventBusField.set(appContext, realBus);
            handler.shutDown();
        }
    }

    // --- Boot / event-bus seams -------------------------------------------------------------------------

    private static Field appContextField(String name) throws Exception {
        Field field = AppContext.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    /**
     * Counts rebuild events on the context's REAL bus: the registered {@code RebuildIndexAsyncListener} runs
     * too, so the rebuild actually happens (synchronously, under a unit test).
     */
    private AtomicInteger countRebuildsOnTheRealBus() throws Exception {
        AtomicInteger count = new AtomicInteger();
        // startUp() runs this hook after the event bus exists and BEFORE the indexing handler starts, which
        // is the only point at which a subscriber can be in place for a boot-time post.
        duringStartUpField.set(null, (Runnable) () -> AppContext.getInstance().getAsyncEventBus()
                .register(new RebuildCounter(count)));
        return count;
    }

    /**
     * Counts rebuild events on a bus carrying ONLY the counter, so a scheduled rebuild is recorded but does
     * not run — the state production is in at the moment the reconciliation check executes (see the class
     * javadoc).
     */
    private AtomicInteger countRebuildsOnACountingOnlyBus() throws Exception {
        AtomicInteger count = new AtomicInteger();
        duringStartUpField.set(null, (Runnable) () -> {
            EventBus countingBus = new EventBus();
            countingBus.register(new RebuildCounter(count));
            try {
                asyncEventBusField.set(AppContext.getInstance(), countingBus);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        });
        return count;
    }

    public static class RebuildCounter {
        private final AtomicInteger count;

        RebuildCounter(AtomicInteger count) {
            this.count = count;
        }

        @Subscribe
        @AllowConcurrentEvents
        public void on(RebuildIndexAsyncEvent event) {
            count.incrementAndGet();
        }
    }

    /**
     * Boots the application context the way production does: on a thread that already carries a transactional
     * context (the request/startup transaction), so the startup path can read the database.
     */
    private void bootAppContext() throws Exception {
        inTransaction(() -> {
            AppContext.getInstance();
            return null;
        });
    }

    private void shutDownAppContext() throws Exception {
        AppContext appContext = AppContext.peekInstance();
        if (appContext != null) {
            appContext.shutDown();
        }
        instanceField.set(null, null);
        constructingField.set(null, null);
    }

    // --- Index directory --------------------------------------------------------------------------------

    private void emptyLuceneDirectory() throws Exception {
        Path luceneDirectory = DirectoryUtil.getLuceneDirectory();
        try (Stream<Path> walk = Files.walk(luceneDirectory)) {
            walk.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(java.io.File::delete);
        }
        Files.createDirectories(luceneDirectory);
    }

    /**
     * The index's single commit point (a {@code segments_N} file), asserting there is exactly one so the
     * caller's identity check cannot be fooled by a leftover.
     */
    private Path onlyCommitPoint() throws Exception {
        List<Path> commitPoints = commitPoints();
        Assertions.assertEquals(1, commitPoints.size(),
                "expected exactly one commit point in the index directory, found " + commitPoints);
        return commitPoints.get(0);
    }

    /**
     * Last-modified time for a diagnostic message, or the reason it could not be read — never throws, so it
     * is safe inside an assertion's failure-message supplier.
     */
    private String lastModifiedQuietly(Path path) {
        try {
            return Files.getLastModifiedTime(path).toString();
        } catch (Exception e) {
            return "unreadable (" + e + ")";
        }
    }

    private List<Path> commitPoints() throws Exception {
        Path luceneDirectory = DirectoryUtil.getLuceneDirectory();
        try (Stream<Path> files = Files.list(luceneDirectory)) {
            return files.filter(path -> path.getFileName().toString().startsWith("segments"))
                    .collect(Collectors.toList());
        }
    }

    /**
     * Overwrites the index's commit point, so opening the index writer over it fails the way a truncated or
     * partially written index does — the failure {@code startUp()} classifies as corruption.
     */
    private void corruptIndexCommitPoint() throws Exception {
        List<Path> commitPoints = commitPoints();
        Assertions.assertFalse(commitPoints.isEmpty(),
                "precondition: a committed index must exist before it can be corrupted");
        for (Path commitPoint : commitPoints) {
            Files.write(commitPoint, "this is not a Lucene commit point".getBytes(StandardCharsets.UTF_8));
        }
    }

    // --- Database ---------------------------------------------------------------------------------------

    private interface TransactionalWork<T> {
        T run() throws Exception;
    }

    private static <T> T inTransaction(TransactionalWork<T> work) throws Exception {
        EntityManager em = EMF.get().createEntityManager();
        ThreadLocalContext.get().setEntityManager(em);
        EntityTransaction tx = em.getTransaction();
        tx.begin();
        try {
            T result = work.run();
            tx.commit();
            return result;
        } catch (Exception e) {
            if (tx.isActive()) {
                tx.rollback();
            }
            throw e;
        } finally {
            if (em.isOpen()) {
                em.close();
            }
            ThreadLocalContext.cleanup();
        }
    }

    private static String uniqueTerm() {
        return "reconcile" + UUID.randomUUID().toString().replace("-", "");
    }

    private void seedDocument(String titleTerm) throws Exception {
        inTransaction(() -> {
            String username = "recon_" + UUID.randomUUID().toString().substring(0, 12);
            User user = new User();
            user.setUsername(username);
            user.setPassword("12345678");
            user.setEmail(username + "@docs.com");
            user.setRoleId("admin");
            user.setStorageQuota(100_000L);
            String userId = new UserDao().create(user, username);

            Document document = new Document();
            document.setUserId(userId);
            document.setLanguage("eng");
            document.setTitle(titleTerm);
            document.setCreateDate(new Date());
            return new DocumentDao().create(document, userId);
        });
    }

    private long documentCount() throws Exception {
        return inTransaction(() -> new DocumentDao().getDocumentCount());
    }

    /**
     * Hides every active document (the test database is shared by the scenarios in this class), returning the
     * ids to restore.
     */
    @SuppressWarnings("unchecked")
    private List<String> softDeleteAllDocuments() throws Exception {
        return inTransaction(() -> {
            EntityManager em = ThreadLocalContext.get().getEntityManager();
            List<String> ids = em.createNativeQuery(
                            "select DOC_ID_C from T_DOCUMENT where DOC_DELETEDATE_D is null")
                    .getResultList();
            if (!ids.isEmpty()) {
                em.createNativeQuery("update T_DOCUMENT set DOC_DELETEDATE_D = CURRENT_TIMESTAMP where DOC_ID_C in :ids")
                        .setParameter("ids", ids)
                        .executeUpdate();
            }
            return ids;
        });
    }

    private void restoreDocuments(List<String> ids) throws Exception {
        if (ids.isEmpty()) {
            return;
        }
        inTransaction(() -> ThreadLocalContext.get().getEntityManager()
                .createNativeQuery("update T_DOCUMENT set DOC_DELETEDATE_D = null where DOC_ID_C in :ids")
                .setParameter("ids", ids)
                .executeUpdate());
    }

    private void useLuceneStorage(String storage) throws Exception {
        inTransaction(() -> ThreadLocalContext.get().getEntityManager()
                .createNativeQuery("update T_CONFIG set CFG_VALUE_C = :value where CFG_ID_C = :id")
                .setParameter("value", storage)
                .setParameter("id", ConfigType.LUCENE_DIRECTORY_STORAGE.name())
                .executeUpdate());
    }

    /**
     * The real fulltext search path, through the booted context's indexing handler.
     */
    private List<DocumentDto> search(String term) throws Exception {
        return inTransaction(() -> {
            PaginatedList<DocumentDto> paginatedList = PaginatedLists.create(10, 0);
            List<String> suggestionList = new ArrayList<>();
            DocumentCriteria criteria = new DocumentCriteria();
            // "admin" skips the ACL join (SecurityUtil.skipAclCheck), so the assertion is about the index.
            criteria.setTargetIdList(List.of("admin"));
            criteria.setFullSearch(term);
            AppContext.getInstance().getIndexingHandler()
                    .findByCriteria(paginatedList, suggestionList, criteria, new SortCriteria(3, false));
            return paginatedList.getResultList();
        });
    }
}
