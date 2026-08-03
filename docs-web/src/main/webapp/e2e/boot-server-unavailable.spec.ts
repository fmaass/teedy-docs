import { test, expect, type Page, type Route } from './fixtures'
import { ROUTE_ROOT, expectRouteReady, gotoRaw, gotoRouteReady } from './helpers'

// #245 — a /api/user failure that says nothing about the session must not be reported as
// "anonymous".
//
// The defect: the auth store's fetchCurrentUser caught EVERY rejection and set `user = null`. The
// router guard (which the boot awaits before mounting, #216) then read that as anonymous and bounced
// to /#/login. So one transient 5xx — a request that lands while the backend is still finishing its
// own start-up — logged a signed-in user out, with no indication that anything had gone wrong.
//
// These specs make the failure DETERMINISTIC instead of hoping to catch a real one: the app is
// booted with /api/user under interception, serving exactly the failure each case is about. The
// transient case must boot normally; the persistent case must land on an honest outage surface
// rather than a credential form that cannot work.

// GET /api/user — the auth guard's fetch. Narrow enough not to catch /api/user/list and friends.
const API_USER = /\/api\/user(\?|$)/

const INTERNAL_ERROR_BODY = JSON.stringify({ type: 'InternalServerError', message: 'unavailable' })

/** Every /api/user request the run saw, so a precondition can be stated instead of assumed. */
type UserRequestLog = { served500: number; total: number }

/**
 * Intercept /api/user and fail the first `failures` of them with a 500; let the rest through.
 * `failures: Infinity` is the persistent-outage case.
 */
async function interceptUserApi(page: Page, failures: number): Promise<UserRequestLog> {
  const log: UserRequestLog = { served500: 0, total: 0 }
  await page.route(API_USER, async (route: Route) => {
    log.total += 1
    if (log.served500 < failures) {
      log.served500 += 1
      await route
        .fulfill({ status: 500, contentType: 'application/json', body: INTERNAL_ERROR_BODY })
        .catch(() => {})
      return
    }
    // A route still pending when the page navigates away can no longer be continued; that is a
    // torn-down request, not a test failure.
    await route.continue().catch(() => {})
  })
  return log
}

test.describe('#245 a transient /api/user failure at boot does not log the user out', () => {
  // The store waits before its single retry, and each boot here pays that wait at least once.
  test.setTimeout(60_000)

  test('a single 500 is retried and the app boots signed in, with no login bounce', async ({ page }) => {
    const log = await interceptUserApi(page, 1)

    await gotoRouteReady(page, '/#/document', ROUTE_ROOT.documentList)

    // Non-vacuity: this passes trivially if the interception never fired, so prove it did — one
    // 500 served, and a SECOND request afterwards, which is the store's retry and nothing else
    // (the guard fetches once per boot).
    expect(log.served500, 'the boot really did receive a 500 from /api/user').toBe(1)
    expect(log.total, "the store retried that failure exactly once — the retry is what recovers the boot").toBe(2)

    // The behaviour under test: the session survived the blip.
    await expect(page.locator(ROUTE_ROOT.documentList)).toBeVisible()
    expect(new URL(page.url()).hash, 'the boot stayed on the requested route instead of bouncing to login').toBe(
      '#/document',
    )
    await expect(
      page.locator(ROUTE_ROOT.login),
      'the login page never rendered — a transient failure is not a sign-out',
    ).toHaveCount(0)
  })

  test('a persistent 500 shows the outage surface, not a credential form', async ({ page }) => {
    const log = await interceptUserApi(page, Infinity)

    // gotoRaw: the landing route is deliberately NOT the requested one — this test proves the
    // guard's availability bounce, so pinning /#/document would assert the opposite.
    await gotoRaw(page, '/#/document')
    await expectRouteReady(page, '/#/login', ROUTE_ROOT.login)

    expect(log.total, 'the store made both attempts before giving up — one try and one retry').toBe(2)

    // The honest surface: an error with a Retry, in the login shell.
    await expect(page.locator(`${ROUTE_ROOT.login} .teedy-error`)).toBeVisible()
    await expect(page.getByRole('button', { name: 'Retry' })).toBeVisible()
    // And NOT the credential form: offering a sign-in that cannot succeed is what made the outage
    // indistinguishable from having been logged out.
    await expect(
      page.locator('#login-user'),
      'the username field is withheld while the server cannot answer',
    ).toHaveCount(0)
  })

  test('Retry after the server recovers lands on the documents list', async ({ page }) => {
    // Fail the boot's two attempts, then serve normally — so the Retry click is the first request
    // that succeeds.
    const log = await interceptUserApi(page, 2)

    // gotoRaw: same deliberate bounce as above — the boot lands on the outage surface, not on the
    // requested route.
    await gotoRaw(page, '/#/document')
    await expectRouteReady(page, '/#/login', ROUTE_ROOT.login)
    await expect(page.locator(`${ROUTE_ROOT.login} .teedy-error`)).toBeVisible()
    expect(log.served500, 'the boot exhausted its one retry before the surface appeared').toBe(2)

    await page.getByRole('button', { name: 'Retry' }).click()

    await expectRouteReady(page, '/#/document', ROUTE_ROOT.documentList)
    await expect(page.locator(ROUTE_ROOT.documentList)).toBeVisible()
  })
})

