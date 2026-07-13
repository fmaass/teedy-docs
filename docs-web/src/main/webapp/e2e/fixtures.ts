import { test as base, expect } from '@playwright/test'

// Re-export every other named/type export (request, type Page, type Locator,
// type APIRequestContext, type ConsoleMessage, type Request, …) so specs can import
// them from './fixtures' exactly as they did from '@playwright/test'. Only `test` is
// overridden below with the toast-click-through fixture; `expect` is re-exported
// unchanged. `export *` does not re-export the default or a named `test`, so our
// override is authoritative.
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

export const test = base.extend<Record<never, never>>({
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
})

export { expect }
