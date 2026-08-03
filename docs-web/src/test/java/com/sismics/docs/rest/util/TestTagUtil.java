package com.sismics.docs.rest.util;

import com.sismics.docs.core.constant.ConfigType;
import com.sismics.docs.core.dao.ConfigDao;
import com.sismics.docs.core.dao.dto.TagDto;
import com.sismics.docs.rest.BaseTransactionalTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

/**
 * Semantics of a tag search term, as resolved by {@link TagUtil#findByName}.
 *
 * <p>A term resolves in this order:
 * <ol>
 *   <li>a tag whose name equals the term verbatim wins -- so a tag really named {@code Invoice*}
 *       stays reachable by typing it, and the historical "exact wins over its prefix siblings"
 *       behaviour is unchanged;</li>
 *   <li>otherwise a single trailing {@code *} is the prefix operator: it is stripped and the
 *       remainder prefix-matches, whatever TAG_SEARCH_MODE says (an explicit ask beats the mode);</li>
 *   <li>otherwise the configured mode decides (prefix by default, exact when TAG_SEARCH_MODE=EXACT).</li>
 * </ol>
 *
 * <p>{@code *} is not glob syntax: only a trailing one is an operator, any other is an ordinary
 * name character, and a bare {@code *} has no prefix left to match so it selects nothing.
 *
 * <p>The class is transactional because TAG_SEARCH_MODE is read through the config table.
 */
public class TestTagUtil extends BaseTransactionalTest {

    private static final String RECHNUNG = "rechnung-id";
    private static final String RECHNUNGSKORREKTUR = "rechnungskorrektur-id";
    private static final String STEUER = "steuer-id";
    private static final String UMSATZSTEUER = "umsatzsteuer-id";

    /**
     * Two tags sharing a prefix, plus a hierarchy whose child does NOT share its parent's prefix
     * (the child is reached by the parent link, never by prefix matching).
     */
    private static List<TagDto> tagList() {
        return List.of(
                tag(RECHNUNG, "Rechnung"),
                tag(RECHNUNGSKORREKTUR, "Rechnungskorrektur"),
                tag(STEUER, "Steuer"),
                tag(UMSATZSTEUER, "Umsatzsteuer").setParentId(STEUER)
        );
    }

    private static TagDto tag(String id, String name) {
        return new TagDto().setId(id).setName(name);
    }

    private static void assertIds(List<TagDto> actual, String... expectedIds) {
        Assertions.assertEquals(List.of(expectedIds), actual.stream().map(TagDto::getId).toList());
    }

    private static void setExactMatchMode() {
        new ConfigDao().update(ConfigType.TAG_SEARCH_MODE, "EXACT");
    }

    /**
     * A term that is the exact name of a tag resolves to that tag alone, even though a longer
     * sibling shares its prefix.
     */
    @Test
    public void exactNameWinsOverItsPrefixSiblings() {
        assertIds(TagUtil.findByName("Rechnung", tagList()), RECHNUNG);
    }

    /**
     * A term that names no tag exactly falls back to prefix matching.
     */
    @Test
    public void termWithoutAnExactNameFallsBackToPrefix() {
        assertIds(TagUtil.findByName("Rechnun", tagList()), RECHNUNG, RECHNUNGSKORREKTUR);
    }

    /**
     * The trailing wildcard is what makes the prefix siblings reachable again for a term that
     * also happens to be a tag name.
     */
    @Test
    public void trailingWildcardForcesPrefixDespiteAnExactName() {
        assertIds(TagUtil.findByName("Rechnung*", tagList()), RECHNUNG, RECHNUNGSKORREKTUR);
    }

    /**
     * Names are compared case-insensitively, wildcard or not.
     */
    @Test
    public void matchingIgnoresCase() {
        assertIds(TagUtil.findByName("rechnung", tagList()), RECHNUNG);
        assertIds(TagUtil.findByName("rEcHnUnG*", tagList()), RECHNUNG, RECHNUNGSKORREKTUR);
    }

    /**
     * A wildcard on a term with no prefix siblings is harmless: it resolves to the same single tag
     * the bare term would. The child tag is not picked up here -- it does not share the prefix and
     * is added later by the parent-link expansion.
     */
    @Test
    public void wildcardWithoutPrefixSiblingsResolvesToTheSameTag() {
        assertIds(TagUtil.findByName("Steuer", tagList()), STEUER);
        assertIds(TagUtil.findByName("Steuer*", tagList()), STEUER);
    }

    /**
     * A bare wildcard has no prefix left after stripping, so it must select nothing rather than
     * every tag.
     */
    @Test
    public void bareWildcardSelectsNothing() {
        Assertions.assertTrue(TagUtil.findByName("*", tagList()).isEmpty());
    }

