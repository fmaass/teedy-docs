package com.sismics.docs.core.util;

import com.sismics.docs.core.constant.AclTargetType;
import com.sismics.docs.core.dao.GroupDao;
import com.sismics.docs.core.dao.RouteModelDao;
import com.sismics.docs.core.model.jpa.Group;
import com.sismics.docs.core.model.jpa.RouteModel;
import com.sismics.util.context.ThreadLocalContext;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Concurrency-safe integrity helpers for route-model step targets stored as name-blobs in
 * {@code RTM_STEPS_C}. Two writers can touch these blobs concurrently — a route-model create/update
 * (which references a group by name) and a group rename (which repairs the referencing blobs). They
 * are serialized with a single <strong>group-first pessimistic-lock protocol</strong> so no race can
 * leave a blob naming a group that no longer exists:
 * <ul>
 *   <li>Every {@code RTM_STEPS_C} writer first locks (FOR UPDATE) each distinct GROUP row it
 *       references, in a deterministic (sorted-by-id) order, before it validates group existence or
 *       writes the blob. Deterministic ordering makes the multi-group case deadlock-free.</li>
 *   <li>A group rename locks the (old) group row first, then locks the referencing route-model rows,
 *       re-reads their blobs fresh under the locks, and rewrites the matching GROUP targets.</li>
 * </ul>
 * Because both writers take the GROUP lock before proceeding, they cannot interleave: a route-model
 * write that names group G blocks until an in-flight rename of G commits (then it validates against
 * the now-vanished old name and fails, or the caller re-reads the new name), and a rename of G blocks
 * until an in-flight create/update naming G commits (then it repairs the just-committed blob). The
 * DB lock — held until the request transaction commits via RequestContextFilter — is the guarantee,
 * not JPA entity attachment (ThreadLocalContext flushes/clears the L1 cache on every access).
 *
 * @author teedy
 */
public final class RouteModelStepUtil {
    private RouteModelStepUtil() {
        // Utility class
    }

    /**
     * Parses a route-model step blob and returns the distinct GROUP target names it references, in a
     * deterministic (natural-sorted) order suitable for lock acquisition. USER/SHARE/other targets
     * are ignored — only GROUP targets participate in the group-first lock protocol. Malformed blobs
     * yield an empty set (validation happens elsewhere; this method never throws on bad JSON so it can
     * be used defensively by both writers).
     *
     * @param steps Steps JSON blob
     * @return Sorted distinct GROUP target names (possibly empty, never null)
     */
    public static List<String> parseGroupTargetNames(String steps) {
        Set<String> names = new TreeSet<>();
        if (steps == null || steps.isEmpty()) {
            return new ArrayList<>(names);
        }
        try (JsonReader reader = Json.createReader(new StringReader(steps))) {
            JsonArray stepsJson = reader.readArray();
            for (int i = 0; i < stepsJson.size(); i++) {
                JsonObject step = stepsJson.getJsonObject(i);
                if (!step.containsKey("target")) {
                    continue;
                }
                JsonObject target = step.getJsonObject("target");
                if (target == null || !target.containsKey("type") || !target.containsKey("name")) {
                    continue;
                }
                if (AclTargetType.GROUP.name().equals(target.getString("type"))) {
                    names.add(target.getString("name"));
                }
            }
        } catch (RuntimeException e) {
            return new ArrayList<>();
        }
        return new ArrayList<>(names);
    }

    /**
     * Acquires a pessimistic write lock on each of the given GROUP rows (by name), in the order the
     * names are supplied. Callers must pass a deterministically ordered collection (see
     * {@link #parseGroupTargetNames}) so concurrent writers acquire multiple group locks in the same
     * order and cannot deadlock. A name that does not resolve to a live group is skipped (no row to
     * lock); existence is validated separately by the caller after locking.
     *
     * @param groupNames Deterministically ordered GROUP names to lock
     */
    public static void lockGroupsByName(List<String> groupNames) {
        GroupDao groupDao = new GroupDao();
        for (String groupName : groupNames) {
            groupDao.getActiveByNameForUpdate(groupName);
        }
    }

    /**
     * Rewrites every {@code {type:"GROUP", name:oldName}} step target in a steps blob to
     * {@code newName}, preserving all other content and structure. Returns the original blob unchanged
     * if it contains no matching target.
     *
     * @param steps Steps JSON blob
     * @param oldName Group name to replace
     * @param newName Replacement group name
     * @return Rewritten blob
     */
    public static String rewriteGroupTargetName(String steps, String oldName, String newName) {
        try (JsonReader reader = Json.createReader(new StringReader(steps))) {
            JsonArray stepsJson = reader.readArray();
            JsonArrayBuilder rebuilt = Json.createArrayBuilder();
            for (int i = 0; i < stepsJson.size(); i++) {
                JsonObject step = stepsJson.getJsonObject(i);
                JsonObjectBuilder stepBuilder = Json.createObjectBuilder();
                for (String key : step.keySet()) {
                    if ("target".equals(key)) {
                        JsonObject target = step.getJsonObject("target");
                        if (AclTargetType.GROUP.name().equals(target.getString("type", null))
                                && oldName.equals(target.getString("name", null))) {
                            JsonObjectBuilder targetBuilder = Json.createObjectBuilder();
                            for (String tKey : target.keySet()) {
                                if ("name".equals(tKey)) {
                                    targetBuilder.add("name", newName);
                                } else {
                                    targetBuilder.add(tKey, target.get(tKey));
                                }
                            }
                            stepBuilder.add("target", targetBuilder);
                        } else {
                            stepBuilder.add("target", target);
                        }
                    } else {
                        stepBuilder.add(key, (JsonValue) step.get(key));
                    }
                }
                rebuilt.add(stepBuilder);
            }
            return rebuilt.build().toString();
        }
    }

