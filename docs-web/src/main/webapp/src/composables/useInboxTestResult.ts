import { ref, watch, nextTick, type Ref } from 'vue'
import type { SaveThenTestStep } from '../utils/inboxTestFlow'

export interface InboxTestResultApi {
  /** Unread-message count from the last successful test; null = no result shown. */
  testCount: Ref<number | null>
  /** Which step failed on the last attempt ('save' | 'test'); null = no error shown. */
  testError: Ref<SaveThenTestStep | null>
  /** Wipe the shown result (plain-save path: the saved config changed). */
  clear: () => void
  /** Show a successful test count (clears any error). */
  setCount: (count: number) => void
  /** Show a failure, labeled with the step that failed (clears any count). */
  setError: (step: SaveThenTestStep) => void
  /**
   * Run a programmatic form mutation WITHOUT it counting as dirty (e.g. blanking
   * the password field after a successful save) — otherwise the dirty-watcher
   * would immediately wipe the result the flow is about to show.
   */
  mutateSilently: (mutate: () => void) => Promise<void>
}

/**
 * Owns the inline test-result state for the inbox settings view and enforces its
 * one invariant: a shown result always describes the CURRENTLY-SAVED config. Any
 * user edit to the form (dirty) clears it; the plain Save path calls clear()
 * explicitly because a fresh save also invalidates a previous test's meaning.
 */
export function useInboxTestResult(form: object): InboxTestResultApi {
  const testCount = ref<number | null>(null)
  const testError = ref<SaveThenTestStep | null>(null)
  let suppressDirtyClear = false

  watch(form, () => {
    if (suppressDirtyClear) return
    testCount.value = null
    testError.value = null
  }, { deep: true })

  function clear() {
    testCount.value = null
    testError.value = null
  }

  function setCount(count: number) {
    testCount.value = count
    testError.value = null
  }

  function setError(step: SaveThenTestStep) {
    testError.value = step
    testCount.value = null
  }

  async function mutateSilently(mutate: () => void) {
    suppressDirtyClear = true
    try {
      mutate()
      // The deep watcher flushes on the next tick; keep suppression up until then.
      await nextTick()
    } finally {
      suppressDirtyClear = false
    }
  }

  return { testCount, testError, clear, setCount, setError, mutateSilently }
}
