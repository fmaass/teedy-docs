import {
  test,
  expect,
  request as pwRequest,
  type APIRequestContext,
  type CleanupFixture,
  type Page,
} from './fixtures'
import {
  unique,
  uniqueTag,
  isMobileViewport,
  deleteDocApi,
  deleteTagApi,
  gotoDocumentList,
  login,
  MAX_TAG_NAME_LENGTH,
} from './helpers'

// #284 — the quick menu's tag list, MEASURED. Two glitches the reporter hit on an instance
// with many tags, both pure CSS and both engine-independent (reproduced in Chromium and
// Firefox at identical sub-pixel values):
//
//  1. `.tqm-option { overflow: hidden }` zeroes the row's automatic minimum size (CSS Flexbox
//     §4.5), so the bounded, column-flex list above it squashed every row down to its padding
//     — 9.6px tall with a text box of 0 — from roughly 20 assignable tags on. The list then
//     painted as an EMPTY bordered box whose rows were still full-width buttons that added a
//     tag on click: an invisible mis-tagging hazard, not a cosmetic glitch.
//  2. `.tqm-body { width: 15rem }` pinned the panel at 240px whatever the content, and the row
//     carried no `title`, so a realistic tag name was clipped by ~106px with no way to read
//     the rest (the aria-label has it, but only a screen reader gets that).
//
// Why the rest of the suite could not catch it, and this spec has to exist:
//   * `tag-add-focus.spec.ts` always FILTERS the list down to one survivor before asserting —
//     one row never shrinks, because there is no negative free space to distribute.
//   * Playwright's `isVisible()` returns TRUE for a 9.6px zero-content row, so the existing
//     `toBeVisible()` on the first option stayed green through a fully collapsed list.
//   * `src/components/TagQuickMenu.spec.ts` is jsdom — no layout engine at all.
//   * No visual baseline captures the menu open (`visual.spec.ts` has zero `tqm-`/contextmenu
//     hits), so the screenshot gate never saw it either.
// The measurement therefore has to be geometric, taken from a list that is genuinely OVER the
// collapse threshold — which is why the seeding below is deliberately large.

// Comfortably past the ~20-tag threshold at which the rows bottomed out, so the red is the
// steady state and not a borderline one.
const SEEDED_TAGS = 25

// One tag at the server's ceiling but for a character (TagResource caps a name at
// MAX_TAG_NAME_LENGTH): the reporter's names are 23–35 characters, and the clipping only
// shows on a realistic one.
const LONG_NAME_LENGTH = MAX_TAG_NAME_LENGTH - 1

// A name that FITS the widened panel, so "not clipped" is asserted where it is a real
// property. It is deliberately not the long name: no finite width accommodates every
// possible tag name, which is why the long name's contract is the recoverable `title`
// rather than a promise that it always fits.
const SHORT_NAME_LENGTH = 20

// The rendered text box floor. A row that keeps its single line of ~19px text measures ~28.6px;
// a fully collapsed one measures 9.6px (clientHeight 10 — padding only, content box 0). 14 sits
// between the two with no ambiguity, and is low enough not to encode the theme's exact metrics.
const MIN_ROW_CONTENT_HEIGHT = 14

// The panel's old, defect-carrying fixed width, in CSS pixels (15rem at the app's 16px root).
// Asserting strictly ABOVE it is what makes the width half of the fix falsifiable: the panel
// now takes the room a viewport offers instead of a constant.
const OLD_FIXED_PANEL_WIDTH = 240

// A unique tag name of an EXACT length. The unique tail replaces the tail of the realistic
// stem instead of being appended to it, so the name keeps its intended width while staying
// inside the server's cap — the property that matters here is how wide the name RENDERS.
function nameOfLength(stem: string, length: number): string {
  const tail = uniqueTag('geo').slice('geo'.length)
  if (tail.length >= length) {
    throw new Error(`nameOfLength: the unique tail "${tail}" does not leave room for a ${length}-character name`)
  }
  const name = stem.slice(0, length - tail.length) + tail
  if (name.length !== length) {
    throw new Error(`nameOfLength: stem "${stem}" is too short to reach ${length} characters (got "${name}")`)
  }
  return name
}

