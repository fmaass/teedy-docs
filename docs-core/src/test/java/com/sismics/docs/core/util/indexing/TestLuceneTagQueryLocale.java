package com.sismics.docs.core.util.indexing;

import com.sismics.docs.BaseTransactionalTest;
import com.sismics.docs.core.dao.DocumentDao;
import com.sismics.docs.core.dao.TagDao;
import com.sismics.docs.core.dao.criteria.DocumentCriteria;
import com.sismics.docs.core.dao.dto.DocumentDto;
import com.sismics.docs.core.model.jpa.Document;
import com.sismics.docs.core.model.jpa.Tag;
import com.sismics.docs.core.model.jpa.User;
import com.sismics.docs.core.util.jpa.PaginatedList;
import com.sismics.docs.core.util.jpa.PaginatedLists;
import com.sismics.util.context.ThreadLocalContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Tag-filtered document search must build its SQL with ASCII parameter names regardless of the
 * host's default locale.
 *
 * <p>The tag joins render the join index into BOTH a table alias and a named parameter
 * ({@code :tagId0} / {@code :tagIdEx0}) with {@code String.format("…%d…", index)}, while the
 * matching bind is assembled by plain ASCII concatenation ({@code "tagId" + index}). On a host whose
 * default locale uses a non-ASCII numbering system the two disagree — the SQL declares
 * {@code :tagId٠} while {@code tagId0} is bound — so the parameter never binds and the search
 * fails outright.
 *
 * <p>These tests drive the REAL query end to end against the database, so the assertion is made by
 * the persistence layer itself: a name in the SQL with no matching bind (or a bind with no matching
 * name) is rejected. A test that only inspected the generated alias text would pass while the
 * binding was still broken.
 */
public class TestLuceneTagQueryLocale extends BaseTransactionalTest {
    /**
     * A locale whose default numbering system is Eastern Arabic-Indic, so {@code %d} renders
     * non-ASCII digits. Arabic-locale hosts are ordinary environments, not a lab construction.
     */
    private static final Locale NON_ASCII_DIGIT_LOCALE = Locale.forLanguageTag("ar-EG");

    private Locale previousDefault;

    /**
     * Deliberately NOT named setUp: {@link BaseTransactionalTest#setUp()} opens the entity manager
     * and transaction, and overriding it would silently disable that.
     */
    @BeforeEach
    public void applyHostileLocale() {
        previousDefault = Locale.getDefault();
        Locale.setDefault(NON_ASCII_DIGIT_LOCALE);
    }

    @AfterEach
    public void restoreLocale() {
        // Locale.setDefault is JVM-global: restore it so a hostile default cannot leak into any
        // sibling test, whatever this test did or threw.
        Locale.setDefault(previousDefault);
    }

    private String createDocument(User user, String title) {
        Document document = new Document();
        document.setUserId(user.getId());
        document.setLanguage("eng");
        document.setTitle(title);
        document.setCreateDate(new Date());
        return new DocumentDao().create(document, user.getId());
    }

    private String createTag(User user, String name) {
        Tag tag = new Tag();
        tag.setName(name);
        tag.setColor("#ff0000");
        tag.setUserId(user.getId());
        return new TagDao().create(tag, user.getId());
    }

    /**
     * Run the production search and return the ids it found. The entity manager is flushed first so
     * the native query sees the rows staged by this transaction.
     */
    private Set<String> search(DocumentCriteria criteria) throws Exception {
        ThreadLocalContext.get().getEntityManager().flush();
        PaginatedList<DocumentDto> paginatedList = PaginatedLists.create();
        new LuceneIndexingHandler().findByCriteria(paginatedList, new ArrayList<>(), criteria, null);
        return paginatedList.getResultList().stream().map(DocumentDto::getId).collect(Collectors.toSet());
    }

    private static DocumentCriteria adminCriteria() {
        DocumentCriteria criteria = new DocumentCriteria();
        // "admin" skips the ACL check, so the search is not filtered by ACL rows this test does not seed.
        criteria.setTargetIdList(List.of("admin"));
        return criteria;
    }

    /**
     * Premise check: the chosen locale must really render non-ASCII digits on this JDK, otherwise
     * the tests below would pass vacuously.
     */
    @Test
    public void chosenLocaleReallyRendersNonAsciiDigits() {
        Assertions.assertFalse(String.format("%d", 0).chars().allMatch(c -> c < 128),
                "premise: the default locale must render non-ASCII digits for these tests to mean anything");
    }

