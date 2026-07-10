package com.sismics.docs.core.util;

import com.sismics.docs.core.constant.AclTargetType;
import com.sismics.docs.core.dao.GroupDao;
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
}
