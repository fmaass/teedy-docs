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
     * <p>Strictly by NAME, with no id fallback: this resolver serves the ACL surface, where the name is
     * user-supplied request input ({@code AclResource.add}). Accepting an id there would let a caller
     * name a principal by a key the picker never offers. Route-model step targets, whose names are
     * stored data rather than request input, use {@link #getRouteTargetIdFromName} instead.</p>
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
     * Resolve a ROUTE-MODEL step target name to a principal id: by name first, then — for a GROUP — by
     * id. This is the single resolver every route-model path uses (the offered/incomplete flag, the
     * route start, the model create/update gate and the derived target index), so all of them agree on
     * what a stored blob resolves to: a model is offered if and only if it can actually be started.
     *
     * <p><b>Why an id fallback (#312).</b> Step targets are stored in the {@code RTM_STEPS_C} blob by
     * NAME, while the derived {@code T_ROUTE_MODEL_TARGET} index holds the ID. On a legacy install the
     * seeded principals carry an id that EQUALS their name ({@code dbupdate-008}:
     * {@code GRP_ID_C='administrators'}, {@code GRP_NAME_C='administrators'}), so a rename performed
     * before the rename-repair existed (v3.3.0, {@code GroupResource.update}) left the blob naming what
     * is now only the group's id — the model kept being offered (the id still resolves) while every
     * start failed 400 InvalidRouteModel. Falling back to the id resolves exactly that legacy shape.</p>
     *
     * <p>Name wins over id, deliberately: if a live group is genuinely NAMED like another group's id,
     * the blob refers to that group, and the fallback must not steal the reference. A target that
     * resolves neither way still resolves to {@code null} — an unresolvable target keeps failing
     * closed, it is not "repaired" into some other principal.</p>
     *
     * <p>USER targets have no fallback: usernames are immutable (there is no rename endpoint), so a
     * USER target can never drift away from its stored name.</p>
     *
     * @param name Step target name as stored in the route model blob
     * @param type Step target type
     * @return The resolved principal ID, or null if the target resolves to no active principal
     */
    public static String getRouteTargetIdFromName(String name, AclTargetType type) {
        String targetId = getTargetIdFromName(name, type);
        if (targetId != null) {
            return targetId;
        }
        if (type == AclTargetType.GROUP) {
            Group group = new GroupDao().getActiveById(name);
            if (group != null) {
                return group.getId();
            }
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