    /**
     * THE DEFECT (included tags): filtering by a tag must work on a non-ASCII-digit host. Before the
     * fix the generated {@code :tagId٠} never matches the bound {@code tagId0} and the search throws.
     */
    @Test
    public void tagFilteredSearchWorksUnderANonAsciiDigitLocale() throws Exception {
        User user = createUser("taglocale_inc");
        String taggedId = createDocument(user, "Tagged doc");
        String untaggedId = createDocument(user, "Untagged doc");
        String tagId = createTag(user, "loc-inc");
        new TagDao().updateTagList(taggedId, Set.of(tagId));

        DocumentCriteria criteria = adminCriteria();
        criteria.getTagIdList().add(List.of(tagId));

        Set<String> found = search(criteria);
        Assertions.assertTrue(found.contains(taggedId), "the tagged document must be found");
        Assertions.assertFalse(found.contains(untaggedId), "the untagged document must be filtered out");
    }

    /**
     * THE DEFECT (excluded tags): the {@code :tagIdEx%d} site is a separate call site with the same
     * bug, so it needs its own coverage — excluding a tag must work on a non-ASCII-digit host.
     */
    @Test
    public void excludedTagSearchWorksUnderANonAsciiDigitLocale() throws Exception {
        User user = createUser("taglocale_exc");
        String keepId = createDocument(user, "Keep doc");
        String excludeId = createDocument(user, "Exclude doc");
        String includedTagId = createTag(user, "loc-keep");
        String excludedTagId = createTag(user, "loc-drop");
        new TagDao().updateTagList(keepId, Set.of(includedTagId));
        new TagDao().updateTagList(excludeId, Set.of(includedTagId, excludedTagId));

        DocumentCriteria criteria = adminCriteria();
        criteria.getTagIdList().add(List.of(includedTagId));
        criteria.getExcludedTagIdList().add(List.of(excludedTagId));

        Set<String> found = search(criteria);
        Assertions.assertTrue(found.contains(keepId), "the document without the excluded tag must be found");
        Assertions.assertFalse(found.contains(excludeId), "the document carrying the excluded tag must be dropped");
    }

    /**
     * More than one tag join is emitted for a multi-tag search, so indices beyond 0 are rendered too.
     * This pins every emitted alias/parameter pair, not just the first.
     */
    @Test
    public void multiTagSearchWorksUnderANonAsciiDigitLocale() throws Exception {
        User user = createUser("taglocale_multi");
        String bothId = createDocument(user, "Both tags");
        String oneId = createDocument(user, "One tag");
        String tagA = createTag(user, "loc-a");
        String tagB = createTag(user, "loc-b");
        new TagDao().updateTagList(bothId, Set.of(tagA, tagB));
        new TagDao().updateTagList(oneId, Set.of(tagA));

        DocumentCriteria criteria = adminCriteria();
        // Two AND-ed groups -> two joins -> indices 0 and 1.
        criteria.getTagIdList().add(List.of(tagA));
        criteria.getTagIdList().add(List.of(tagB));

        Set<String> found = search(criteria);
        Assertions.assertTrue(found.contains(bothId), "the document carrying both tags must be found");
        Assertions.assertFalse(found.contains(oneId), "a document missing one required tag must be dropped");
    }

    /**
     * The ASCII host must be completely unaffected. This is the no-regression half of the fix and
     * passes both before and after it.
     */
    @Test
    public void asciiHostTagSearchIsUnchanged() throws Exception {
        Locale.setDefault(Locale.US);
        User user = createUser("taglocale_ascii");
        String taggedId = createDocument(user, "Tagged doc");
        String untaggedId = createDocument(user, "Untagged doc");
        String tagId = createTag(user, "loc-ascii");
        new TagDao().updateTagList(taggedId, Set.of(tagId));

        DocumentCriteria criteria = adminCriteria();
        criteria.getTagIdList().add(List.of(tagId));

        Set<String> found = search(criteria);
        Assertions.assertEquals(new HashSet<>(List.of(taggedId)), found,
                "an ASCII-digit host must return exactly the tagged document");
        Assertions.assertFalse(found.contains(untaggedId));
    }
}
