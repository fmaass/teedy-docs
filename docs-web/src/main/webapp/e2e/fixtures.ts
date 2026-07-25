import {
  test as base,
  expect,
  request as pwRequest,
  type APIRequestContext,
  type TestInfo,
} from '@playwright/test'
import { appendFileSync, mkdirSync } from 'node:fs'
import { resolve } from 'node:path'

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

/**
 * Shared-admin baseline assertion (#186 instrumentation — an ASSERTION, never a restore).
 *
 * Almost every spec drives the UI through ENGLISH, LIGHT-MODE selectors while sharing ONE
 * server-side account (`admin`). Two of the account's preferences are persisted SERVER-SIDE
 * and re-seeded into any fresh browser context that carries no explicit on-device choice
 * (`stores/auth.ts:41` for the locale, `:56` for dark mode) — and the shared storageState
 * carries neither key. So a spec that leaves `locale=de` or `dark_mode=true` behind on the
 * admin account silently re-themes/re-languages EVERY LATER SPEC IN THE RUN, at which point
 * failures appear as unrelated selector timeouts in unrelated files.
 *
 * This fixture reads the account's authoritative state before every test and fails LOUDLY the
 * moment it has drifted, naming the last test that completed — turning "some spec later times
 * out on a button" into "spec X left the admin locale at de".
 *
 * Deliberate design points:
 *  - A DEDICATED authenticated admin API context, never the spec's own. `two-factor.spec.ts:8`,
 *    `auth.spec.ts` and `locale-persist.spec.ts` run on cleared storage state, where a
 *    context-borrowed probe would simply 403 and check nothing at all.
 *  - The probe NEVER LOGS IN. It is hydrated from the storage-state file global setup already
 *    produced, and issues GETs only. This is load-bearing for the investigation, not a
 *    convenience: a successful `POST /api/user/login` calls `recordLoginSuccess`, which CLEARS
 *    the account / network / pair lockout counters in `LoginThrottleStore` — so a probe that
 *    logged in would reset the very state #186 suspects, most visibly after a failure (a
 *    replacement worker would start against a freshly-cleared throttle). A probe that mutates
 *    its own subject proves nothing.
 *  - A storage state that cannot read the account is a HARD ERROR naming the cause. There is
 *    deliberately no login fallback: silently re-authenticating would reintroduce exactly the
 *    perturbation above, at the least observable moment.
 *  - The context is created ONCE per worker and reused (workers:1, fullyParallel:false), so the
 *    probe costs one GET per test and zero logins.
 *  - It ASSERTS and never repairs: a restore would hide the very leak being hunted, and the
 *    plan's Do-NOT list forbids a global restore hook.
 *  - The accepted baseline is "absent OR the English/light value": specs that restore correctly
 *    POST `locale=en` explicitly (a pristine container has the field absent), and both states
 *    render the same English light UI the selectors assume.
 */
const BASELINE_DESCRIPTION = 'locale ∈ {absent, "en"} and dark_mode ∈ {absent, false}'

interface AdminPreferences {
  locale?: string | null
  dark_mode?: boolean | null
}

// Written next to the harness-preserved server logs so a run's diagnostics live together.
// `e2e-artifacts/` at the repo root is already git-ignored. The harness exports E2E_DIAG_DIR;
// the relative fallback keeps a bare `npx playwright test` working from the webapp directory.
const DIAG_DIR = process.env.E2E_DIAG_DIR ?? resolve(process.cwd(), '../../../../e2e-artifacts')

// Diagnostics must never be the reason a test fails: every write is best-effort.
function recordDiagnostic(event: Record<string, unknown>): void {
  try {
    mkdirSync(DIAG_DIR, { recursive: true })
    appendFileSync(
      resolve(DIAG_DIR, 'admin-baseline-drift.jsonl'),
      `${JSON.stringify({ at: new Date().toISOString(), ...event })}\n`,
    )
  } catch {
    // A read-only or missing artifacts directory is not a test failure.
  }
}

let adminProbe: APIRequestContext | null = null
let lastCompletedTest = '<none — this is the first test of the run>'

async function describeProbeFailure(res: { status(): number; headers(): Record<string, string>; text(): Promise<string> }) {
  const body = await res.text().catch(() => '<unreadable body>')
  return `HTTP ${res.status()} | Retry-After: ${res.headers()['retry-after'] ?? '<absent>'} | body: ${body.slice(0, 500)}`
}

