package com.sismics.docs.core.util;

import com.sismics.docs.core.constant.AclType;
import com.sismics.docs.core.constant.PermType;
import com.sismics.docs.core.dao.AclDao;
import com.sismics.docs.core.dao.TagDao;
import com.sismics.docs.core.dao.UserDao;
import com.sismics.docs.core.exception.InactiveOwnerException;
import com.sismics.docs.core.model.jpa.Acl;
import com.sismics.docs.core.model.jpa.Tag;
import com.sismics.docs.core.model.jpa.User;

/**
 * Tag creation utilities.
 *
 * <p>Named {@code TagCreationUtil} rather than {@code TagUtil} because a different {@code TagUtil}
 * already exists at the REST edge ({@code com.sismics.docs.rest.util.TagUtil}); reusing the simple
 * name would invite wrong-import defects.</p>
 */
public class TagCreationUtil {
    /**
     * Create a tag and add the base ACLs, under the owner's row lock.
     *
     * @param tag Tag to create (its owner must already be set to {@code userId})
     * @param userId User creating the tag
     * @return Created tag ID
     * @throws InactiveOwnerException if the owner is not (or is no longer) an active user
     */
    public static String createTag(Tag tag, String userId) {
        // #185 stranded-tag guard: lock the owner's user row FOR UPDATE (eligibility-scoped) before the
        // insert, exactly as DocumentUtil.createDocument does for documents (#111). A user deletion —
        // self-delete or admin reassign-delete — takes the SAME owner-row lock and then snapshots the
        // owner's active tags for reassignment; without this lock a tag created in the race window is
        // missed by that snapshot and survives ACTIVE under a soft-deleted owner. Such a stranded tag is
        // still listed and linkable for an admin caller (SecurityUtil.skipAclCheck drops the ACL join and
        // the owner join carries no delete-date condition), and the next clean_storage orphan-tag purge
        // then strips it off whatever surviving document it was applied to — the #54/#122 tag-loss class.
        // If the owner is no longer active the creation aborts, fail closed.
        //
        // Lock order is USER -> TAG, which conforms to the canonical USER -> DOCUMENT -> TAG order
        // (ADR-0023): this path never requests a document row, so it introduces no cycle.
        User owner = new UserDao().getActiveByIdForUpdate(userId);
        if (owner == null) {
            throw new InactiveOwnerException("Cannot create a tag for an inactive owner: " + userId);
        }

        TagDao tagDao = new TagDao();
        String tagId = tagDao.create(tag, userId);

        // Create read ACL
        AclDao aclDao = new AclDao();
        Acl acl = new Acl();
        acl.setPerm(PermType.READ);
        acl.setType(AclType.USER);
        acl.setSourceId(tagId);
        acl.setTargetId(userId);
        aclDao.create(acl, userId);

        // Create write ACL
        acl = new Acl();
        acl.setPerm(PermType.WRITE);
        acl.setType(AclType.USER);
        acl.setSourceId(tagId);
        acl.setTargetId(userId);
        aclDao.create(acl, userId);

        return tagId;
    }
}
