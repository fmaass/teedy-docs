package com.sismics.docs.core.dao;

import com.sismics.docs.core.model.jpa.Share;
import com.sismics.util.context.ThreadLocalContext;

import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.Query;
import java.util.Date;
import java.util.UUID;

/**
 * Share DAO.
 * 
 * @author bgamard
 */
public class ShareDao {
    /**
     * Creates a new share.
     * 
     * @param share Share
     * @return New ID
     */
    public String create(Share share) {
        // Create the UUID
        share.setId(UUID.randomUUID().toString());
        
        // Create the share
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        share.setCreateDate(new Date());
        em.persist(share);
        
        return share.getId();
    }
    
    /**
     * Returns an active (non-deleted) share by its ID, or {@code null} if there is no such share.
     *
     * <p>Used to validate an untrusted {@code ?share=} request parameter before it is trusted as an ACL
     * target. A share ID is a server-generated random UUID, so a forged value (a reserved ACL name such as
     * {@code "admin"}, or another principal's ID) resolves to {@code null} here and is never added to the
     * caller's ACL target list — closing the share-parameter ACL bypass.</p>
     *
     * @param id Share ID
     * @return The active share, or null if it does not exist or is deleted
     */
    public Share getActiveShare(String id) {
        if (id == null) {
            return null;
        }
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        Query q = em.createQuery("select s from Share s where s.id = :id and s.deleteDate is null");
        q.setParameter("id", id);
        try {
            return (Share) q.getSingleResult();
        } catch (NoResultException e) {
            return null;
        }
    }

    /**
     * Deletes a share.
     *
     * @param id Share ID
     */
    public void delete(String id) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
            
        // Get the share
        Query q = em.createQuery("select s from Share s where s.id = :id and s.deleteDate is null");
        q.setParameter("id", id);
        Share shareDb = (Share) q.getSingleResult();
        
        // Delete the share
        Date dateNow = new Date();
        shareDb.setDeleteDate(dateNow);
        
        // Delete the linked ACL
        q = em.createQuery("update Acl a set a.deleteDate = :dateNow where a.targetId = :targetId and a.deleteDate is null");
        q.setParameter("targetId", id);
        q.setParameter("dateNow", dateNow);
        q.executeUpdate();
    }
}
