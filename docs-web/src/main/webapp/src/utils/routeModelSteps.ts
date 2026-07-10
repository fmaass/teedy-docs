// Route-model step (workflow template) editor model <-> wire JSON.
//
// The backend's RouteModelResource.validateRouteModelSteps is STRICT about object
// shape (exact key counts). The wire JSON for a step is EXACTLY:
//
//   { name, type, target: { type, name }, transitions: [...] }   // exactly 4 keys
//
//   type ∈ { VALIDATE, APPROVE }
//   target.type ∈ { USER, GROUP }; target.name is the user/group NAME (not id)
//   VALIDATE → exactly one transition { name: "VALIDATED", actions: [] }
//   APPROVE  → exactly two transitions { name: "APPROVED", actions: [] } and
//              { name: "REJECTED", actions: [] }   (in that order)
//   each transition = exactly 2 keys { name, actions }
//   action ∈ { type: "ADD_TAG", tag: <tagId> }
//          | { type: "REMOVE_TAG", tag: <tagId> }
//          | { type: "PROCESS_FILES" }            // no extra property
//
// The editor works with a friendlier model where the transition *set* is implied by the
// step type; serializeSteps materialises the exact transition names/order the backend
// wants so the produced blob always validates. parseSteps is the inverse (for editing an
// existing model), tolerant of the backend's transition ordering.

export type RouteStepType = 'VALIDATE' | 'APPROVE'
export type AclTargetType = 'USER' | 'GROUP'
export type TransitionName = 'VALIDATED' | 'APPROVED' | 'REJECTED'

export interface StepAction {
  type: 'ADD_TAG' | 'REMOVE_TAG' | 'PROCESS_FILES'
  // Present (and required) only for ADD_TAG / REMOVE_TAG.
  tag?: string
}

// Editor-facing step. `actions` is keyed by transition name; only the transitions valid
// for the step's type are consulted at serialize time (extras are ignored).
export interface StepModel {
  name: string
  type: RouteStepType
  target: { type: AclTargetType; name: string }
  actions: Partial<Record<TransitionName, StepAction[]>>
}

// The exact transition-name sequence the backend requires per step type.
export function transitionNamesFor(type: RouteStepType): TransitionName[] {
  return type === 'VALIDATE' ? ['VALIDATED'] : ['APPROVED', 'REJECTED']
}

// Materialise one action into its exact wire object (no extra keys).
function serializeAction(action: StepAction): Record<string, unknown> {
  if (action.type === 'PROCESS_FILES') {
    return { type: 'PROCESS_FILES' }
  }
  return { type: action.type, tag: action.tag ?? '' }
}

// Editor model -> wire JSON string (the exact strict shape).
export function serializeSteps(steps: StepModel[]): string {
  const wire = steps.map((step) => ({
    name: step.name,
    type: step.type,
    target: { type: step.target.type, name: step.target.name },
    transitions: transitionNamesFor(step.type).map((name) => ({
      name,
      actions: (step.actions[name] ?? []).map(serializeAction),
    })),
  }))
  return JSON.stringify(wire)
}

interface WireAction {
  type: string
  tag?: string
}
interface WireTransition {
  name: string
  actions: WireAction[]
}
interface WireStep {
  name: string
  type: string
  target: { type: string; name: string }
  transitions: WireTransition[]
}

// Wire JSON string (from GET /routemodel/{id}.steps) -> editor model.
export function parseSteps(json: string): StepModel[] {
  const raw = JSON.parse(json) as WireStep[]
  return raw.map((step) => {
    const type = step.type as RouteStepType
    const actions: Partial<Record<TransitionName, StepAction[]>> = {}
    for (const tr of step.transitions ?? []) {
      actions[tr.name as TransitionName] = (tr.actions ?? []).map((a) =>
        a.type === 'PROCESS_FILES'
          ? { type: 'PROCESS_FILES' }
          : { type: a.type as StepAction['type'], tag: a.tag },
      )
    }
    return {
      name: step.name,
      type,
      target: { type: step.target.type as AclTargetType, name: step.target.name },
      actions,
    }
  })
}

// A fresh step with sensible defaults for the editor.
export function newStep(): StepModel {
  return {
    name: '',
    type: 'VALIDATE',
    target: { type: 'USER', name: '' },
    actions: {},
  }
}
