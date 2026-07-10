package com.sismics.docs.rest.resource;

import com.sismics.docs.core.constant.*;
import com.sismics.docs.core.dao.*;
import com.sismics.docs.core.dao.criteria.RouteCriteria;
import com.sismics.docs.core.dao.criteria.RouteStepCriteria;
import com.sismics.docs.core.dao.dto.DocumentDto;
import com.sismics.docs.core.dao.dto.RouteDto;
import com.sismics.docs.core.dao.dto.RouteStepDto;
import com.sismics.docs.core.event.RouteCompletedAsyncEvent;
import com.sismics.docs.core.event.RouteStartedAsyncEvent;
import com.sismics.docs.core.event.RouteStepTransitionedAsyncEvent;
import com.sismics.docs.core.model.jpa.Route;
import com.sismics.docs.core.model.jpa.RouteModel;
import com.sismics.docs.core.model.jpa.RouteStep;
import com.sismics.docs.core.util.ActionUtil;
import com.sismics.docs.core.util.PrincipalDeletionUtil;
import com.sismics.docs.core.util.RoutingUtil;
import com.sismics.docs.core.util.SecurityUtil;
import com.sismics.docs.core.util.jpa.SortCriteria;
import com.sismics.rest.exception.ClientException;
import com.sismics.rest.exception.ForbiddenClientException;
import com.sismics.rest.util.ValidationUtil;
import com.sismics.util.JsonUtil;
import com.sismics.util.context.ThreadLocalContext;

import jakarta.json.*;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import java.io.StringReader;
import java.util.List;

/**
 * Route REST resources.
 *
 * @author bgamard
 */
@Path("/route")
public class RouteResource extends BaseResource {
    /**
     * Start a route on a document.
     *
     * @api {post} /route/start Start a route on a document
     * @apiName PostRouteStart
     * @apiGroup Route
     * @apiParam {String} routeModelId Route model ID
     * @apiParam {String} documentId Document ID
     * @apiSuccess {String} status Status OK
     * @apiError (client) InvalidRouteModel Invalid route model
     * @apiError (client) RunningRoute A running route already exists on this document
     * @apiError (client) ForbiddenError Access denied
     * @apiError (client) NotFound Route model or document not found
     * @apiPermission user
     * @apiVersion 1.5.0
     *
     * @return Response
     */
    @POST
    @Path("start")
    public Response start(@FormParam("routeModelId") String routeModelId,
                          @FormParam("documentId") String documentId) {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }

        // Get the document
        AclDao aclDao = new AclDao();
        if (!aclDao.checkPermission(documentId, PermType.WRITE, getTargetIdList(null))) {
            throw new NotFoundException();
        }

        // Get the route model
        RouteModelDao routeModelDao = new RouteModelDao();
        RouteModel routeModel = routeModelDao.getActiveById(routeModelId);
        if (routeModel == null) {
            throw new NotFoundException();
        }

        // Check permission on this route model
        if (!aclDao.checkPermission(routeModelId, PermType.READ, getTargetIdList(null))) {
            throw new ForbiddenClientException();
        }

        // Avoid creating 2 running routes on the same document
        RouteStepDao routeStepDao = new RouteStepDao();
        if (routeStepDao.getCurrentStep(documentId) != null) {
            throw new ClientException("RunningRoute", "A running route already exists on this document");
        }

        // Create the route (RouteDao.create sets the initiator and ACTIVE status)
        Route route = new Route()
                .setDocumentId(documentId)
                .setName(routeModel.getName());
        RouteDao routeDao = new RouteDao();
        routeDao.create(route, principal.getId());

        // Create the steps
        try (JsonReader reader = Json.createReader(new StringReader(routeModel.getSteps()))) {
            JsonArray stepsJson = reader.readArray();
            for (int order = 0; order < stepsJson.size(); order++) {
                JsonObject step = stepsJson.getJsonObject(order);
                JsonObject target = step.getJsonObject("target");
                AclTargetType targetType = AclTargetType.valueOf(target.getString("type"));
                String targetName = target.getString("name");
                String transitions = null;
                if (step.containsKey("transitions")) {
                    transitions = step.getJsonArray("transitions").toString();
                }

                RouteStep routeStep = new RouteStep()
                        .setRouteId(route.getId())
                        .setName(step.getString("name"))
                        .setOrder(order)
                        .setType(RouteStepType.valueOf(step.getString("type")))
                        .setTransitions(transitions)
                        .setTargetId(SecurityUtil.getTargetIdFromName(targetName, targetType));

                if (routeStep.getTargetId() == null) {
                    throw new ClientException("InvalidRouteModel", "A step has an invalid target");
                }

                routeStepDao.create(routeStep);
            }
        }

