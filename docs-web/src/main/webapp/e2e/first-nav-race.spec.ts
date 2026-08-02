import { test, expect, type Page } from './fixtures'
import { gotoRaw } from './helpers'

// #216 — a navigation that arrives WHILE the app is still booting must win over the
// initial one. The user's latest intent is authoritative.
//
// The defect: vue-router's first navigation awaits the lazy route chunk and the async
// auth guard (src/router/index.ts). Its `finalizeNavigation` ends that navigation by
// REPLACING the URL with its own target (isFirstNavigation branch) and only then marks
// the router ready and starts listening for history events. A navigation issued inside
// that window therefore updates `location`, is never observed by the router, and is
// silently reverted by that replace: the URL snaps back and the app renders the route
// the user has already moved on from. Measured in triage at 10/10 on a fast LAN box
// with no CPU pinning and no added latency, and the window widens with the link speed
// (~350ms unthrottled, 4.3s on Fast 3G, 16s on Slow 3G).
//
// These specs make that window WIDE and DETERMINISTIC instead of hoping to hit it:
//   * every route chunk / stylesheet AND every /api call is held back BOOT_DELAY_MS,
//     which stretches the window from a few hundred ms to several seconds;
//   * the second navigation is fired on OBSERVING the boot's own `/api/user` request —
//     the async guard's fetch, i.e. the moment the first navigation starts waiting.
//     Strict observation, never a "poll for a while and continue anyway" deadline: a
//     boot that stops issuing that request fails the spec instead of quietly firing the
//     second navigation outside the window and passing.
//
// Every test asserts its own preconditions before it asserts the behaviour, so a future
// refactor that removes the delay interception, the guard's request, or the race window
// itself fails LOUDLY rather than passing vacuously.

// The deep link the boot navigates to (and, on a clobber, snaps back to).
const BOOT_URL = '/#/document'
const BOOT_ROOT = '.doc-list-page'
// The navigation the user issues while that boot is still in flight.
const LATE_HASH = '#/tag'
const LATE_ROOT = '.tag-list-page'

// Held back on every route chunk and every API call. 1500ms is the triage's
// deterministic cell: it puts the whole window in the seconds range on any machine,
// so the fire point is never a coin flip on runner speed.
const BOOT_DELAY_MS = 1500

// The boot's auth-guard fetch (src/router/index.ts `beforeEach` -> fetchCurrentUser).
const API_USER = /\/api\/user(\?|$)/
// Vite's hashed route chunks and stylesheets — what the delay is aimed at.
const ROUTE_CHUNK = /\/assets\/.*\.(js|css)(\?|$)/

type MountProbe = { __routeMounts: string[] }

/**
 * Records, IN PAGE, the order in which route roots first appear in the DOM.
 *
 * This is what distinguishes "the right page won" from "the right page won eventually":
 * a fix that lets the clobbered route mount and then corrects itself would leave both
 * roots in this list, and the flash it produces is exactly what the fix must not do.
 * Installed via addInitScript so it observes from before the app's own scripts run.
 */
function recordRouteMounts(roots: string[]) {
  const probe = window as unknown as MountProbe
  probe.__routeMounts = []
  const scan = () => {
    for (const selector of roots) {
      if (!probe.__routeMounts.includes(selector) && document.querySelector(selector)) {
        probe.__routeMounts.push(selector)
      }
    }
  }
  const start = () => {
    new MutationObserver(scan).observe(document.documentElement, { childList: true, subtree: true })
    scan()
  }
  if (document.documentElement) start()
  else document.addEventListener('DOMContentLoaded', start)
}

function routeMounts(page: Page): Promise<string[]> {
  return page.evaluate(() => (window as unknown as MountProbe).__routeMounts)
}

/** A request the probe timed, so evidence can be stated as an ORDER and not just a count. */
type TimedRequest = { url: string; at: number }

type BootProbe = {
  /** Every route chunk / stylesheet the delay was actually applied to. */
  delayedChunks: TimedRequest[]
  /** Every boot request for the auth guard's `/api/user`. */
  apiUserRequests: TimedRequest[]
}

