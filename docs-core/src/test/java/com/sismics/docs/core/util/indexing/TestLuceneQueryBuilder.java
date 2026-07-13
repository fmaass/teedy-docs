package com.sismics.docs.core.util.indexing;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.queryparser.simple.SimpleQueryParser;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Characterization + behavior tests for the free-text Lucene query building used by document
 * search.
 *
 * <p>The first two tests are CHARACTERIZATION tests: they document the pre-#53 baseline of the
 * stock {@link SimpleQueryParser} path (bare term = whole-term match only, explicit wildcard
 * works). They must pass on BOTH the old and the new code — they pin the operator semantics we
 * must never regress. The remaining tests assert the #53 forgiving-search behavior added by
 * {@link LuceneSearchQueryBuilder}.
 */
public class TestLuceneQueryBuilder {
    private static final String[] FIELDS = {"title", "description", "subject", "identifier",
            "publisher", "format", "source", "type", "coverage", "rights", "filename", "content"};

    private Directory directory;
    private Analyzer analyzer;

    @BeforeEach
    public void setUp() throws Exception {
        directory = new ByteBuffersDirectory();
        analyzer = new StandardAnalyzer();
    }

    @AfterEach
    public void tearDown() throws Exception {
        directory.close();
        analyzer.close();
    }

