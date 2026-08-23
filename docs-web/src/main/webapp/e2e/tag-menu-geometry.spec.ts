import { test, expect, type APIRequestContext, type CleanupFixture, type Page } from './fixtures'
import {
  unique,
  uniqueTag,
  isMobileViewport,
  deleteDocApi,
  deleteTagApi,
  gotoDocumentList,
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

  const docTitle = unique('tqm-geometry-doc')
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

// The right-click that raises this menu has no touch equivalent: on the mobile project
// (Pixel 5) neither a right-button click nor a dispatched `contextmenu` opens it — verified
// and documented in e2e/COVERAGE.md, and the reason every quick-menu spec is desktop-only.
// The narrow-viewport half of the fix is therefore measured in the SECOND test below, which
// drives the same desktop pointer at a 393px viewport. The mobile INTERACTION path stays
// unasserted here, exactly as it is in the rest of the suite.
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

// The narrow-viewport half, driven with the desktop project's pointer at the mobile
// project's own viewport (Pixel 5 = 393×851). `width: min(24rem, calc(100vw - 2rem))` has two
// branches and only this one exercises the clamp: 24rem (384px) plus the popover's chrome
// would otherwise be wider than a 393px screen.
test('at a 393px-wide viewport the quick menu still fits on screen (#284)', async ({
  page,
  request,
  cleanup,
}) => {
  test.skip(isMobileViewport(page), NO_TOUCH_CONTEXTMENU)
  test.setTimeout(60_000)

  const { docTitle } = await seed(request, cleanup)

  // Set BEFORE the navigation so the app lays out for this width from the start.
  await page.setViewportSize({ width: 393, height: 851 })
  await openQuickMenu(page, docTitle)
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

  // Its PLACEMENT is deliberately NOT asserted here, and that is a finding rather than a gap.
  // MEASURED in the first e2e run of this spec (trace inline style `inset-inline-start: 325px`,
  // panel width 385.1px, panel right 710.1px, click point 204.5/390.5 — inside the viewport):
  // at 393px the document table is ~694px wide and scrolls horizontally inside its own
  // container, so the right-clicked `<tr>` — which IS the popover's anchor — ends at ~710px,
  // well off screen. PrimeVue's absolutePosition then right-aligns the panel to that anchor
  // edge (`N = max(0, targetLeft + scrollX + targetWidth - panelWidth)`), which puts the panel
  // off screen too. The panel's own width drops out of that expression, so the SAME right edge
  // (710.1px) comes out of the old 15rem panel: the behaviour predates this fix and is not
  // changed by it — the fix only moves the panel's LEFT edge from ~446px to ~325px, i.e. more
  // of it on screen, not less. Clamping it is popover-collision work on a shared overlay, well
  // outside a CSS fix to the tag rows; it is reported for its own ticket.
})
