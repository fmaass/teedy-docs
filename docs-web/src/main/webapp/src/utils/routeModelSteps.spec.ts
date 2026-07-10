import { describe, it, expect } from 'vitest'
import {
  serializeSteps,
  parseSteps,
  transitionNamesFor,
  newStep,
  type StepModel,
} from './routeModelSteps'

// These assertions encode the EXACT wire shape the backend's validateRouteModelSteps
// enforces (RouteModelResource.java): step = exactly 4 keys {name,type,target,transitions};
// target = exactly 2 keys {type,name}; transition = exactly 2 keys {name,actions};
// VALIDATE→1 transition (VALIDATED); APPROVE→2 (APPROVED,REJECTED); tag key is "tag".

describe('transitionNamesFor', () => {
  it('VALIDATE has exactly one transition VALIDATED', () => {
    expect(transitionNamesFor('VALIDATE')).toEqual(['VALIDATED'])
  })
  it('APPROVE has exactly two transitions APPROVED then REJECTED', () => {
    expect(transitionNamesFor('APPROVE')).toEqual(['APPROVED', 'REJECTED'])
  })
})

describe('serializeSteps — strict shape', () => {
  it('serializes a VALIDATE step to exactly {name,type,target,transitions} with one VALIDATED transition', () => {
    const steps: StepModel[] = [
      {
        name: 'Check metadata',
        type: 'VALIDATE',
        target: { type: 'GROUP', name: 'administrators' },
        actions: {},
      },
    ]
    const parsed = JSON.parse(serializeSteps(steps))
    expect(parsed).toHaveLength(1)
    const step = parsed[0]
    // Exactly 4 keys, no more.
    expect(Object.keys(step).sort()).toEqual(['name', 'target', 'transitions', 'type'])
    expect(step.type).toBe('VALIDATE')
    // Target: exactly 2 keys {type,name}.
    expect(Object.keys(step.target).sort()).toEqual(['name', 'type'])
    expect(step.target).toEqual({ type: 'GROUP', name: 'administrators' })
    // Transitions: exactly one, named VALIDATED, with 2 keys {name,actions}.
    expect(step.transitions).toHaveLength(1)
    expect(Object.keys(step.transitions[0]).sort()).toEqual(['actions', 'name'])
    expect(step.transitions[0].name).toBe('VALIDATED')
    expect(step.transitions[0].actions).toEqual([])
  })

  it('serializes an APPROVE step with exactly two transitions APPROVED and REJECTED', () => {
    const steps: StepModel[] = [
      {
        name: 'Approve',
        type: 'APPROVE',
        target: { type: 'USER', name: 'alice' },
        actions: {
          APPROVED: [{ type: 'ADD_TAG', tag: 'tag-123' }],
          REJECTED: [{ type: 'PROCESS_FILES' }],
        },
      },
    ]
    const parsed = JSON.parse(serializeSteps(steps))
    const step = parsed[0]
    expect(step.transitions.map((t: { name: string }) => t.name)).toEqual(['APPROVED', 'REJECTED'])
    // ADD_TAG carries exactly {type, tag} — key is "tag", not "tagId".
    expect(step.transitions[0].actions).toEqual([{ type: 'ADD_TAG', tag: 'tag-123' }])
    // PROCESS_FILES carries only {type} — no extra property.
    expect(step.transitions[1].actions).toEqual([{ type: 'PROCESS_FILES' }])
    expect(Object.keys(step.transitions[1].actions[0])).toEqual(['type'])
  })

  it('drops transition actions that do not belong to the step type (APPROVE actions on a VALIDATE step)', () => {
    const steps: StepModel[] = [
      {
        name: 'Check',
        type: 'VALIDATE',
        target: { type: 'USER', name: 'bob' },
        // Stale APPROVED/REJECTED entries (e.g. type was switched from APPROVE) are ignored.
        actions: {
          VALIDATED: [{ type: 'REMOVE_TAG', tag: 't9' }],
          APPROVED: [{ type: 'ADD_TAG', tag: 'ignored' }],
        },
      },
    ]
    const parsed = JSON.parse(serializeSteps(steps))
    const step = parsed[0]
    expect(step.transitions).toHaveLength(1)
    expect(step.transitions[0].name).toBe('VALIDATED')
    expect(step.transitions[0].actions).toEqual([{ type: 'REMOVE_TAG', tag: 't9' }])
  })
})

describe('parseSteps → serializeSteps round-trip', () => {
  it('round-trips a VALIDATE model produced by the backend GET', () => {
    const wire = JSON.stringify([
      {
        name: "Check the document's metadata",
        type: 'VALIDATE',
        target: { type: 'GROUP', name: 'administrators' },
        transitions: [{ name: 'VALIDATED', actions: [] }],
      },
    ])
    const model = parseSteps(wire)
    expect(model[0].type).toBe('VALIDATE')
    expect(model[0].target).toEqual({ type: 'GROUP', name: 'administrators' })
    // Re-serialize and confirm identical structure.
    expect(JSON.parse(serializeSteps(model))).toEqual(JSON.parse(wire))
  })

  it('round-trips an APPROVE model with actions, preserving the tag key', () => {
    const wire = JSON.stringify([
      {
        name: 'Approval',
        type: 'APPROVE',
        target: { type: 'USER', name: 'carol' },
        transitions: [
          { name: 'APPROVED', actions: [{ type: 'ADD_TAG', tag: 'abc' }] },
          { name: 'REJECTED', actions: [{ type: 'PROCESS_FILES' }] },
        ],
      },
    ])
    const model = parseSteps(wire)
    expect(model[0].actions.APPROVED).toEqual([{ type: 'ADD_TAG', tag: 'abc' }])
    expect(JSON.parse(serializeSteps(model))).toEqual(JSON.parse(wire))
  })
})

describe('newStep', () => {
  it('produces a valid empty VALIDATE step scaffold', () => {
    const step = newStep()
    expect(step.type).toBe('VALIDATE')
    expect(step.target).toEqual({ type: 'USER', name: '' })
    expect(step.actions).toEqual({})
  })
})
