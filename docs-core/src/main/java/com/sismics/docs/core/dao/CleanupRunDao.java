package com.sismics.docs.core.dao;

import com.sismics.docs.core.model.jpa.CleanupRun;
import com.sismics.util.context.ThreadLocalContext;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Storage-cleanup run DAO (#60). Persists and reads the durable clean_storage protocol.
 */
public class CleanupRunDao {
    /**
     * Records a completed REAL clean_storage run.
     *
     * @param cleanupRun Run to persist (id/createDate are assigned here)
     * @return Generated ID
     */
    public String create(CleanupRun cleanupRun) {
        cleanupRun.setId(UUID.randomUUID().toString());
        if (cleanupRun.getCreateDate() == null) {
            cleanupRun.setCreateDate(new Date());
        }
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        em.persist(cleanupRun);
        return cleanupRun.getId();
    }

    /**
     * Returns the most recent cleanup runs, newest first.
     *
     * @param limit Maximum number of rows to return
     * @return Recent cleanup runs
     */
    public List<CleanupRun> findRecent(int limit) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        TypedQuery<CleanupRun> q = em.createQuery(
                "select c from CleanupRun c order by c.createDate desc", CleanupRun.class);
        q.setMaxResults(limit);
        return q.getResultList();
    }

    /**
     * Returns the total number of recorded cleanup runs.
     *
     * @return Count of protocol rows
     */
    public long count() {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        return em.createQuery("select count(c) from CleanupRun c", Long.class).getSingleResult();
    }
}
