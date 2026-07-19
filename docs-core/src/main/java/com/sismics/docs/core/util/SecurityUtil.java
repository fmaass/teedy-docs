package com.sismics.docs.core.util;

import com.sismics.docs.core.constant.AclTargetType;
import com.sismics.docs.core.dao.GroupDao;
import com.sismics.docs.core.dao.ShareDao;
import com.sismics.docs.core.dao.UserDao;
import com.sismics.docs.core.model.jpa.Group;
import com.sismics.docs.core.model.jpa.User;

import java.util.List;

/**
 * Security utilities.
 *
 * @author bgamard
 */
public class SecurityUtil {
    /**
     * Get an ACL target ID from an object name and type.
     *
     * @param name Object name
     * @param type Object type
     * @return Target ID
     */
    public static String getTargetIdFromName(String name, AclTargetType type) {
        switch (type) {
            case USER:
                UserDao userDao = new UserDao();
                User user = userDao.getActiveByUsername(name);
                return user != null ? user.getId() : null;
            case GROUP:
                GroupDao groupDao = new GroupDao();
                Group group = groupDao.getActiveByName(name);
                return group != null ? group.getId() : null;
        }

        return null;
    }

    /**
     * Return true if the ACL targets provided don't need security checks (administrator users).
     *
     * @param targetIdList Target ID list
     * @return True if skip ACL checks
     */
    public static boolean skipAclCheck(List<String> targetIdList) {
        return targetIdList.contains("admin") || targetIdList.contains("administrators");
    }

    /**
     * Returns true if the given id is a genuine, active share.
     *
     * <p>Used to validate the untrusted {@code ?share=} request parameter before it is trusted as an ACL
     * target: a forged value (a reserved ACL name such as {@code "admin"}, or another principal's id) is
     * not a share, so it must not be added to the caller's ACL target list. Share ids are server-generated
     * random UUIDs, so a reserved name or a principal id can never resolve to a share.</p>
     *
     * @param shareId Candidate share id (may be null)
     * @return true if it resolves to an active share
     */
    public static boolean isActiveShare(String shareId) {
        return shareId != null && new ShareDao().getActiveShare(shareId) != null;
    }
}
