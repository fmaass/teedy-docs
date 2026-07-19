package com.sismics.docs.core.model.jpa;

import com.google.common.base.MoreObjects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.Date;

/**
 * Idempotency receipt for an imported IMAP inbox message.
 *
 * <p>One row records that a specific message has been durably imported. The logical identity of a
 * message is (account, folder, UIDVALIDITY, UID); the physical uniqueness that makes an import
 * exactly-once is the {@code (identity digest, UIDVALIDITY, UID)} triple. The digest folds the raw
 * account (host + port + protocol + username) and folder into one lowercase-hex value so the unique
 * index behaves identically on H2 (which case-folds raw string columns under {@code SET IGNORECASE})
 * and PostgreSQL. The raw account/folder columns are kept for diagnostics only and are NOT part of
 * the unique index.</p>
 *
 * <p>The receipt is inserted BEFORE the document exists (claim-first), so its document id is
 * schema-nullable and populated before the same transaction commits. It is never cascade-deleted with
 * the document: purging the imported document nulls the link (the FK is {@code on delete set null})
 * but keeps the receipt, so the message is never re-imported.</p>
 */
@Entity
@Table(name = "T_INBOX_RECEIPT")
public class InboxImportReceipt {
    /**
     * Receipt ID.
     */
    @Id
    @Column(name = "INR_ID_C", length = 36)
    private String id;

    /**
     * Lowercase-hex digest of the length-prefixed (host, port, protocol, username, folder) bytes.
     */
    @Column(name = "INR_IDENTITY_C", nullable = false, length = 64)
    private String identityDigest;

    /**
     * Captured folder UIDVALIDITY (IMAP 32-bit unsigned, stored as a 64-bit signed column).
     */
    @Column(name = "INR_UIDVALIDITY_N", nullable = false)
    private Long uidValidity;

    /**
     * Message UID within the UIDVALIDITY epoch.
     */
    @Column(name = "INR_UID_N", nullable = false)
    private Long uid;

    /**
     * Raw account identity (host + port + protocol + username), for diagnostics only.
     */
    @Column(name = "INR_ACCOUNT_C", length = 500)
    private String account;

    /**
     * Raw folder name, for diagnostics only.
     */
    @Column(name = "INR_FOLDER_C", length = 500)
    private String folder;

    /**
     * ID of the document created for this message. Null until the claim's transaction populates it;
     * nulled (never cascade-deleted) if the document is later purged.
     */
    @Column(name = "INR_IDDOCUMENT_C", length = 36)
    private String documentId;

    /**
     * Creation date.
     */
    @Column(name = "INR_CREATEDATE_D", nullable = false)
    private Date createDate;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getIdentityDigest() {
        return identityDigest;
    }

    public void setIdentityDigest(String identityDigest) {
        this.identityDigest = identityDigest;
    }

    public Long getUidValidity() {
        return uidValidity;
    }

    public void setUidValidity(Long uidValidity) {
        this.uidValidity = uidValidity;
    }

    public Long getUid() {
        return uid;
    }

    public void setUid(Long uid) {
        this.uid = uid;
    }

    public String getAccount() {
        return account;
    }

    public void setAccount(String account) {
        this.account = account;
    }

    public String getFolder() {
        return folder;
    }

    public void setFolder(String folder) {
        this.folder = folder;
    }

    public String getDocumentId() {
        return documentId;
    }

    public void setDocumentId(String documentId) {
        this.documentId = documentId;
    }

    public Date getCreateDate() {
        return createDate;
    }

    public void setCreateDate(Date createDate) {
        this.createDate = createDate;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("id", id)
                .add("identityDigest", identityDigest)
                .add("uidValidity", uidValidity)
                .add("uid", uid)
                .toString();
    }
}
