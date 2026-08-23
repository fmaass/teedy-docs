package com.sismics.docs.core.util.indexing;

import com.google.common.base.Strings;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.simple.SimpleQueryParser;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.ConstantScoreQuery;
import org.apache.lucene.search.FuzzyQuery;
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the per-field free-text query used by document fulltext search.
 *
 * <p>Two routes:
 * <ul>
 *   <li><b>Operator route</b> — if the free text contains ANY {@link SimpleQueryParser} operator
 *       character ({@code | + " * ~ ( ) \}, or a {@code -} in negation position) it is handed to
 *       the stock parser UNCHANGED, so OR, NOT, precedence, explicit fuzzy, wildcard, phrase and
 *       escape all keep their exact prior behavior. A {@code -} INSIDE a word is term content for
 *       the stock parser too, so it does not route the query away from the forgiving path.</li>
 *   <li><b>Forgiving route</b> — for a pure bare-term query, each analyzed term becomes
 *       {@code exact^3 OR (last term only) prefix^1 OR (length-gated) fuzzy^0.5}, MUST-combined
 *       across terms. This makes a bare partial term find a longer compound token and tolerates
 *       small typos, WITHOUT any index-time analyzer change or reindex.</li>
 * </ul>
 *
 * <p>The fuzzy edit budget is length-gated per term: 0 edits below 4 chars, 1 for 4-7, 2 for 8+, and
 * every fuzzy arm requires the first two characters to match exactly (see {@link #FUZZY_PREFIX_LENGTH}).
 * Only the LAST term gets a prefix clause (matches "search-as-you-type" on the trailing word while
 * keeping earlier terms strict).
 */
public final class LuceneSearchQueryBuilder {
    /**
     * Characters that make a free-text query "operator-bearing" and route it to the stock parser
     * wherever they appear. The exclusion operator '-' is NOT among them: it is position-dependent
     * and handled by {@link #isNegationPosition}.
     */
    private static final String OPERATOR_CHARS = "|+\"*~()\\";

    /** The exclusion operator, which only acts as one at the start of a clause. */
    private static final char NEGATION_CHAR = '-';

    private static final float EXACT_BOOST = 3f;
    private static final float PREFIX_BOOST = 1f;
    private static final float FUZZY_BOOST = 0.5f;

    /** Minimum trailing-term length (code points) before an implicit prefix arm is added. */
    private static final int MIN_PREFIX_LENGTH = 2;

    /**
     * Number of leading characters a fuzzy match must reproduce exactly.
     *
     * <p>A {@link FuzzyQuery} with prefixLength 0 runs its Levenshtein automaton over the WHOLE term
     * dictionary of every searched field - including the full OCR {@code content} dictionary - so a
     * single typo'd term costs a complete dictionary scan twelve times over (#290: 20 s for one
     * search on a real corpus, against 123 ms for the same term spelled correctly at an identical
     * payload). Requiring the first two characters seeks straight to the matching slice instead.
     *
     * <p>The cost is a semantics change, deliberately taken: a typo INSIDE the first two characters
     * is no longer forgiven. A typo anywhere after position 2 still is, on OCR content too.
     */
    private static final int FUZZY_PREFIX_LENGTH = 2;

    private LuceneSearchQueryBuilder() {
    }

    /**
     * True if the free text carries a SimpleQueryParser operator: any of {@code | + " * ~ ( ) \}
     * anywhere, or a {@code -} in negation position.
     *
     * @param search Free-text query
     * @return True if operator-bearing
     */
    public static boolean hasOperator(String search) {
        if (search == null) {
            return false;
        }
        for (int i = 0; i < search.length(); i++) {
            char c = search.charAt(i);
            if (c == NEGATION_CHAR) {
                if (isNegationPosition(search, i)) {
                    return true;
                }
                continue;
            }
            if (OPERATOR_CHARS.indexOf(c) >= 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * True if the '-' at this index is the exclusion OPERATOR rather than term content.
     *
     * <p>{@link SimpleQueryParser} only reads '-' as exclusion at the start of a clause — the
     * start of the query, or directly after whitespace. Everywhere else it is an ordinary term
     * character: the parser renders {@code "alpha beta-gamma"} as {@code +alpha +(+beta +gamma)},
     * two required terms and no NOT clause. Treating every '-' as an operator therefore pushed
     * queries carrying a hyphenated identifier onto the stock route for no semantic gain, and cost
     * them the forgiving path's prefix expansion on the trailing term.
     */
    private static boolean isNegationPosition(String search, int index) {
        return index == 0 || Character.isWhitespace(search.charAt(index - 1));
    }

    /**
     * Build the per-field query for the given free text.
     *
     * @param analyzer Analyzer (same instance the index uses)
     * @param field    Field name
     * @param search   Free-text query
     * @return Query for this field
     */
    public static Query build(Analyzer analyzer, String field, String search) {
        if (hasOperator(search)) {
            // Preserve every operator: route the WHOLE query through the stock parser unchanged.
            SimpleQueryParser parser = new SimpleQueryParser(analyzer, field);
            parser.setDefaultOperator(BooleanClause.Occur.MUST);
            return parser.parse(search);
        }

        List<String> terms = analyze(analyzer, field, search);
        if (terms.isEmpty()) {
            // Fall back to the stock parser so an all-stopword / empty query keeps prior behavior.
            SimpleQueryParser parser = new SimpleQueryParser(analyzer, field);
            parser.setDefaultOperator(BooleanClause.Occur.MUST);
            return parser.parse(search);
        }

        BooleanQuery.Builder outer = new BooleanQuery.Builder();
        for (int i = 0; i < terms.size(); i++) {
            boolean isLast = i == terms.size() - 1;
            outer.add(buildTermQuery(field, terms.get(i), isLast), BooleanClause.Occur.MUST);
        }
        return outer.build();
    }

    /**
     * exact^3 OR (last term only, >=2 chars) prefix^1 OR (length-gated) fuzzy^0.5 for a single
     * analyzed term.
     *
     * <p>Tier ordering (exact &gt; prefix &gt; fuzzy) MUST be deterministic and independent of
     * term rarity. Boosts alone do not guarantee it: {@link PrefixQuery} rewrites to a
     * constant-score query (~boost), but {@link FuzzyQuery} scores per-hit via BM25/IDF, so a rare
     * high-IDF fuzzy match could otherwise outscore a prefix match. The fuzzy arm is therefore
     * wrapped in a {@link ConstantScoreQuery} so it contributes a flat {@code FUZZY_BOOST} (0.5),
     * strictly below the prefix arm's flat {@code PREFIX_BOOST} (1.0). The exact arm stays a normal
     * boosted {@link TermQuery} (×3): it retains intra-tier IDF relevance AND, because a document
     * matched exactly ALSO matches the prefix/fuzzy arms (their scores add), an exact hit's total
     * always exceeds a prefix/fuzzy-only hit's total.
     */
    private static Query buildTermQuery(String field, String term, boolean allowPrefix) {
        Term t = new Term(field, term);
        BooleanQuery.Builder builder = new BooleanQuery.Builder();

        builder.add(new BoostQuery(new TermQuery(t), EXACT_BOOST), BooleanClause.Occur.SHOULD);

        // Require >=2 code points before enumerating a prefix: a 1-char trailing term would
        // enumerate a broad slice of every field dictionary (amplified by the MAX_VALUE fetch),
        // a real perf trap. A 1-char term stays exact + fuzzy only.
        if (allowPrefix && term.codePointCount(0, term.length()) >= MIN_PREFIX_LENGTH) {
            builder.add(new BoostQuery(new PrefixQuery(t), PREFIX_BOOST), BooleanClause.Occur.SHOULD);
        }

        int maxEdits = fuzzyEdits(term.codePointCount(0, term.length()));
        if (maxEdits > 0) {
            // Constant-score the fuzzy arm so it contributes a FLAT FUZZY_BOOST regardless of the
            // matched term's IDF — this is what guarantees prefix (1.0) strictly > fuzzy (0.5).
            Query fuzzy = new ConstantScoreQuery(new FuzzyQuery(t, maxEdits, FUZZY_PREFIX_LENGTH));
            builder.add(new BoostQuery(fuzzy, FUZZY_BOOST), BooleanClause.Occur.SHOULD);
        }

        return builder.build();
    }

    /**
     * Length-gated fuzzy edit budget: 0 below 4 chars, 1 for 4-7, 2 for 8+. The length is measured
     * in Unicode CODE POINTS (matching Lucene's code-point-oriented fuzzy distance), so a
     * supplementary-plane character does not inflate the gate the way UTF-16 {@code String.length()}
     * would.
     */
    static int fuzzyEdits(int codePointLength) {
        if (codePointLength < 4) {
            return 0;
        }
        if (codePointLength < 8) {
            return 1;
        }
        return 2;
    }

    /**
     * Run the field analyzer over the free text and collect the emitted terms (lower-cased,
     * stop-words removed — exactly what is in the index).
     */
    private static List<String> analyze(Analyzer analyzer, String field, String text) {
        List<String> terms = new ArrayList<>();
        try (TokenStream tokenStream = analyzer.tokenStream(field, text)) {
            CharTermAttribute termAttribute = tokenStream.addAttribute(CharTermAttribute.class);
            tokenStream.reset();
            while (tokenStream.incrementToken()) {
                terms.add(termAttribute.toString());
            }
            tokenStream.end();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return terms;
    }
}
