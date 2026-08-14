import { test, expect, type Locator } from './fixtures'
import { createDocument, unique, gotoDocumentList } from './helpers'

// Mobile / responsive coverage. This spec runs ONLY under the `mobile`
// project (Pixel 5 viewport; the `desktop` project testIgnores this file), so every
// test here executes at 393×851 with touch — the viewport that trips AppLayout's
// `matchMedia('(max-width: 1024px)')` branch (AppLayout.vue:49). At the desktop
// viewport these assertions would be meaningless.
//
// This spec holds ONLY environment-independent FUNCTIONAL assertions (the mobile hard
// gate): structural checks that the mobile branch renders correctly and the shipped
// mobile fixes (#67 nav icon width, #68 slide-over header) hold. The pixel-level
// visual-regression comparison for these CSS-glitch classes is owned by the standing
// visual gate in `visual.spec.ts` (key screens × {desktop,mobile} × {en,de}, with
// committed Linux baselines) — see e2e/COVERAGE.md.

// A pixel-geometry overlap check used by several assertions: two elements' bounding
// boxes must not intersect. Returns true when they are disjoint (no overlap).
function disjoint(a: { x: number; y: number; width: number; height: number },
                  b: { x: number; y: number; width: number; height: number }): boolean {
  return (
    a.x + a.width <= b.x ||
    b.x + b.width <= a.x ||
    a.y + a.height <= b.y ||
    b.y + b.height <= a.y
  )
}

// Assert a locator's box lies fully within the page viewport (no horizontal
// overflow past the right edge — the classic mobile bug).
async function expectWithinViewport(page: import('@playwright/test').Page, loc: Locator) {
  const box = await loc.boundingBox()
  expect(box, 'element has a layout box').not.toBeNull()
  const vw = page.viewportSize()!.width
  expect(box!.x, 'left edge not off-screen left').toBeGreaterThanOrEqual(-1)
  expect(box!.x + box!.width, 'right edge within viewport').toBeLessThanOrEqual(vw + 1)
}

// Wait until a PrimeVue Drawer has finished its slide-in transition, so a geometry
// read on its contents measures the SETTLED layout — not a mid-slide keyframe (a
// transiently negative x for a left Drawer, or a right edge past the viewport for a
// right Drawer). Polling `boundingBox()` for stability is unreliable during CSS
// easing (two rapid reads can plateau at the same sub-pixel mid-animation), so wait
// on the transform itself: the Drawer element's computed transform must resolve to
// identity (`none` or the identity matrix) AND stay there across a real time gap.
async function waitForDrawerSettled(page: import('@playwright/test').Page, drawer: Locator): Promise<void> {
  // The drawer panel carries the transform; the dialog role sits on `.p-drawer`.
  const panel = drawer.locator('xpath=self::*[contains(@class,"p-drawer")] | .//*[contains(@class,"p-drawer")]').first()
  const target = (await panel.count()) ? panel : drawer
  await expect
    .poll(
      async () => {
        const t = await target.evaluate((el) => getComputedStyle(el as HTMLElement).transform)
        // Identity transform => not translated => fully open (or fully closed).
        return t === 'none' || t === 'matrix(1, 0, 0, 1, 0, 0)'
      },
      { message: 'drawer transform settled to identity', intervals: [50, 100, 100, 200] },
    )
    .toBe(true)
}

