package com.sismics.docs.core.dao;

import com.sismics.docs.core.constant.ConfigType;
import com.sismics.docs.core.model.jpa.Config;
import com.sismics.util.context.ThreadLocalContext;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.NoResultException;
import jakarta.persistence.TypedQuery;

/**
 * Configuration parameter DAO.
 * 
 * @author jtremeaux
 */
public class ConfigDao {
    /**
     * Gets a configuration parameter by its ID.
     * 
     * @param id Configuration parameter ID
     * @return Configuration parameter
     */
    public Config getById(ConfigType id) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        
        // Prevents from getting parameters outside of a transactional context (e.g. jUnit)
        if (em == null) {
            return null;
        }
        
        try {
            return em.find(Config.class, id);
        } catch (NoResultException e) {
            return null;
        }
    }

    /**
     * Acquires a {@code PESSIMISTIC_WRITE} row lock on a configuration parameter, held for the rest of
     * the caller's transaction (dialect-portable {@code SELECT ... FOR UPDATE} — H2 and PostgreSQL both
     * emit it). Used as a mutual-exclusion guard: clean_storage locks the {@code CLEAN_STORAGE_LOCK}
     * sentinel row so two concurrent runs are serialized (the second blocks here until the first
     * commits), preventing double-count / double-credit of the same file.
     *
     * @param id Configuration parameter ID to lock (must exist as a row)
     * @return true if the row existed and was locked; false if the row is absent (nothing to lock)
     */
    public boolean lockForUpdate(ConfigType id) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        if (em == null) {
            return false;
        }
        TypedQuery<Config> q = em.createQuery("select c from Config c where c.id = :id", Config.class);
        q.setParameter("id", id);
        q.setLockMode(LockModeType.PESSIMISTIC_WRITE);
        try {
            q.getSingleResult();
            return true;
        } catch (NoResultException e) {
            return false;
        }
    }

    /**
     * Updates a configuration parameter.
     *
     * @param id Configuration parameter ID
     * @param value Configuration parameter value
     */
    public void update(ConfigType id, String value) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        Config config = getById(id);
        if (config == null) {
            config = new Config();
            config.setId(id);
            config.setValue(value);
            em.persist(config);
        } else {
            config.setValue(value);
        }
    }
}
