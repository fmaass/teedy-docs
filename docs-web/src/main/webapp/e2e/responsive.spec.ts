import { test, expect, type Locator } from '@playwright/test'
import { createDocument, unique } from './helpers'

// Mobile / responsive coverage. This spec runs ONLY under the `mobile-chrome`
// project (Pixel 5 viewport, testMatch in playwright.config.ts), so every test
// here executes at 393×851 with touch — the viewport that trips AppLayout's
// `matchMedia('(max-width: 1024px)')` branch (AppLayout.vue:49). The desktop
// `chromium` project testIgnores this file, so these assertions never run at the
// desktop viewport (where they'd be meaningless).
//
// Two kinds of assertion live here:
//   1. FUNCTIONAL (the hard gate): environment-independent structural checks that
//      the mobile branch renders correctly and the shipped mobile fixes (#67 nav
//      icon width, #68 slide-over header) hold. These MUST pass in CI.
//   2. VISUAL (soft, baseline-pending): toHaveScreenshot glitch detectors on two
//      key mobile screens. Baselines are renderer/OS-sensitive and MUST be
//      generated on the Linux CI runner — see the note at the bottom of this file
//      and e2e/COVERAGE.md. They are NOT the hard gate.

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
    await page.goto('/#/document')
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
    await page.goto('/#/document')
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
    await page.goto('/#/document')

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
    await page.goto('/#/document')
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

  // --- VISUAL REGRESSION (soft, baseline-pending) ----------------------------
  // These are the CSS-glitch class the #67/#68 fixes belong to. They are NOT the
  // hard gate: toHaveScreenshot baselines are renderer/OS-sensitive, so a macOS
  // baseline flakes on Linux CI. Approach:
  //   * config sets a generous maxDiffPixelRatio/threshold (playwright.config.ts).
  //   * NO baseline PNGs are committed from macOS. On the first CI run the
  //     authoritative Linux baselines are generated with `--update-snapshots`
  //     (see e2e/COVERAGE.md), then committed from that run.
  //   * Until a Linux baseline exists a bare `playwright test` on a fresh checkout
  //     reports these as "missing snapshot". To keep the FUNCTIONAL assertions the
  //     hard gate and prevent a missing baseline from blocking, this whole block is
  //     gated on E2E_VISUAL=1 — CI's baseline-generation/verify step sets it; the
  //     default e2e run skips it. Once Linux baselines are committed, flip CI to set
  //     E2E_VISUAL=1 on every run to make the visual diff a standing gate.
  test.describe('visual regression', () => {
    test.skip(process.env.E2E_VISUAL !== '1',
      'visual snapshots run only when E2E_VISUAL=1 (Linux baselines required — see e2e/COVERAGE.md)')

    test('document list — mobile', async ({ page }) => {
      await page.goto('/#/document')
      // The header Logout action is the always-visible shell anchor on mobile (the
      // brand link is hidden inside the closed Drawer), so wait on it before capture.
      await expect(page.getByRole('button', { name: 'Logout' })).toBeVisible()
      await expect(page).toHaveScreenshot('mobile-document-list.png', { fullPage: true })
    })

    test('slide-over — mobile', async ({ page }) => {
      const title = unique('Visual-Slide-Over-Doc')
      await createDocument(page, title)
      await page.goto('/#/document')
      await page.getByRole('cell', { name: title }).click()
      const slideOver = page.getByRole('dialog')
      await expect(slideOver).toBeVisible()
      await expect(page.locator('.slide-over-title')).toHaveText(title)
      await expect(slideOver).toHaveScreenshot('mobile-slide-over.png')
    })
  })
})
