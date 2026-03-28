package com.sismics.docs.core.dao;

import com.sismics.docs.core.model.jpa.TagMatchRule;
import com.sismics.util.context.ThreadLocalContext;

import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.Query;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Tag match rule DAO.
 *
 * @author fmaass
 */
public class TagMatchRuleDao {
    /**
     * Creates a new tag match rule.
     *
     * @param rule Tag match rule
     * @return Created rule ID
     */
    public String create(TagMatchRule rule) {
        rule.setId(UUID.randomUUID().toString());
        rule.setCreateDate(new Date());

        EntityManager em = ThreadLocalContext.get().getEntityManager();
        em.persist(rule);

        return rule.getId();
    }

    /**
     * Updates a tag match rule.
     *
     * @param rule Tag match rule with updated fields
     * @return Updated rule
     */
    public TagMatchRule update(TagMatchRule rule) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        Query q = em.createQuery("select r from TagMatchRule r where r.id = :id and r.deleteDate is null");
        q.setParameter("id", rule.getId());
        TagMatchRule ruleDb = (TagMatchRule) q.getSingleResult();

        ruleDb.setTagId(rule.getTagId());
        ruleDb.setRuleType(rule.getRuleType());
        ruleDb.setPattern(rule.getPattern());
        ruleDb.setOrder(rule.getOrder());
        ruleDb.setEnabled(rule.isEnabled());

        return ruleDb;
    }

    /**
     * Soft-deletes a tag match rule.
     *
     * @param id Rule ID
     */
    public void delete(String id) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        Query q = em.createQuery("select r from TagMatchRule r where r.id = :id and r.deleteDate is null");
        q.setParameter("id", id);
        TagMatchRule ruleDb = (TagMatchRule) q.getSingleResult();
        ruleDb.setDeleteDate(new Date());
    }

    /**
     * Gets an active rule by ID.
     *
     * @param id Rule ID
     * @return Tag match rule or null
     */
    public TagMatchRule getActiveById(String id) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        try {
            Query q = em.createQuery("select r from TagMatchRule r where r.id = :id and r.deleteDate is null");
            q.setParameter("id", id);
            return (TagMatchRule) q.getSingleResult();
        } catch (NoResultException e) {
            return null;
        }
    }

    /**
     * Returns all active rules, ordered by execution order.
     *
     * @return List of enabled tag match rules
     */
    @SuppressWarnings("unchecked")
    public List<TagMatchRule> findAllEnabled() {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        Query q = em.createQuery("select r from TagMatchRule r where r.enabled = true and r.deleteDate is null order by r.order asc");
        return q.getResultList();
    }

    /**
     * Returns all active rules.
     *
     * @return List of all tag match rules
     */
    @SuppressWarnings("unchecked")
    public List<TagMatchRule> findAll() {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        Query q = em.createQuery("select r from TagMatchRule r where r.deleteDate is null order by r.order asc");
        return q.getResultList();
    }
}
