package com.sismics.docs.core.dao;

import com.sismics.docs.core.model.jpa.InboxImportReceipt;
import com.sismics.util.context.ThreadLocalContext;

import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.TypedQuery;

import java.util.Date;
import java.util.UUID;

/**
 * Idempotency-receipt DAO for the IMAP inbox import.
 *
 * <p>The import claims a message by inserting its receipt FIRST and forcing the INSERT to the database
 * with a {@code flush()} (claim-first): a concurrent importer that already claimed the same identity
 * makes the unique index reject this INSERT immediately, atomically, before any document or encrypted
 * blob is created. A deferred {@code persist()} without a flush would let the violation surface only at
 * commit, after both importers had already created documents and blobs.</p>
 *
 * <p>On PostgreSQL a unique violation POISONS the surrounding transaction, so callers must let the
 * violation ESCAPE the per-message transaction, roll it back, and confirm the winning receipt in a
 * FRESH transaction via {@link #findByIdentity}. This DAO deliberately does not catch the violation.</p>
 */
public class InboxImportReceiptDao {
    /**
     * Insert and flush a receipt claiming a message identity. The flush surfaces a unique-constraint
     * violation immediately (as a {@code PersistenceException}) so the caller can treat it as an
     * already-claimed signal; it is NOT caught here.
     *
     * @param identityDigest Source identity digest
     * @param uidValidity Captured UIDVALIDITY
     * @param uid Message UID
     * @param account Raw account string (diagnostics)
     * @param folder Raw folder name (diagnostics)
     * @return the managed receipt (its document id is populated later, before commit)
     */
    public InboxImportReceipt claim(String identityDigest, long uidValidity, long uid, String account, String folder) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        InboxImportReceipt receipt = new InboxImportReceipt();
        receipt.setId(UUID.randomUUID().toString());
        receipt.setIdentityDigest(identityDigest);
        receipt.setUidValidity(uidValidity);
        receipt.setUid(uid);
        receipt.setAccount(account);
        receipt.setFolder(folder);
        receipt.setCreateDate(new Date());
        em.persist(receipt);
        // Force the INSERT so a concurrent duplicate is caught now, atomically claiming the message,
        // rather than at the deferred end-of-transaction commit.
        em.flush();
        return receipt;
    }

    /**
     * Link a claimed receipt to the document created for the message, and flush the update within the
     * same transaction. Called after the document exists but before the claim transaction commits.
     *
     * @param receipt Managed receipt returned by {@link #claim}
     * @param documentId Document ID
     */
    public void linkDocument(InboxImportReceipt receipt, String documentId) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        receipt.setDocumentId(documentId);
        em.flush();
    }

    /**
     * Find a committed receipt by its logical identity. Used in a FRESH transaction, after a claim
     * violation, to confirm a winning receipt exists before treating a message as an already-imported
     * duplicate (never ack on an unrelated integrity error).
     *
     * @param identityDigest Source identity digest
     * @param uidValidity Captured UIDVALIDITY
     * @param uid Message UID
     * @return the receipt, or null if none exists
     */
    public InboxImportReceipt findByIdentity(String identityDigest, long uidValidity, long uid) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        TypedQuery<InboxImportReceipt> q = em.createQuery(
                "select r from InboxImportReceipt r where r.identityDigest = :identityDigest"
                        + " and r.uidValidity = :uidValidity and r.uid = :uid", InboxImportReceipt.class);
        q.setParameter("identityDigest", identityDigest);
        q.setParameter("uidValidity", uidValidity);
        q.setParameter("uid", uid);
        try {
            return q.getSingleResult();
        } catch (NoResultException e) {
            return null;
        }
    }
}
