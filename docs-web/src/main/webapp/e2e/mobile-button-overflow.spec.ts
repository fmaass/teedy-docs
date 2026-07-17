import { test, expect, type Page } from './fixtures'

// Regression gate for issue #86 (two buttons broken at the mobile viewport in German):
//   1. Tag create button ("Erstellen") is squeezed by its flex row and clips its label.
//   2. Metadata "Feld hinzufügen" button wraps its label to two lines and clips at the
//      right edge, crowding the section heading.
//
// This is a SHARED spec (not responsive.spec.ts): it runs under BOTH the `desktop` and
// `mobile` Playwright projects. On mobile it is the hard gate that reproduces #86; on
// desktop it is the guarantee the fix introduced no desktop-layout regression (the same
// geometry invariants hold at the wide viewport, where both labels already fit).
//
// German is the reported failure locale (the English labels happen to fit), so every test
// activates `de` before the app boots.

const TOL = 1

interface Box {
  x: number
  y: number
  width: number
  height: number
}

// True when `inner` lies fully inside `outer` (with a sub-pixel tolerance).
function contains(outer: Box, inner: Box, tol = TOL): boolean {
  return (
    inner.x >= outer.x - tol &&
    inner.y >= outer.y - tol &&
    inner.x + inner.width <= outer.x + outer.width + tol &&
    inner.y + inner.height <= outer.y + outer.height + tol
  )
}

// True when two boxes do not intersect (touching edges count as disjoint).
function disjoint(a: Box, b: Box): boolean {
  return (
    a.x + a.width <= b.x ||
    b.x + b.width <= a.x ||
    a.y + a.height <= b.y ||
    b.y + b.height <= a.y
  )
}

interface ButtonGeom {
  box: Box
  scrollWidth: number
  clientWidth: number
  scrollHeight: number
  clientHeight: number
  labelText: string | null
  // Client rects of the label text, measured via a Range — where the glyphs are
  // ACTUALLY painted. Asserting only the label's own scrollWidth would be fooled by an
  // `overflow: hidden` parent that clips the label (it reports no overflow while its
  // glyphs are visibly cut off); the Range rects catch a clipped label.
  labelRects: Box[]
}

// Assert the shared per-button invariants (a,b,c): box inside the viewport, no internal
// overflow, every painted label rect inside the button box.
function assertButtonWithinAndLabelUnclipped(geom: ButtonGeom, viewportWidth: number) {
  // (a) button box right edge within the viewport.
  expect(geom.box.x + geom.box.width, 'button right edge within viewport').toBeLessThanOrEqual(
    viewportWidth + 1,
  )
  // (b) button root does not overflow internally (a squeezed/wrapped button clips text).
  expect(geom.scrollWidth, 'button scrollWidth <= clientWidth').toBeLessThanOrEqual(
    geom.clientWidth + 1,
  )
  expect(geom.scrollHeight, 'button scrollHeight <= clientHeight').toBeLessThanOrEqual(
    geom.clientHeight + 1,
  )
  // (c) every painted label rect lies inside the button box (glyphs not clipped).
  expect(geom.labelRects.length, 'label has painted text rects').toBeGreaterThan(0)
  for (const [i, rect] of geom.labelRects.entries()) {
    expect(contains(geom.box, rect), `label rect ${i} painted within the button box`).toBe(true)
  }
}