async function apiCreateDocument(request: APIRequestContext, title: string): Promise<string> {
  const res = await request.put('/api/document', { form: { title, language: 'eng' } })
  expect(res.ok(), `create document ${title}`).toBeTruthy()
  return (await res.json()).id as string
}

async function apiCreateTag(request: APIRequestContext, name: string): Promise<string> {
  const res = await request.put('/api/tag', { form: { name, color: '#3399cc' } })
  expect(res.ok(), `create tag ${name}`).toBeTruthy()
  return (await res.json()).id as string
}

interface Seeded {
  docTitle: string
  longName: string
  shortName: string
}

// Seed one document with NO tags and SEEDED_TAGS assignable tags, so every seeded tag is
// offered by the menu. Returns the names the assertions address.
async function seed(
  request: APIRequestContext,
  cleanup: CleanupFixture,
): Promise<Seeded> {
  const longName = nameOfLength('Bebauungsplan_Zentrale_Chemnitz_Nord', LONG_NAME_LENGTH)
  const shortName = nameOfLength('Rechnung_Q1_Chemnitz', SHORT_NAME_LENGTH)
  const names = [
    longName,
    shortName,
    ...Array.from({ length: SEEDED_TAGS - 2 }, () => uniqueTag('geo')),
  ]
  for (const name of names) {
    const id = await apiCreateTag(request, name)
    cleanup.defer(`delete the geometry tag ${name}`, () => deleteTagApi(request, id))
  }

  // The narrow-viewport test's PREMISE is that the document table is wider than a 393px
  // screen. That width comes from the table's CONTENT, so the seeded document — which is also
  // the row the menu anchors to — carries a title built to force the table off screen.
  // The lever is a single UNBREAKABLE token: the title cell has no nowrap or ellipsis, so
  // hyphens and spaces are line-break opportunities and a long hyphenated title simply wraps,
  // leaving the row no wider than its container. One ~60-character compound with no break
  // points gives the cell a min-content width well past 393px. Total stays under the API's
  // 100-character title cap.
  // The token alone is NOT sufficient, and that is the other half of the narrow test's design.
  // MEASURED 2026-08-25: with this exact title, the anchor row's right edge read >393px when
  // the spec ran alone but sat pinned at 377px (the container width under the full suite's
  // scrollbar) when ambient suite documents shared the table — the same title, the same
  // viewport, a different premise. The mechanism was not diagnosed; the answer is not a longer
  // token but removing the ambient rows, which is why the narrow test seeds through a FRESH
  // USER whose list holds nothing else (see the describe block below).
  const docTitle =
    unique('tqmgeo') + '-Bebauungsplanaenderungsverfahrensdokumentationszusammenfassung'
  const docId = await apiCreateDocument(request, docTitle)
  cleanup.defer('purge the geometry document', () => deleteDocApi(request, docId))

  return { docTitle, longName, shortName }
}

