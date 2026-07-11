import { test, expect } from '@playwright/test'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'
import { unique, createDocument, confirmDanger } from './helpers'

// #35 — view-only PDF/image rotation. These assert a FALSIFIABLE, orientation-
// observable change (transform / aspect ratio), NOT "swapped pixel dimensions"
// alone, and that rotation does NOT persist across a reload (it's display-only).
//
// Fixtures: wide.png is a deliberately ASYMMETRIC rectangle (60x20) so a 90/270
// rotation is geometrically observable; the 1x1 pixel.png cannot demonstrate it.
// nearsquare.png (600x560) is near-square and LARGER than the preview stage box,
// so the sizing caps — not its natural size — determine its rendered dimensions;
// that is what makes rotated CLIPPING falsifiable in a narrow column (a fixed
// 400px cap would paint it wider than a <400px stage and clip).
// sample.pdf is a single-page portrait PDF.

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

test('image preview rotates asymmetrically, leaves siblings alone, and resets on reload', async ({ page }) => {
  const title = unique('rotate-img')
  const { id } = await createDocument(page, title)

  await page.goto(`/#/document/view/${id}/content`)

  // Upload the asymmetric image TWICE (two sibling cards of the same file shape).
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

  // Capture the first image's on-screen bounding box while UPRIGHT (wide: w > h).
  const before = await firstImg.boundingBox()
  expect(before, 'first image bounding box').not.toBeNull()
  expect(before!.width).toBeGreaterThan(before!.height)

  // Rotate the first card RIGHT (90deg). The stage gains the sideways class and
  // the image carries a rotate(90deg) transform — an orientation-observable change.
  await cards.nth(0).getByRole('button', { name: 'Rotate right' }).click()
  await expect(cards.nth(0).locator('.image-preview-stage')).toHaveClass(/is-sideways/)
  expect(await appliedRotation(firstImg)).toBe(90)

  // The rotated image's rendered orientation is now tall (the 60x20 wide image
  // painted at 90deg is 20 wide x 60 tall on screen) — falsifiable geometry, not
  // a mere dimension swap in the DOM box.
  await expect
    .poll(async () => {
      const box = await firstImg.boundingBox()
      return box ? box.height > box.width : false
    })
    .toBe(true)

  // Sibling is UNAFFECTED: still upright, no transform, no sideways class.
  expect(await appliedRotation(secondImg)).toBe(0)
  await expect(cards.nth(1).locator('.image-preview-stage')).not.toHaveClass(/is-sideways/)

  // Reload: rotation is display-only, never persisted → first card returns upright.
  await page.reload()
  const firstAfterReload = page.locator('.file-preview-card').nth(0).locator('.rotatable-image')
  await expect(firstAfterReload).toBeVisible()
  expect(await appliedRotation(firstAfterReload)).toBe(0)
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

  // And the rotation actually took visual effect: the near-square 600x560 turns
  // taller-than-wide once sideways (its aspect inverts), so this is not a no-op.
  const rotated = await img.boundingBox()
  expect(rotated!.height).toBeGreaterThan(rotated!.width)

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
