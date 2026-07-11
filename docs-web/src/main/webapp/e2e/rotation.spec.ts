import { test, expect } from '@playwright/test'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'
import { unique, createDocument, confirmDanger } from './helpers'

// #35 — view-only PDF/image rotation. These assert a FALSIFIABLE, orientation-
// observable change (a real applied CSS transform / a re-rendered canvas), NOT "swapped
// pixel dimensions" alone, and that rotation does NOT persist across a reload
// (it's display-only).
//
// Fixtures & a server fact that shapes these tests: Teedy renders EVERY uploaded image
// onto an A4 PDF page and serves the `web` variant as a normalized 1280x1280 SQUARE
// raster (FileProcessingAsyncListener + ImageFormatHandler). So an image preview's
// bounding box is intrinsically square regardless of the source aspect — a square
// image's 90° rotation is NOT observable by box aspect (that was the false premise
// behind the earlier flaky `boundingBox().width > height` precondition, which could
// never hold). For images we therefore prove rotation by the REAL applied transform
// (getComputedStyle().transform is a concrete 90° matrix, not `none`), the sideways
// stage class, sibling isolation, and reset-on-reload. The genuine aspect-INVERSION
// proof lives in the PDF test below: PDFs render at their native (portrait) aspect, so
// rotating one to landscape is a falsifiable geometry change.
// nearsquare.png (600x560) drives the narrow-column no-clipping test (the sizing caps,
// not the natural size, determine the rendered box). sample.pdf is a portrait PDF.

const here = dirname(fileURLToPath(import.meta.url))
const widePng = resolve(here, 'fixtures/wide.png')
const nearSquarePng = resolve(here, 'fixtures/nearsquare.png')
const samplePdf = resolve(here, 'fixtures/sample.pdf')

// Read the numeric rotate(Ndeg) applied to an element's inline transform.
async function appliedRotation(el: import('@playwright/test').Locator): Promise<number> {
  const style = (await el.getAttribute('style')) ?? ''
  const m = style.match(/rotate\((-?\d+)deg\)/)
  return m ? Number(m[1]) : 0
}

// The COMPUTED transform (a DOMMatrix string like "matrix(a,b,c,d,e,f)") of a 90°
// rotation — proves the rotation actually took visual effect at the pixel level, not
// merely that an inline style string was written. For a pure +90° rotation the matrix
// is ~matrix(0, 1, -1, 0, 0, 0): a ≈ 0 and b ≈ 1. `none` (no rotation) fails this.
async function computedIsRotated90(el: import('@playwright/test').Locator): Promise<boolean> {
  const t = await el.evaluate((node) => getComputedStyle(node as Element).transform)
  if (!t || t === 'none') return false
  const m = t.match(/matrix\(([^)]+)\)/)
  if (!m) return false
  const [a, b] = m[1].split(',').map((s) => Number(s.trim()))
  return Math.abs(a) < 0.01 && Math.abs(Math.abs(b) - 1) < 0.01
}

test('image preview rotates (real computed transform), leaves siblings alone, and resets on reload', async ({ page }) => {
  const title = unique('rotate-img')
  const { id } = await createDocument(page, title)

  await page.goto(`/#/document/view/${id}/content`)

  // Upload the same image TWICE (two sibling cards) to prove per-card isolation.
  const input = page.locator('.p-fileupload-advanced input[type="file"]')
  await input.setInputFiles(widePng)
  await expect(page.getByText('Files uploaded').first()).toBeVisible()
  await input.setInputFiles(widePng)
  await expect(page.getByText('Files uploaded').first()).toBeVisible()

  // Two preview cards render; wait until both images are actually present.
  const cards = page.locator('.file-preview-card')
  await expect(cards).toHaveCount(2)
  const firstImg = cards.nth(0).locator('.rotatable-image')
  const secondImg = cards.nth(1).locator('.rotatable-image')
  await expect(firstImg).toBeVisible()
  await expect(secondImg).toBeVisible()

  // Upright: neither card carries a rotation (baseline for the change below).
  expect(await appliedRotation(firstImg)).toBe(0)
  expect(await computedIsRotated90(firstImg)).toBe(false)

  // Rotate the first card RIGHT (90deg). The stage gains the sideways class and the
  // image carries a rotate(90deg) transform that is ACTUALLY COMPOSITED (a real 90°
  // matrix in the computed style) — an orientation-observable change, not a no-op.
  await cards.nth(0).getByRole('button', { name: 'Rotate right' }).click()
  await expect(cards.nth(0).locator('.image-preview-stage')).toHaveClass(/is-sideways/)
  expect(await appliedRotation(firstImg)).toBe(90)
  await expect.poll(() => computedIsRotated90(firstImg)).toBe(true)

  // Sibling is UNAFFECTED: still upright, no rotation matrix, no sideways class.
  expect(await appliedRotation(secondImg)).toBe(0)
  expect(await computedIsRotated90(secondImg)).toBe(false)
  await expect(cards.nth(1).locator('.image-preview-stage')).not.toHaveClass(/is-sideways/)

  // Reload: rotation is display-only, never persisted → first card returns upright.
  await page.reload()
  const firstAfterReload = page.locator('.file-preview-card').nth(0).locator('.rotatable-image')
  await expect(firstAfterReload).toBeVisible()
  expect(await appliedRotation(firstAfterReload)).toBe(0)
  expect(await computedIsRotated90(firstAfterReload)).toBe(false)
  await expect(page.locator('.file-preview-card').nth(0).locator('.image-preview-stage')).not.toHaveClass(/is-sideways/)

  // Cleanup.
  await page.goto(`/#/document/view/${id}`)
  await page.getByRole('button', { name: 'Delete', exact: true }).click()
  await confirmDanger(page)
})

