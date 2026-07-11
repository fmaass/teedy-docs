package com.sismics.docs.rest.resource;

import com.google.common.collect.Lists;
import com.sismics.docs.core.constant.*;
import com.sismics.docs.core.dao.AclDao;
import com.sismics.docs.core.dao.GroupDao;
import com.sismics.docs.core.dao.RouteModelDao;
import com.sismics.docs.core.dao.UserDao;
import com.sismics.docs.core.dao.criteria.RouteModelCriteria;
import com.sismics.docs.core.dao.dto.RouteModelDto;
import com.sismics.docs.core.model.jpa.Acl;
import com.sismics.docs.core.model.jpa.Group;
import com.sismics.docs.core.model.jpa.RouteModel;
import com.sismics.docs.core.model.jpa.User;
import com.sismics.docs.core.util.ActionUtil;
import com.sismics.docs.core.util.RouteModelStepUtil;
import com.sismics.docs.core.util.jpa.SortCriteria;
import com.sismics.docs.rest.constant.BaseFunction;
import com.sismics.rest.exception.ClientException;
import com.sismics.rest.exception.ForbiddenClientException;
import com.sismics.rest.util.AclUtil;
import com.sismics.rest.util.ValidationUtil;

import jakarta.json.*;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import java.io.StringReader;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Route model REST resources.
 * 
 * @author bgamard
 */
@Path("/routemodel")
public class RouteModelResource extends BaseResource {
    /**
     * Returns the list of all route models.
     *
     * @api {get} /routemodel Get route models
     * @apiName GetRouteModel
     * @apiGroup RouteModel
     * @apiParam {Number} sort_column Column index to sort on
     * @apiParam {Boolean} asc If true, sort in ascending order
     * @apiSuccess {Object[]} routemodels List of route models
     * @apiSuccess {String} routemodels.id ID
     * @apiSuccess {String} routemodels.name Name
     * @apiSuccess {Number} routemodels.create_date Create date (timestamp)
     * @apiError (client) ForbiddenError Access denied
     * @apiPermission user
     * @apiVersion 1.5.0
     *
     * @return Response
     */
    @GET
    public Response list(
            @QueryParam("sort_column") Integer sortColumn,
            @QueryParam("asc") Boolean asc) {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }

        JsonArrayBuilder routeModels = Json.createArrayBuilder();
        SortCriteria sortCriteria = new SortCriteria(sortColumn, asc);

        RouteModelDao routeModelDao = new RouteModelDao();
        List<RouteModelDto> routeModelDtoList = routeModelDao.findByCriteria(new RouteModelCriteria().setTargetIdList(getTargetIdList(null)), sortCriteria);

        // Per-model "incomplete" flag, derived at read time: a model is incomplete when any of its
        // step targets no longer resolves to an active user/group (e.g. the target was deleted).
        Set<String> incompleteModelIdSet = new HashSet<>(routeModelDao.findIncompleteModelIds());

        for (RouteModelDto routeModelDto : routeModelDtoList) {
            routeModels.add(Json.createObjectBuilder()
                    .add("id", routeModelDto.getId())
                    .add("name", routeModelDto.getName())
                    .add("create_date", routeModelDto.getCreateTimestamp())
                    .add("incomplete", incompleteModelIdSet.contains(routeModelDto.getId())));
        }