    private void index(String title, String content) throws Exception {
        try (IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig(new StandardAnalyzer()))) {
            Document doc = new Document();
            doc.add(new TextField("title", title, Field.Store.YES));
            doc.add(new TextField("content", content, Field.Store.YES));
            writer.addDocument(doc);
        }
    }

    /**
     * Run a query across the same SHOULD-across-fields shape the production search uses and return
     * the number of matching documents.
     */
    private int hits(Query query) throws Exception {
        try (DirectoryReader reader = DirectoryReader.open(directory)) {
            IndexSearcher searcher = new IndexSearcher(reader);
            TopDocs topDocs = searcher.search(query, 100);
            return topDocs.scoreDocs.length;
        }
    }

    /**
     * The stock parser path (baseline): OR the raw query across every field via SimpleQueryParser.
     * This is the pre-#53 behavior.
     */
    private Query stockQuery(String search) {
        BooleanQuery.Builder builder = new BooleanQuery.Builder();
        for (String field : FIELDS) {
            SimpleQueryParser parser = new SimpleQueryParser(analyzer, field);
            parser.setDefaultOperator(BooleanClause.Occur.MUST);
            builder.add(parser.parse(search), BooleanClause.Occur.SHOULD);
        }
        return builder.build();
    }

    /**
     * The #53 forgiving query: OR the per-field forgiving query across every field.
     */
    private Query forgivingQuery(String search) {
        BooleanQuery.Builder builder = new BooleanQuery.Builder();
        for (String field : FIELDS) {
            builder.add(LuceneSearchQueryBuilder.build(analyzer, field, search), BooleanClause.Occur.SHOULD);
        }
        return builder.build();
    }

    // ----------------------------------------------------------------------------------------
    // CHARACTERIZATION (baseline) — the stock SimpleQueryParser path
    // ----------------------------------------------------------------------------------------

    @Test
    public void baselineBareTermDoesNotMatchCompound() throws Exception {
        // A German compound is emitted as ONE token by StandardAnalyzer. A bare partial term does
        // NOT match it with the stock parser — this is the exact gap #53 closes.
        index("Übungsleitervertrag", "");
        Assertions.assertEquals(0, hits(stockQuery("Übungsleit")),
                "baseline: bare partial term must NOT match the compound (whole-term semantics)");
    }

    @Test
    public void baselineExplicitWildcardMatchesCompound() throws Exception {
        // The operators are already enabled (two-arg ctor -> flags=-1); explicit wildcard already
        // works.
        index("Übungsleitervertrag", "");
        Assertions.assertTrue(hits(stockQuery("Übungsleit*")) >= 1,
                "baseline: explicit trailing wildcard already matches the compound");
    }

    // ----------------------------------------------------------------------------------------
    // #53 forgiving search behavior
    // ----------------------------------------------------------------------------------------

    @Test
    public void bareTermNowMatchesCompound() throws Exception {
        // The core #53 acceptance: a bare partial term finds the longer compound with no reindex.
        index("Übungsleitervertrag", "");
        Assertions.assertTrue(hits(forgivingQuery("Übungsleit")) >= 1,
                "bare 'Übungsleit' must find 'Übungsleitervertrag'");
    }

    @Test
    public void exactRankedAbovePrefixRankedAboveFuzzy() throws Exception {
        // exact^3 > prefix^1 > fuzzy^0.5 : an exact-token doc must outrank a prefix-only doc which
        // must outrank a fuzzy-only doc for the same query term.
        try (IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig(new StandardAnalyzer()))) {
            Document exact = new Document();
            exact.add(new TextField("title", "vertrag", Field.Store.YES));
            exact.add(new TextField("content", "exact", Field.Store.YES));
            writer.addDocument(exact);

            Document prefix = new Document();
            prefix.add(new TextField("title", "vertragspartner", Field.Store.YES));
            prefix.add(new TextField("content", "prefix", Field.Store.YES));
            writer.addDocument(prefix);

            Document fuzzy = new Document();
            fuzzy.add(new TextField("title", "vertrog", Field.Store.YES));
            fuzzy.add(new TextField("content", "fuzzy", Field.Store.YES));
            writer.addDocument(fuzzy);
        }

        Query q = forgivingQuery("vertrag");
        try (DirectoryReader reader = DirectoryReader.open(directory)) {
            IndexSearcher searcher = new IndexSearcher(reader);
            TopDocs topDocs = searcher.search(q, 10);
            Assertions.assertEquals(3, topDocs.scoreDocs.length, "all three docs match");
            String first = searcher.storedFields().document(topDocs.scoreDocs[0].doc).get("content");
            String second = searcher.storedFields().document(topDocs.scoreDocs[1].doc).get("content");
            String third = searcher.storedFields().document(topDocs.scoreDocs[2].doc).get("content");
            Assertions.assertEquals("exact", first, "exact match ranks first");
            Assertions.assertEquals("prefix", second, "prefix match ranks second");
            Assertions.assertEquals("fuzzy", third, "fuzzy match ranks last");
        }
    }

    @Test
    public void multiTermAndPreserved() throws Exception {
        // Two bare terms are AND-ed (MUST between terms): a doc with only one term must NOT match.
        // The trailing term is the partial-compound one (prefix applies to the LAST term only).
        try (IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig(new StandardAnalyzer()))) {
            Document both = new Document();
            both.add(new TextField("title", "muster Übungsleitervertrag", Field.Store.YES));
            both.add(new TextField("content", "", Field.Store.YES));
            writer.addDocument(both);

            Document one = new Document();
            one.add(new TextField("title", "Übungsleitervertrag", Field.Store.YES));
            one.add(new TextField("content", "", Field.Store.YES));
            writer.addDocument(one);
        }
        Assertions.assertEquals(1, hits(forgivingQuery("muster Übungsleit")),
                "both bare terms must be required (AND)");
    }

    @Test
    public void shortTermFuzzyGatedOff() throws Exception {
        // A short term (<4 chars) must NOT fuzzy-match a 1-edit neighbour.
        index("cat", "");
        // "car" is 1 edit from "cat" but 3 chars -> fuzzy gated to 0 edits, and it is not a prefix.
        Assertions.assertEquals(0, hits(forgivingQuery("car")),
                "short term (<4) must not fuzzy-match a neighbour");
    }

    @Test
    public void mediumTermFuzzyOneEdit() throws Exception {
        // A 4-7 char term tolerates 1 edit.
        index("hello", "");
        Assertions.assertTrue(hits(forgivingQuery("hallo")) >= 1,
                "medium term must fuzzy-match within 1 edit");
    }

    @Test
    public void prefixRanksAboveFuzzyEvenWhenFuzzyIsRare() throws Exception {
        // The tier order must NOT depend on term rarity. Construct the adversarial case the boosts
        // alone would fail: the PREFIX-matched token is COMMON (low IDF -> low BM25) while the
        // FUZZY-matched token is RARE (high IDF -> high BM25). With a plain boosted FuzzyQuery the
        // rare fuzzy hit could outscore the common prefix hit; the ConstantScoreQuery wrapper makes
        // the fuzzy arm a FLAT 0.5 so prefix (flat ~1.0) strictly wins regardless of IDF.
        try (IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig(new StandardAnalyzer()))) {
            // The prefix-matching token "vertragxyz" is COMMON (1000+1 docs -> very low IDF). It is
            // 3 edits from "vertrag", so it matches the PREFIX arm but NOT the fuzzy arm (gate=1) —
            // i.e. it earns ONLY the prefix score, not prefix+fuzzy. This isolates the
            // prefix-vs-fuzzy comparison (a token within 1 edit would also earn the fuzzy arm and
            // trivially win regardless of the fix). The corpus is deliberately LARGE: a rare 1-doc
            // fuzzy term's BM25/IDF then exceeds 2× the prefix's constant score, so a PLAIN
            // (non-constant-scored) fuzzy arm would rank the fuzzy doc ABOVE the prefix doc —
            // exactly the ranking inversion the ConstantScoreQuery fix prevents (and the mutation
            // that removes it must trip this test).
            for (int i = 0; i < 1000; i++) {
                Document filler = new Document();
                filler.add(new TextField("title", "vertragxyz", Field.Store.YES));
                filler.add(new TextField("content", "filler", Field.Store.YES));
                writer.addDocument(filler);
            }
            Document prefix = new Document();
            prefix.add(new TextField("title", "vertragxyz", Field.Store.YES));
            prefix.add(new TextField("content", "prefix", Field.Store.YES));
            writer.addDocument(prefix);

            // The fuzzy-matching token "vertrog" (1 edit from "vertrag") is RARE: exactly one doc ->
            // very high IDF. It matches ONLY the fuzzy arm. Under plain BM25 fuzzy scoring its high
            // IDF would rank it ABOVE the common prefix hit; the ConstantScoreQuery wrapper prevents
            // that.
            Document fuzzy = new Document();
            fuzzy.add(new TextField("title", "vertrog", Field.Store.YES));
            fuzzy.add(new TextField("content", "fuzzy", Field.Store.YES));
            writer.addDocument(fuzzy);
        }

        Query q = forgivingQuery("vertrag");
        try (DirectoryReader reader = DirectoryReader.open(directory)) {
            IndexSearcher searcher = new IndexSearcher(reader);
            // Compare the two docs' ACTUAL scores directly (not top-N rank position): with 1000+
            // tied prefix docs a rank scan would be brittle. Locate each asserted doc by its stored
            // content marker, then read its query score via explain() — exact and corpus-robust.
            float prefixScore = scoreOfDocWithContent(searcher, q, "prefix");
            float fuzzyScore = scoreOfDocWithContent(searcher, q, "fuzzy");
            Assertions.assertTrue(prefixScore > 0, "the prefix doc must match");
            Assertions.assertTrue(fuzzyScore > 0, "the fuzzy doc must match");
            Assertions.assertTrue(prefixScore > fuzzyScore,
                    "a COMMON prefix hit must score above a RARE fuzzy hit (constant-scored fuzzy arm) — "
                            + "prefixScore=" + prefixScore + " fuzzyScore=" + fuzzyScore);
        }
    }

    /**
     * The query score of the single document whose stored "content" equals the marker, via
     * IndexSearcher.explain — independent of top-N truncation and tie ordering.
     */
    private float scoreOfDocWithContent(IndexSearcher searcher, Query q, String contentMarker) throws Exception {
        org.apache.lucene.index.IndexReader reader = searcher.getIndexReader();
        for (int docId = 0; docId < reader.maxDoc(); docId++) {
            String c = searcher.storedFields().document(docId).get("content");
            if (contentMarker.equals(c)) {
                org.apache.lucene.search.Explanation ex = searcher.explain(q, docId);
                return ex.isMatch() ? (float) ex.getValue().doubleValue() : -1f;
            }
        }
        return -1f;
    }

    @Test
    public void singleCharTrailingTermGetsNoPrefix() throws Exception {
        // A 1-char trailing term must NOT add a PrefixQuery arm (a broad dictionary enumeration).
        // "a" is a StandardAnalyzer stopword, so use a non-stopword 1-char token: "x".
        try (IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig(new StandardAnalyzer()))) {
            Document exact = new Document();
            exact.add(new TextField("title", "x", Field.Store.YES));
            exact.add(new TextField("content", "exact-one-char", Field.Store.YES));
            writer.addDocument(exact);

            // A token that a prefix "x*" WOULD match — it must NOT be returned for the bare 1-char
            // query, proving no prefix arm was added.
            Document prefixOnly = new Document();
            prefixOnly.add(new TextField("title", "xylophone", Field.Store.YES));
            prefixOnly.add(new TextField("content", "prefix-only", Field.Store.YES));
            writer.addDocument(prefixOnly);
        }
        try (DirectoryReader reader = DirectoryReader.open(directory)) {
            IndexSearcher searcher = new IndexSearcher(reader);
            TopDocs topDocs = searcher.search(forgivingQuery("x"), 100);
            boolean prefixOnlySeen = false;
            boolean exactSeen = false;
            for (ScoreDoc sd : topDocs.scoreDocs) {
                String c = searcher.storedFields().document(sd.doc).get("content");
                if ("prefix-only".equals(c)) prefixOnlySeen = true;
                if ("exact-one-char".equals(c)) exactSeen = true;
            }
            Assertions.assertTrue(exactSeen, "an EXACT 1-char match must still be found");
            Assertions.assertFalse(prefixOnlySeen,
                    "a 1-char trailing term must NOT enumerate a prefix (xylophone must not match 'x')");
        }
    }

    @Test
    public void fuzzyGateCountsCodePointsNotUtf16Units() {
        // A 3-code-point string of supplementary-plane characters is 6 UTF-16 units. The gate must
        // count 3 CODE POINTS (fuzzy off, <4), not 6 UTF-16 units (which would be >=4 -> fuzzy ON).
        // Direct method sanity first.
        String threeAstralCodePoints = "😀😁😂"; // 3 emoji = 6 UTF-16 chars
        Assertions.assertEquals(6, threeAstralCodePoints.length(), "sanity: 6 UTF-16 units");
        Assertions.assertEquals(3,
                threeAstralCodePoints.codePointCount(0, threeAstralCodePoints.length()),
                "sanity: 3 code points");
        Assertions.assertEquals(0, LuceneSearchQueryBuilder.fuzzyEdits(3),
                "a 3-code-point count must gate fuzzy off");

        // Call-site behavior: the built query for the 3-emoji term must contain NO FuzzyQuery arm.
        // A WhitespaceAnalyzer preserves the astral token verbatim (no lowercase/stopword drop) so
        // the builder sees the exact 6-UTF-16-unit / 3-code-point term. If the gate mistakenly used
        // String.length() (=6 -> 2 edits) a FuzzyQuery arm would appear; with codePointCount (=3)
        // it must not. This is the mutation-checked assertion: flip length()<->codePointCount at the
        // call site and this fails.
        try (org.apache.lucene.analysis.core.WhitespaceAnalyzer wa =
                     new org.apache.lucene.analysis.core.WhitespaceAnalyzer()) {
            Query built = LuceneSearchQueryBuilder.build(wa, "title", threeAstralCodePoints);
            Assertions.assertFalse(containsFuzzyClause(built),
                    "a 3-code-point (6 UTF-16 unit) term must NOT get a fuzzy arm: " + built);
        }
    }

    /** True if the query tree contains any FuzzyQuery (unwrapping Boost/ConstantScore/Boolean). */
    private boolean containsFuzzyClause(Query q) {
        if (q instanceof org.apache.lucene.search.FuzzyQuery) {
            return true;
        }
        if (q instanceof org.apache.lucene.search.BoostQuery bq) {
            return containsFuzzyClause(bq.getQuery());
        }
        if (q instanceof org.apache.lucene.search.ConstantScoreQuery cq) {
            return containsFuzzyClause(cq.getQuery());
        }
        if (q instanceof BooleanQuery bool) {
            for (BooleanClause clause : bool.clauses()) {
                if (containsFuzzyClause(clause.query())) {
                    return true;
                }
            }
        }
        return false;
    }

    // ----------------------------------------------------------------------------------------
    // OPERATOR PRESERVATION — an operator-bearing query routes through the stock parser unchanged
    // ----------------------------------------------------------------------------------------

    @Test
    public void operatorOrPreserved() throws Exception {
        index("alpha", "");
        index("beta", "");
        Assertions.assertEquals(hits(stockQuery("alpha | beta")), hits(forgivingQuery("alpha | beta")),
                "OR must behave identically to the stock parser");
        Assertions.assertEquals(2, hits(forgivingQuery("alpha | beta")));
    }

    @Test
    public void operatorNotPreserved() throws Exception {
        index("alpha beta", "");
        index("alpha", "");
        Assertions.assertEquals(hits(stockQuery("alpha -beta")), hits(forgivingQuery("alpha -beta")),
                "NOT must behave identically to the stock parser");
        Assertions.assertEquals(1, hits(forgivingQuery("alpha -beta")));
    }

    @Test
    public void operatorPrecedencePreserved() throws Exception {
        index("alpha beta gamma", "");
        index("gamma", "");
        Assertions.assertEquals(hits(stockQuery("(alpha beta) gamma")), hits(forgivingQuery("(alpha beta) gamma")),
                "precedence grouping must behave identically to the stock parser");
    }

    @Test
    public void operatorExplicitFuzzyPreserved() throws Exception {
        index("hello", "");
        Assertions.assertEquals(hits(stockQuery("hallo~")), hits(forgivingQuery("hallo~")),
                "explicit fuzzy must behave identically to the stock parser");
    }

    @Test
    public void operatorPhrasePreserved() throws Exception {
        index("alpha beta", "");
        index("beta alpha", "");
        Assertions.assertEquals(hits(stockQuery("\"alpha beta\"")), hits(forgivingQuery("\"alpha beta\"")),
                "phrase must behave identically to the stock parser");
        Assertions.assertEquals(1, hits(forgivingQuery("\"alpha beta\"")));
    }

    @Test
    public void operatorWildcardPreserved() throws Exception {
        index("Übungsleitervertrag", "");
        Assertions.assertEquals(hits(stockQuery("Übungsleit*")), hits(forgivingQuery("Übungsleit*")),
                "explicit wildcard must behave identically to the stock parser");
    }

    @Test
    public void operatorDetection() {
        // The routing predicate: any of | + - " * ~ ( ) \ triggers the stock path.
        for (String op : List.of("a | b", "a + b", "a -b", "a \"b\"", "a*", "a~", "(a)", "a\\ b")) {
            Assertions.assertTrue(LuceneSearchQueryBuilder.hasOperator(op), "operator-bearing: " + op);
        }
        for (String plain : List.of("alpha", "alpha beta", "Übungsleit", "a1 b2")) {
            Assertions.assertFalse(LuceneSearchQueryBuilder.hasOperator(plain), "plain: " + plain);
        }
    }
}