/**
 * Arm the slow-boot conditions on `page`: the mount recorder, the per-response delay,
 * and the counters the preconditions are asserted from. Call it BEFORE the navigation
 * whose boot is under test (its init script applies from the next navigation on).
 */
async function armSlowBoot(page: Page): Promise<BootProbe> {
  const probe: BootProbe = { delayedChunks: [], apiUserRequests: [] }
  page.on('request', (request) => {
    if (API_USER.test(request.url())) probe.apiUserRequests.push({ url: request.url(), at: Date.now() })
  })
  await page.addInitScript(recordRouteMounts, [BOOT_ROOT, LATE_ROOT])
  await page.route('**/*', async (route) => {
    const url = route.request().url()
    const isChunk = ROUTE_CHUNK.test(url)
    if (isChunk) probe.delayedChunks.push({ url, at: Date.now() })
    if (isChunk || url.includes('/api/')) {
      await new Promise((resolve) => setTimeout(resolve, BOOT_DELAY_MS))
    }
    // A route still pending when the page navigates away can no longer be continued;
    // that is a torn-down request, not a test failure.
    await route.continue().catch(() => {})
  })
  return probe
}

/**
 * Assert the race window is genuinely OPEN right now — the permanent non-vacuity control.
 *
 * All three have to hold for the behaviour assertion below to mean anything: the delay was
 * really applied, the guard really fetched (so the first navigation is really waiting), and
 * the first navigation has not rendered its destination yet (so what follows lands inside
 * the window, not after it).
 */
async function expectWindowOpen(page: Page, probe: BootProbe): Promise<void> {
  expect(
    probe.delayedChunks.length,
    'the route-chunk delay was applied to at least one chunk — the boot window is widened on purpose, ' +
      'and a build that no longer serves hashed chunks from /assets would make this spec test nothing',
  ).toBeGreaterThan(0)
  expect(
    probe.apiUserRequests.length,
    "the boot issued the auth guard's /api/user request — that fetch IS the first navigation's wait, " +
      'and without it there is no window to race',
  ).toBeGreaterThan(0)
  expect(
    await routeMounts(page),
    'no route has mounted yet — the first navigation is still in flight, so the navigation ' +
      'fired next lands INSIDE the window rather than after it',
  ).toEqual([])
}

/**
 * Assert the delay landed on the NAVIGATION and not merely on the entry bundle.
 *
 * A chunk delayed before the guard fetch only proves the page loaded slowly. The lazily
 * imported route component is requested by vue-router AFTER the async guard resolves — it is
 * the second half of what the first navigation waits on — so a delayed chunk timed after the
 * guard fetch is the one that actually holds the window open. Checked AFTER the boot settles,
 * because at fire time that request has not been issued yet.
 *
 * Only for boots on a COLD cache. The Back test reaches its boot through a reload, where the
 * route chunks are already cached and may be served without a network request at all, so
 * there is nothing for `page.route` to intercept and the absence would prove nothing.
 */
function expectDelayedRouteChunk(probe: BootProbe): void {
  const guardFetchedAt = probe.apiUserRequests[0].at
  expect(
    probe.delayedChunks.filter((chunk) => chunk.at >= guardFetchedAt).map((chunk) => chunk.url),
    'a delayed chunk was requested AFTER the guard fetch — the lazily imported route component, ' +
      'i.e. the delay is holding the first navigation open and not just the initial bundle',
  ).not.toEqual([])
}

/** Wait until the boot has put a route on screen, then report which roots ever mounted. */
async function settledMounts(page: Page): Promise<string[]> {
  await expect
    .poll(async () => (await routeMounts(page)).length, {
      timeout: 60_000,
      message: 'the SPA finished booting and mounted a route',
    })
    .toBeGreaterThan(0)
  return routeMounts(page)
}