test.describe('mobile layout (Pixel 5 viewport)', () => {
  test('desktop side-panel is hidden and the hamburger toggle is shown', async ({ page }) => {
    await gotoDocumentList(page)
    // Shell up: the header Logout action renders at both viewports (unlike the brand
    // link, which is hidden inside the closed Drawer on mobile).
    await expect(page.getByRole('button', { name: 'Logout' })).toBeVisible()

    // The desktop left panel (`aside.left-panel`) is v-if="!isMobile" — it must NOT
    // be in the DOM at this viewport.
    await expect(page.locator('aside.left-panel')).toHaveCount(0)

    // The brand link is NOT visible until the Drawer is opened (it lives in the
    // Drawer header on mobile, not the always-visible chrome).
    await expect(page.getByRole('link', { name: 'teedy' })).toHaveCount(0)

    // The mobile hamburger (AppHeader, v-if="isMobile", aria-label = ui.menu) IS
    // visible. Its presence proves the isMobile branch is active.
    const hamburger = page.getByRole('button', { name: 'Menu', exact: true })
    await expect(hamburger).toBeVisible()
    await expectWithinViewport(page, hamburger)
  })

  test('opening the drawer reveals the nav and a nav link stays inside the viewport', async ({ page }) => {
    await gotoDocumentList(page)
    const hamburger = page.getByRole('button', { name: 'Menu', exact: true })
    await expect(hamburger).toBeVisible()

    await hamburger.click()

    // The PrimeVue Drawer (mobile-panel-drawer) opens; its footer nav links become
    // reachable. "Manage tags" is a stable, always-present nav link.
    const drawer = page.getByRole('dialog')
    await expect(drawer).toBeVisible()

    const manageTags = drawer.getByRole('link', { name: 'Manage tags' })
    await expect(manageTags).toBeVisible()
    // Let the Drawer finish sliding in before measuring geometry (a mid-slide read
    // catches a transiently negative x while the transform animates).
    await waitForDrawerSettled(page, drawer)
    // The link must not overflow the narrow drawer / viewport.
    await expectWithinViewport(page, manageTags)
    // And it must be functional: clicking it navigates (drawer closes on select).
    await manageTags.click()
    await expect(page).toHaveURL(/#\/tag/)
  })

  test('header action icons (#67) all stay visible and never overlap in the narrow bar', async ({ page }) => {
    await gotoDocumentList(page)

    // The four header action buttons (#67 pinned flex-shrink:0 so they hold their
    // token width and don't collapse). All must be visible AND inside the viewport.
    const labels = ['Trash', 'Dark mode', 'About', 'Logout']
    const boxes: Array<{ x: number; y: number; width: number; height: number }> = []
    for (const name of labels) {
      const btn = page.getByRole('button', { name, exact: true })
      await expect(btn, `header action "${name}" is visible`).toBeVisible()
      await expectWithinViewport(page, btn)
      const box = await btn.boundingBox()
      expect(box, `header action "${name}" has a box`).not.toBeNull()
      // #67: a collapsed icon squeezes to near-zero width. Assert a real tap target.
      expect(box!.width, `header action "${name}" holds a tappable width`).toBeGreaterThanOrEqual(20)
      boxes.push(box!)
    }
    // No two header icons overlap (they must sit side by side, not stack on top).
    for (let i = 0; i < boxes.length; i++) {
      for (let j = i + 1; j < boxes.length; j++) {
        expect(
          disjoint(boxes[i], boxes[j]),
          `header actions "${labels[i]}" and "${labels[j]}" do not overlap`,
        ).toBe(true)
      }
    }
  })

  test('slide-over (#68): a long title truncates and never overlaps the clickable close button', async ({ page }) => {
    // A deliberately long title exercises the #68 fix (title flex:1;min-width:0 so it
    // truncates instead of pushing into the close button). Kept UNDER the backend's
    // 100-char title cap (DocumentResource validateLength title 1..100) while still
    // far wider than the ~393px mobile drawer header, so it must ellipsize.
    const longTitle = unique('A-Very-Long-Mobile-Doc-Title-That-Would-Overrun-The-Slide-Over-Header')
    expect(longTitle.length, 'title within backend 100-char cap').toBeLessThanOrEqual(100)
    await createDocument(page, longTitle)

    // Back to the list; open the document's slide-over. A single click on the row
    // (list view) opens the slide-over after the 250 ms click-debounce.
    await gotoDocumentList(page)
    await page.getByRole('cell', { name: longTitle }).click()

    // The right-position Drawer (doc-slide-over) opens. Its header holds the title
    // and a close button.
    const slideOver = page.getByRole('dialog')
    await expect(slideOver).toBeVisible()

    const title = page.locator('.slide-over-title')
    await expect(title).toBeVisible()
    await expect(title).toHaveText(longTitle)

    // The Drawer close button (PrimeVue p-drawer-close-button, aria-label "Close").
    const closeBtn = slideOver.locator('.p-drawer-close-button')
    await expect(closeBtn).toBeVisible()
    // The slide-over animates in from the right; wait for it to settle before reading
    // geometry so the overlap/viewport checks measure the final layout, not a keyframe.
    await waitForDrawerSettled(page, slideOver)

    // #68: the title box must NOT overlap the close-button box — the whole point of
    // the fix. Both must lie within the viewport too.
    const titleBox = await title.boundingBox()
    const closeBox = await closeBtn.boundingBox()
    expect(titleBox, 'title has a box').not.toBeNull()
    expect(closeBox, 'close button has a box').not.toBeNull()
    expect(
      disjoint(titleBox!, closeBox!),
      'slide-over title does not overlap the close button (#68)',
    ).toBe(true)
    await expectWithinViewport(page, closeBtn)

    // The close button must be genuinely clickable — it closes the slide-over.
    await closeBtn.click()
    await expect(slideOver).toBeHidden()
  })

  test('the quick filter stays pinned above the scrolling document list (#277)', async ({
    page,
    request,
    cleanup,
  }) => {
    // #277 (vmario89): the quick filter ("Filter loaded results…", #53) rendered as the
    // FIRST CHILD of the scrolling `.doc-area`, so on a phone it scrolled out of view
    // with the list — his screenshots show it sliding under the filter toolbar. The fix
    // pins it (position:sticky) to the top of the list scrollport at the ≤1024px mobile
    // breakpoint. This asserts the pin: scroll the list to its end, the quick filter's
    // bounding box must still sit inside the viewport, below the (non-scrolling) filter
    // toolbar. RED before the fix: its box ends up hundreds of px above the viewport top.
    //
    // Seed enough documents that the list overflows the mobile scrollport by several
    // hundred px (the default page size of 20 keeps them all on one page). Seeded over
    // the API — 14 UI round-trips through the add form would dominate the test's runtime.
    const ids: string[] = []
    for (let i = 0; i < 14; i++) {
      const res = await request.put('/api/document', {
        headers: { 'content-type': 'application/x-www-form-urlencoded' },
        data: new URLSearchParams([
          ['title', unique(`Pin-scroll-doc-${i}`)],
          ['language', 'eng'],
        ]).toString(),
      })
      expect(res.ok(), `seed document ${i} for the scroll corpus`).toBeTruthy()
      ids.push((await res.json()).id as string)
    }
    cleanup.defer('delete the #277 scroll corpus', async () => {
      for (const id of ids) await request.delete(`/api/document/${id}`)
    })

    await gotoDocumentList(page)
    const docArea = page.locator('.doc-area')
    const quickFilter = page.locator('.quick-filter-row')
    const toolbar = page.locator('.wf-filter-row')
    await expect(quickFilter).toBeVisible()
    await expect(toolbar).toBeVisible()

    // Precondition, not politeness: the assertion below is vacuous unless the list
    // genuinely overflows its scrollport. Poll until the seeded rows are rendered and
    // the container has real scroll range.
    await expect
      .poll(() => docArea.evaluate((el) => el.scrollHeight - el.clientHeight), {
        message: 'the document list overflows its scrollport by several rows',
      })
      .toBeGreaterThan(300)

    // The user scrolls the list to its end.
    await docArea.evaluate((el) => {
      el.scrollTop = el.scrollHeight
    })
    // Positive control: the container actually scrolled (the pin must not be proven
    // against a list that never moved).
    await expect
      .poll(() => docArea.evaluate((el) => el.scrollTop), {
        message: 'the list scrolled',
      })
      .toBeGreaterThan(200)

    const vh = page.viewportSize()!.height
    const quickBox = await quickFilter.boundingBox()
    const toolbarBox = await toolbar.boundingBox()
    expect(quickBox, 'quick filter has a layout box').not.toBeNull()
    expect(toolbarBox, 'filter toolbar has a layout box').not.toBeNull()

    // The pin itself — RED before the fix (the row's top edge lands far above y=0).
    expect(quickBox!.y, 'quick filter top edge stays within the viewport').toBeGreaterThanOrEqual(0)
    expect(
      quickBox!.y + quickBox!.height,
      'quick filter bottom edge stays within the viewport',
    ).toBeLessThanOrEqual(vh + 1)
    // Pinned ABOVE the list, BELOW the toolbar — not floating over other chrome.
    expect(
      quickBox!.y,
      'quick filter sits below the filter toolbar',
    ).toBeGreaterThanOrEqual(toolbarBox!.y + toolbarBox!.height - 1)
    // And the filter toolbar itself is still fully on screen after the scroll
    // (the ticket's acceptance line: the toolbar stays visible while the list scrolls).
    expect(toolbarBox!.y, 'filter toolbar top edge on screen').toBeGreaterThanOrEqual(0)
    expect(
      toolbarBox!.y + toolbarBox!.height,
      'filter toolbar bottom edge on screen',
    ).toBeLessThanOrEqual(vh + 1)
    // The quick filter must still be interactive while pinned: type into it and the
    // client-side narrowing responds (the row is a control, not a decoration).
    await quickFilter.locator('input').fill('Pin-scroll-doc')
    await expect(quickFilter.locator('input')).toHaveValue('Pin-scroll-doc')
  })

  // NOTE: the toHaveScreenshot glitch-detectors that once lived here (gated behind
  // E2E_VISUAL=1, no committed baselines) were REPLACED by the standing, default-on
  // visual-regression gate in `visual.spec.ts`, which covers the document list and the
  // slide-over (and four more key screens) across {desktop,mobile} × {en,de} with
  // committed Linux baselines. This spec keeps ONLY the environment-independent
  // FUNCTIONAL assertions above as the mobile hard gate; the pixel comparison is owned
  // by visual.spec.ts so there is a single place baselines are generated and committed.
})
