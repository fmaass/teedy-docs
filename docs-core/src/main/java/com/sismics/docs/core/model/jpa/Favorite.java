package com.sismics.docs.core.model.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Date;

/**
 * A per-user star on a document. Favorites are private: never shared with, nor
 * visible to, any other user (including admins). The (user, document) pair is
 * unique; a star is a hard-deletable membership row, not a soft-deleted record.
 */
@Entity
@Table(name = "T_FAVORITE")
public class Favorite {
    @Id
    @Column(name = "FAV_ID_C", length = 36)
    private String id;

    @Column(name = "FAV_IDUSER_C", nullable = false, length = 36)
    private String userId;

    @Column(name = "FAV_IDDOCUMENT_C", nullable = false, length = 36)
    private String documentId;

    @Column(name = "FAV_CREATEDATE_D", nullable = false)
    private Date createDate;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getDocumentId() { return documentId; }
    public void setDocumentId(String documentId) { this.documentId = documentId; }

    public Date getCreateDate() { return createDate; }
    public void setCreateDate(Date createDate) { this.createDate = createDate; }
}
