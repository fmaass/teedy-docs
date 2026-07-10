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
                null
        );
        Assertions.assertTrue(documentCriteria.getActiveRoute());
    }

}
