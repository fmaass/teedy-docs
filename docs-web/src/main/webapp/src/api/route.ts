import api from './client'

// Workflow route *instance* API (documents in flight). Distinct from api/routeModel.ts,
// which manages the workflow *templates* (admin). A route is an instance of a model started
// on a document; it advances step by step until DONE, REJECTED, or CANCELLED.

export type RouteStatus = 'ACTIVE' | 'DONE' | 'REJECTED' | 'CANCELLED'
export type RouteStepType = 'VALIDATE' | 'APPROVE'
export type RouteStepTransition = 'VALIDATED' | 'APPROVED' | 'REJECTED'

// A step as returned by GET /route (history) and by the doc-detail / start / validate route_step.
// `id` is the step's own id (exposed for the B3 step-id guard); `end_date` null => still open;
// `transition` null on an open step OR on a system-ended step (cancelled/halted, nobody acted).
export interface RouteStep {
  id: string
  name: string
  type: RouteStepType
  comment: string | null
  end_date: number | null
  validator_username: string | null
  target: { id: string; name: string | null; type: 'USER' | 'GROUP' }
  transition: RouteStepTransition | null
}

// The current-step summary returned by DocumentResource doc-detail (and start/validate). It is a
// RouteStep plus `transitionable` (true when the current user may act on it now).
export interface RouteStepSummary extends RouteStep {
  transitionable: boolean
}

// A full route in GET /route history. `end_date` null while ACTIVE.
export interface Route {
  id: string
  name: string
  status: RouteStatus
  create_date: number
  end_date: number | null
  initiator_username: string | null
  steps: RouteStep[]
}

// POST /route/validate response. `readable` is false when the caller lost read access to the
// document as a side effect of the transition (routing ACL shifted away). `route_step` is present
// only when readable && a next step exists.
export interface ValidateResponse {
  readable: boolean
  route_step?: RouteStepSummary
}

// POST /route/start response — always carries the newly-current step.
export interface StartResponse {
  route_step: RouteStepSummary
}

export function getRoutes(documentId: string) {
  return api.get<{ routes: Route[] }>('/route', { params: { documentId } })
}

export function startRoute(documentId: string, routeModelId: string) {
  const params = new URLSearchParams()
  params.set('documentId', documentId)
  params.set('routeModelId', routeModelId)
  return api.post<StartResponse>('/route/start', params)
}

// Validate/approve/reject the current step. `routeStepId` binds the action to the step the caller
// actually saw (B3): the backend rejects StepChanged if the current step has since advanced. Always
// pass it from the displayed step.
export function validateRoute(
  documentId: string,
  transition: RouteStepTransition,
  routeStepId: string,
  comment?: string,
) {
  const params = new URLSearchParams()
  params.set('documentId', documentId)
  params.set('transition', transition)
  params.set('routeStepId', routeStepId)
  if (comment) params.set('comment', comment)
  return api.post<ValidateResponse>('/route/validate', params)
}

export function cancelRoute(documentId: string) {
  return api.delete<{ status: string }>('/route', { params: { documentId } })
}

// Query keys local to this module (per-phase convention — mirrors routeModelKeys).
export const routeKeys = {
  all: (documentId: string) => ['routes', documentId] as const,
}
