package com.sismics.docs.core.dao;

import com.sismics.docs.BaseTransactionalTest;
import com.sismics.docs.core.model.jpa.CleanupRun;
import com.sismics.util.context.ThreadLocalContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;

/**
 * Unit tests for {@link CleanupRunDao} — the durable clean_storage protocol store (#60).
 */
public class TestCleanupRunDao extends BaseTransactionalTest {
    @Test
    public void createAssignsIdAndDate() {
        CleanupRunDao dao = new CleanupRunDao();
        String id = dao.create(new CleanupRun()
                .setFileCount(3L)
                .setBytes(4096L)
                .setUserId("admin")
                .setUsername("admin"));
        Assertions.assertNotNull(id, "create must assign a generated id");
        ThreadLocalContext.get().getEntityManager().flush();

        CleanupRun stored = ThreadLocalContext.get().getEntityManager().find(CleanupRun.class, id);
        Assertions.assertNotNull(stored);
        Assertions.assertEquals(3L, stored.getFileCount());
        Assertions.assertEquals(4096L, stored.getBytes());
        Assertions.assertEquals("admin", stored.getUsername());
        Assertions.assertNotNull(stored.getCreateDate(), "create must stamp a date when none was set");
    }

    @Test
    public void countReflectsInsertedRows() {
        CleanupRunDao dao = new CleanupRunDao();
        long before = dao.count();
        dao.create(new CleanupRun().setFileCount(1L).setBytes(1L).setUserId("admin").setUsername("admin"));
        dao.create(new CleanupRun().setFileCount(2L).setBytes(2L).setUserId("admin").setUsername("admin"));
        ThreadLocalContext.get().getEntityManager().flush();
        Assertions.assertEquals(before + 2, dao.count(), "count must include every inserted protocol row");
    }

    @Test
    public void findRecentReturnsNewestFirstAndHonorsLimit() {
        CleanupRunDao dao = new CleanupRunDao();
        long t = System.currentTimeMillis();
        dao.create(new CleanupRun().setFileCount(1L).setBytes(1L).setUserId("admin").setUsername("admin")
                .setCreateDate(new Date(t)));
        dao.create(new CleanupRun().setFileCount(2L).setBytes(2L).setUserId("admin").setUsername("admin")
                .setCreateDate(new Date(t + 1000)));
        dao.create(new CleanupRun().setFileCount(3L).setBytes(3L).setUserId("admin").setUsername("admin")
                .setCreateDate(new Date(t + 2000)));
        ThreadLocalContext.get().getEntityManager().flush();

        List<CleanupRun> recent = dao.findRecent(2);
        Assertions.assertEquals(2, recent.size(), "limit must be honored");
        Assertions.assertEquals(3L, recent.get(0).getFileCount(), "newest run first");
        Assertions.assertEquals(2L, recent.get(1).getFileCount(), "then the next newest");
    }
}
