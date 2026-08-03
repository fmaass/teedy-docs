package com.sismics.docs.rest.util;

import com.sismics.docs.core.dao.TagDao;
import com.sismics.docs.core.dao.criteria.DocumentCriteria;
import com.sismics.docs.core.dao.criteria.TagCriteria;
import com.sismics.docs.core.dao.dto.TagDto;
import com.sismics.docs.core.model.jpa.Tag;
import com.sismics.docs.core.model.jpa.User;
import com.sismics.docs.rest.BaseTransactionalTest;
import com.sismics.util.mime.MimeType;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;

public class TestDocumentSearchCriteriaUtil extends BaseTransactionalTest {

    @Test
    public void testHttpParamsBy() throws Exception {
        User user = createUser("user1");

        DocumentCriteria documentCriteria = new DocumentCriteria();
        DocumentSearchCriteriaUtil.addHttpSearchParams(
                documentCriteria,
                "user1",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
        Assertions.assertEquals(documentCriteria.getCreatorId(), user.getId());

        documentCriteria = new DocumentCriteria();
        DocumentSearchCriteriaUtil.addHttpSearchParams(
                documentCriteria,
                "missing",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
        Assertions.assertNotNull(documentCriteria.getCreatorId());
    }

    @Test
    public void testHttpParamsCreatedAfter()  {
        DocumentCriteria documentCriteria = new DocumentCriteria();
        DocumentSearchCriteriaUtil.addHttpSearchParams(
                documentCriteria,
                null,
                "2022-03-27",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
        Assertions.assertEquals(documentCriteria.getCreateDateMin(), Date.from(LocalDate.of(2022, 3, 27).atStartOfDay(ZoneId.systemDefault()).toInstant()));
    }

    @Test
    public void testHttpParamsCreatedBefore() {
        DocumentCriteria documentCriteria = new DocumentCriteria();
        DocumentSearchCriteriaUtil.addHttpSearchParams(
                documentCriteria,
                null,
                null,
                "2022-03-27",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
        Assertions.assertEquals(documentCriteria.getCreateDateMax(), Date.from(LocalDate.of(2022, 3, 27).atStartOfDay(ZoneId.systemDefault()).toInstant()));
    }

    @Test
    public void testHttpParamsFull() {
        DocumentCriteria documentCriteria = new DocumentCriteria();
        DocumentSearchCriteriaUtil.addHttpSearchParams(
                documentCriteria,
                null,
                null,
                null,
                "full",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
        Assertions.assertEquals(documentCriteria.getFullSearch(), "full");
    }

    @Test
    public void testHttpParamsLang() {
        DocumentCriteria documentCriteria = new DocumentCriteria();
        DocumentSearchCriteriaUtil.addHttpSearchParams(
                documentCriteria,
                null,
                null,
                null,
                null,
                "fra",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
        Assertions.assertEquals(documentCriteria.getLanguage(), "fra");

        documentCriteria = new DocumentCriteria();
        DocumentSearchCriteriaUtil.addHttpSearchParams(
                documentCriteria,
                null,
                null,
                null,
                null,
                "unknown",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
        Assertions.assertNotNull(documentCriteria.getLanguage());
        Assertions.assertNotEquals(documentCriteria.getLanguage(), "unknown");
    }

    @Test
    public void testHttpParamsMime() {
        DocumentCriteria documentCriteria = new DocumentCriteria();
        DocumentSearchCriteriaUtil.addHttpSearchParams(
                documentCriteria,
                null,
                null,
                null,
                null,
                null,
                MimeType.IMAGE_GIF,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
        Assertions.assertEquals(documentCriteria.getMimeType(), MimeType.IMAGE_GIF);
    }

    @Test
    public void testHttpParamsShared() {
        DocumentCriteria documentCriteria = new DocumentCriteria();
        DocumentSearchCriteriaUtil.addHttpSearchParams(
                documentCriteria,
                null,
                null,
                null,
                null,
                null,
                null,
                true,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
        Assertions.assertTrue(documentCriteria.getShared());
    }

    @Test
    public void testHttpParamsSimple()  {
        DocumentCriteria documentCriteria = new DocumentCriteria();
        DocumentSearchCriteriaUtil.addHttpSearchParams(
                documentCriteria,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "simple",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
        Assertions.assertEquals(documentCriteria.getSimpleSearch(), "simple");
    }

    @Test
    public void testHttpParamsTag() throws Exception {
        TagDao tagDao = new TagDao();

        User user = createUser("user1");
        Tag tag1 = new Tag();
        tag1.setName("tag1");
        tag1.setColor("#bbb");
        tag1.setUserId(user.getId());
        tagDao.create(tag1, user.getId());

        Tag tag2 = new Tag();
        tag2.setName("tag2");
        tag2.setColor("#bbb");
        tag2.setUserId(user.getId());
        tagDao.create(tag2, user.getId());

        Tag tag3 = new Tag();
        tag3.setName("tag3");
        tag3.setColor("#bbb");
        tag3.setUserId(user.getId());
        tag3.setParentId(tag2.getId());
        tagDao.create(tag3, user.getId());

        DocumentCriteria documentCriteria = new DocumentCriteria();
        List<TagDto> allTagDtoList = tagDao.findByCriteria(new TagCriteria(), null);
        DocumentSearchCriteriaUtil.addHttpSearchParams(
                documentCriteria,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "tag1",
                null,
                null,
                null,
                null,
                null,
                null,
                allTagDtoList
        );
        Assertions.assertEquals(documentCriteria.getTagIdList(), List.of(Collections.singletonList(tag1.getId())));

        documentCriteria = new DocumentCriteria();
        DocumentSearchCriteriaUtil.addHttpSearchParams(
                documentCriteria,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "tag2",
                null,
                null,
                null,
                null,
                null,
                null,
                allTagDtoList
        );
        Assertions.assertEquals(documentCriteria.getTagIdList(), List.of(List.of(tag2.getId(), tag3.getId())));
    }

    @Test
    public void testHttpParamsNotTag() throws Exception {
        TagDao tagDao = new TagDao();

        User user = createUser("user1");
        Tag tag1 = new Tag();
        tag1.setName("tag1");
        tag1.setColor("#bbb");
        tag1.setUserId(user.getId());
        tagDao.create(tag1, user.getId());

        Tag tag2 = new Tag();
        tag2.setName("tag2");
        tag2.setColor("#bbb");
        tag2.setUserId(user.getId());
        tagDao.create(tag2, user.getId());

        Tag tag3 = new Tag();
        tag3.setName("tag3");
        tag3.setColor("#bbb");
        tag3.setUserId(user.getId());
        tag3.setParentId(tag2.getId());
        tagDao.create(tag3, user.getId());

        DocumentCriteria documentCriteria = new DocumentCriteria();
        List<TagDto> allTagDtoList = tagDao.findByCriteria(new TagCriteria(), null);
        DocumentSearchCriteriaUtil.addHttpSearchParams(
                documentCriteria,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "tag1",
                null,
                null,
                null,
                null,
                null,
                allTagDtoList
        );
        Assertions.assertEquals(documentCriteria.getExcludedTagIdList(), List.of(Collections.singletonList(tag1.getId())));

        documentCriteria = new DocumentCriteria();
        DocumentSearchCriteriaUtil.addHttpSearchParams(
                documentCriteria,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "tag2",
                null,
                null,
                null,
                null,
                null,
                allTagDtoList
        );
        Assertions.assertEquals(documentCriteria.getExcludedTagIdList(), List.of(List.of(tag2.getId(), tag3.getId())));
    }

    @Test
    public void testHttpParamsTitle()  {
        DocumentCriteria documentCriteria = new DocumentCriteria();
        DocumentSearchCriteriaUtil.addHttpSearchParams(
                documentCriteria,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "title1,title2",
                null,
                null,
                null,
                null,
                null
        );
        Assertions.assertEquals(documentCriteria.getTitleList(), Arrays.asList(new String[]{"title1", "title2"}));
    }

    @Test
    public void testHttpParamsUpdatedAfter()  {
        DocumentCriteria documentCriteria = new DocumentCriteria();
        DocumentSearchCriteriaUtil.addHttpSearchParams(
                documentCriteria,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "2022-03-27",
                null,
                null,
                null,
                null
        );
        Assertions.assertEquals(documentCriteria.getUpdateDateMin(), Date.from(LocalDate.of(2022, 3, 27).atStartOfDay(ZoneId.systemDefault()).toInstant()));
    }

    @Test
    public void testHttpParamsUpdatedBefore()  {
        DocumentCriteria documentCriteria = new DocumentCriteria();
        DocumentSearchCriteriaUtil.addHttpSearchParams(
                documentCriteria,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "2022-03-27",
                null,
                null,
                null
        );
        Assertions.assertEquals(documentCriteria.getUpdateDateMax(), Date.from(LocalDate.of(2022, 3, 27).atStartOfDay(ZoneId.systemDefault()).toInstant()));
    }

    @Test
    public void testHttpParamsWorkflow()  {
        DocumentCriteria documentCriteria = new DocumentCriteria();
        DocumentSearchCriteriaUtil.addHttpSearchParams(
                documentCriteria,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "me",
                null,
                null
        );
        Assertions.assertTrue(documentCriteria.getActiveRoute());
    }

    private static final String RECHNUNG = "rechnung-id";
    private static final String RECHNUNGSKORREKTUR = "rechnungskorrektur-id";
    private static final String STEUER = "steuer-id";
    private static final String UMSATZSTEUER = "umsatzsteuer-id";

    /**
     * Two tags sharing a prefix, plus a hierarchy whose child does not share its parent's prefix
     * (so the child can only arrive through the parent-link expansion).
     */
    private static List<TagDto> wildcardTagList() {
        return List.of(
                new TagDto().setId(RECHNUNG).setName("Rechnung"),
                new TagDto().setId(RECHNUNGSKORREKTUR).setName("Rechnungskorrektur"),
                new TagDto().setId(STEUER).setName("Steuer"),
                new TagDto().setId(UMSATZSTEUER).setName("Umsatzsteuer").setParentId(STEUER)
        );
    }

    /**
     * In the query grammar, a wildcard on a tag term selects the siblings that the bare term
     * (being an exact tag name) would collapse away -- here in its trailing, prefix-matching form.
     */
    @Test
    public void testSearchQueryTagWildcard() {
        DocumentCriteria bare = DocumentSearchCriteriaUtil.parseSearchQuery("tag:Rechnung", wildcardTagList());
        Assertions.assertEquals(List.of(List.of(RECHNUNG)), bare.getTagIdList());

        DocumentCriteria wildcard = DocumentSearchCriteriaUtil.parseSearchQuery("tag:Rechnung*", wildcardTagList());
        Assertions.assertEquals(List.of(List.of(RECHNUNG, RECHNUNGSKORREKTUR)), wildcard.getTagIdList());
    }

    /**
     * The negated form gets the same wildcard treatment, so the whole prefix union is excluded.
     */
    @Test
    public void testSearchQueryNotTagWildcard() {
        DocumentCriteria bare = DocumentSearchCriteriaUtil.parseSearchQuery("!tag:Rechnung", wildcardTagList());
        Assertions.assertEquals(List.of(List.of(RECHNUNG)), bare.getExcludedTagIdList());

        DocumentCriteria wildcard = DocumentSearchCriteriaUtil.parseSearchQuery("!tag:Rechnung*", wildcardTagList());
        Assertions.assertEquals(List.of(List.of(RECHNUNG, RECHNUNGSKORREKTUR)), wildcard.getExcludedTagIdList());
    }

    /**
     * Child tags are expanded for a wildcard match exactly as for a plain one.
     */
    @Test
    public void testSearchQueryTagWildcardExpandsChildren() {
        DocumentCriteria bare = DocumentSearchCriteriaUtil.parseSearchQuery("tag:Steuer", wildcardTagList());
        Assertions.assertEquals(List.of(List.of(STEUER, UMSATZSTEUER)), bare.getTagIdList());

        DocumentCriteria wildcard = DocumentSearchCriteriaUtil.parseSearchQuery("tag:Steuer*", wildcardTagList());
        Assertions.assertEquals(List.of(List.of(STEUER, UMSATZSTEUER)), wildcard.getTagIdList());
    }

    /**
     * A bare wildcard names no tag, so it must behave like any unmatched tag term (a criteria that
     * no document can satisfy) rather than selecting every tag.
     */
    @Test
    public void testSearchQueryBareTagWildcardMatchesNothing() {
        DocumentCriteria documentCriteria = DocumentSearchCriteriaUtil.parseSearchQuery("tag:*", wildcardTagList());
        Assertions.assertEquals(1, documentCriteria.getTagIdList().size());
        List<String> tagIdList = documentCriteria.getTagIdList().get(0);
        Assertions.assertEquals(1, tagIdList.size());
        Assertions.assertFalse(List.of(RECHNUNG, RECHNUNGSKORREKTUR, STEUER, UMSATZSTEUER).contains(tagIdList.get(0)));
    }

    /**
     * The legacy HTTP parameters resolve tag terms through the same code, so they honour the
     * wildcard identically.
     */
    @Test
    public void testHttpParamsTagWildcard() {
        DocumentCriteria documentCriteria = new DocumentCriteria();
        DocumentSearchCriteriaUtil.addHttpSearchParams(
                documentCriteria,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "Rechnung*",
                null,
                null,
                null,
                null,
                null,
                null,
                wildcardTagList()
        );
        Assertions.assertEquals(List.of(List.of(RECHNUNG, RECHNUNGSKORREKTUR)), documentCriteria.getTagIdList());
    }

    /**
     * Same for the legacy negated parameter.
     */
    @Test
    public void testHttpParamsNotTagWildcard() {
        DocumentCriteria documentCriteria = new DocumentCriteria();
        DocumentSearchCriteriaUtil.addHttpSearchParams(
                documentCriteria,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "Rechnung*",
                null,
                null,
                null,
                null,
                null,
                wildcardTagList()
        );
        Assertions.assertEquals(List.of(List.of(RECHNUNG, RECHNUNGSKORREKTUR)), documentCriteria.getExcludedTagIdList());
    }

    /**
     * The wildcard is a full glob in the query grammar too: it may lead the term or sit between two
     * literals, and the criteria carries whatever tags it resolved to.
     */
    @Test
    public void testSearchQueryTagGlob() {
        DocumentCriteria contains = DocumentSearchCriteriaUtil.parseSearchQuery("tag:*rechnung*", wildcardTagList());
        Assertions.assertEquals(List.of(List.of(RECHNUNG, RECHNUNGSKORREKTUR)), contains.getTagIdList());

        DocumentCriteria infix = DocumentSearchCriteriaUtil.parseSearchQuery("tag:R*korrektur", wildcardTagList());
        Assertions.assertEquals(List.of(List.of(RECHNUNGSKORREKTUR)), infix.getTagIdList());
    }

    /**
     * The negated form gets the same glob treatment, so the whole matched union is excluded.
     */
    @Test
    public void testSearchQueryNotTagGlob() {
        DocumentCriteria documentCriteria = DocumentSearchCriteriaUtil.parseSearchQuery("!tag:*rechnung*", wildcardTagList());
        Assertions.assertEquals(List.of(List.of(RECHNUNG, RECHNUNGSKORREKTUR)), documentCriteria.getExcludedTagIdList());
    }

    /**
     * A glob match pulls in the descendants of every tag it matched, exactly as a plain term does.
     */
    @Test
    public void testSearchQueryTagGlobExpandsChildren() {
        DocumentCriteria documentCriteria = DocumentSearchCriteriaUtil.parseSearchQuery("tag:S*r", wildcardTagList());
        Assertions.assertEquals(List.of(List.of(STEUER, UMSATZSTEUER)), documentCriteria.getTagIdList());
    }

    /**
     * The legacy HTTP parameter resolves through the same code, so it reads the glob identically.
     */
    @Test
    public void testHttpParamsTagGlob() {
        DocumentCriteria documentCriteria = new DocumentCriteria();
        DocumentSearchCriteriaUtil.addHttpSearchParams(
                documentCriteria,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "*rechnung*",
                null,
                null,
                null,
                null,
                null,
                null,
                wildcardTagList()
        );
        Assertions.assertEquals(List.of(List.of(RECHNUNG, RECHNUNGSKORREKTUR)), documentCriteria.getTagIdList());
    }

    /**
     * Same for the legacy negated parameter.
     */
    @Test
    public void testHttpParamsNotTagGlob() {
        DocumentCriteria documentCriteria = new DocumentCriteria();
        DocumentSearchCriteriaUtil.addHttpSearchParams(
                documentCriteria,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "*rechnung*",
                null,
                null,
                null,
                null,
                null,
                wildcardTagList()
        );
        Assertions.assertEquals(List.of(List.of(RECHNUNG, RECHNUNGSKORREKTUR)), documentCriteria.getExcludedTagIdList());
    }

    /**
     * A term that matches no tag is unsatisfiable as an inclusion, so it keeps contributing the
     * sentinel that makes the search return nothing.
     */
    @Test
    public void testSearchQueryUnmatchedTagStillMatchesNothing() {
        for (String term : List.of("tag:zzznosuchtag", "tag:*zzznosuchtag*", "tag:*")) {
            DocumentCriteria documentCriteria = DocumentSearchCriteriaUtil.parseSearchQuery(term, wildcardTagList());
            Assertions.assertEquals(1, documentCriteria.getTagIdList().size(), term);
            List<String> tagIdList = documentCriteria.getTagIdList().get(0);
            Assertions.assertEquals(1, tagIdList.size(), term);
            Assertions.assertFalse(List.of(RECHNUNG, RECHNUNGSKORREKTUR, STEUER, UMSATZSTEUER).contains(tagIdList.get(0)), term);
        }
    }

    /**
     * Negated, the same term excludes nothing at all: it must add no filter to either list, so the
     * search returns what it would have returned without the term.
     */
    @Test
    public void testSearchQueryUnmatchedNotTagAddsNoFilter() {
        for (String term : List.of("!tag:zzznosuchtag", "!tag:*zzznosuchtag*", "!tag:*", "!tag:**")) {
            DocumentCriteria documentCriteria = DocumentSearchCriteriaUtil.parseSearchQuery(term, wildcardTagList());
            Assertions.assertTrue(documentCriteria.getTagIdList().isEmpty(),
                    term + " must not add an unsatisfiable inclusion filter");
            Assertions.assertTrue(documentCriteria.getExcludedTagIdList().isEmpty(),
                    term + " must not add an exclusion filter either");
        }
    }

    /**
     * The legacy negated parameter takes the same branch.
     */
    @Test
    public void testHttpParamsUnmatchedNotTagAddsNoFilter() {
        DocumentCriteria documentCriteria = new DocumentCriteria();
        DocumentSearchCriteriaUtil.addHttpSearchParams(
                documentCriteria,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "zzznosuchtag",
                null,
                null,
                null,
                null,
                null,
                wildcardTagList()
        );
        Assertions.assertTrue(documentCriteria.getTagIdList().isEmpty());
        Assertions.assertTrue(documentCriteria.getExcludedTagIdList().isEmpty());
    }

}
