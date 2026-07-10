package com.sismics.docs.core.model.jpa;

import com.google.common.base.MoreObjects;
import com.sismics.docs.core.constant.RouteStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Date;

/**
 * Route.
 *
 * @author bgamard
 */
@Entity
@Table(name = "T_ROUTE")
public class Route implements Loggable {
    /**
     * Route ID.
     */
    @Id
    @Column(name = "RTE_ID_C", length = 36)
    private String id;

    /**
     * Document ID.
     */
    @Column(name = "RTE_IDDOCUMENT_C", nullable = false, length = 36)
    private String documentId;

    /**
     * Name.
     */
    @Column(name = "RTE_NAME_C", nullable = false, length = 50)
    private String name;

    /**
     * Status.
     */
    @Column(name = "RTE_STATUS_C", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private RouteStatus status;

    /**
     * Initiator user ID.
     */
    @Column(name = "RTE_IDUSER_C", nullable = false, length = 36)
    private String userId;

    /**
     * Creation date.
     */
    @Column(name = "RTE_CREATEDATE_D", nullable = false)
    private Date createDate;

    /**
     * End date (route reached a terminal status).
     */
    @Column(name = "RTE_ENDDATE_D")
    private Date endDate;

    /**
     * Deletion date.
     */
    @Column(name = "RTE_DELETEDATE_D")
    private Date deleteDate;

    public String getId() {
        return id;
    }

    public Route setId(String id) {
        this.id = id;
        return this;
    }

    public String getDocumentId() {
        return documentId;
    }

    public Route setDocumentId(String documentId) {
        this.documentId = documentId;
        return this;
    }

    public String getName() {
        return name;
    }

    public Route setName(String name) {
        this.name = name;
        return this;
    }

    public RouteStatus getStatus() {
        return status;
    }

    public Route setStatus(RouteStatus status) {
        this.status = status;
        return this;
    }

    public String getUserId() {
        return userId;
    }

    public Route setUserId(String userId) {
        this.userId = userId;
        return this;
    }

    public Date getCreateDate() {
        return createDate;
    }

    public Route setCreateDate(Date createDate) {
        this.createDate = createDate;
        return this;
    }

    public Date getEndDate() {
        return endDate;
    }

    public Route setEndDate(Date endDate) {
        this.endDate = endDate;
        return this;
    }

    public Date getDeleteDate() {
        return deleteDate;
    }

    public Route setDeleteDate(Date deleteDate) {
        this.deleteDate = deleteDate;
        return this;
    }

    @Override
    public String toMessage() {
        return documentId;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("id", id)
                .add("name", name)
                .add("documentId", documentId)
                .add("status", status)
                .add("createDate", createDate)
                .toString();
    }
}
