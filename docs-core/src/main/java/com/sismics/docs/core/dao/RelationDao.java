package com.sismics.docs.core.dao;

import com.sismics.docs.core.dao.dto.RelationDto;
import com.sismics.docs.core.model.jpa.Relation;
import com.sismics.util.context.ThreadLocalContext;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.sql.Timestamp;
import java.util.*;

/**
 * Relation DAO.
 * 
 * @author bgamard
 */
public class RelationDao {
    /**
     * Get all relations from/to a document.
     *
     * <p>The joined document {@code d} is always the OTHER end of the relation — the ON clause matches the
     * from-side only when it is not the queried document and the to-side only when it is not the queried
     * document — so every column selected from it (title, creation date) describes the linked document in
     * BOTH directions, and a self-relation joins nothing at all.</p>
     *
     * @param documentId Document ID
     * @return List of relations
     */
    @SuppressWarnings("unchecked")
    public List<RelationDto> getByDocumentId(String documentId) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        StringBuilder sb = new StringBuilder("select d.DOC_ID_C, d.DOC_TITLE_C, d.DOC_CREATEDATE_D, r.REL_IDDOCFROM_C ");
        sb.append(" from T_RELATION r ");
        sb.append(" join T_DOCUMENT d on d.DOC_ID_C = r.REL_IDDOCFROM_C and r.REL_IDDOCFROM_C != :documentId or d.DOC_ID_C = r.REL_IDDOCTO_C and r.REL_IDDOCTO_C != :documentId ");
        sb.append(" where (r.REL_IDDOCFROM_C = :documentId or r.REL_IDDOCTO_C = :documentId) ");
        sb.append(" and r.REL_DELETEDATE_D is null ");
        sb.append(" order by d.DOC_TITLE_C ");
        
        // Perform the query
        Query q = em.createNativeQuery(sb.toString());
        q.setParameter("documentId", documentId);
        List<Object[]> l = q.getResultList();
        