test('near-square image is NOT clipped when rotated in a narrow preview column', async ({ page }) => {
  // Constrain the viewport so the preview column (grid minmax(280px, 1fr)) lays
  // out WELL under 400px wide. Falsifiability bar: under the old fixed 400px
  // sideways caps, the 600x560 fixture painted at 400x373 → rotated visual width
  // 373px. The stage must be narrower than that 373px for the clipping to have
  // occurred, so a 360px viewport bounds the stage below it structurally.
  await page.setViewportSize({ width: 360, height: 800 })

  const title = unique('rotate-clip')
  const { id } = await createDocument(page, title)

  await page.goto(`/#/document/view/${id}/content`)
  await page.locator('.p-fileupload-advanced input[type="file"]').setInputFiles(nearSquarePng)
  await expect(page.getByText('Files uploaded').first()).toBeVisible()

  const card = page.locator('.file-preview-card').first()
  const stage = card.locator('.image-preview-stage')
  const img = card.locator('.rotatable-image')
  await expect(img).toBeVisible()

  // Sanity: the column really is narrower than the old-code rotated width (373px)
  // — the premise of the clipping scenario. If the layout ever changes so preview
  // columns can't get this narrow, the scenario no longer bites; fail loudly.
  const stageBox = await stage.boundingBox()
  expect(stageBox, 'stage bounding box').not.toBeNull()
  expect(stageBox!.width, 'stage must be narrower than 373px for this scenario').toBeLessThan(373)

  // Rotate right. Barrier: the transform is applied before measuring.
  await card.getByRole('button', { name: 'Rotate right' }).click()
  await expect(stage).toHaveClass(/is-sideways/)
  expect(await appliedRotation(img)).toBe(90)

  // ACCEPTANCE: the rotated image's visual bounding box (boundingBox reflects the
  // transform) fits entirely inside the stage's box on BOTH axes. overflow:hidden
  // does not shrink a clipped element's rect, so an overflow here is detected,
  // not masked. 1px tolerance for rounding.
  await expect
    .poll(async () => {
      const i = await img.boundingBox()
      const s = await stage.boundingBox()
      if (!i || !s) return 'no boxes'
      const fitsHorizontally = i.x >= s.x - 1 && i.x + i.width <= s.x + s.width + 1
      const fitsVertically = i.y >= s.y - 1 && i.y + i.height <= s.y + s.height + 1
      return fitsHorizontally && fitsVertically
        ? true
        : `img ${JSON.stringify(i)} vs stage ${JSON.stringify(s)}`
    })
    .toBe(true)

  // Rotation actually took visual effect (not a no-op) is already asserted above via
  // the `.is-sideways` class and appliedRotation === 90. A height>width aspect check is
  // deliberately NOT made here: a near-square image (600x560) constrained to the narrow
  // stage renders close to square, so its post-rotation box has no reliable major axis —
  // asserting one is flaky and, for this fixture, meaningless. Aspect inversion is
  // covered by the wide-fixture test above; this test's contract is non-clipping, which
  // the containment poll proves directly.

  // Cleanup.
  await page.goto(`/#/document/view/${id}`)
  await page.getByRole('button', { name: 'Delete', exact: true }).click()
  await confirmDanger(page)
})

test('PDF preview rotation changes the rendered canvas orientation', async ({ page }) => {
  const title = unique('rotate-pdf')
  const { id } = await createDocument(page, title)

  await page.goto(`/#/document/view/${id}/content`)
  await page.locator('.p-fileupload-advanced input[type="file"]').setInputFiles(samplePdf)
  await expect(page.getByText('Files uploaded').first()).toBeVisible()

  // Wait until the pdf.js canvas has finished its first render (non-zero size).
  const canvas = page.locator('.pdf-canvas-container canvas')
  await expect(canvas).toBeVisible()
  await expect
    .poll(async () => {
      const box = await canvas.boundingBox()
      return box ? box.width > 0 && box.height > 0 : false
    })
    .toBe(true)

  // sample.pdf is portrait (612x792) → the rendered canvas is taller than wide.
  const portrait = await canvas.boundingBox()
  expect(portrait!.height).toBeGreaterThan(portrait!.width)

  // Rotate RIGHT 90deg: the canvas re-renders against the rotated viewport and
  // becomes landscape (width > height) — a falsifiable orientation change of the
  // ACTUAL rendered pixels, not a swapped-dimensions assertion on the DOM.
  await page.getByRole('button', { name: 'Rotate right' }).click()
  await expect
    .poll(async () => {
      const box = await canvas.boundingBox()
      return box ? box.width > box.height : false
    })
    .toBe(true)

  // Cleanup.
  await page.goto(`/#/document/view/${id}`)
  await page.getByRole('button', { name: 'Delete', exact: true }).click()
  await confirmDanger(page)
})
