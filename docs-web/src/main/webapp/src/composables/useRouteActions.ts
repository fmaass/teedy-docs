import { ref, type Ref } from 'vue'
import type { AxiosError } from 'axios'
import { validateRoute, type RouteStepSummary, type RouteStepTransition, type ValidateResponse } from '../api/route'

// Owns the ACT-controls behavior of the document Workflow tab so it is unit-testable without
// mounting: a single in-flight guard (pending) that disables the controls, always submitting the
// DISPLAYED step's id (B3), and classifying the two special outcomes the caller must react to —
// StepChanged (the route advanced under the user) and readable=false (the user lost access).

export type ActOutcome =
  | { kind: 'advanced'; response: ValidateResponse } // a next step exists and is still readable
  | { kind: 'completed' } // route ended (DONE) and the caller can still read
  | { kind: 'access-ended' } // readable=false: the caller lost read access as a side effect
  | { kind: 'step-changed' } // StepChanged: the current step advanced since it was displayed
  | { kind: 'error'; error: unknown } // any other failure

function isStepChanged(err: unknown): boolean {
  const ax = err as AxiosError<{ type?: string }>
  return ax?.response?.status === 400 && ax.response?.data?.type === 'StepChanged'
}

export function useRouteActions(documentId: string, currentStep: Ref<RouteStepSummary | null | undefined>) {
  const pending = ref(false)

  // True when the controls must be disabled: mid-request, or there is no actionable step.
  function isDisabled(): boolean {
    return pending.value || !currentStep.value?.transitionable
  }

  async function act(transition: RouteStepTransition, comment?: string): Promise<ActOutcome> {
    const step = currentStep.value
    // Guard against double-submit and against acting with no displayed step.
    if (pending.value || !step) {
      return { kind: 'error', error: new Error('no actionable step or a request is already pending') }
    }
    pending.value = true
    try {
      // ALWAYS bind to the displayed step's id (B3).
      const { data } = await validateRoute(documentId, transition, step.id, comment)
      if (!data.readable) return { kind: 'access-ended' }
      if (data.route_step) return { kind: 'advanced', response: data }
      return { kind: 'completed' }
    } catch (error) {
      if (isStepChanged(error)) return { kind: 'step-changed' }
      return { kind: 'error', error }
    } finally {
      pending.value = false
    }
  }

  return { pending, isDisabled, act }
}