test.describe('#216 a navigation during boot beats the first navigation', () => {
  // The deliberate BOOT_DELAY_MS on every chunk and API call puts a single boot in the
  // several-second range; the suite default of 30s is not enough for boot + settle.
  test.setTimeout(120_000)

  test('a hash navigation issued during boot wins', async ({ page }) => {
    const probe = await armSlowBoot(page)

    // Strict observation: this REJECTS if the guard's fetch never happens, instead of
    // continuing on a deadline and firing outside the window.
    const guardFetched = page.waitForRequest(API_USER, { timeout: 60_000 })
    // gotoRaw: the raw, un-awaited boot navigation IS the subject here — a readiness helper
    // would wait out the very window this test has to act inside.
    await gotoRaw(page, BOOT_URL)
    await guardFetched

    await expectWindowOpen(page, probe)

    // A bookmark, a typed URL or an in-page anchor: a same-document hash navigation.
    await page.evaluate((hash) => {
      window.location.hash = hash
    }, LATE_HASH)

    expect(
      await settledMounts(page),
      'the boot mounted ONLY the route the user asked for last — the first navigation neither ' +
        'reverted it nor flashed its own route on the way',
    ).toEqual([LATE_ROOT])
    await expect(page.locator(LATE_ROOT)).toBeVisible()
    expect(new URL(page.url()).hash, 'the URL kept the navigation the user issued').toBe(LATE_HASH)
    expectDelayedRouteChunk(probe)
  })

  test('browser Back pressed during boot wins', async ({ page }) => {
    // Build a same-document history so Back during boot lands on the tag list: visit the
    // tag page, then the document list, then RELOAD (that reload is the boot under test)
    // and press Back. These two navigations run at full speed — the delay and the mount
    // recorder are armed afterwards, so only the reload's boot is instrumented.
    // gotoRaw: these two are the goto half of a goto-then-reload pair — the reload below is
    // the boot under test, so readiness belongs after it, and each is followed by its own
    // destination-root wait anyway.
    await gotoRaw(page, `/${LATE_HASH}`)
    await expect(page.locator(LATE_ROOT)).toBeVisible()
    await gotoRaw(page, BOOT_URL)
    await expect(page.locator(BOOT_ROOT)).toBeVisible()

    const probe = await armSlowBoot(page)
    const guardFetched = page.waitForRequest(API_USER, { timeout: 60_000 })
    await page.reload({ waitUntil: 'commit' })
    await guardFetched

    await expectWindowOpen(page, probe)

    // The other realistic trigger, and the one deferring the mount cannot cover on its
    // own: routing starts when the router is installed, long before anything is mounted.
    await page.goBack({ waitUntil: 'commit' })

    expect(
      await settledMounts(page),
      'the boot honoured the Back press instead of swallowing it, and never flashed the route ' +
        'it was already navigating to',
    ).toEqual([LATE_ROOT])
    await expect(page.locator(LATE_ROOT)).toBeVisible()
    expect(new URL(page.url()).hash, 'Back left the URL on the entry it went back to').toBe(LATE_HASH)
  })

  // The differential control for the two tests above: the SAME slow boot and the SAME
  // navigation, issued once the window has CLOSED. It passes with and without the fix, so
  // a red pair above is evidence about the window and not about the delay, the selectors
  // or the harness.
  test('a hash navigation issued after boot completes wins (control)', async ({ page }) => {
    const probe = await armSlowBoot(page)

    const guardFetched = page.waitForRequest(API_USER, { timeout: 60_000 })
    // gotoRaw: same raw boot navigation as the race tests — this control differs only in
    // WHEN the second navigation is fired, so it must arrive the same way.
    await gotoRaw(page, BOOT_URL)
    await guardFetched

    await expectWindowOpen(page, probe)

    // Let the first navigation finish completely — the window is closed once its own
    // route is on screen, which is the point this control differs on.
    expect(await settledMounts(page), 'the boot landed on its deep link').toEqual([BOOT_ROOT])

    await page.evaluate((hash) => {
      window.location.hash = hash
    }, LATE_HASH)

    await expect(page.locator(LATE_ROOT)).toBeVisible()
    expect(
      await routeMounts(page),
      'a post-boot navigation is honoured normally, after the boot route it replaces',
    ).toEqual([BOOT_ROOT, LATE_ROOT])
    expect(new URL(page.url()).hash).toBe(LATE_HASH)
    expectDelayedRouteChunk(probe)
  })
})