    /**
     * The maximum length of the {@code RTM_STEPS_C} column. A repaired blob may not exceed it — a
     * longer group name can legitimately push a near-limit blob over the ceiling.
     */
    public static final int STEPS_MAX_LENGTH = 5000;

    /**
     * The prepared (locked, preflighted, not-yet-applied) repair of the route-model blobs referencing
     * a group being renamed. Produced by {@link #prepareGroupRenameRepair} BEFORE the group row is
     * renamed; applied by {@link #applyGroupRenameRepair} AFTER the rename, because applying re-syncs
     * the derived T_ROUTE_MODEL_TARGET index from the repaired blob, whose NEW group name only
     * resolves once the rename is visible to the transaction. The route-model row locks taken during
     * prepare are held for the rest of the transaction, so the planned blobs cannot change between
     * prepare and apply.
     */
    public static class GroupRenameRepairPlan {
        private final List<String> modelIds = new ArrayList<>();
        private final List<String> modelNames = new ArrayList<>();
        private final List<String> repairedBlobs = new ArrayList<>();

        public List<String> getModelNames() {
            return modelNames;
        }

        public boolean isEmpty() {
            return modelIds.isEmpty();
        }
    }

    /**
     * Prepares the repair of every route-model step blob that references a group about to be renamed:
     * the rename half of the group-first lock protocol. The caller has ALREADY locked the (old) group
     * row; this method locks each referencing route-model row (in deterministic id order), re-reads
     * its blob fresh under the lock, computes the repaired blob, and PREFLIGHTS every repaired blob
     * against {@link #STEPS_MAX_LENGTH} — all before ANY mutation, so a single overflow aborts the
     * whole rename with nothing half-written. Returns an empty plan when {@code oldName.equals(newName)}
     * (no repair on a same-name update).
     *
     * <p>Nothing is written here. The caller renames the group row FIRST, then calls
     * {@link #applyGroupRenameRepair}.
     *
     * @param oldName Current group name (still stored in the blobs)
     * @param newName New group name
     * @return The prepared plan (may be empty, never null)
     * @throws RouteModelStepOverflowException if any repaired blob would exceed the column limit
     */
    public static GroupRenameRepairPlan prepareGroupRenameRepair(String oldName, String newName) {
        GroupRenameRepairPlan plan = new GroupRenameRepairPlan();
        if (oldName == null || oldName.equals(newName)) {
            return plan;
        }

        RouteModelDao routeModelDao = new RouteModelDao();
        Group oldGroup = new GroupDao().getActiveByName(oldName);
        if (oldGroup == null) {
            return plan;
        }

        // Referencing model IDs (via the derived target index), sorted for deterministic lock order.
        List<String> modelIds = new ArrayList<>(new LinkedHashSet<>(
                routeModelDao.findModelsReferencingTarget(oldGroup.getId())));
        Collections.sort(modelIds);

        // Lock each model row, re-read its blob fresh, compute the repaired blob, and preflight ALL
        // lengths before anything is mutated.
        List<String> overflowModelNames = new ArrayList<>();
        for (String modelId : modelIds) {
            RouteModel model = routeModelDao.getActiveByIdForUpdate(modelId);
            if (model == null) {
                continue;
            }
            String repaired = rewriteGroupTargetName(model.getSteps(), oldName, newName);
            if (repaired.equals(model.getSteps())) {
                // No GROUP target actually matched (e.g. the index row is stale); nothing to write.
                continue;
            }
            if (repaired.length() > STEPS_MAX_LENGTH) {
                overflowModelNames.add(model.getName());
                continue;
            }
            plan.modelIds.add(model.getId());
            plan.modelNames.add(model.getName());
            plan.repairedBlobs.add(repaired);
        }

        if (!overflowModelNames.isEmpty()) {
            throw new RouteModelStepOverflowException(overflowModelNames);
        }
        return plan;
    }

    /**
     * Applies a prepared group-rename repair: writes each repaired blob back (the row locks from
     * prepare are still held) and re-syncs the derived target index. MUST be called AFTER the group
     * row has been renamed in the same transaction — the index re-sync resolves the repaired blob's
     * NEW group name to its ID, which only resolves once the rename is visible.
     *
     * @param plan Plan from {@link #prepareGroupRenameRepair}
     * @return The names of the route models whose blob was repaired (may be empty, never null)
     */
    public static List<String> applyGroupRenameRepair(GroupRenameRepairPlan plan) {
        RouteModelDao routeModelDao = new RouteModelDao();
        for (int i = 0; i < plan.modelIds.size(); i++) {
            routeModelDao.updateSteps(plan.modelIds.get(i), plan.repairedBlobs.get(i));
        }
        return plan.getModelNames();
    }

    /**
     * Thrown when a group rename would push a repaired route-model step blob over the column length
     * limit. Carries the names of the offending route models so the caller can report them.
     */
    public static class RouteModelStepOverflowException extends RuntimeException {
        private final List<String> modelNames;

        public RouteModelStepOverflowException(List<String> modelNames) {
            super("Renaming this group would exceed the step size limit of route models: "
                    + String.join(", ", modelNames));
            this.modelNames = modelNames;
        }

        public List<String> getModelNames() {
            return modelNames;
        }
    }
}
