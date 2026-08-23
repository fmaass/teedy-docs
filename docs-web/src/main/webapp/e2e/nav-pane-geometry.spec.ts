import {
  test,
  expect,
  type APIRequestContext,
  type CleanupFixture,
  type Locator,
  type Page,
} from './fixtures'
import {
  ROUTE_ROOT,
  deleteTagApi,
  expectRouteReady,
  gotoDocumentList,
  gotoRouteReady,
  isMobileViewport,
  tagTreePanel,
  uniqueTag,
} from './helpers'

// #299 — THE LEFT NAVIGATION PANE MUST SCROLL ON ITS OWN IN A SHORT WINDOW.
//
// Reported against 3.8.6: in a window that is not tall (a laptop with browser chrome, a
// tiled or split window), the settings navigation runs past the bottom of the pane and the
// LAST entry is cut in half — the reporter's screenshot shows "Statistics" sliced by the
// pinned footer, with "Monitoring" not reachable at all. Zooming out was the only
// workaround, because the pane offered no scrolling of its own: `.panel-middle` was
// `overflow: hidden`, which CLIPS the surplus instead of letting the user reach it. The admin
// settings nav is the worst case (1 back button + 4 section headings + 16 links ≈ 607px of
// content) and, unlike the tag tree, it cannot be shortened by filtering.
//
// WHAT CARRIES THE RED — and what does not. Measured in headless Chromium against the shipped
// scoped CSS at 1280x560 with `.panel-middle { overflow: hidden }` in force:
//
//   * `locator.scrollIntoViewIfNeeded()` + `toBeVisible()` + `click()` ALL PASS on the broken
//     pane. `overflow: hidden` still produces a scroll BOX — it only refuses USER input — so
//     the programmatic scroll Playwright issues moves it anyway (scrollTop 0 -> 229, the entry
//     lands inside the viewport, the click lands). An assertion built on those three would be
//     green on the defect, so it is not the gate here.
//   * A real WHEEL gesture over the pane is the discriminator, because wheel input is exactly
//     what `overflow: hidden` drops: pre-fix the pane stays at scrollTop 0 and the last link
//     stays 229px below the pane's bottom edge; post-fix the wheel brings it into the box.
//   * The computed `overflow-y` is asserted next to it as the contract in one word.
//
// The NESTING contract is asserted too (second test): the tag tree keeps its own scrolling
// (`.panel-tree`, `overflow-y: auto`) and the pane around it must NOT become a second scroll
// area in the documents context — two nested scrollbars in one 250px column is the failure a
// blanket "make everything scroll" fix would ship.

const NAV_VIEWPORT = { width: 1280, height: 560 }

// The admin settings nav: 2 personal + 4 access & users + 4 content model + 6 system links.
// Asserted as a FLOOR, not an equality — a future settings page must not turn this geometry
// gate red, but a nav that lost half its entries would no longer be the worst case.
const ADMIN_NAV_LINKS = 16

// Enough tags that the tree provably overflows its own box at NAV_VIEWPORT (the tree's client
// height there is ~330px and a node is ~26px), so "the tree still scrolls" is a measurement
// rather than a vacuous truth.
const SEEDED_TAGS = 20

interface PaneMetrics {
  overflowY: string
  scrollTop: number
  scrollHeight: number
  clientHeight: number
  scrollWidth: number
  clientWidth: number
  paneTop: number
  paneBottom: number
  lastTop: number
  lastBottom: number
  viewportHeight: number
  docScrollWidth: number
  docClientWidth: number
  docScrollHeight: number
  docClientHeight: number
}

// ONE atomic read of a scroll container and of the LAST element matching `itemSelector` inside
// it, taken at a single scroll position so nothing shifts between measurements.
async function measurePane(
  page: Page,
  paneSelector: string,
  itemSelector: string,
): Promise<PaneMetrics> {
  return page.evaluate(([paneSel, itemSel]) => {
    const pane = document.querySelector(paneSel) as HTMLElement
    if (!pane) throw new Error(`no element matched "${paneSel}"`)
    const items = document.querySelectorAll(itemSel)
    const last = items[items.length - 1] as HTMLElement
    if (!last) throw new Error(`no element matched "${itemSel}"`)
    const pb = pane.getBoundingClientRect()
    const lb = last.getBoundingClientRect()
    return {
      overflowY: getComputedStyle(pane).overflowY,
      scrollTop: pane.scrollTop,
      scrollHeight: pane.scrollHeight,
      clientHeight: pane.clientHeight,
      scrollWidth: pane.scrollWidth,
      clientWidth: pane.clientWidth,
      paneTop: pb.top,
      paneBottom: pb.bottom,
      lastTop: lb.top,
      lastBottom: lb.bottom,
      viewportHeight: window.innerHeight,
      docScrollWidth: document.documentElement.scrollWidth,
      docClientWidth: document.documentElement.clientWidth,
      docScrollHeight: document.documentElement.scrollHeight,
      docClientHeight: document.documentElement.clientHeight,
    }
  }, [paneSelector, itemSelector] as const)
}

