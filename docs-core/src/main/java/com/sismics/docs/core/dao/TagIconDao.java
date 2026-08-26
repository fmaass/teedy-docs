package com.sismics.docs.core.dao;

import com.sismics.docs.core.model.jpa.TagIcon;
import com.sismics.util.context.ThreadLocalContext;

import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.Query;
import jakarta.persistence.TypedQuery;
import java.util.Date;
import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * Tag icon DAO (#287).
 *
 * @author fmaass
 */
public class TagIconDao {
    /**
     * Creates a new icon.
     *
     * @param tagIcon Icon to create
     * @return Created icon ID
     */
    public String create(TagIcon tagIcon) {
        tagIcon.setId(UUID.randomUUID().toString());
        tagIcon.setCreateDate(new Date());

        EntityManager em = ThreadLocalContext.get().getEntityManager();
        em.persist(tagIcon);

        return tagIcon.getId();
    }

    /**
     * Returns an icon that still exists, or null.
     *
     * @param id Icon ID
     * @return Icon, or null when unknown or deleted
     */
    public TagIcon getActiveById(String id) {
        if (id == null) {
            return null;
        }
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        TypedQuery<TagIcon> q = em.createQuery(
                "select i from TagIcon i where i.id = :id and i.deleteDate is null", TagIcon.class);
        q.setParameter("id", id);
        try {
            return q.getSingleResult();
        } catch (NoResultException e) {
            return null;
        }
    }

    /**
     * Returns the whole set, oldest first. There is one shared set of a few tens of icons, so this
     * is deliberately unpaged and unfiltered — the picker shows all of it.
     *
     * @return All icons that still exist
     */
    public List<TagIcon> findAll() {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        TypedQuery<TagIcon> q = em.createQuery(
                "select i from TagIcon i where i.deleteDate is null order by i.createDate asc, i.id asc",
                TagIcon.class);
        return q.getResultList();
    }

    /**
     * Soft-deletes an icon AND clears it off every tag still pointing at it.
     *
     * <p>The two happen together on purpose. A tag holding a reference to an icon that no longer
     * exists would render as a broken image on every document that carries the tag, and there is
     * no later moment at which anything would notice: the reference is a plain string, not a
     * foreign key (it has to be, because the same column also holds emoji). Clearing it here is
     * what makes "the icon is gone" mean "those tags simply have no icon" — which is the same
     * state they were in before anyone chose one.</p>
     *
     * <p>Both statements run in the caller's transaction, so a failure between them rolls the
     * deletion back rather than stranding the references.</p>
     *
     * <p>IDEMPOTENT: an icon that is already gone is reported as absent rather than thrown at the
     * caller. Two administrators deleting the same icon is a race the client cannot avoid, and the
     * losing request has nothing to report but "it is not there" — which is the same answer the
     * winner's request produced.</p>
     *
     * @param id Icon ID
     * @return Number of tags whose icon was cleared, or empty when no live icon has that ID
     */
    public OptionalInt delete(String id) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();

        TypedQuery<TagIcon> q = em.createQuery(
                "select i from TagIcon i where i.id = :id and i.deleteDate is null", TagIcon.class);
        q.setParameter("id", id);
        List<TagIcon> found = q.getResultList();
        if (found.isEmpty()) {
            return OptionalInt.empty();
        }
        found.get(0).setDeleteDate(new Date());

        // NO `and t.deleteDate is null` here, deliberately. A soft-deleted tag is not gone: the
        // trash restores documents and the tag links that go with them, so a reference left on one
        // would come back pointing at an icon that no longer exists — the exact dangling state this
        // clear exists to prevent, surfacing later with nothing left to explain it.
        Query clear = em.createQuery("update Tag t set t.icon = null where t.icon = :reference");
        clear.setParameter("reference", TagIcon.setReference(id));
        return OptionalInt.of(clear.executeUpdate());
    }
}