// The IdP hand-off is the one thing that can hide the outage entirely: Login.vue auto-redirects to
// the provider whenever GET /api/app reports OIDC enabled, so on an SSO instance the outage surface
// would be replaced by a round-trip that returns to a still-broken /api/user — and a provider that
// keeps its own session sends the browser straight back, which is a loop.
//
// OIDC is enabled by rewriting the app-info RESPONSE rather than the server's configuration: it
// isolates these two tests completely (no shared config to restore, no ordering coupling with
// oidc.spec.ts) and makes the flag true on exactly the boot under test. `api/oidc/login` is stubbed
// as well, so the control's redirect resolves against a local stub instead of a real IdP hand-off.
test.describe('#245 the outage surface is not hidden by the OIDC auto-login', () => {
  test.setTimeout(60_000)

  // Both tests boot ANONYMOUS, so the only thing that differs between the subject and its control
  // is whether /api/user answers. A signed-in storageState would change two variables at once and
  // the control would prove nothing about the suppression.
  test.use({ storageState: { cookies: [], origins: [] } })

  const OIDC_LOGIN = /\/api\/oidc\/login(\?|$)/

  /** Report /api/app with oidc_enabled true, and record every hand-off to api/oidc/login. */
  async function enableOidcAndStubHandoff(page: Page): Promise<{ handoffs: number }> {
    const state = { handoffs: 0 }
    await page.route(/\/api\/app(\?|$)/, async (route: Route) => {
      const response = await route.fetch().catch(() => null)
      if (!response) {
        await route.continue().catch(() => {})
        return
      }
      const body = await response.json()
      await route
        .fulfill({
          response,
          contentType: 'application/json',
          body: JSON.stringify({ ...body, oidc_enabled: true }),
        })
        .catch(() => {})
    })
    await page.route(OIDC_LOGIN, async (route: Route) => {
      state.handoffs += 1
      await route
        .fulfill({ status: 200, contentType: 'text/html', body: '<html><body>idp stub</body></html>' })
        .catch(() => {})
    })
    return state
  }

  test('the auto-redirect is suppressed while the server is unavailable', async ({ page }) => {
    const handoff = await enableOidcAndStubHandoff(page)
    await interceptUserApi(page, Infinity)

    // The decision point: Login.vue reads oidc_enabled off THIS response and redirects (or does
    // not) on the continuation. Waiting for it is what makes the negative assertion below an
    // observation rather than a guess about timing.
    const appInfoServed = page.waitForResponse(/\/api\/app(\?|$)/)
    // gotoRaw: the guard's availability bounce is the subject, so the landing route is deliberately
    // not the requested one.
    await gotoRaw(page, '/#/document')
    await expectRouteReady(page, '/#/login', ROUTE_ROOT.login)
    await appInfoServed
    await expect(page.locator(`${ROUTE_ROOT.login} .teedy-error`)).toBeVisible()

    // The response EVENT precedes the redirect decision (axios still has to consume the body and
    // the mounted continuation has to run), so asserting zero hand-offs immediately would pass
    // even with the suppression reverted. Hold a bounded observation window instead: the healthy
    // control below proves the redirect lands within a few hundred milliseconds of /api/app on
    // this same machine, so a 2s window is a >5x margin for the negative to be meaningful.
    await page.waitForTimeout(2000)

    // Still on the outage surface, and still in the SPA: a hand-off would have replaced the whole
    // document with the IdP stub, so all three of these would fail.
    await expect(page.locator(`${ROUTE_ROOT.login} .teedy-error`)).toBeVisible()
    expect(new URL(page.url()).hash, 'the browser never left the SPA for the provider').toBe('#/login')
    expect(handoff.handoffs, 'the browser was never handed to the IdP — the outage stays visible').toBe(0)
  })

  // The differential control: SAME OIDC fixture, healthy server. It proves the assertion above is
  // about the outage state and not about the fixture failing to enable OIDC at all.
  test('the auto-redirect still fires on a healthy server (control)', async ({ page }) => {
    const handoff = await enableOidcAndStubHandoff(page)

    // gotoRaw: this boot deliberately leaves the SPA — the hand-off IS the expected outcome.
    await gotoRaw(page, '/#/document')

    await expect
      .poll(() => handoff.handoffs, { message: 'Login.vue handed the browser to the IdP as it normally does' })
      .toBe(1)
  })
})