// The authenticated session global setup already produced. Taken from the PROJECT-level `use`
// so a spec-level `test.use({ storageState: … })` override (which is how the login specs clear
// their state) can never point the probe at an unauthenticated context.
function adminStorageStatePath(testInfo: TestInfo): string {
  const configured = testInfo.project.use?.storageState
  if (typeof configured !== 'string') {
    throw new Error(
      'admin-baseline probe needs the project-level storageState FILE produced by global setup ' +
        `(playwright.config.ts projects[].use.storageState), but found: ${JSON.stringify(configured)}. ` +
        'The probe must not log in — see the fixture comment.',
    )
  }
  return configured
}

async function openAdminProbe(baseURL: string, storageState: string): Promise<APIRequestContext> {
  // No login: the session is READ from disk. See the fixture comment for why this is mandatory.
  return pwRequest.newContext({ baseURL, storageState })
}

async function readAdminPreferences(baseURL: string, storageState: string): Promise<AdminPreferences> {
  if (!adminProbe) adminProbe = await openAdminProbe(baseURL, storageState)

  const res = await adminProbe.get('/api/user')
  if (!res.ok()) {
    throw new Error(
      `admin-baseline probe could not read /api/user using the stored admin session ` +
        `(${storageState}) — ${await describeProbeFailure(res)}. The probe deliberately does NOT ` +
        `log in (a login would clear the throttle state under investigation); re-run global setup.`,
    )
  }
  const body = (await res.json()) as AdminPreferences & { anonymous?: boolean }
  if (body?.anonymous) {
    throw new Error(
      `admin-baseline probe read /api/user as ANONYMOUS using the stored admin session ` +
        `(${storageState}) — that session has expired or was never authenticated. The probe ` +
        `deliberately does NOT log in (a login would clear the throttle state under ` +
        `investigation); re-run global setup.`,
    )
  }
  return { locale: body?.locale, dark_mode: body?.dark_mode }
}

function baselineViolation(prefs: AdminPreferences): string | null {
  const problems: string[] = []
  if (prefs.locale != null && prefs.locale !== 'en') {
    problems.push(`locale = ${JSON.stringify(prefs.locale)} (expected absent or "en")`)
  }
  if (prefs.dark_mode != null && prefs.dark_mode !== false) {
    problems.push(`dark_mode = ${JSON.stringify(prefs.dark_mode)} (expected absent or false)`)
  }
  return problems.length ? problems.join('; ') : null
}

export const test = base.extend<{ cleanup: CleanupFixture; adminBaseline: void }>({
  // Automatic: it must guard specs that never ask for it, which is all of them.
  adminBaseline: [
    async ({ baseURL }, use, testInfo) => {
      const thisTest = `[${testInfo.project.name}] ${testInfo.titlePath.filter(Boolean).join(' › ')}`
      if (!baseURL) throw new Error('admin-baseline probe needs a baseURL (config `use.baseURL`)')

      const prefs = await readAdminPreferences(baseURL, adminStorageStatePath(testInfo))
      const violation = baselineViolation(prefs)
      if (violation) {
        recordDiagnostic({
          kind: 'admin-baseline-drift',
          detectedBefore: thisTest,
          lastCompletedTest,
          observed: prefs,
          violation,
        })
        // Not repaired on purpose: the drift is the finding. `lastCompletedTest` is NOT advanced
        // for a test whose body never ran, so every subsequent failure keeps naming the real culprit.
        throw new Error(
          `Shared-admin server-side baseline has DRIFTED (#186 instrumentation).\n` +
            `  expected: ${BASELINE_DESCRIPTION}\n` +
            `  observed: ${violation}\n` +
            `  last test that completed before this one: ${lastCompletedTest}\n` +
            `  Every later spec inherits this state: a fresh browser context with no on-device\n` +
            `  choice re-seeds the UI locale and dark mode from the account (stores/auth.ts:41,:56),\n` +
            `  so English/light selectors elsewhere will fail for reasons of their own.`,
        )
      }

      await use()

      lastCompletedTest = thisTest
    },
    { auto: true },
  ],

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
