package com.sismics.docs.core.model.jpa;

import java.util.Date;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.google.common.base.MoreObjects;

/**
 * One icon in the instance's custom tag-icon set (#287).
 *
 * <p>This is METADATA only: the image bytes live in the file store under
 * {@code docs.home/tagicon/<id>}, which is where this application has always put images (the
 * theme logo/background/favicon in {@code docs.home/theme}, document files in
 * {@code docs.home/storage}). Nothing in the schema is a binary column and an icon is not the
 * place to make the first one.</p>
 *
 * <p>There is exactly ONE set — the reporter asked for one to start with ("i think one custom
 * icon set should be enough for the start") — so there is no set id here: every row IS the set.
 * An icon is reusable across any number of tags, which was the point of a set rather than a
 * per-tag upload.</p>
 *
 * @author fmaass
 */
@Entity
@Table(name = "T_TAG_ICON")
public class TagIcon {
    /**
     * The prefix a tag's {@code TAG_ICON_C} carries when it names an icon from this set. Defined
     * here, beside the rows it points at, so the DAO that clears stale references and the REST
     * layer that writes them cannot disagree about its spelling.
     */
    public static final String SET_PREFIX = "set:";

    /**
     * The prefix a tag's {@code TAG_ICON_C} carries when it holds an emoji instead. No row of this
     * table is involved — it is the other half of the same discriminated column.
     */
    public static final String EMOJI_PREFIX = "emoji:";

    /**
     * The value stored on a tag that uses the given icon.
     *
     * @param id Icon ID
     * @return Stored icon reference
     */
    public static String setReference(String id) {
        return SET_PREFIX + id;
    }

    /**
     * Icon ID. Also the file name in the icon store, so the row and its bytes cannot drift apart.
     */
    @Id
    @Column(name = "TIC_ID_C", length = 36)
    private String id;

    /**
     * The name shown beside the icon in the picker.
     */
    @Column(name = "TIC_NAME_C", nullable = false, length = 50)
    private String name;

    /**
     * The image's media type, decided by SNIFFING the uploaded bytes rather than by trusting the
     * client's declared type, and replayed as the Content-Type when the icon is served.
     */
    @Column(name = "TIC_MIMETYPE_C", nullable = false, length = 100)
    private String mimeType;

    /**
     * The uploading administrator. A plain value and NOT a foreign key: tags all over the instance
     * point at this icon, so it has to outlive that account's deletion.
     */
    @Column(name = "TIC_IDUSER_C", nullable = false, length = 36)
    private String userId;

    /**
     * Upload date.
     */
    @Column(name = "TIC_CREATEDATE_D", nullable = false)
    private Date createDate;

    /**
     * Deletion date.
     */
    @Column(name = "TIC_DELETEDATE_D")
    private Date deleteDate;

    public String getId() {
        return id;
    }

    public TagIcon setId(String id) {
        this.id = id;
        return this;
    }

    public String getName() {
        return name;
    }

    public TagIcon setName(String name) {
        this.name = name;
        return this;
    }

    public String getMimeType() {
        return mimeType;
    }

    public TagIcon setMimeType(String mimeType) {
        this.mimeType = mimeType;
        return this;
    }

    public String getUserId() {
        return userId;
    }

    public TagIcon setUserId(String userId) {
        this.userId = userId;
        return this;
    }

    public Date getCreateDate() {
        return createDate;
    }

    public TagIcon setCreateDate(Date createDate) {
        this.createDate = createDate;
        return this;
    }

    public Date getDeleteDate() {
        return deleteDate;
    }

    public TagIcon setDeleteDate(Date deleteDate) {
        this.deleteDate = deleteDate;
        return this;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("id", id)
                .add("name", name)
                .add("mimeType", mimeType)
                .toString();
    }
}
