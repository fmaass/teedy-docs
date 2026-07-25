import { test as base, expect, type TestInfo } from '@playwright/test'

// Re-export every other named/type export (request, type Page, type Locator,
// type APIRequestContext, type ConsoleMessage, type Request, …) so specs can import
// them from './fixtures' exactly as they did from '@playwright/test'. Only `test` is
// overridden below with the toast-click-through + cleanup fixtures; `expect` is
// re-exported unchanged. `export *` does not re-export the default or a named `test`,
// so our override is authoritative.
export * from '@playwright/test'

// Shared test base for ALL e2e specs. Its one job: make the PrimeVue toast layer
// click-through in the test environment, GLOBALLY and by construction, so a toast
// can never intercept a click regardless of timing.
//
// Why: PrimeVue Toasts teleport to a fixed top-right layer 25rem (400px) wide. On
// the mobile project (Pixel 5, 393px) a toast overflows full-width and the seed
// "Document created/deleted" toasts (life ~2000ms) sit directly over the page-header
// controls. A click issued while a toast covers the trigger's hit-point lands on the
// TOAST, is silently dropped, and the intended action never happens. CI's slower
// timing keeps a toast over the trigger deterministically, producing flaky failures
// (e.g. documents.spec's +N popover and bulk.spec's multi-select actions) that are
// pure input-drop races, not app bugs.
//
// The fix: `pointer-events: none` on the toast layer means clicks pass THROUGH the
// toast to whatever is beneath it. We never CLICK a toast in e2e — at most we assert
// its text, which needs no pointer events — so this is safe and deterministic at any
// viewport, on both the desktop and mobile projects (harmless on desktop). It changes
// no pixels, so the @visual baselines are unaffected (pointer-events is not a paint
// property).
//
// SCOPE: this rule only neutralises the TOAST layer. It deliberately does NOT touch
// modal masks (`.p-overlay-mask`/`.p-dialog-mask`) — those are legitimately modal and a
// global pointer-events kill would defeat their modality. (trash.spec:51's separate
// empty-trash failure turned out to be a SERVER 500, fixed in FileUtil, not an overlay
// issue — see trash.spec.ts.)
//
// Delivered via addInitScript so it runs BEFORE page scripts and survives every
// in-app navigation (SPA route changes AND full page.goto reloads) for the whole
// test — no per-navigation re-injection needed, no per-spec calls.
const TOAST_CLICK_THROUGH_CSS = '.p-toast,.p-toast-message{pointer-events:none !important}'

/**
 * Deferred-cleanup fixture (#187).
 *
 * The idiom this replaces — teardown inside the body's own `finally` — has two
 * defects that cost real debugging time:
 *
 *  1. A throwing teardown SUPERSEDES the body's exception. The failure the report
 *     shows is the cleanup's ("Delete button not visible"), and the actual defect is
 *     gone. Worse, the teardown drives the very UI the failed body left broken, so
 *     the masking is not rare — it is the common case.
 *  2. A hanging teardown converts a precise assertion failure into a bare test
 *     timeout.
 *
 * `cleanup.defer(label, fn)` registers a teardown step that runs AFTER the body, in
 * REGISTRATION ORDER (FIFO). FIFO — not LIFO — is deliberate: a migrated `finally`
 * block's statements ran top-to-bottom, and registering them in that same order
 * reproduces the original teardown semantics exactly. It also keeps order-sensitive
 * teardown correct where LIFO would invert it (guest.spec.ts must disable
 * `guest_login` BEFORE closing the guest context).
 *
 * Each step is run individually, caught individually, and bounded by its own timeout,
 * so one broken step neither aborts the rest nor turns into a suite-level hang. Then:
 *
 *  - body FAILED  → cleanup errors are ATTACHED as a `cleanup-failures` diagnostic and
 *                   swallowed. The reported failure stays the body's.
 *  - body PASSED  → a failed cleanup step is thrown, so the test goes RED. Cleanup is
 *                   not allowed to fail silently and leak entities.
 *
 * This is NOT a blanket `try/catch` swallow: a broken teardown is always either the
 * reported failure or an attached diagnostic — never invisible.
 */