        JsonObjectBuilder response = Json.createObjectBuilder()
                .add("routemodels", routeModels);
        return Response.ok().entity(response.build()).build();
    }

    /**
     * Add a route model.
     *
     * @api {put} /routemodel Add a route model
     * @apiName PutRouteModel
     * @apiGroup RouteModel
     * @apiParam {String} name Route model name
     * @apiParam {String} steps Steps data in JSON
     * @apiSuccess {String} id Route model ID
     * @apiError (client) ForbiddenError Access denied
     * @apiError (client) ValidationError Validation error
     * @apiPermission admin
     * @apiVersion 1.5.0
     *
     * @return Response
     */
    @PUT
    public Response add(@FormParam("name") String name, @FormParam("steps") String steps) {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }
        checkBaseFunction(BaseFunction.ADMIN);

        // Validate input
        name = ValidationUtil.validateLength(name, "name", 1, 50, false);
        steps = ValidationUtil.validateLength(steps, "steps", 1, 5000, false);

        // Shared integrity gate (validate -> lock groups -> re-validate under locks)
        validateAndLockForWrite(steps, null);

        // Create the route model
        RouteModelDao routeModelDao = new RouteModelDao();
        String id = routeModelDao.create(new RouteModel()
                .setName(name)
                .setSteps(steps), principal.getId());

        // Create read ACL
        AclDao aclDao = new AclDao();
        Acl acl = new Acl();
        acl.setPerm(PermType.READ);
        acl.setType(AclType.USER);
        acl.setSourceId(id);
        acl.setTargetId(principal.getId());
        aclDao.create(acl, principal.getId());

        // Create write ACL
        acl = new Acl();
        acl.setPerm(PermType.WRITE);
        acl.setType(AclType.USER);
        acl.setSourceId(id);
        acl.setTargetId(principal.getId());
        aclDao.create(acl, principal.getId());

        // Always return OK
        JsonObjectBuilder response = Json.createObjectBuilder()
                .add("id", id);
        return Response.ok().entity(response.build()).build();
    }

    /**
     * Shared step-target integrity gate for BOTH route-model writers (create and update): validate
     * the blob (rejecting malformed content and unsupported target types BEFORE contending on any
     * lock), acquire the group-first locks — every referenced GROUP row in deterministic order, then
     * (update only) the route-model row — and RE-validate under the held locks, so a concurrent
     * group rename can no longer slip between validation and the caller's write and orphan the name
     * about to be persisted. Every write path MUST route through this method; it is the single
     * resource-layer serialization point against GroupResource's rename repair.
     *
     * @param steps Steps JSON blob about to be written
     * @param routeModelId Route model ID to lock for update, or null for a create
     * @return The locked route model (update), or null (create)
     */
    private RouteModel validateAndLockForWrite(String steps, String routeModelId) {
        // Reject malformed blobs and unsupported target types (SHARE, unknown) BEFORE taking any
        // lock — a doomed request should not contend on group rows.
        validateRouteModelSteps(steps);

        // Group-first lock protocol: lock every referenced GROUP row (deterministic order), then the
        // target route-model row when updating.
        RouteModelStepUtil.lockGroupsByName(RouteModelStepUtil.parseGroupTargetNames(steps));
        RouteModel routeModel = null;
        if (routeModelId != null) {
            routeModel = new RouteModelDao().getActiveByIdForUpdate(routeModelId);
            if (routeModel == null) {
                throw new NotFoundException();
            }
        }

        // RE-validate step targets under the held locks: a group renamed before we locked it now
        // fails existence here rather than being written back as an orphaned name.
        validateRouteModelSteps(steps);
        return routeModel;
    }

    /**
     * Validate route model steps.
     *
     * @param steps Route model steps data
     */
    private void validateRouteModelSteps(String steps) {
        UserDao userDao = new UserDao();
        GroupDao groupDao = new GroupDao();

        try (JsonReader reader = Json.createReader(new StringReader(steps))) {
            JsonArray stepsJson = reader.readArray();
            if (stepsJson.isEmpty()) {
                throw new ClientException("ValidationError", "At least one step is required");
            }
            for (int i = 0; i < stepsJson.size(); i++) {
                JsonObject step = stepsJson.getJsonObject(i);
                if (step.size() != 4) {
                    throw new ClientException("ValidationError", "Steps data not valid");
                }

                // Name
                ValidationUtil.validateLength(step.getString("name"), "step.name", 1, 200, false);

                // Type
                String typeStr = step.getString("type");
                RouteStepType type;
                try {
                    type = RouteStepType.valueOf(typeStr);
                } catch (IllegalArgumentException e) {
                    throw new ClientException("ValidationError", typeStr + "is not a valid route step type");
                }

                // Target
                JsonObject target = step.getJsonObject("target");
                if (target.size() != 2) {
                    throw new ClientException("ValidationError", "Step target is not valid");
                }
                AclTargetType targetType;
                String targetTypeStr = target.getString("type");
                String targetName = target.getString("name");
                ValidationUtil.validateRequired(targetName, "step.target.name");
                ValidationUtil.validateRequired(targetTypeStr, "step.target.type");
                try {
                    targetType = AclTargetType.valueOf(targetTypeStr);
                } catch (IllegalArgumentException e) {
                    throw new ClientException("ValidationError", targetTypeStr + " is not a valid ACL target type");
                }
                switch (targetType) {
                    case USER:
                        User user = userDao.getActiveByUsername(targetName);
                        if (user == null) {
                            throw new ClientException("ValidationError", targetName + " is not a valid user");
                        }
                        break;
                    case GROUP:
                        Group group = groupDao.getActiveByName(targetName);
                        if (group == null) {
                            throw new ClientException("ValidationError", targetName + " is not a valid group");
                        }
                        break;
                    case SHARE:
                        // A step target can only be a USER or a GROUP: a SHARE is an anonymous,
                        // link-scoped grant with no principal to route work to. Accepting it would
                        // silently produce an unstartable model (the derived index cannot resolve it).
                        throw new ClientException("ValidationError", "SHARE is not a valid step target type");
                    default:
                        // Fail closed on any future AclTargetType value rather than silently skipping
                        // the switch and accepting an unvalidated target.
                        throw new ClientException("ValidationError", targetTypeStr + " is not a valid step target type");
                }

                // Transitions
                List<RouteStepTransition> transitionsNames = Lists.newArrayList();
                JsonArray transitions = step.getJsonArray("transitions");
                if (type == RouteStepType.VALIDATE) {
                    if (transitions.size() != 1) {
                        throw new ClientException("ValidationError", "VALIDATE steps should have one transition");
                    }
                    transitionsNames.add(RouteStepTransition.VALIDATED);
                } else if (type == RouteStepType.APPROVE) {
                    if (transitions.size() != 2) {
                        throw new ClientException("ValidationError", "APPROVE steps should have two transition");
                    }
                    transitionsNames.add(RouteStepTransition.APPROVED);
                    transitionsNames.add(RouteStepTransition.REJECTED);
                }

                for (int j = 0; j < transitions.size(); j++) {
                    // Transition
                    JsonObject transition = transitions.getJsonObject(j);
                    if (transition.size() != 2) {
                        throw new ClientException("ValidationError", "Transition data is not valid");
                    }

                    // Transition name
                    String routeStepTransitionStr = transition.getString("name");
                    ValidationUtil.validateRequired(routeStepTransitionStr, "step.transitions.name");
                    RouteStepTransition routeStepTransition;
                    try {
                        routeStepTransition = RouteStepTransition.valueOf(routeStepTransitionStr);
                    } catch (IllegalArgumentException e) {
                        throw new ClientException("ValidationError", routeStepTransitionStr + " is not a valid route step transition type");
                    }
                    if (!transitionsNames.contains(routeStepTransition)) {
                        throw new ClientException("ValidationError", routeStepTransitionStr + " is not allowed for this step type");
                    }

                    // Actions
                    JsonArray actions = transition.getJsonArray("actions");
                    for (int k = 0; k < actions.size(); k++) {
                        JsonObject action = actions.getJsonObject(k);

                        // Action type
                        String actionTypeStr = action.getString("type");
                        ActionType actionType;
                        ValidationUtil.validateRequired(routeStepTransitionStr, "step.transitions.actions.type");
                        try {
                            actionType = ActionType.valueOf(actionTypeStr);
                        } catch (IllegalArgumentException e) {
                            throw new ClientException("ValidationError", actionTypeStr + " is not a valid action type");
                        }

                        // Validate action
                        try {
                            ActionUtil.validateAction(actionType, action);
                        } catch (Exception e) {
                            throw new ClientException("ValidationError", e.getMessage());
                        }
                    }
                }
            }
        } catch (JsonException e) {
            throw new ClientException("ValidationError", "Steps data not valid");
        }
    }

    /**
     * Update a route model.
     *
     * @api {post} /routemodel/:id Update a route model
     * @apiName PostRouteModel
     * @apiGroup RouteModel
     * @apiParam {String} name Route model name
     * @apiParam {String} steps Steps data in JSON
     * @apiSuccess {String} status Status OK
     * @apiError (client) ForbiddenError Access denied
     * @apiError (client) ValidationError Validation error
     * @apiError (client) NotFound Route model not found
     * @apiPermission admin
     * @apiVersion 1.5.0
     *
     * @return Response
     */
    @POST
    @Path("{id: [a-z0-9\\-]+}")
    public Response update(@PathParam("id") String id,
                           @FormParam("name") String name,
                           @FormParam("steps") String steps) {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }
        checkBaseFunction(BaseFunction.ADMIN);

        // Validate input
        name = ValidationUtil.validateLength(name, "name", 1, 50, false);
        steps = ValidationUtil.validateLength(steps, "steps", 1, 5000, false);

        // Shared integrity gate (validate -> lock groups -> lock model row -> re-validate under locks)
        RouteModel routeModel = validateAndLockForWrite(steps, id);

        // Update the route model
        new RouteModelDao().update(routeModel.setName(name)
                .setSteps(steps), principal.getId());

        // Always return OK
        JsonObjectBuilder response = Json.createObjectBuilder()
                .add("status", "ok");
        return Response.ok().entity(response.build()).build();
    }

    /**
     * Delete a route model.
     *
     * @api {delete} /routemodel/:id Delete a route model
     * @apiName DeleteRouteModel
     * @apiGroup RouteModel
     * @apiParam {String} id Route model ID
     * @apiSuccess {String} status Status OK
     * @apiError (client) ForbiddenError Access denied
     * @apiError (client) NotFound Route model not found
     * @apiPermission admin
     * @apiVersion 1.5.0
     *
     * @return Response
     */
    @DELETE
    @Path("{id: [a-z0-9\\-]+}")
    public Response delete(@PathParam("id") String id) {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }
        checkBaseFunction(BaseFunction.ADMIN);

        // Get the route model
        RouteModelDao routeModelDao = new RouteModelDao();
        RouteModel routeModel = routeModelDao.getActiveById(id);
        if (routeModel == null) {
            throw new NotFoundException();
        }

        // Delete the route model
        routeModelDao.delete(routeModel.getId(), principal.getId());

        // Always return OK
        JsonObjectBuilder response = Json.createObjectBuilder()
                .add("status", "ok");
        return Response.ok().entity(response.build()).build();
    }

    /**
     * Get a route model.
     *
     * @api {get} /routemodel/:id Get a route model
     * @apiName GetRouteModel
     * @apiGroup RouteModel
     * @apiParam {String} id Route model ID
     * @apiSuccess {String} id Route model ID
     * @apiSuccess {String} name Route model name
     * @apiSuccess {String} create_date Create date (timestamp)
     * @apiSuccess {String} steps Steps data in JSON
     * @apiError (client) ForbiddenError Access denied
     * @apiError (client) NotFound Route model not found
     * @apiPermission admin
     * @apiVersion 1.5.0
     *
     * @param id RouteModel name
     * @return Response
     */
    @GET
    @Path("{id: [a-z0-9\\-]+}")
    public Response get(@PathParam("id") String id) {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }
        checkBaseFunction(BaseFunction.ADMIN);

        // Get the route model
        RouteModelDao routeModelDao = new RouteModelDao();
        RouteModel routeModel = routeModelDao.getActiveById(id);
        if (routeModel == null) {
            throw new NotFoundException();
        }

        // Build the response
        JsonObjectBuilder response = Json.createObjectBuilder()
                .add("id", routeModel.getId())
                .add("name", routeModel.getName())
                .add("create_date", routeModel.getCreateDate().getTime())
                .add("steps", routeModel.getSteps());

        // Add ACL
        AclUtil.addAcls(response, id, getTargetIdList(null));

        return Response.ok().entity(response.build()).build();
    }
}
