package com.sismics.docs.core.dao;

import com.sismics.docs.core.model.jpa.ApiKey;
import com.sismics.util.context.ThreadLocalContext;

import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.Query;
import jakarta.persistence.TypedQuery;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * API key DAO.
 */
public class ApiKeyDao {
    /**
     * Creates a new API key.
     *
     * @param apiKey API key to persist
     * @return Generated ID
     */
    public String create(ApiKey apiKey) {
        apiKey.setId(UUID.randomUUID().toString());
        apiKey.setCreateDate(new Date());
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        em.persist(apiKey);
        return apiKey.getId();
    }

    /**
     * Finds an active API key by its SHA-256 hash.
     *
     * @param keyHash SHA-256 hex hash of the raw token
     * @return API key or null
     */
    public ApiKey getByKeyHash(String keyHash) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        TypedQuery<ApiKey> q = em.createQuery(
                "select k from ApiKey k where k.keyHash = :hash and k.deleteDate is null", ApiKey.class);
        q.setParameter("hash", keyHash);
        try {
            return q.getSingleResult();
        } catch (NoResultException e) {
            return null;
        }
    }

    /**
     * Lists all active API keys for a user.
     *
     * @param userId User ID
     * @return List of API keys
     */
    public List<ApiKey> getByUserId(String userId) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        TypedQuery<ApiKey> q = em.createQuery(
                "select k from ApiKey k where k.userId = :userId and k.deleteDate is null order by k.createDate desc", ApiKey.class);
        q.setParameter("userId", userId);
        return q.getResultList();
    }

    /**
     * Soft-deletes an API key.
     *
     * @param id API key ID
     * @param userId Owner user ID (for authorization)
     * @return true if deleted, false if not found or not owned
     */
    public boolean delete(String id, String userId) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        Query q = em.createQuery(
                "update ApiKey k set k.deleteDate = :now where k.id = :id and k.userId = :userId and k.deleteDate is null");
        q.setParameter("now", new Date());
        q.setParameter("id", id);
        q.setParameter("userId", userId);
        return q.executeUpdate() > 0;
    }

    /**
     * Updates the last-used timestamp on an API key.
     *
     * @param id API key ID
     */
    public void updateLastUsedDate(String id) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        Query q = em.createNativeQuery("update T_API_KEY set APK_LASTUSEDDATE_D = :now where APK_ID_C = :id");
        q.setParameter("now", new Date());
        q.setParameter("id", id);
        q.executeUpdate();
    }
}
