package com.sismics.docs.core.dao;

import com.sismics.docs.BaseTransactionalTest;
import com.sismics.docs.core.constant.AclType;
import com.sismics.docs.core.constant.PermType;
import com.sismics.docs.core.model.jpa.Acl;
import com.sismics.docs.core.model.jpa.Group;
import com.sismics.docs.core.model.jpa.Tag;
import com.sismics.docs.core.model.jpa.User;
import com.sismics.util.context.ThreadLocalContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.UUID;

/**
 * #135: {@link AclDao#countAcls} — the distinct-holder count that backs the #88 last-WRITE guard — must
 * ignore a holder whose target is a SOFT-DELETED user account (an inert orphan ACL left by a grant that
 * raced a concurrent user soft-delete), yet must count a GROUP holder UNCHANGED.
 *
 * <p>A grant to a group is stored as a {@code USER}-type ACL (see {@code AclResource}) whose target id is a
 * {@code T_GROUP} id — groups are NOT a distinct {@code AclType}. So the orphan filter cannot key off
 * {@code ACL_TYPE_C}; it keys off the target being a soft-deleted {@code T_USER} row. A group target has no
 * {@code T_USER} row and must stay counted — this test proves the filter does not zero group permissions.</p>
 *
 * Single-transaction DAO tests (H2 + PostgreSQL) on the shared transactional base.
 */
public class TestAclCountActiveUser extends BaseTransactionalTest {

    private String createTag(String ownerId) {
        Tag tag = new Tag();
        tag.setName("t" + UUID.randomUUID().toString().substring(0, 8));
        tag.setColor("#ff0000");
        tag.setUserId(ownerId);
        return new TagDao().create(tag, ownerId);
    }

    /** Grants a permission the way the app does — always a USER-type ACL, whatever the target kind. */
    private void grant(String sourceId, PermType perm, String targetId) {
        Acl acl = new Acl();
        acl.setSourceId(sourceId);
        acl.setPerm(perm);
        acl.setType(AclType.USER);
        acl.setTargetId(targetId);
        new AclDao().create(acl, targetId);
    }

    private void softDeleteUser(String userId) {
        ThreadLocalContext.get().getEntityManager()
                .createNativeQuery("update T_USER set USE_DELETEDATE_D = :now where USE_ID_C = :id")
                .setParameter("now", new Date())
                .setParameter("id", userId)
                .executeUpdate();
    }

    private void flush() {
        ThreadLocalContext.get().getEntityManager().flush();
    }

    @Test
    public void countAcls_excludesSoftDeletedUserTargetButCountsActiveOne() throws Exception {
        User active = createUser("cau_active");
        User gone = createUser("cau_gone");
        String tagId = createTag(active.getId());
        grant(tagId, PermType.WRITE, active.getId());
        grant(tagId, PermType.WRITE, gone.getId());
        softDeleteUser(gone.getId());
        flush();

        Assertions.assertEquals(1L, new AclDao().countAcls(tagId, PermType.WRITE, AclType.USER),
                "only the active-user holder counts; the soft-deleted target is an inert orphan ACL and must"
                        + " not be counted (without the filter the count would be 2)");
    }

    @Test
    public void countAcls_countsGroupTargetUnchanged() throws Exception {
        User owner = createUser("cau_group_owner");
        String tagId = createTag(owner.getId());
        // A real group holder: a USER-type ACL whose target is a T_GROUP id (not a T_USER row). The orphan
        // filter must leave it counted — if it keyed off ACL_TYPE_C or required a T_USER row, this would be
        // wrongly zeroed, breaking group-permission counting.
        Group group = new Group();
        group.setName("g" + UUID.randomUUID().toString().substring(0, 8));
        String groupId = new GroupDao().create(group, owner.getId());
        grant(tagId, PermType.WRITE, groupId);
        flush();

        Assertions.assertEquals(1L, new AclDao().countAcls(tagId, PermType.WRITE, AclType.USER),
                "a group WRITE holder is counted unchanged even though its target is not in T_USER — the"
                        + " orphan filter drops only soft-deleted-user targets and must not zero group permissions");
    }
}