        // Assemble results
        List<RelationDto> relationDtoList = new ArrayList<>();
        for (Object[] o : l) {
            int i = 0;
            RelationDto relationDto = new RelationDto();
            relationDto.setId((String) o[i++]);
            relationDto.setTitle((String) o[i++]);
            // Nullable in the schema (dbupdate-000-0.sql declares DOC_CREATEDATE_D without `not null`),
            // so a legacy row with no creation date must travel as a null rather than throw here and
            // take the whole document request down.
            Timestamp createDate = (Timestamp) o[i++];
            relationDto.setCreateTimestamp(createDate == null ? null : createDate.getTime());
            String fromDocId = (String) o[i];
            relationDto.setSource(documentId.equals(fromDocId));
            relationDtoList.add(relationDto);
        }
        return relationDtoList;
    }
    
    /**
     * Active relation rows pointing from one document to another, ordered by id.
     *
     * <p>The schema carries no unique constraint on {@code (REL_IDDOCFROM_C, REL_IDDOCTO_C)}
     * (dbupdate-007-0.sql), so several active rows for the same ordered pair are representable and every
     * caller must cope with a list rather than assume at most one.</p>
     *
     * @param fromDocumentId Source document ID
     * @param toDocumentId Destination document ID
     * @return The active rows, oldest id first (a deterministic order across engines)
     */
    public List<Relation> getActiveBetween(String fromDocumentId, String toDocumentId) {
        return getActiveBetween(ThreadLocalContext.get().getEntityManager(), fromDocumentId, toDocumentId);
    }

    /**
     * Same query against an entity manager the caller already holds. Every accessor call on the context
     * flushes AND CLEARS the persistence context, so a caller that needs two result sets to stay managed
     * at once — {@link #swap(String, String)} does — must reuse a single manager rather than fetch a new
     * one per query, or the first result set is silently detached and its mutations are lost.
     *
     * @param em The entity manager to query with
     * @param fromDocumentId Source document ID
     * @param toDocumentId Destination document ID
     * @return The active rows, oldest id first
     */
    private List<Relation> getActiveBetween(EntityManager em, String fromDocumentId, String toDocumentId) {
        return em.createQuery("select r from Relation r where r.fromDocumentId = :fromDocumentId"
                        + " and r.toDocumentId = :toDocumentId and r.deleteDate is null order by r.id", Relation.class)
                .setParameter("fromDocumentId", fromDocumentId)
                .setParameter("toDocumentId", toDocumentId)
                .getResultList();
    }

    /**
     * Reverse the relation between two documents so it reads {@code targetDocumentId -> documentId},
     * collapsing the pair onto a single canonical row.
     *
     * <p>Because the schema permits duplicates and both directions at once, the four representable states
     * are given one canonical outcome each:</p>
     * <ul>
     *   <li><b>forward and reverse both active</b> — the reverse row already expresses the requested
     *       direction, so every forward row is soft-deleted. Running the operation again is a no-op, which
     *       makes a retried or duplicated request harmless.</li>
     *   <li><b>only forward</b> — the oldest forward row is flipped in place and any remaining forward
     *       duplicates are soft-deleted, so the pair ends on exactly one active row.</li>
     *   <li><b>only reverse</b> — nothing is written; the relation already points the requested way.</li>
     *   <li><b>neither</b> — there is nothing to reverse; the caller turns this into a not-found.</li>
     * </ul>
     *
     * <p>The caller MUST already hold the pessimistic write lock on both document rows: the read below and
     * the writes that follow are only atomic under that lock, and without it two opposite-direction swaps
     * can each observe "both directions active" and delete the other's row, leaving the pair unrelated.</p>
     *
     * @param documentId Document the relation should end up pointing AT
     * @param targetDocumentId Document the relation should end up pointing FROM
     * @return True when a relation existed in either direction, false when the two documents are unrelated
     */
    public boolean swap(String documentId, String targetDocumentId) {
        // ONE accessor call: the context's getter flushes and clears, so fetching the second result set
        // through a fresh call would detach the first and drop the writes made below on it.
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        List<Relation> forwardList = getActiveBetween(em, documentId, targetDocumentId);
        List<Relation> reverseList = getActiveBetween(em, targetDocumentId, documentId);

        if (forwardList.isEmpty()) {
            // Only the reverse direction (or nothing at all): already canonical, so write nothing.
            return !reverseList.isEmpty();
        }

        Date deleteDate = new Date();
        if (!reverseList.isEmpty()) {
            // Both directions are active: keep the reverse rows and drop the forward ones rather than
            // flipping one into a duplicate of a row that already exists.
            for (Relation forward : forwardList) {
                forward.setDeleteDate(deleteDate);
            }
            return true;
        }

        // Only the forward direction: flip the oldest row and collapse any duplicates onto it.
        Relation kept = forwardList.get(0);
        kept.setFromDocumentId(targetDocumentId);
        kept.setToDocumentId(documentId);
        for (Relation duplicate : forwardList.subList(1, forwardList.size())) {
            duplicate.setDeleteDate(deleteDate);
        }
        return true;
    }

    /**
     * Update relations on a document.
     *
     * @param documentId Document ID
     * @param documentIdSet Set of document ID
     */
    public void updateRelationList(String documentId, Set<String> documentIdSet) {
        // Take the source document row FOR UPDATE explicitly, before reading its outgoing relations. A
        // concurrent relation swap locks BOTH document rows (in id order) before touching the same rows,
        // so holding this one row for the whole read-then-reconcile below is what serializes the two: the
        // reconcile can never read a row the swap is midway through flipping and then write a stale
        // decision on it. Taking a single row of the swap's ordered pair cannot close a lock cycle
        // against the swap. Both callers reach here already holding this row exclusively — the create
        // path inserted it, the update path wrote it — so this states the requirement rather than adding
        // a new position to the global lock order, and it keeps the guarantee if either caller's
        // preceding write is ever removed or deferred.
        new DocumentDao().getActiveByIdForUpdate(documentId);

        // Fetched AFTER the lock: the accessor clears the persistence context on every call, so the rows
        // read below must come from the LAST call, or they are detached before the reconcile writes them.
        EntityManager em = ThreadLocalContext.get().getEntityManager();

        // Get current relations from this document
        Query q = em.createQuery("select r from Relation r where r.fromDocumentId = :documentId and r.deleteDate is null");
        q.setParameter("documentId", documentId);
        @SuppressWarnings("unchecked")
        List<Relation> relationList = q.getResultList();
        
        // Deleting relations no longer there
        for (Relation relation : relationList) {
            if (!documentIdSet.contains(relation.getToDocumentId())) {
                relation.setDeleteDate(new Date());
            }
        }
        
        // Adding new relations
        for (String targetDocId : documentIdSet) {
            boolean found = false;
            for (Relation relation : relationList) {
                if (relation.getToDocumentId().equals(targetDocId)) {
                    found = true;
                    break;
                }
            }
            
            if (!found) {
                Relation relation = new Relation();
                relation.setId(UUID.randomUUID().toString());
                relation.setFromDocumentId(documentId);
                relation.setToDocumentId(targetDocId);
                em.persist(relation);
            }
        }
    }
}