        // Initialize ACLs on the first step
        RouteStepDto routeStepDto = routeStepDao.getCurrentStep(documentId);
        RoutingUtil.updateAcl(documentId, routeStepDto, null, principal.getId());
        RoutingUtil.sendRouteStepEmail(documentId, routeStepDto);

        // Fire the ROUTE_STARTED webhook event
        RouteStartedAsyncEvent routeStartedAsyncEvent = new RouteStartedAsyncEvent();
        routeStartedAsyncEvent.setUserId(principal.getId());
        routeStartedAsyncEvent.setDocumentId(documentId)
                .setRouteId(route.getId())
                .setStepName(routeStepDto.getName());
        ThreadLocalContext.get().addAsyncEvent(routeStartedAsyncEvent);

        JsonObjectBuilder step = routeStepDto.toJson();
        step.add("transitionable", getTargetIdList(null).contains(routeStepDto.getTargetId()));
        JsonObjectBuilder response = Json.createObjectBuilder()
                .add("route_step", step);
        return Response.ok().entity(response.build()).build();
    }

    /**
     * Validate the current step of a route.
     *
     * @api {post} /route/validate Validate the current step of a route
     * @apiName PostRouteValidate
     * @apiGroup Route
     * @apiParam {String} documentId Document ID
     * @apiParam {String} transition Route step transition
     * @apiParam {String} comment Route step comment
     * @apiParam {String} [routeStepId] Expected current step ID. When supplied, the resolved current
     *   step MUST match it or the request is rejected StepChanged, binding the action to the step the
     *   caller actually saw (stale tab / double-click / concurrent actor protection). Optional for
     *   back-compatible API clients that act on whatever the current step is.
     * @apiSuccess {String} status Status OK
     * @apiError (client) ValidationError Invalid transition
     * @apiError (client) StepChanged The current step differs from the one the caller acted on
     * @apiError (client) AlreadyEnded The step was already ended by a concurrent validation
     * @apiError (client) ForbiddenError Access denied
     * @apiError (client) NotFound Document or route not found
     * @apiPermission user
     * @apiVersion 1.5.0
     *
     * @return Response
     */
    @POST
    @Path("validate")
    public Response validate(@FormParam("documentId") String documentId,
                             @FormParam("transition") String transitionStr,
                             @FormParam("comment") String comment,
                             @FormParam("routeStepId") String routeStepId) {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }

        // Get the document
        AclDao aclDao = new AclDao();
        DocumentDao documentDao = new DocumentDao();
        DocumentDto documentDto = documentDao.getDocument(documentId, PermType.READ, getTargetIdList(null));
        if (documentDto == null) {
            throw new NotFoundException();
        }

        // Get the current step
        RouteStepDao routeStepDao = new RouteStepDao();
        RouteStepDto routeStepDto = routeStepDao.getCurrentStep(documentId);
        if (routeStepDto == null) {
            throw new NotFoundException();
        }

        // Bind the action to the step the caller actually saw. When routeStepId is supplied and the
        // resolved current step is a DIFFERENT step, the route has advanced since the caller loaded it
        // (stale tab, double-click, or a concurrent group member): reject StepChanged and advance
        // NOTHING. Consecutive same-type/same-target steps (e.g. the seeded two administrator VALIDATE
        // steps) make this essential — without it, one intended action could advance two steps. Absent
        // routeStepId, today's behavior is preserved for back-compat API clients.
        if (routeStepId != null && !routeStepId.equals(routeStepDto.getId())) {
            throw new ClientException("StepChanged", "The current step differs from the one you acted on");
        }

        // Check permission to validate this step
        if (!getTargetIdList(null).contains(routeStepDto.getTargetId())) {
            throw new ForbiddenClientException();
        }

        // Validate data
        ValidationUtil.validateRequired(transitionStr, "transition");
        comment = ValidationUtil.validateLength(comment, "comment", 1, 500, true);

        // Parse the transition, turning a bad value into a 400 ValidationError (never a 500)
        RouteStepTransition routeStepTransition;
        try {
            routeStepTransition = RouteStepTransition.valueOf(transitionStr);
        } catch (IllegalArgumentException e) {
            throw new ClientException("ValidationError", transitionStr + " is not a valid route step transition");
        }
        if (routeStepDto.getType() == RouteStepType.VALIDATE && routeStepTransition != RouteStepTransition.VALIDATED
                || routeStepDto.getType() == RouteStepType.APPROVE
                && routeStepTransition != RouteStepTransition.APPROVED && routeStepTransition != RouteStepTransition.REJECTED) {
            throw new ClientException("ValidationError", "Invalid transition for this route step type");
        }

        // Win the guarded step-end FIRST. Only the caller that flips the open step to closed (update
        // count 1) is allowed to run the transition; a concurrent/duplicate validation gets 0 and is
        // rejected with AlreadyEnded before any transition side effect runs.
        int endCount = routeStepDao.endRouteStep(routeStepDto.getId(), routeStepTransition, comment, principal.getId());
        if (endCount != 1) {
            throw new ClientException("AlreadyEnded", "This route step has already been ended");
        }

        // Execute the transition's actions (only after winning the step-end)
        if (routeStepDto.getTransitions() != null) {
            try (JsonReader reader = Json.createReader(new StringReader(routeStepDto.getTransitions()))) {
                JsonArray transitions = reader.readArray();
                for (int i = 0; i < transitions.size(); i++) {
                    JsonObject transition = transitions.getJsonObject(i);
                    if (transition.getString("name").equals(routeStepTransition.name())) {
                        JsonArray actions = transition.getJsonArray("actions");
                        for (int j = 0; j < actions.size(); j++) {
                            JsonObject action = actions.getJsonObject(j);
                            ActionType actionType = ActionType.valueOf(action.getString("type"));
                            ActionUtil.executeAction(actionType, action, documentDto);
                        }
                    }
                }
            }
        }

        String routeId = routeStepDto.getRouteId();
        RouteDao routeDao = new RouteDao();

        // A rejection halts the whole route: no next step is granted.
        if (routeStepTransition == RouteStepTransition.REJECTED) {
            // Mark the route rejected and stamp its end date.
            routeDao.endRoute(routeId, RouteStatus.REJECTED);
            // Close any still-open steps of the route. NULL transition = system-ended: only the
            // ACTED step carries REJECTED; nobody acted on the remaining steps.
            routeStepDao.endAllOpenSteps(routeId, null, "Route halted: a step was rejected");
            // Remove the rejected step's routing READ ACL and grant NO next one.
            RoutingUtil.updateAcl(documentId, null, routeStepDto, principal.getId());
            // Notify the route initiator with the dedicated rejection notice.
            notifyInitiator(routeDao, routeId, documentId, routeStepDto.getName(), comment);

            // A reject is a transition -> fire ROUTE_STEP_TRANSITIONED (but NOT ROUTE_COMPLETED).
            fireStepTransitioned(routeId, documentId, routeStepDto.getName(), routeStepTransition);

            JsonObjectBuilder response = Json.createObjectBuilder()
                    .add("readable", aclDao.checkPermission(documentId, PermType.READ, getTargetIdList(null)));
            return Response.ok().entity(response.build()).build();
        }

        // Not a rejection: advance to the next step if any.
        RouteStepDto newRouteStep = routeStepDao.getCurrentStep(documentId);
        RoutingUtil.updateAcl(documentId, newRouteStep, routeStepDto, principal.getId());

        // Every validate/approve/reject fires a transition event.
        fireStepTransitioned(routeId, documentId, routeStepDto.getName(), routeStepTransition);

        if (newRouteStep != null) {
            // There is a next step: notify its target.
            RoutingUtil.sendRouteStepEmail(documentId, newRouteStep);
        } else {
            // No next step: the route is completed.
            routeDao.endRoute(routeId, RouteStatus.DONE);

            RouteCompletedAsyncEvent routeCompletedAsyncEvent = new RouteCompletedAsyncEvent();
            routeCompletedAsyncEvent.setUserId(principal.getId());
            routeCompletedAsyncEvent.setDocumentId(documentId)
                    .setRouteId(routeId);
            ThreadLocalContext.get().addAsyncEvent(routeCompletedAsyncEvent);
        }

        // The route_step payload is included ONLY when the caller can still read the document after
        // the transition (readable == true).
        boolean readable = aclDao.checkPermission(documentId, PermType.READ, getTargetIdList(null));
        JsonObjectBuilder response = Json.createObjectBuilder()
                .add("readable", readable);
        if (readable && newRouteStep != null) {
            JsonObjectBuilder step = newRouteStep.toJson();
            step.add("transitionable", getTargetIdList(null).contains(newRouteStep.getTargetId()));
            response.add("route_step", step);
        }
        return Response.ok().entity(response.build()).build();
    }

    /**
     * Fire a ROUTE_STEP_TRANSITIONED webhook event.
     *
     * @param routeId Route ID
     * @param documentId Document ID
     * @param stepName Name of the transitioned step
     * @param transition Transition applied
     */
    private void fireStepTransitioned(String routeId, String documentId, String stepName, RouteStepTransition transition) {
        RouteStepTransitionedAsyncEvent event = new RouteStepTransitionedAsyncEvent();
        event.setUserId(principal.getId());
        event.setDocumentId(documentId)
                .setRouteId(routeId)
                .setStepName(stepName)
                .setTransition(transition.name());
        ThreadLocalContext.get().addAsyncEvent(event);
    }

    /**
     * Notify the route initiator that the route was rejected, via the dedicated rejection-notice
     * email carrying the rejected step's name and comment.
     *
     * @param routeDao Route DAO
     * @param routeId Route ID
     * @param documentId Document ID
     * @param stepName Name of the rejected step
     * @param comment Rejection comment (may be null)
     */
    private void notifyInitiator(RouteDao routeDao, String routeId, String documentId, String stepName, String comment) {
        List<RouteDto> routeDtoList = routeDao.findByCriteria(new RouteCriteria()
                .setDocumentId(documentId), new SortCriteria(2, false));
        String initiatorId = null;
        for (RouteDto routeDto : routeDtoList) {
            if (routeId.equals(routeDto.getId())) {
                initiatorId = routeDto.getUserId();
                break;
            }
        }
        if (initiatorId != null) {
            RoutingUtil.sendRouteRejectedEmail(documentId, initiatorId, stepName, comment);
        }
    }

    /**
     * Returns the routes on a document.
     *
     * @api {get} /route Get the routes on a document
     * @apiName GetRoutes
     * @apiGroup Route
     * @apiParam {String} documentId Document ID
     * @apiSuccess {Object[]} routes List of routes
     * @apiSuccess {String} routes.name Name
     * @apiSuccess {Number} routes.create_date Create date (timestamp)
     * @apiSuccess {Object[]} routes.steps Route steps
     * @apiSuccess {String} routes.steps.name Route step name
     * @apiSuccess {String="APPROVE", "VALIDATE"} routes.steps.type Route step type
     * @apiSuccess {String} routes.steps.comment Route step comment
     * @apiSuccess {Number} routes.steps.end_date Route step end date (timestamp)
     * @apiSuccess {String="APPROVED","REJECTED","VALIDATED"} routes.steps.transition Route step transition
     * @apiSuccess {Object} routes.steps.validator_username Validator username
     * @apiSuccess {Object} routes.steps.target Route step target
     * @apiSuccess {String} routes.steps.target.id Route step target ID
     * @apiSuccess {String} routes.steps.target.name Route step target name
     * @apiSuccess {String="USER","GROUP"} routes.steps.target.type Route step target type
     * @apiError (client) NotFound Document not found
     * @apiPermission none
     * @apiVersion 1.5.0
     *
     * @param documentId Document ID
     * @return Response
     */
    @GET
    public Response get(@QueryParam("documentId") String documentId) {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }

        DocumentDao documentDao = new DocumentDao();
        DocumentDto documentDto = documentDao.getDocument(documentId, PermType.READ, getTargetIdList(null));
        if (documentDto == null) {
            throw new NotFoundException();
        }

        JsonArrayBuilder routes = Json.createArrayBuilder();

        RouteDao routeDao = new RouteDao();
        RouteStepDao routeStepDao = new RouteStepDao();
        List<RouteDto> routeDtoList = routeDao.findByCriteria(new RouteCriteria()
                .setDocumentId(documentId), new SortCriteria(3, false));
        for (RouteDto routeDto : routeDtoList) {
            // History query: routeId set, endDateIsNull unset -> steps of any (active/terminal) route,
            // ordered by step order.
            List<RouteStepDto> routeStepDtoList = routeStepDao.findByCriteria(new RouteStepCriteria()
                    .setRouteId(routeDto.getId()), new SortCriteria(6, true));
            JsonArrayBuilder steps = Json.createArrayBuilder();

            for (RouteStepDto routeStepDto : routeStepDtoList) {
                steps.add(routeStepDto.toJson());
            }

            routes.add(Json.createObjectBuilder()
                    .add("id", routeDto.getId())
                    .add("name", routeDto.getName())
                    .add("status", JsonUtil.nullable(routeDto.getStatus()))
                    .add("create_date", routeDto.getCreateTimestamp())
                    .add("end_date", JsonUtil.nullable(routeDto.getEndTimestamp()))
                    .add("initiator_username", JsonUtil.nullable(routeDto.getInitiatorUsername()))
                    .add("steps", steps));
        }

        JsonObjectBuilder json = Json.createObjectBuilder()
                .add("routes", routes);
        return Response.ok().entity(json.build()).build();
    }

    /**
     * Cancel a route.
     *
     * @api {delete} /route Cancel a route
     * @apiName DeleteRoute
     * @apiGroup Route
     * @apiParam {String} documentId Document ID
     * @apiSuccess {String} status Status OK
     * @apiError (client) ForbiddenError Access denied
     * @apiError (client) NotFound Document or route not found
     * @apiPermission user
     * @apiVersion 1.5.0
     *
     * @return Response
     */
    @DELETE
    public Response delete(@QueryParam("documentId") String documentId) {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }

        // Get the document
        AclDao aclDao = new AclDao();
        if (!aclDao.checkPermission(documentId, PermType.WRITE, getTargetIdList(null))) {
            throw new NotFoundException();
        }

        // Get the current step (only an active route has one)
        RouteStepDao routeStepDao = new RouteStepDao();
        RouteStepDto routeStepDto = routeStepDao.getCurrentStep(documentId);
        if (routeStepDto == null) {
            throw new NotFoundException();
        }

        // Terminal-cancel semantics: mark the route CANCELLED + end date and close all open steps,
        // rather than hard-deleting the history. The route and its ended steps stay listable in GET.
        String routeId = routeStepDto.getRouteId();
        RouteDao routeDao = new RouteDao();
        routeDao.endRoute(routeId, RouteStatus.CANCELLED);
        // NULL transition = system-ended: nobody acted on the open steps of a cancelled route.
        routeStepDao.endAllOpenSteps(routeId, null, "Route cancelled");

        // Remove the ROUTING READ grant so no transient workflow ACL survives this terminal route.
        // Target-AGNOSTIC (all ROUTING ACLs on the document), not scoped to the step we resolved above:
        // a concurrent validate can advance the route between our getCurrentStep() read and here,
        // shifting the ROUTING ACL from the resolved step's target to a later step. A step-scoped
        // removal would then miss the shifted ACL and leave a persistent READ grant on a cancelled
        // route (authz leak). Deleting every ROUTING ACL for the document closes that race regardless
        // of which step advanced — matching the trash-cancel path's semantics (W2c invariant).
        PrincipalDeletionUtil.deleteAllRoutingAclsForDocument(documentId, principal.getId());

        // Always return OK
        JsonObjectBuilder response = Json.createObjectBuilder()
                .add("status", "ok");
        return Response.ok().entity(response.build()).build();
    }
}