test.describe('mobile button label overflow (#86)', () => {
  // Activate German BEFORE any app script runs so main.ts (main.ts:19) boots into `de`.
  // Scoped to this describe's pages; not written back to the shared storageState.
  test.beforeEach(async ({ page }: { page: Page }) => {
    await page.addInitScript(() => localStorage.setItem('teedy-locale', 'de'))
  })

  test('tag create button ("Erstellen") stays within its row and never clips its label', async ({
    page,
  }) => {
    await page.goto('/#/tag')
    // Gate on the German heading so measurement waits for the `de` chunk to swap in.
    await expect(page.getByRole('heading', { name: 'Tag erstellen' })).toBeVisible()

    // The second `.create-row` is the Select + submit-button row (the first holds the
    // colour picker + name input).
    const createRow = page.locator('.create-row').nth(1)
    const button = createRow.locator('.p-button')
    await expect(button).toBeVisible()
    // Confirm it is the create button in German (guards against a selector drift).
    await expect(button.locator('.p-button-label')).toHaveText('Erstellen')

    // ONE atomic layout snapshot of the whole row (row + select + button + label rects),
    // read at a single scroll position. Measuring the container and its children in
    // separate calls risks Playwright's auto-scroll shifting the viewport between reads.
    const geom = await createRow.evaluate((row) => {
      const box = (el: Element) => {
        const r = el.getBoundingClientRect()
        return { x: r.x, y: r.y, width: r.width, height: r.height }
      }
      const probeButton = (btn: Element) => {
        const b = btn.getBoundingClientRect()
        const label = btn.querySelector('.p-button-label')
        let labelRects: { x: number; y: number; width: number; height: number }[] = []
        let labelText: string | null = null
        if (label) {
          labelText = label.textContent
          const range = document.createRange()
          range.selectNodeContents(label)
          labelRects = Array.from(range.getClientRects()).map((x) => ({
            x: x.x,
            y: x.y,
            width: x.width,
            height: x.height,
          }))
        }
        return {
          box: { x: b.x, y: b.y, width: b.width, height: b.height },
          scrollWidth: (btn as HTMLElement).scrollWidth,
          clientWidth: (btn as HTMLElement).clientWidth,
          scrollHeight: (btn as HTMLElement).scrollHeight,
          clientHeight: (btn as HTMLElement).clientHeight,
          labelText,
          labelRects,
        }
      }
      return {
        rowBox: box(row),
        selectBox: box(row.querySelector('.p-select')!),
        button: probeButton(row.querySelector('.p-button')!),
        viewportWidth: window.innerWidth,
      }
    })

    assertButtonWithinAndLabelUnclipped(geom.button, geom.viewportWidth)
    // (row containment) button box contained by its own .create-row.
    expect(contains(geom.rowBox, geom.button.box), 'button box contained by its .create-row').toBe(
      true,
    )
    // (e) the Select is also contained by the row (the row is not overrun).
    expect(contains(geom.rowBox, geom.selectBox), 'Select box contained by its .create-row').toBe(
      true,
    )
  })

  test('metadata "Feld hinzufügen" button fits without clipping or crowding the heading', async ({
    page,
  }) => {
    await page.goto('/#/settings/metadata')
    await expect(page.getByRole('heading', { name: 'Benutzerdefinierte Metadaten' })).toBeVisible()

    const button = page.locator('.section-header .p-button')
    await expect(button).toBeVisible()
    await expect(button.locator('.p-button-label')).toHaveText('Feld hinzufügen')

    // ONE atomic snapshot of the section header: the heading/description wrapper (first
    // child div) and the add-field button, measured at a single scroll position.
    const geom = await page.locator('.section-header').evaluate((header) => {
      const box = (el: Element) => {
        const r = el.getBoundingClientRect()
        return { x: r.x, y: r.y, width: r.width, height: r.height }
      }
      const probeButton = (btn: Element) => {
        const b = btn.getBoundingClientRect()
        const label = btn.querySelector('.p-button-label')
        let labelRects: { x: number; y: number; width: number; height: number }[] = []
        let labelText: string | null = null
        if (label) {
          labelText = label.textContent
          const range = document.createRange()
          range.selectNodeContents(label)
          labelRects = Array.from(range.getClientRects()).map((x) => ({
            x: x.x,
            y: x.y,
            width: x.width,
            height: x.height,
          }))
        }
        return {
          box: { x: b.x, y: b.y, width: b.width, height: b.height },
          scrollWidth: (btn as HTMLElement).scrollWidth,
          clientWidth: (btn as HTMLElement).clientWidth,
          scrollHeight: (btn as HTMLElement).scrollHeight,
          clientHeight: (btn as HTMLElement).clientHeight,
          labelText,
          labelRects,
        }
      }
      return {
        headingBox: box(header.querySelector(':scope > div')!),
        button: probeButton(header.querySelector('.p-button')!),
        viewportWidth: window.innerWidth,
      }
    })

    assertButtonWithinAndLabelUnclipped(geom.button, geom.viewportWidth)
    // (d) button box disjoint from the heading/description wrapper (no overlap).
    expect(
      disjoint(geom.button.box, geom.headingBox),
      'button does not overlap the heading/description',
    ).toBe(true)
  })
})
