package com.sismics.docs.core.util.indexing;

import com.sismics.docs.BaseTransactionalTest;
import com.sismics.docs.core.constant.ConfigType;
import com.sismics.docs.core.dao.DocumentDao;
import com.sismics.docs.core.dao.criteria.DocumentCriteria;
import com.sismics.docs.core.dao.dto.DocumentDto;
import com.sismics.docs.core.model.jpa.Document;
import com.sismics.docs.core.model.jpa.File;
import com.sismics.docs.core.model.jpa.User;
import com.sismics.docs.core.util.jpa.PaginatedList;
import com.sismics.docs.core.util.jpa.PaginatedLists;
import com.sismics.docs.core.util.jpa.SortCriteria;
import com.sismics.util.context.ThreadLocalContext;
import org.apache.lucene.document.DocumentStoredFieldVisitor;
import org.apache.lucene.index.FieldInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The two passes of a fulltext search (#290).
 *
 * <p>A search collects EVERY Lucene hit, because the complete document id set is what the SQL layer
 * needs: the ACL filter and the pagination live there, so an id dropped in Lucene is a row the user
 * would never see. Highlighting is the opposite kind of work — it deserializes a file's whole stored
 * OCR text and runs the highlighter over it, and only the rows of the page actually returned can
 * ever show one. Doing it inside the all-hits loop therefore paid a full document deserialization
 * per hit for a twenty-row page, which is what made a typo'd search on a real corpus cost seconds.
 *
 * <p>These tests pin both halves of the split: the id set stays complete, the expensive stored field
 * is read once per page row, and the highlight a page row gets is the one from its best-matching
 * FILE rather than whatever the score-ordered loop happened to write last.
 */
public class TestLuceneSearchPasses extends BaseTransactionalTest {

    /** Number of times a search pass deserialized a stored {@code content} field. */
    private final AtomicInteger contentDeliveries = new AtomicInteger();

    private LuceneIndexingHandler handler;

    /**
     * Deliberately NOT named setUp: {@link BaseTransactionalTest#setUp()} opens the entity manager
     * and transaction, and overriding it would silently disable that.
     */
    @BeforeEach
    public void startRamIndexingHandler() throws Exception {
        // A private RAM index, so this test neither touches nor contends for the file-backed index a
        // booted AppContext would use (the idiom of TestFileIndexUpsert).
        ThreadLocalContext.get().getEntityManager()
                .createNativeQuery("update T_CONFIG set CFG_VALUE_C = 'RAM' where CFG_ID_C = :id")
                .setParameter("id", ConfigType.LUCENE_DIRECTORY_STORAGE.name())
                .executeUpdate();
        handler = new LuceneIndexingHandler();
        handler.startUp();
        handler.storedFieldVisitorFactory = fields -> new DocumentStoredFieldVisitor(fields) {
            @Override
            public void stringField(FieldInfo fieldInfo, String value) throws IOException {
                // Count only the DELIVERY of the value, never needsField: Lucene has to ask
                // needsField about a rejected field too, so a needsField counter would report work
                // that never happened.
                if ("content".equals(fieldInfo.name)) {
                    contentDeliveries.incrementAndGet();
                }
                super.stringField(fieldInfo, value);
            }
        };
    }

    @AfterEach
    public void shutDownIndexingHandler() {
        if (handler != null) {
            handler.shutDown();
        }
    }

    /**
     * (i) The id set stays complete while the stored OCR text is deserialized once per PAGE ROW.
     *
     * <p>Twelve documents match, the page holds ten. The result COUNT must still be twelve — that is
     * the number the user sees and pages through, and it comes from the SQL layer over the complete
     * Lucene id set. The stored {@code content} of a file, on the other hand, may only be read for
     * the rows of the returned page.
     */
    @Test
    public void everyHitIsCountedWhileOnlyThePageDeserializesItsContent() throws Exception {
        String term = uniqueTerm("kennwort");
        int total = 12;
        for (int i = 0; i < total; i++) {
            // The term lives ONLY in the file content, so every hit is a file hit.
            indexFile(createDocument("Ablage " + i), "Zeile " + i + " " + term + " Ende der Seite");
        }
        flush();

        contentDeliveries.set(0);
        PaginatedList<DocumentDto> paginatedList = search(term, 10);

        Assertions.assertEquals(total, paginatedList.getResultCount(),
                "the complete id set must reach SQL: all " + total + " matching documents are counted");
        Assertions.assertEquals(10, paginatedList.getResultList().size(), "the page holds ten rows");
        Assertions.assertEquals(paginatedList.getResultList().size(), contentDeliveries.get(),
                "in the common case - one file per document - the stored OCR content is deserialized"
                        + " exactly once per PAGE ROW, not once per hit");
        Assertions.assertTrue(
                contentDeliveries.get()
                        <= paginatedList.getResultList().size() * LuceneIndexingHandler.HIGHLIGHT_CANDIDATE_FILES,
                "and the worst case stays bounded by page rows x the candidate cap: " + contentDeliveries.get());
        for (DocumentDto documentDto : paginatedList.getResultList()) {
            Assertions.assertNotNull(documentDto.getHighlight(),
                    "and every page row must still carry its highlight");
            Assertions.assertTrue(documentDto.getHighlight().contains("<strong>" + term + "</strong>"),
                    "the highlight must mark the matched term: " + documentDto.getHighlight());
        }
    }

    /**
     * (ii) The highlight comes from the best-matching FILE of the document.
     *
     * <p>The fixture is the one that fails without the split: a document with two files where only
     * the SECOND file's content matches, AND the parent document itself carries a lower-scoring
     * fuzzy match on its title. The all-hits loop walks the hits in score order and writes into one
     * map keyed by document id, so it highlighted the matching file first and then OVERWROTE that
     * fragment with the document hit's {@code null}. The page-bounded pass asks a different
     * question — the top-scoring FILE of this document under the same query — and cannot be
     * overwritten by the document's own metadata hit.
     */
    @Test
    public void theHighlightComesFromTheMatchingFileNotTheDocumentMetadataHit() throws Exception {
        String term = uniqueTerm("zielbegriff");
        // One edit from the query term, and outside the first two characters, so the document's own
        // title matches through the (low-scoring, constant-scored) fuzzy arm.
        String fuzzyTitleTerm = term.substring(0, term.length() - 1) + "x";

        String documentId = createDocument(fuzzyTitleTerm);
        indexFile(documentId, "Die erste Datei nennt den gesuchten Ausdruck nicht.");
        indexFile(documentId, "Die zweite Datei nennt " + term + " mitten im Text.");
        flush();

        PaginatedList<DocumentDto> paginatedList = search(term, 10);

        Assertions.assertEquals(1, paginatedList.getResultList().size(), "the document must be found once");
        String highlight = paginatedList.getResultList().get(0).getHighlight();
        Assertions.assertNotNull(highlight,
                "the matching file's fragment must survive the document's own metadata hit");
        Assertions.assertTrue(highlight.contains("<strong>" + term + "</strong>"),
                "the highlight must be the fragment from the file that actually matches: " + highlight);
    }

    /**
     * (iii) A commit BETWEEN the two passes.
     *
     * <p>The passes are deliberately not joined by a shared reader: pass 1 ends before the SQL phase
     * (which can take as long as it takes) and pass 2 acquires the reader again afterwards, carrying
     * only the query and the application document ids across. Nothing that belongs to a reader — the
     * reader itself, a lease, a Lucene doc number — survives the gap, so an index that was committed
     * and reopened in between cannot turn the second pass into an {@code AlreadyClosedException} or,
     * worse, a highlight read from a doc number that now means a different document.
     */
    @Test
    public void aCommitBetweenThePassesLeavesTheSecondPassWorking() throws Exception {
        String term = uniqueTerm("zwischenlauf");
        String documentId = createDocument("Ablage");
        indexFile(documentId, "Der Text nennt " + term + " genau einmal.");
        flush();

        LuceneIndexingHandler.SearchResult pass1 = handler.search(null, term);
        Assertions.assertEquals(Set.of(documentId), Set.copyOf(pass1.getDocumentIds()),
                "pass 1 must find the document");
        DirectoryReader readerOfPass1 = currentReader();

        // A commit, then a reacquisition: the cached reader is replaced and the old one released.
        indexFile(documentId, "Eine weitere Datei ohne den Suchbegriff.");
        handler.acquireDirectoryReader().decRef();
        Assertions.assertNotSame(readerOfPass1, currentReader(),
                "the commit must have produced a new reader generation");
        Assertions.assertEquals(0, readerOfPass1.getRefCount(),
                "the superseded reader must be fully released once no pass holds it");

        Map<String, String> highlights = handler.highlightPage(pass1.getQuery(), pass1.getDocumentIds());

        Assertions.assertNotNull(highlights.get(documentId),
                "the second pass must still highlight through the reopened reader");
        Assertions.assertTrue(highlights.get(documentId).contains("<strong>" + term + "</strong>"),
                "and the fragment must mark the matched term: " + highlights.get(documentId));
    }

    /**
     * (iv) A commit and reopen DURING the second pass, from another thread.
     *
     * <p>This is what the reference-counted lease exists for. The pass acquires the reader inside the
     * monitor with an {@code incRef}, so the replacement's {@code close()} only drops the CACHE's
     * reference: the reader stays alive underneath the running pass and is released when the pass
     * ends. The refcounts are asserted for what they can be, not for the impossible: the superseded
     * reader ends at 0 (nobody holds it any more) and the newly cached reader at 1 (the cache's own
     * reference) — a "same reader, same count before and after" assertion could not hold across a
     * replacement at all.
     */
    @Test
    public void aReopenDuringTheSecondPassNeitherThrowsNorLeaksAReader() throws Exception {
        String term = uniqueTerm("nebenlauf");
        String documentId = createDocument("Ablage");
        indexFile(documentId, "Der Text nennt " + term + " genau einmal.");
        flush();

        LuceneIndexingHandler.SearchResult pass1 = handler.search(null, term);
        DirectoryReader leasedByPass2 = currentReader();

        CountDownLatch insideTheContentRead = new CountDownLatch(1);
        CountDownLatch reopenDone = new CountDownLatch(1);
        handler.storedFieldVisitorFactory = fields -> new DocumentStoredFieldVisitor(fields) {
            @Override
            public void stringField(FieldInfo fieldInfo, String value) throws IOException {
                if ("content".equals(fieldInfo.name)) {
                    insideTheContentRead.countDown();
                    try {
                        if (!reopenDone.await(30, TimeUnit.SECONDS)) {
                            throw new IOException("the reopen never happened");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException(e);
                    }
                }
                super.stringField(fieldInfo, value);
            }
        };

        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<Map<String, String>> highlights = new AtomicReference<>();
        Thread secondPass = new Thread(() -> {
            try {
                highlights.set(handler.highlightPage(pass1.getQuery(), pass1.getDocumentIds()));
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "search-pass-2");
        secondPass.start();
        Assertions.assertTrue(insideTheContentRead.await(30, TimeUnit.SECONDS),
                "the second pass must reach the stored-content read");

        // Another thread commits and reopens while the pass holds its lease.
        indexFile(documentId, "Eine weitere Datei ohne den Suchbegriff.");
        handler.acquireDirectoryReader().decRef();
        Assertions.assertNotSame(leasedByPass2, currentReader(), "the cached reader must have been replaced");
        reopenDone.countDown();
        secondPass.join(TimeUnit.SECONDS.toMillis(30));

        Assertions.assertFalse(secondPass.isAlive(), "the second pass must have finished");
        Assertions.assertNull(failure.get(), () -> "the leased reader must survive its replacement, but the"
                + " pass failed with: " + failure.get());
        Assertions.assertNotNull(highlights.get().get(documentId),
                "and it must have produced its fragment from the reader it leased");
        Assertions.assertEquals(0, leasedByPass2.getRefCount(),
                "the superseded reader must be released once the pass that leased it has ended");
        Assertions.assertEquals(1, currentReader().getRefCount(),
                "and the newly cached reader must be held by the cache alone");
    }

    /**
     * (v) The suggester is built once per reader generation.
     *
     * <p>Every fulltext request used to construct a {@link org.apache.lucene.search.suggest.analyzing.FuzzySuggester}
     * and build a finite-state transducer over the WHOLE title dictionary before answering. It can
     * only change when the index does, so it is cached against the reader it was built from and
     * rebuilt when that reader is replaced.
     */
    @Test
    public void theSuggesterIsBuiltOncePerReaderGeneration() throws Exception {
        String term = uniqueTerm("vorschlag");
        String documentId = createDocument(term + " Ablage");
        indexFile(documentId, "Der Text nennt " + term + " genau einmal.");
        flush();

        search(term, 10);
        Object builtOnce = cachedSuggester();
        Assertions.assertNotNull(builtOnce, "the first search must build and publish a suggester");

        search(term, 10);
        Assertions.assertSame(builtOnce, cachedSuggester(),
                "a second search over the same index generation must reuse the built suggester");

        // A committed title makes the next acquisition reopen the reader, invalidating the cache.
        commitATitle(term + " Nachtrag");
        search(term, 10);
        Assertions.assertNotSame(builtOnce, cachedSuggester(),
                "a new reader generation must rebuild the suggester");
    }

    /**
     * (vi) The best-scoring file of a document need not be the one that can produce a fragment.
     *
     * <p>A file matches the query on any of its indexed fields, and {@code filename} is one of them: a
     * scan called "<term> Ablage.txt" matches on a SHORT field with a high field norm, so it can
     * outrank a second file of the same document that carries the term deep inside a long OCR text.
     * Only the second one can produce a highlight — the first has no query term anywhere in its
     * content. Taking the single top-scoring file per document therefore silently dropped the
     * fragment; the all-hits loop this replaced kept it, because it processed the second file too.
     *
     * <p>The pass now walks the document's best files in score order and takes the first fragment it
     * can actually build, still bounded per page row.
     */
    @Test
    public void theHighlightSurvivesAHigherScoringFileThatMatchedOnItsNameOnly() throws Exception {
        String term = uniqueTerm("dateiname");
        String documentId = createDocument("Ablage");
        // A: the term is its FILE NAME, and appears nowhere in its content.
        String namedFileId = indexFile(documentId, term + " Ablage.txt",
                "Diese Datei enthaelt den gesuchten Ausdruck an keiner Stelle ihres Textes.");
        // B: the term appears only in a long OCR text - the only file that can yield a fragment.
        String scannedFileId = indexFile(documentId, "anlage.txt", longPageContaining(term));
        flush();

        LuceneIndexingHandler.SearchResult pass1 = handler.search(null, term);
        Assertions.assertEquals(List.of(namedFileId, scannedFileId), rankedFileIdsOf(documentId, pass1.getQuery()),
                "premise: the filename-matching file must outrank the content-matching one, otherwise this"
                        + " fixture proves nothing");

        PaginatedList<DocumentDto> paginatedList = search(term, 10);

        Assertions.assertEquals(1, paginatedList.getResultList().size(), "the document must be found once");
        String highlight = paginatedList.getResultList().get(0).getHighlight();
        Assertions.assertNotNull(highlight,
                "a higher-scoring filename match must not cost the document the fragment its other file has");
        Assertions.assertTrue(highlight.contains("<strong>" + term + "</strong>"),
                "the fragment must come from the file whose CONTENT matches: " + highlight);
        Assertions.assertTrue(contentDeliveries.get() <= LuceneIndexingHandler.HIGHLIGHT_CANDIDATE_FILES,
                "the search for a usable fragment stays bounded per page row: " + contentDeliveries.get());
    }

    // --- Fixtures ---------------------------------------------------------------------------------------

    private String uniqueTerm(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private User owner;

    private String createDocument(String title) throws Exception {
        if (owner == null) {
            owner = createUser("searchpasses_" + UUID.randomUUID().toString().substring(0, 8));
        }
        Document document = new Document();
        document.setUserId(owner.getId());
        document.setLanguage("eng");
        document.setTitle(title);
        document.setCreateDate(new Date());
        String documentId = new DocumentDao().create(document, owner.getId());
        handler.createDocument(document);
        return documentId;
    }

    /**
     * Indexes a file of the given document with the given OCR content. Only the Lucene entry matters
     * here: the search's SQL side selects from T_DOCUMENT alone, so no T_FILE row is needed.
     */
    private void indexFile(String documentId, String content) {
        indexFile(documentId, "scan.txt", content);
    }

    private String indexFile(String documentId, String name, String content) {
        File file = new File();
        file.setId("searchpasses-" + UUID.randomUUID());
        file.setName(name);
        file.setMimeType("text/plain");
        file.setDocumentId(documentId);
        file.setContent(content);
        Assertions.assertTrue(handler.createFile(file), "the fixture file must be indexed");
        return file.getId();
    }

    /**
     * German prose long enough that its field norm puts a content match below a short filename match,
     * with the search term in the middle of it.
     */
    private String longPageContaining(String term) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            sb.append("Der Vorgang wurde in der Ablage abgelegt und dem Sachbearbeiter vorgelegt. ");
            if (i == 20) {
                sb.append("Hier steht ").append(term).append(" mitten im Text. ");
            }
        }
        return sb.toString();
    }

    /**
     * The Lucene hits of one document under the query pass 1 built, in score order - the ranking the
     * page-bounded pass sees. Used to pin the PREMISE of the ordering fixture, so the test cannot pass
     * vacuously because the intended loser happened to rank first.
     */
    private List<String> rankedFileIdsOf(String documentId, Query originalQuery) throws Exception {
        DirectoryReader reader = handler.acquireDirectoryReader();
        try {
            IndexSearcher searcher = new IndexSearcher(reader);
            Query perDocument = new BooleanQuery.Builder()
                    .add(originalQuery, BooleanClause.Occur.MUST)
                    .add(new TermQuery(new Term("document_id", documentId)), BooleanClause.Occur.FILTER)
                    .build();
            TopDocs topDocs = searcher.search(perDocument, 10);
            List<String> ids = new ArrayList<>();
            for (org.apache.lucene.search.ScoreDoc scoreDoc : topDocs.scoreDocs) {
                ids.add(searcher.storedFields().document(scoreDoc.doc).get("id"));
            }
            return ids;
        } finally {
            reader.decRef();
        }
    }

    private void flush() {
        // The search's native SQL must see the rows this transaction staged.
        ThreadLocalContext.get().getEntityManager().flush();
    }

    private PaginatedList<DocumentDto> search(String fullSearch, int pageSize) throws Exception {
        PaginatedList<DocumentDto> paginatedList = PaginatedLists.create(pageSize, 0);
        DocumentCriteria criteria = new DocumentCriteria();
        // "admin" skips the ACL join (SecurityUtil.skipAclCheck), so the assertions are about the index.
        criteria.setTargetIdList(List.of("admin"));
        criteria.setFullSearch(fullSearch);
        handler.findByCriteria(paginatedList, new ArrayList<>(), criteria, new SortCriteria(3, false));
        return paginatedList;
    }

    /**
     * Commits a document title into the index, without a database row: enough to produce a new reader
     * generation and a changed title dictionary.
     */
    private void commitATitle(String title) {
        Document document = new Document();
        document.setId("searchpasses-" + UUID.randomUUID());
        document.setLanguage("eng");
        document.setTitle(title);
        handler.updateDocument(document);
    }

    /**
     * The handler's currently cached reader, read WITHOUT taking a reference, so the refcount
     * assertions observe the leases and not the observation itself. Reflection is the established
     * mechanism for a private seam in this package (TestIndexBootReconciliation).
     */
    private DirectoryReader currentReader() throws Exception {
        return (DirectoryReader) handlerField("directoryReader").get(handler);
    }

    private Object cachedSuggester() throws Exception {
        return handlerField("suggester").get(handler);
    }

    private static Field handlerField(String name) throws Exception {
        Field field = LuceneIndexingHandler.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
