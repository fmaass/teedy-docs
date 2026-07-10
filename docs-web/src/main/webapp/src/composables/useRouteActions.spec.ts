import { describe, it, expect, beforeEach, vi } from 'vitest'
import { ref } from 'vue'
import type { RouteStepSummary } from '../api/route'

// The validate API is a dependency, not the unit under test — mock it so we assert exactly what
// useRouteActions submits and how it classifies each outcome.
const validateRouteMock = vi.fn()
vi.mock('../api/route', () => ({
  validateRoute: (...args: unknown[]) => validateRouteMock(...args),
}))

import { useRouteActions } from './useRouteActions'

function step(overrides: Partial<RouteStepSummary> = {}): RouteStepSummary {
  return {
    id: 'step1',
    name: 'Approve the document',
    type: 'APPROVE',
    comment: null,
    end_date: null,
    validator_username: null,
    target: { id: 't1', name: 'administrators', type: 'GROUP' },
    transition: null,
    transitionable: true,
    ...overrides,
  }
}

describe('useRouteActions', () => {
  beforeEach(() => {
    validateRouteMock.mockReset()
  })

  it('ALWAYS submits the DISPLAYED step id (B3)', async () => {
    validateRouteMock.mockResolvedValue({ data: { readable: true, route_step: step({ id: 'step2' }) } })
    const current = ref<RouteStepSummary | null>(step({ id: 'stepABC' }))
    const { act } = useRouteActions('doc1', current)
    await act('APPROVED', 'looks good')
    expect(validateRouteMock).toHaveBeenCalledWith('doc1', 'APPROVED', 'stepABC', 'looks good')
  })

  it('is disabled while a request is pending and re-enabled after (no double-submit)', async () => {
    let resolve!: (v: unknown) => void
    validateRouteMock.mockReturnValue(new Promise((r) => (resolve = r)))
    const current = ref<RouteStepSummary | null>(step())
    const { pending, isDisabled, act } = useRouteActions('doc1', current)

    expect(isDisabled()).toBe(false)
    const p = act('APPROVED')
    expect(pending.value).toBe(true)
    expect(isDisabled()).toBe(true)

    // A second concurrent call is refused without hitting the API again.
    const second = await act('APPROVED')
    expect(second.kind).toBe('error')
    expect(validateRouteMock).toHaveBeenCalledTimes(1)

    resolve({ data: { readable: true } })
    await p
    expect(pending.value).toBe(false)
    expect(isDisabled()).toBe(false)
  })

  it('is disabled when the current step is not transitionable', () => {
    const current = ref<RouteStepSummary | null>(step({ transitionable: false }))
    const { isDisabled } = useRouteActions('doc1', current)
    expect(isDisabled()).toBe(true)
  })

  it('classifies readable=false as access-ended', async () => {
    validateRouteMock.mockResolvedValue({ data: { readable: false } })
    const { act } = useRouteActions('doc1', ref(step()))
    expect((await act('VALIDATED')).kind).toBe('access-ended')
  })

  it('classifies a next step as advanced and no next step as completed', async () => {
    validateRouteMock.mockResolvedValueOnce({ data: { readable: true, route_step: step({ id: 's2' }) } })
    expect((await useRouteActions('doc1', ref(step())).act('VALIDATED')).kind).toBe('advanced')
    validateRouteMock.mockResolvedValueOnce({ data: { readable: true } })
    expect((await useRouteActions('doc1', ref(step())).act('VALIDATED')).kind).toBe('completed')
  })

  it('classifies a 400 StepChanged as step-changed', async () => {
    validateRouteMock.mockRejectedValue({ response: { status: 400, data: { type: 'StepChanged' } } })
    const { act } = useRouteActions('doc1', ref(step()))
    expect((await act('APPROVED')).kind).toBe('step-changed')
  })

  it('classifies any other failure as error', async () => {
    validateRouteMock.mockRejectedValue({ response: { status: 500, data: {} } })
    const { act } = useRouteActions('doc1', ref(step()))
    expect((await act('APPROVED')).kind).toBe('error')
  })
})
