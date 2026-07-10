// POST /api/app/test_inbox takes NO params — it tests the SAVED inbox configuration
// server-side. Testing therefore only makes sense against config that has been
// persisted, so the UI enforces save-then-test: the "Test connection" action is a
// SAVE followed by a TEST, and it is gated so it can only run when the form is not
// mid-flight. These pure helpers make that gate unit-testable without a component.

export interface InboxTestFlowState {
  /** A save (config_inbox) is in flight. */
  saving: boolean
  /** A test (test_inbox) is in flight. */
  testing: boolean
  /** Inbox scanning is enabled in the form — testing a disabled inbox is pointless. */
  enabled: boolean
}

/**
 * The Save & test button is disabled while either request is in flight, or when the
 * inbox is disabled in the form (nothing meaningful to test). It is NOT gated on
 * form-dirtiness because the flow SAVES first — the test always runs against exactly
 * what the admin sees on screen.
 */
export function isSaveAndTestDisabled(state: InboxTestFlowState): boolean {
  return state.saving || state.testing || !state.enabled
}

/** Which step of the save-then-test flow failed. */
export type SaveThenTestStep = 'save' | 'test'

/**
 * A failure in the save-then-test flow, tagged with the step that failed so the UI
 * can label it truthfully: a save failure means the connection test NEVER ran and
 * must not be reported as a failed test. The original error is kept as `cause`.
 */
export class SaveThenTestError extends Error {
  readonly step: SaveThenTestStep
  readonly cause: unknown

  constructor(step: SaveThenTestStep, cause: unknown) {
    super(`inbox ${step} step failed`)
    this.name = 'SaveThenTestError'
    this.step = step
    this.cause = cause
  }
}

/**
 * Orchestrates save-then-test: persist the current config, THEN test the saved
 * config. If the save fails the test is NOT attempted (testing stale/rejected config
 * would be misleading) and a SaveThenTestError with step 'save' is thrown; a failure
 * of the test itself throws with step 'test'. Returns the test result on success.
 * The caller supplies the two thin API wrappers so this stays pure/testable.
 */
export async function runSaveThenTest<R>(
  save: () => Promise<unknown>,
  test: () => Promise<R>,
): Promise<R> {
  try {
    await save()
  } catch (e) {
    throw new SaveThenTestError('save', e)
  }
  try {
    return await test()
  } catch (e) {
    throw new SaveThenTestError('test', e)
  }
}