    /**
     * An empty term selects nothing.
     */
    @Test
    public void emptyTermSelectsNothing() {
        Assertions.assertTrue(TagUtil.findByName("", tagList()).isEmpty());
    }

    /**
     * TAG_SEARCH_MODE=EXACT still suppresses the prefix fallback for a plain term.
     */
    @Test
    public void exactModeSuppressesThePrefixFallback() {
        setExactMatchMode();
        Assertions.assertTrue(TagUtil.findByName("Rechnun", tagList()).isEmpty());
        assertIds(TagUtil.findByName("Rechnung", tagList()), RECHNUNG);
    }

    /**
     * An explicit wildcard is a per-term ask and outranks TAG_SEARCH_MODE=EXACT.
     */
    @Test
    public void wildcardOverridesExactMode() {
        setExactMatchMode();
        assertIds(TagUtil.findByName("Rechnung*", tagList()), RECHNUNG, RECHNUNGSKORREKTUR);
    }

    /**
     * A tag whose name really ends with an asterisk is matched literally by that name, so such a
     * pre-existing tag stays reachable (the sidebar sends the name verbatim). The wildcard reading
     * only applies when nothing carries the name.
     */
    @Test
    public void aTagNamedWithATrailingAsteriskIsMatchedLiterally() {
        List<TagDto> siblings = List.of(
                tag("invoice-id", "Invoice"),
                tag("correction-id", "InvoiceCorrection")
        );
        List<TagDto> withLiteral = List.of(
                tag("literal-id", "Invoice*"),
                tag("invoice-id", "Invoice"),
                tag("correction-id", "InvoiceCorrection")
        );
        assertIds(TagUtil.findByName("Invoice*", withLiteral), "literal-id");
        // Without the literal tag in the list, the same term is the prefix operator again.
        assertIds(TagUtil.findByName("Invoice*", siblings), "invoice-id", "correction-id");
    }

    /**
     * Case-insensitive matching has to survive the Turkish dotted/dotless I in BOTH directions, so
     * neither a locale-sensitive nor a fixed-locale whole-string fold is enough:
     *
     * <ul>
     *   <li>folding with the JVM's default locale breaks ASCII names on a Turkish host: the ASCII
     *       capital I maps to the DOTLESS lowercase i (U+0131), so a tag named "Invoice" folds to
     *       a name whose first character is U+0131 while the typed "invoice" keeps its ASCII i,
     *       and the two stop matching;</li>
     *   <li>folding with a fixed root locale instead breaks genuinely Turkish names: the dotted
     *       capital I (U+0130) expands to an ASCII i FOLLOWED BY a combining dot above (U+0307),
     *       so a tag named with it folds to nine characters that neither equal nor start with the
     *       typed eight-character "istanbul".</li>
     * </ul>
     *
     * <p>Comparing per character with the JDK's case-insensitive primitives satisfies both, which
     * is what {@code TagUtil} does. Every case is asserted together so a regression in one is not
     * hidden behind the failure of another.
     */
    @Test
    public void matchingIsIndependentOfTheDefaultLocale() {
        List<TagDto> tags = List.of(
                tag("invoice-id", "Invoice"),
                tag("correction-id", "InvoiceCorrection"),
                // U+0130 is the Turkish dotted capital I; written as an escape so this file stays
                // pure ASCII and the character under test cannot be lost to a re-encoding.
                tag("istanbul-id", "\u0130stanbul"),
                tag("istanbul-district-id", "\u0130stanbulAnadolu")
        );
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            Assertions.assertAll(
                    () -> assertIds(TagUtil.findByName("invoice", tags), "invoice-id"),
                    () -> assertIds(TagUtil.findByName("invoice*", tags), "invoice-id", "correction-id"),
                    () -> assertIds(TagUtil.findByName("istanbul", tags), "istanbul-id"),
                    () -> assertIds(TagUtil.findByName("istanbul*", tags), "istanbul-id", "istanbul-district-id")
            );
        } finally {
            Locale.setDefault(previous);
        }
    }

    /**
     * An asterisk anywhere but at the end is an ordinary name character, not glob syntax.
     */
    @Test
    public void anAsteriskInsideATermIsALiteralCharacter() {
        List<TagDto> tags = List.of(
                tag("star-id", "In*voiceDetail"),
                tag("plain-id", "Invoice")
        );
        assertIds(TagUtil.findByName("In*voice", tags), "star-id");
    }

    /**
     * Only one trailing asterisk is the operator; a second one stays part of the prefix.
     */
    @Test
    public void onlyOneTrailingAsteriskIsConsumedAsTheOperator() {
        List<TagDto> tags = List.of(
                tag("star-id", "Invoice*Detail"),
                tag("plain-id", "Invoice")
        );
        assertIds(TagUtil.findByName("Invoice**", tags), "star-id");
    }
}
