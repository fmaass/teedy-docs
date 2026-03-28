package com.sismics.docs.core.model.jpa;

import com.google.common.base.MoreObjects;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.Date;

/**
 * Tag match rule entity. Defines a regex-based rule that automatically applies
 * a tag to documents when the pattern matches the document title, filename, or
 * extracted content.
 *
 * @author fmaass
 */
@Entity
@Table(name = "T_TAG_MATCH_RULE")
public class TagMatchRule {
    @Id
    @Column(name = "TMR_ID_C", length = 36)
    private String id;

    @Column(name = "TMR_IDTAG_C", nullable = false, length = 36)
    private String tagId;

    @Column(name = "TMR_RULETYPE_C", nullable = false, length = 20)
    private String ruleType;

    @Column(name = "TMR_PATTERN_C", nullable = false, length = 2000)
    private String pattern;

    @Column(name = "TMR_ORDER_N", nullable = false)
    private int order;

    @Column(name = "TMR_ENABLED_B", nullable = false)
    private boolean enabled;

    @Column(name = "TMR_CREATEDATE_D", nullable = false)
    private Date createDate;

    @Column(name = "TMR_DELETEDATE_D")
    private Date deleteDate;

    public String getId() {
        return id;
    }

    public TagMatchRule setId(String id) {
        this.id = id;
        return this;
    }

    public String getTagId() {
        return tagId;
    }

    public TagMatchRule setTagId(String tagId) {
        this.tagId = tagId;
        return this;
    }

    public String getRuleType() {
        return ruleType;
    }

    public TagMatchRule setRuleType(String ruleType) {
        this.ruleType = ruleType;
        return this;
    }

    public String getPattern() {
        return pattern;
    }

    public TagMatchRule setPattern(String pattern) {
        this.pattern = pattern;
        return this;
    }

    public int getOrder() {
        return order;
    }

    public TagMatchRule setOrder(int order) {
        this.order = order;
        return this;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public TagMatchRule setEnabled(boolean enabled) {
        this.enabled = enabled;
        return this;
    }

    public Date getCreateDate() {
        return createDate;
    }

    public TagMatchRule setCreateDate(Date createDate) {
        this.createDate = createDate;
        return this;
    }

    public Date getDeleteDate() {
        return deleteDate;
    }

    public TagMatchRule setDeleteDate(Date deleteDate) {
        this.deleteDate = deleteDate;
        return this;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("id", id)
                .add("tagId", tagId)
                .add("ruleType", ruleType)
                .add("pattern", pattern)
                .toString();
    }
}
