package com.sismics.docs.core.dao;

import com.sismics.docs.BaseTransactionalTest;
import com.sismics.docs.core.model.jpa.Vocabulary;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Unit tests for {@link VocabularyDao}.
 */
public class TestVocabularyDao extends BaseTransactionalTest {
    @Test
    public void crudCycle() {
        VocabularyDao dao = new VocabularyDao();

        Vocabulary vocabulary = new Vocabulary();
        vocabulary.setName("crud-test-name");
        vocabulary.setValue("Test value");
        vocabulary.setOrder(0);
        String id = dao.create(vocabulary);
        Assertions.assertNotNull(id);

        Vocabulary loaded = dao.getById(id);
        Assertions.assertNotNull(loaded);
        Assertions.assertEquals("crud-test-name", loaded.getName());
        Assertions.assertEquals("Test value", loaded.getValue());

        Vocabulary update = new Vocabulary();
        update.setId(id);
        update.setName("crud-test-name");
        update.setValue("Updated value");
        update.setOrder(3);
        dao.update(update);
        Assertions.assertEquals("Updated value", dao.getById(id).getValue());
        Assertions.assertEquals(3, dao.getById(id).getOrder());

        List<Vocabulary> byName = dao.getByName("crud-test-name");
        Assertions.assertEquals(1, byName.size());

        dao.delete(id);
        Assertions.assertNull(dao.getById(id));
    }

    @Test
    public void getByNameIsOrdered() {
        VocabularyDao dao = new VocabularyDao();
        Vocabulary second = new Vocabulary();
        second.setName("ordered-name");
        second.setValue("second");
        second.setOrder(5);
        dao.create(second);
        Vocabulary first = new Vocabulary();
        first.setName("ordered-name");
        first.setValue("first");
        first.setOrder(1);
        dao.create(first);

        List<Vocabulary> list = dao.getByName("ordered-name");
        Assertions.assertEquals(2, list.size());
        Assertions.assertEquals("first", list.get(0).getValue());
        Assertions.assertEquals("second", list.get(1).getValue());
    }

    @Test
    public void seedVocabularyCountIs270() {
        VocabularyDao dao = new VocabularyDao();
        // The seed data is spread across several vocabulary names; count them all.
        int total = 0;
        for (String name : new String[]{"type", "coverage", "rights"}) {
            total += dao.getByName(name).size();
        }
        // All 270 seeded rows belong to type/coverage/rights.
        Assertions.assertEquals(270, total, "Expected 270 seeded vocabulary entries");
    }
}