// Right-click the seeded document's row and wait for the menu to be live (its own search box
// painted), not merely present in the DOM.
async function openQuickMenu(page: Page, docTitle: string): Promise<void> {
  await gotoDocumentList(page)
  const row = page.getByRole('row', {
    name: new RegExp(docTitle.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')),
  })
  await expect(row).toBeVisible()
  await row.click({ button: 'right' })
  await expect(page.locator('.p-popover input.tqm-filter-input')).toBeVisible()
}

interface PanelGeometry {
  rowCount: number
  minRowContentHeight: number
  listScrollHeight: number
  listClientHeight: number
  bodyWidth: number
  panelWidth: number
  panel: { left: number; top: number; right: number; bottom: number }
  // The right edge of the widest document row. PrimeVue positions the popover RELATIVE TO ITS
  // ANCHOR, and the anchor is the `<tr>` that was right-clicked (TagQuickMenu.show reads
  // `event.currentTarget`), so where the panel lands is a function of this number — see the
  // note on the narrow-viewport test.
  anchorRowRight: number
  viewport: { width: number; height: number; documentClientWidth: number }
}

async function measure(page: Page): Promise<PanelGeometry> {
  // SETTLED geometry only. The popover plays a 300ms `scale(0.93)` enter animation and
  // `getBoundingClientRect()` reports the TRANSFORMED box, so a measurement taken while it is
  // still running reads a panel ~7% narrower than the one the user ends up looking at — which
  // would let an off-screen panel measure as fitting. Waiting for the transform to clear is
  // what makes every number below the geometry the user actually gets.
  await page.waitForFunction(() => {
    const el = document.querySelector('.p-popover')
    return !!el && getComputedStyle(el).transform === 'none'
  })
  return page.evaluate(() => {
    const rows = Array.from(document.querySelectorAll<HTMLElement>('.p-popover .tqm-option'))
    const list = document.querySelector<HTMLElement>('.p-popover .tqm-tag-list')!
    const body = document.querySelector<HTMLElement>('.p-popover .tqm-body')!
    const panel = document.querySelector<HTMLElement>('.p-popover')!.getBoundingClientRect()
    return {
      rowCount: rows.length,
      // clientHeight, not the bounding box: it is the PADDING box, so a row whose text box
      // collapsed to nothing reads 10 here while its bounding box still reads 9.6 — the
      // padding is incompressible and would mask the collapse in an outer-height check.
      minRowContentHeight: Math.min(...rows.map((r) => r.clientHeight)),
      listScrollHeight: list.scrollHeight,
      listClientHeight: list.clientHeight,
      bodyWidth: body.clientWidth,
      panelWidth: panel.width,
      panel: {
        left: panel.left,
        top: panel.top,
        right: panel.right,
        bottom: panel.bottom,
      },
      anchorRowRight: Math.max(
        0,
        ...Array.from(document.querySelectorAll('tbody tr'), (r) => r.getBoundingClientRect().right),
      ),
      viewport: {
        width: window.innerWidth,
        height: window.innerHeight,
        documentClientWidth: document.documentElement.clientWidth,
      },
    }
  })
}

// Sub-pixel layout rounding is not an overflow.
const EDGE_TOLERANCE = 1

function expectPanelInsideViewport(g: PanelGeometry, where: string): void {
  expect(g.panel.left, `${where}: the panel starts inside the viewport`).toBeGreaterThanOrEqual(-EDGE_TOLERANCE)
  expect(g.panel.top, `${where}: the panel starts below the top edge`).toBeGreaterThanOrEqual(-EDGE_TOLERANCE)
  expect(
    g.panel.right,
    `${where}: the panel ends inside the viewport (${g.viewport.width}px wide). PrimeVue aligns the ` +
      `panel to its ANCHOR — the right-clicked table row, whose right edge is at ${g.anchorRowRight}px — ` +
      `so a row that runs off screen takes the panel with it, whatever the panel's own width is.`,
  ).toBeLessThanOrEqual(g.viewport.width + EDGE_TOLERANCE)
  expect(
    g.panel.bottom,
    `${where}: the panel ends above the bottom edge (${g.viewport.height}px tall)`,
  ).toBeLessThanOrEqual(g.viewport.height + EDGE_TOLERANCE)
}

// The right-click that raises this menu has no touch equivalent, so the mobile project cannot
// reach it the way a user would: on Pixel 5 neither a right-button click nor Playwright's
// `dispatchEvent('contextmenu')` opens the menu (re-measured for the placement work below;
// the same finding is recorded in e2e/COVERAGE.md). A `MouseEvent` hand-built in page context
// DOES open it — so the app's handler is not what refuses — but no touch user can produce
// that event, and asserting geometry through an input the device cannot generate would be
// measuring the test harness rather than the product. The narrow-viewport geometry is
// therefore measured in the SECOND test below, which drives the desktop project's pointer at
// the mobile project's own 393px viewport, over the same horizontally-scrolling table.
const NO_TOUCH_CONTEXTMENU =
  'right-click/contextmenu is a desktop-only pointer affordance with no touch equivalent; ' +
  'the 393px geometry is measured in the narrow-viewport test instead'

test('the quick-menu tag rows keep their text box and the panel takes the room it needs (#284)', async ({
  page,
  request,
  cleanup,
}) => {
  test.skip(isMobileViewport(page), NO_TOUCH_CONTEXTMENU)
  // Seeding 25 tags is 25 sequential API round-trips before the page is even opened.
  test.setTimeout(60_000)

  const { docTitle, longName, shortName } = await seed(request, cleanup)
  expect(longName, 'the long name is long enough to be clipped by the old 15rem panel').toHaveLength(
    LONG_NAME_LENGTH,
  )

  await openQuickMenu(page, docTitle)
  const g = await measure(page)

  // REALNESS: every assertion below is about a list over the collapse threshold. If the seed
  // did not land — or the menu opened filtered — they would all pass vacuously.
  expect(g.rowCount, 'the menu really offers the seeded tags').toBeGreaterThanOrEqual(SEEDED_TAGS)

  // (1) No row may lose its text box. The failure this pins measured 10 (padding only).
  expect(
    g.minRowContentHeight,
    `every tag row keeps a rendered text box (shortest of ${g.rowCount} rows)`,
  ).toBeGreaterThanOrEqual(MIN_ROW_CONTENT_HEIGHT)

  // …and the bounded list handles the overflow by SCROLLING rather than by compressing its
  // rows into it. (This one also held while the rows were collapsed — 25 rows at the 9.6px
  // floor still overflow 12rem — so it is the scroll contract, not the collapse red.)
  expect(
    g.listScrollHeight,
    'the bounded tag list scrolls its overflow instead of squashing the rows',
  ).toBeGreaterThan(g.listClientHeight)

  // (2) The panel is no longer pinned to the old fixed 15rem, and still fits the screen.
  expect(
    g.bodyWidth,
    `the panel uses the width a ${g.viewport.width}px viewport offers instead of the old fixed ${OLD_FIXED_PANEL_WIDTH}px`,
  ).toBeGreaterThan(OLD_FIXED_PANEL_WIDTH)
  // Containment is asserted HERE, at the desktop width, because that is where it is a
  // statement about the panel: the document table fits the viewport, so the anchor row it is
  // aligned to is fully on screen. (At a phone width the table scrolls horizontally and the
  // anchor itself runs off screen — see the narrow-viewport test.)
  expectPanelInsideViewport(g, `${g.viewport.width}px viewport`)

  // The load-bearing half of (2): whatever the width, the full name stays RECOVERABLE. No
  // finite panel fits every possible tag name, so the tooltip is the contract — not "it fits".
  const longRow = page.locator('.p-popover .tqm-option', { hasText: longName })
  await expect(longRow, 'the long seeded tag is offered exactly once').toHaveCount(1)
  await expect(longRow, 'the clipped name is recoverable from the row itself').toHaveAttribute(
    'title',
    longName,
  )

  // A name that DOES fit must not be ellipsized by the panel — the widened panel is only
  // worth anything if realistic names render whole.
  const shortOverflow = await page
    .locator('.p-popover .tqm-option', { hasText: shortName })
    .evaluate((el) => el.scrollWidth - el.clientWidth)
  expect(
    shortOverflow,
    `a ${SHORT_NAME_LENGTH}-character tag name renders whole in the panel`,
  ).toBeLessThanOrEqual(0)
})

// The seeded account's password. The server enforces upper+lower+digit, so a weaker literal
// would 400 at create time and surface as an unrelated "create the geometry user" failure.
const GEOMETRY_USER_PASSWORD = 'GeoPass123'

// Create a throwaway account as admin. A dedicated admin API context is opened and disposed
// here rather than borrowing the spec's `request` fixture: the describe below runs on CLEARED
// storage state (see its comment), where the fixture is anonymous and every admin call 403s.
async function createGeometryUser(baseURL: string): Promise<string> {
  // `unique()` mints a name with separators the username field does not accept; stripping them
  // keeps the per-worker uniqueness (timestamp + pid + counter) inside the server's 50-char cap.
  const username = unique('tqmgeo').replace(/[^a-z0-9]/gi, '').toLowerCase()
  const admin = await pwRequest.newContext({ baseURL })
  const adminLogin = await admin.post('/api/user/login', {
    form: { username: 'admin', password: 'admin', remember: false },
  })
  expect(adminLogin.ok(), 'admin login for the geometry user seed').toBeTruthy()
  const created = await admin.put('/api/user', {
    form: {
      username,
      password: GEOMETRY_USER_PASSWORD,
      email: `${username}@example.com`,
      storage_quota: 1_000_000_000,
    },
  })
  expect(created.ok(), `create the geometry user ${username}`).toBeTruthy()
  await admin.dispose()
  return username
}

// Delete the throwaway account as admin. The reassign target is supplied because deletion is
// refused with ReassignRequired while the account still owns anything (#55/#180) — and its
// success is ASSERTED, or a leaked account (with whatever survived the seed teardown) would
// quietly become ambient data for later runs. The context is built and disposed inside the
// step so nothing has to stay alive across teardown ordering.
async function deleteGeometryUser(baseURL: string, username: string): Promise<void> {
  const admin = await pwRequest.newContext({ baseURL })
  const adminLogin = await admin.post('/api/user/login', {
    form: { username: 'admin', password: 'admin', remember: false },
  })
  expect(adminLogin.ok(), 'admin login for the geometry user teardown').toBeTruthy()
  const deleted = await admin.delete(`/api/user/${username}`, {
    params: { reassign_to_username: 'admin' },
  })
  expect(deleted.ok(), `cleanup: delete the geometry user ${username}`).toBeTruthy()
  await admin.dispose()
}

// An API context carrying THAT user's session, so everything seeded through it is owned by
// them — which is what keeps it out of every other account's list.
async function openGeometryUserRequest(baseURL: string, username: string): Promise<APIRequestContext> {
  const asUser = await pwRequest.newContext({ baseURL })
  const userLogin = await asUser.post('/api/user/login', {
    form: { username, password: GEOMETRY_USER_PASSWORD, remember: false },
  })
  expect(userLogin.ok(), `log the geometry user ${username} in`).toBeTruthy()
  return asUser
}

// The narrow-viewport half, driven with the desktop project's pointer at the mobile
// project's own viewport (Pixel 5 = 393×851). `width: min(24rem, calc(100vw - 2rem))` has two
// branches and only this one exercises the clamp: 24rem (384px) plus the popover's chrome
// would otherwise be wider than a 393px screen.
//
// It runs as a FRESH USER, on CLEARED storage state, for one reason: its premise is a
// measurement of the document table, and a table is only as wide as the rows in it. As admin
// the table also holds whatever every earlier spec in the run left behind, and those rows
// decided the anchor's right edge instead of the seeded title (measured — see `seed`). Teedy's
// document list is ACL-scoped with no admin bypass (`DocumentResource#list` →
// `getTargetIdList`), and tags are owned the same way, so a brand-new account's list contains
// exactly what this test seeds through that account's own session and nothing else. That makes
// the premise a property of the seed rather than of the suite order.
test.describe('at a phone width, on an isolated account', () => {
  // Cleared so the shared admin session cannot log the browser in first; the test authenticates
  // through the real form as the seeded user. `request` is anonymous under this override, which
  // is why the admin and user contexts above are built explicitly.
  test.use({ storageState: { cookies: [], origins: [] } })

  test('at a 393px-wide viewport the quick menu still fits on screen (#284)', async ({
    page,
    baseURL,
    cleanup,
  }) => {
    test.skip(isMobileViewport(page), NO_TOUCH_CONTEXTMENU)
    test.setTimeout(60_000)

    const username = await createGeometryUser(baseURL!)
    const asUser = await openGeometryUserRequest(baseURL!, username)
    // Registration order is teardown order (the `cleanup` fixture is FIFO), and this order is
    // load-bearing: `seed` registers the tag and document deletions against `asUser`, so they
    // run FIRST, while that session and its context are still alive. Only then is the account
    // removed, and only then is the context disposed.
    const { docTitle, shortName } = await seed(asUser, cleanup)
    cleanup.defer('delete the geometry user', () => deleteGeometryUser(baseURL!, username))
    cleanup.defer('dispose the geometry user API context', () => asUser.dispose())

    // Set BEFORE the navigation so the app lays out for this width from the start.
    await page.setViewportSize({ width: 393, height: 851 })
    await login(page, username, GEOMETRY_USER_PASSWORD)
    await openQuickMenu(page, docTitle)

    // ISOLATION, asserted rather than assumed: the premise below reads the widest row in
    // `tbody`, so an ambient row wider (or a scrollbar-forcing list longer) than the seeded one
    // would silently take the measurement over — which is exactly the failure this structure
    // replaces. One row means the number the premise reads is the seeded title's.
    await expect(
      page.locator('tbody tr'),
      "the fresh account's document list holds ONLY this test's seeded row, so the table's " +
        'width — and with it the anchor the panel is aligned to — is decided by the seeded title',
    ).toHaveCount(1)

    const g = await measure(page)

    expect(g.viewport.width, 'the viewport really narrowed').toBe(393)
    expect(g.rowCount, 'the menu really offers the seeded tags').toBeGreaterThanOrEqual(SEEDED_TAGS)
    expect(
      g.minRowContentHeight,
      'every tag row keeps a rendered text box at a phone width too',
    ).toBeGreaterThanOrEqual(MIN_ROW_CONTENT_HEIGHT)
    expect(
      g.bodyWidth,
      'the narrow panel is still wider than the old fixed 15rem it replaced',
    ).toBeGreaterThan(OLD_FIXED_PANEL_WIDTH)

    // The property this phase owns: the panel is never WIDER than the screen. `min()`'s second
    // branch is what delivers it — a flat 24rem would render 408px of panel into a 393px
    // viewport, which is exactly the trade this fix had to avoid while widening the panel.
    expect(
      g.panelWidth,
      `the panel is no wider than the ${g.viewport.width}px screen ` +
        `(document client width ${g.viewport.documentClientWidth}px)`,
    ).toBeLessThanOrEqual(g.viewport.width)

    // PLACEMENT (#284 follow-up, TEEDY-117). MEASURED on 791d258b in the first e2e run of this
    // spec: inline `inset-inline-start: 325px`, panel width 385.1px, panel RIGHT EDGE 710.1px —
    // 317px off a 393px screen, while the click that opened it was at 204.5px, well inside.
    //
    // The panel's own width is not what puts it there, which is why the `min()` clamp asserted
    // just above cannot fix it: `@primeuix/utils`' `absolutePosition` aligns the panel to its
    // ANCHOR, and this menu's anchor is the right-clicked `<tr>` (TagQuickMenu.show hands
    // `event.currentTarget` to `Popover.show`). At a phone width the document table is ~694px
    // wide and scrolls horizontally inside `.p-datatable-table-container`, so the row itself
    // ends off screen; the collision branch then right-aligns the panel to the ROW's right edge
    // (`left = max(0, targetLeft + scrollX + targetWidth - panelWidth)`) and follows it off the
    // screen. The same 710.1px right edge came out of the old 15rem panel, so this predates
    // #284 and is fixed separately, by clamping the placed panel back into the viewport.
    expect(
      g.anchorRowRight,
      'PREMISE: the table really is wider than the viewport, so the anchor row the panel is ' +
        'aligned to runs off screen — without that, the placement assertion below is vacuous',
    ).toBeGreaterThan(g.viewport.width)
    expectPanelInsideViewport(g, `${g.viewport.width}px viewport, anchor row off screen`)

    // …and it STAYS inside when the popover re-aligns itself. `Popover.alignOverlay()` runs
    // again on every delivery of the popover's own content ResizeObserver, so a correction
    // applied once at open time is overwritten as soon as the panel's content changes size.
    // Typing in the search box shrinks the bounded tag list — an ordinary thing to do with this
    // menu open, and a real content resize — so this second measurement is what distinguishes a
    // clamp that holds from one that is undone a frame later.
    await page.locator('.p-popover input.tqm-filter-input').fill(shortName)
    await expect(
      page.locator('.p-popover .tqm-option'),
      'the search narrowed the list to the one seeded match, so the panel really did resize',
    ).toHaveCount(1)
    const afterResize = await measure(page)
    expect(afterResize.rowCount, 'the list really shrank').toBeLessThan(g.rowCount)
    expectPanelInsideViewport(afterResize, 'after a content resize re-aligned the panel')
  })
})