export interface CleanupFixture {
  /**
   * Register a teardown step. Steps run after the test body in registration order.
   * `timeout` bounds this individual step (default 10s) so a hanging teardown cannot
   * consume the test's own budget.
   */
  defer(label: string, fn: () => unknown | Promise<unknown>, options?: { timeout?: number }): void
}

const DEFAULT_STEP_TIMEOUT_MS = 10_000

interface DeferredStep {
  label: string
  fn: () => unknown | Promise<unknown>
  timeout: number
}

function describeError(error: unknown): string {
  if (error instanceof Error) return error.stack ?? `${error.name}: ${error.message}`
  return String(error)
}

// Bound one step. A step that never settles is abandoned (its promise is left dangling
// on purpose — Playwright disposes the underlying page/request context afterwards) and
// reported as a timeout, so a hang surfaces as a named cleanup failure rather than as a
// test-level timeout that hides the body's real error.
async function runStep(step: DeferredStep): Promise<void> {
  let timer: ReturnType<typeof setTimeout> | undefined
  const bound = new Promise<never>((_resolve, reject) => {
    timer = setTimeout(
      () => reject(new Error(`cleanup step "${step.label}" did not settle within ${step.timeout}ms`)),
      step.timeout,
    )
  })
  // `.finally()` (the promise method), not a `try/finally` block: this file is inside
  // the no-teardown-in-finally lint scope, and the method form carries no finalizer.
  await Promise.race([Promise.resolve().then(step.fn), bound]).finally(() => clearTimeout(timer))
}

// The body has already failed if Playwright recorded an error for this test (assertion
// failure, thrown error, or the test-level timeout). `testInfo.errors` is populated as
// failures happen, so it is readable from fixture teardown; `status` is checked too
// because a timed-out test can reach teardown before its error is appended.
function bodyAlreadyFailed(testInfo: TestInfo): boolean {
  return testInfo.errors.length > 0 || testInfo.status === 'failed' || testInfo.status === 'timedOut'
}

export const test = base.extend<{ cleanup: CleanupFixture }>({
  page: async ({ page }, use) => {
    await page.addInitScript((css: string) => {
      const inject = () => {
        if (!document.head) return
        if (document.getElementById('e2e-toast-click-through')) return
        const style = document.createElement('style')
        style.id = 'e2e-toast-click-through'
        style.textContent = css
        document.head.appendChild(style)
      }
      // head may not exist yet when the init script runs on a fresh document; retry
      // once the DOM is ready. Idempotent via the id guard above.
      inject()
      if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', inject, { once: true })
      }
    }, TOAST_CLICK_THROUGH_CSS)
    await use(page)
  },

  // `page` and `request` are declared as DEPENDENCIES, not because this fixture uses
  // them, but because Playwright tears fixtures down in reverse dependency order:
  // depending on both guarantees they are still alive while deferred steps run.
  // Without the `page` dependency, `page` (and with it `page.request`) can be disposed
  // first, every API teardown throws "Target page, context or browser has been closed",
  // the per-step catch turns it into a diagnostic, and EVERY test entity leaks silently.
  cleanup: async ({ page, request }, use, testInfo) => {
    void page
    void request

    const steps: DeferredStep[] = []
    await use({
      defer(label, fn, options) {
        steps.push({ label, fn, timeout: options?.timeout ?? DEFAULT_STEP_TIMEOUT_MS })
      },
    })

    const bodyFailed = bodyAlreadyFailed(testInfo)
    const failures: Array<{ label: string; error: unknown }> = []
    for (const step of steps) {
      try {
        await runStep(step)
      } catch (error) {
        failures.push({ label: step.label, error })
      }
    }
    if (failures.length === 0) return

    const report = failures
      .map(({ label, error }) => `- ${label}\n  ${describeError(error)}`)
      .join('\n\n')
    // `attach` is awaited — an un-awaited attach loses the attachment.
    await testInfo.attach('cleanup-failures', {
      body: `${failures.length} deferred cleanup step(s) failed:\n\n${report}\n`,
      contentType: 'text/plain',
    })

    // The body's failure is the real one; the cleanup noise it caused stays a
    // diagnostic. Only a cleanup that broke on its own turns a green body red.
    if (bodyFailed) return
    throw new Error(
      `${failures.length} deferred cleanup step(s) failed after a passing test body:\n\n${report}\n`,
    )
  },
})

export { expect }
