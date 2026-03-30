package com.sismics.docs.core.model.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Date;

/**
 * API key entity.
 */
@Entity
@Table(name = "T_API_KEY")
public class ApiKey {
    @Id
    @Column(name = "APK_ID_C", length = 36)
    private String id;

    @Column(name = "APK_IDUSER_C", nullable = false, length = 36)
    private String userId;

    @Column(name = "APK_NAME_C", nullable = false, length = 100)
    private String name;

    @Column(name = "APK_KEYHASH_C", nullable = false, length = 100)
    private String keyHash;

    @Column(name = "APK_PREFIX_C", nullable = false, length = 20)
    private String prefix;

    @Column(name = "APK_CREATEDATE_D", nullable = false)
    private Date createDate;

    @Column(name = "APK_DELETEDATE_D")
    private Date deleteDate;

    @Column(name = "APK_LASTUSEDDATE_D")
    private Date lastUsedDate;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getKeyHash() { return keyHash; }
    public void setKeyHash(String keyHash) { this.keyHash = keyHash; }

    public String getPrefix() { return prefix; }
    public void setPrefix(String prefix) { this.prefix = prefix; }

    public Date getCreateDate() { return createDate; }
    public void setCreateDate(Date createDate) { this.createDate = createDate; }

    public Date getDeleteDate() { return deleteDate; }
    public void setDeleteDate(Date deleteDate) { this.deleteDate = deleteDate; }

    public Date getLastUsedDate() { return lastUsedDate; }
    public void setLastUsedDate(Date lastUsedDate) { this.lastUsedDate = lastUsedDate; }
}
