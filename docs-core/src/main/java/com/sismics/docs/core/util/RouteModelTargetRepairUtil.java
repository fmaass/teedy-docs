package com.sismics.docs.core.util;

import com.sismics.docs.core.dao.GroupDao;
import com.sismics.docs.core.model.jpa.Group;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * One-shot startup repair of route-model step blobs that still name a GROUP target by what is now only
 * that group's ID (#312).
 *
 * <p><b>The data state.</b> Step targets live in the {@code RTM_STEPS_C} blob by NAME; the derived
 * {@code T_ROUTE_MODEL_TARGET} index holds the ID. A legacy install's seeded groups carry an id equal to
 * their name ({@code dbupdate-008}: {@code GRP_ID_C='administrators'},
 * {@code GRP_NAME_C='administrators'}), and the seeded route model's blob names that target. Renaming
 * such a group BEFORE the rename-repair existed (v3.3.0, {@code GroupResource.update}) rewrote nothing:
 * the blob kept the old name, which the id column still matched. A reporter's {@code T_GROUP} confirms
 * exactly that — {@code GRP_ID_C='administrators'} with {@code GRP_NAME_C='Administratoren'}.
 *
 * <p><b>What this does.</b> For every ACTIVE group whose name differs from its id, if the id is not the
 * name of any live group, every route-model blob referencing that group and still naming it by the id is
 * rewritten to the group's current name, and the derived index is re-synced. It reuses the rename path's
 * own prepare/apply pair, so it inherits its locking, its fresh re-read under the locks and its column
 * length preflight.
 *
 * <p><b>Guard rails.</b> The rewrite is skipped when the id IS a live group's name — the blob then refers
 * to that group, and stealing the reference would silently re-target a workflow. It is idempotent: a
 * second run finds no blob still carrying the id and writes nothing. It never invents a target for a name
 * that resolves to nothing (that model stays incomplete and keeps failing closed).
 *
 * <p><b>Not a correctness requirement.</b> Since {@link SecurityUtil#getRouteTargetIdFromName} resolves
 * the same legacy shape by id, a drifted model already starts and saves without this repair. The repair
 * exists so the stored data returns to naming principals the way every other blob does — which is why the
 * caller treats a failure here as best-effort and logs it instead of failing startup.
 *
 * @author teedy
 */
public final class RouteModelTargetRepairUtil {
    private static final Logger log = LoggerFactory.getLogger(RouteModelTargetRepairUtil.class);

    private RouteModelTargetRepairUtil() {
        // Utility class
    }

    /**
     * Repairs every route-model step blob still naming a renamed group by its id. Runs in the caller's
     * transaction.
     *
     * @return the number of route models whose blob was rewritten
     */
    public static int repairDriftedGroupTargetNames() {
        GroupDao groupDao = new GroupDao();
        int repairedCount = 0;

        for (Group group : groupDao.findActiveWithNameDifferentFromId()) {
            String staleName = group.getId();

            // A live group genuinely NAMED like this id owns the name: any blob carrying it refers to
            // THAT group, so there is no drift to repair here.
            if (groupDao.getActiveByName(staleName) != null) {
                continue;
            }

            // Group-first lock protocol, ascending id (findActiveWithNameDifferentFromId orders by id):
            // the referencing route-model rows are locked next, inside prepare.
            if (groupDao.getActiveByIdForUpdate(group.getId()) == null) {
                continue;
            }

            RouteModelStepUtil.GroupRenameRepairPlan plan;
            try {
                plan = RouteModelStepUtil.prepareGroupTargetNameRepair(
                        group.getId(), staleName, group.getName());
            } catch (RouteModelStepUtil.RouteModelStepOverflowException e) {
                // The current name is longer than the id, and one blob would outgrow its column. Leave
                // that group's blobs alone — they keep resolving by id — and say which models are stuck.
                log.warn("Cannot repair the step blob of route model(s) {} still naming group {} by its id:"
                                + " the repaired blob would exceed the step size limit",
                        String.join(", ", e.getModelNames()), group.getId());
                continue;
            }
            if (plan.isEmpty()) {
                continue;
            }

            List<String> modelIds = plan.getModelIds();
            List<String> modelNames = plan.getModelNames();
            RouteModelStepUtil.applyGroupRenameRepair(plan);
            for (int i = 0; i < modelIds.size(); i++) {
                log.info("Repaired route model {} (\"{}\"): step target \"{}\" -> \"{}\"",
                        modelIds.get(i), modelNames.get(i), staleName, group.getName());
            }
            repairedCount += modelIds.size();
        }

        return repairedCount;
    }
}