// Scroll a container the way a USER does — wheel input over it — until it reaches its end or
// stops moving, and return the scrollTop reached. A container that ignores wheel input
// (`overflow: hidden`) returns 0, which is what makes this the red-carrying mechanism.
async function wheelToEnd(page: Page, container: Locator): Promise<number> {
  const box = await container.boundingBox()
  expect(box, 'the scroll container is painted').not.toBeNull()
  await page.mouse.move(box!.x + box!.width / 2, box!.y + box!.height / 2)
  const read = () =>
    container.evaluate((el) => ({
      top: el.scrollTop,
      atEnd: el.scrollTop + el.clientHeight >= el.scrollHeight - 1,
    }))
  let state = await read()
  for (let step = 0; step < 20 && !state.atEnd; step++) {
    const before = state.top
    await page.mouse.wheel(0, 200)
    // Chromium applies wheel input asynchronously. Wait two animation frames rather than a
    // fixed sleep — deterministic, and a pane that ignores the wheel simply reads back
    // unchanged, which is the measurement this helper exists to take.
    await page.evaluate(
      () => new Promise<void>((r) => requestAnimationFrame(() => requestAnimationFrame(() => r()))),
    )
    state = await read()
    if (state.top === before) break
  }
  return state.top
}

test.describe('left navigation pane geometry (#299)', () => {
  test('the settings nav scrolls to its last entry in a 560px-tall window', async ({ page }) => {
    // The desktop `aside.left-panel` is the surface under test. On the mobile project the pane
    // is a PrimeVue Drawer instead, whose `.p-drawer-content` already carries `overflow-y:
    // auto` from the library's own base layer (@primeuix/styles/dist/drawer) — measured
    // scrolling at 393x560 before this change, so the Drawer is not part of this fix.
    test.skip(
      isMobileViewport(page),
      'desktop-only surface: the mobile project renders the Drawer, which already scrolls',
    )

    await page.setViewportSize(NAV_VIEWPORT)
    await gotoRouteReady(page, '/#/settings', ROUTE_ROOT.settingsHub)

    const paneSelector = 'aside.left-panel .panel-middle'
    const linkSelector = 'aside.left-panel .admin-nav .admin-nav-link'
    const pane = page.locator(paneSelector)
    const navLinks = page.locator(linkSelector)
    const last = navLinks.last()
    await expect(pane).toBeVisible()
    // The worst case really is under test: an admin sees the whole settings nav. A shorter
    // list would not fill a 560px window and the rest would pass vacuously.
    expect(
      await navLinks.count(),
      'the admin settings nav renders its full link set',
    ).toBeGreaterThanOrEqual(ADMIN_NAV_LINKS)

    const atRest = await measurePane(page, paneSelector, linkSelector)

    // PREMISE (green before and after the fix): the window really is too short for the nav —
    // the situation the reporter is in. Without this the rest could pass vacuously.
    expect(
      atRest.scrollHeight,
      `the nav content (${atRest.scrollHeight}px) exceeds the pane (${atRest.clientHeight}px) at ` +
        `${NAV_VIEWPORT.width}x${NAV_VIEWPORT.height} — the #299 situation`,
    ).toBeGreaterThan(atRest.clientHeight)
    expect(
      atRest.lastBottom,
      'at rest the last entry sits below the pane, exactly as the reporter sees it',
    ).toBeGreaterThan(atRest.paneBottom)

    // (1) THE CONTRACT: the pane is a scroll area of its own.
    expect(
      atRest.overflowY,
      'the nav pane scrolls itself rather than clipping what does not fit (#299)',
    ).toMatch(/^(auto|scroll)$/)

    // (2) THE RED: a user wheel over the pane moves it. `overflow: hidden` ignores this.
    const scrolled = await wheelToEnd(page, pane)
    expect(
      scrolled,
      'a wheel gesture over the nav pane scrolls it (the input `overflow: hidden` drops)',
    ).toBeGreaterThan(0)

    // (3) …and once scrolled, the LAST entry is fully inside the pane AND inside the window.
    const afterWheel = await measurePane(page, paneSelector, linkSelector)
    expect(
      afterWheel.lastBottom,
      'after scrolling the pane, the last nav entry ends inside the pane (not clipped by the footer)',
    ).toBeLessThanOrEqual(afterWheel.paneBottom + 1)
    expect(
      afterWheel.lastTop,
      'the last nav entry starts inside the pane',
    ).toBeGreaterThanOrEqual(afterWheel.paneTop - 1)
    expect(afterWheel.lastBottom, 'the last nav entry ends inside the window').toBeLessThanOrEqual(
      afterWheel.viewportHeight + 1,
    )

    // (4) It is reachable as a CONTROL, not merely as pixels: it takes a real click and routes.
    // ROUTE_ROOT has no key for the monitoring leaf, so its own root class is named here.
    await expect(last).toBeVisible()
    await last.click()
    await expectRouteReady(page, '/#/settings/monitoring', '.monitoring-settings')

    // (5) The fix must not buy that scroll by moving something else. The footer nav stays
    // pinned and visible while the pane scrolls…
    const footerBox = await page.locator('aside.left-panel .panel-footer').boundingBox()
    expect(footerBox, 'the footer nav is painted').not.toBeNull()
    expect(
      footerBox!.y + footerBox!.height,
      'the pinned footer nav stays inside the window while the pane scrolls',
    ).toBeLessThanOrEqual(NAV_VIEWPORT.height + 1)
    expect(
      footerBox!.y + 1,
      'the pinned footer nav stays below the scrolling pane, not overlapped by it',
    ).toBeGreaterThanOrEqual(afterWheel.paneBottom)

    // …and nothing scrolls sideways: neither the pane nor the page.
    expect(afterWheel.scrollWidth, 'the nav pane does not scroll sideways').toBeLessThanOrEqual(
      afterWheel.clientWidth + 1,
    )
    expect(afterWheel.docScrollWidth, 'the page does not scroll sideways').toBeLessThanOrEqual(
      afterWheel.docClientWidth + 1,
    )
    // The shell itself still fits the window — the PANE scrolls, the page does not grow.
    expect(
      afterWheel.docScrollHeight,
      'the app shell still fits the window (the pane scrolls, the page does not)',
    ).toBeLessThanOrEqual(afterWheel.docClientHeight + 1)
  })

  test('the tag tree keeps its own scrolling and the pane does not become a second scroll area', async ({
    page,
    cleanup,
  }) => {
    test.skip(
      isMobileViewport(page),
      'desktop-only surface: the mobile project renders the Drawer, which already scrolls',
    )

    await seedTags(page.request, cleanup)
    await page.setViewportSize(NAV_VIEWPORT)
    await gotoDocumentList(page)

    const tree = await tagTreePanel(page)
    await expect(tree).toBeVisible()
    const treeSelector = 'aside.left-panel .panel-tree'
    const nodeSelector = 'aside.left-panel .panel-tree .tag-tree-node'
    const treeScroller = page.locator(treeSelector)
    await expect(page.locator(nodeSelector).last()).toBeAttached()

    const treeAtRest = await measurePane(page, treeSelector, nodeSelector)
    // PREMISE: with SEEDED_TAGS tags the tree genuinely overflows its own box.
    expect(
      treeAtRest.scrollHeight,
      `the seeded tag tree (${treeAtRest.scrollHeight}px) overflows its box (${treeAtRest.clientHeight}px)`,
    ).toBeGreaterThan(treeAtRest.clientHeight)
    expect(treeAtRest.overflowY, 'the tag tree is still the scroll area for tags').toMatch(
      /^(auto|scroll)$/,
    )

    // The tag tree still answers a user wheel — the behaviour that existed before #299 and
    // that the pane-level fix must not take away.
    const treeScrolled = await wheelToEnd(page, treeScroller)
    expect(treeScrolled, 'a wheel over the tag tree scrolls the tree').toBeGreaterThan(0)
    const treeAfter = await measurePane(page, treeSelector, nodeSelector)
    expect(treeAfter.lastBottom, 'the last tag node is reachable inside the tree').toBeLessThanOrEqual(
      treeAfter.paneBottom + 1,
    )

    // NO DOUBLE SCROLL: the tree absorbs the surplus, so the pane around it has nothing left
    // to scroll — one scrollbar in this column, not two.
    const paneMetrics = await page
      .locator('aside.left-panel .panel-middle')
      .evaluate((el) => ({ scrollHeight: el.scrollHeight, clientHeight: el.clientHeight }))
    expect(
      paneMetrics.scrollHeight,
      'the pane does not scroll in the documents context — the tag tree owns that scrolling',
    ).toBeLessThanOrEqual(paneMetrics.clientHeight + 1)
  })
})

// Seed SEEDED_TAGS tags so the tag tree overflows its box. Tags alone are enough: the tree
// view lists every tag, independent of document assignment.
async function seedTags(request: APIRequestContext, cleanup: CleanupFixture): Promise<void> {
  for (let i = 0; i < SEEDED_TAGS; i++) {
    const name = uniqueTag('nav')
    const res = await request.put('/api/tag', { form: { name, color: '#3399cc' } })
    expect(res.ok(), `create tag ${name}`).toBeTruthy()
    const id = (await res.json()).id as string
    cleanup.defer(`delete the nav-pane tag ${name}`, () => deleteTagApi(request, id))
  }
}
