import { test, expect } from './fixtures'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'
import { unique, createDocument, confirmDanger } from './helpers'

// v3.5.2 — PERSISTED, non-destructive PDF/image rotation. Rotation is stored per file and baked
// into the served `_web`/`_thumb` rasters (images) or applied by pdf.js from the stored value (PDF).
// These assert PERSISTENCE against the AUTHORITATIVE server state (GET /api/document/:id?files=true
// returns files[].rotation), that it SURVIVES a reload and a second session, and — secondary —
// that the served image raster reflects the rotation (its width/height SWAP after 90°; the `_web`
// raster is aspect-preserving, NOT a square canvas, so the swap is a real geometry change).
//
// wide.png (60x20) upscales to an aspect-preserving 1280x427 web raster; before processing finishes
// the server serves a SQUARE 1280x1280 placeholder, so the tests wait for the real (non-square)
// raster before rotating. sample.pdf is a portrait PDF; pdf.js re-renders it landscape at the
// stored rotation.

const here = dirname(fileURLToPath(import.meta.url))
const widePng = resolve(here, 'fixtures/wide.png')
const samplePdf = resolve(here, 'fixtures/sample.pdf')

// Authoritative persisted rotation of a document's first file, read from the REST API using the
// page's authenticated session.
async function persistedRotation(
  request: import('@playwright/test').APIRequestContext,
  documentId: string,
): Promise<number> {
  const res = await request.get(`/api/document/${documentId}?files=true`)
  expect(res.ok()).toBeTruthy()
  const body = await res.json()
  return body.files[0].rotation
}

// True once the file's async processing has produced its real raster (processing flag cleared).
async function processingDone(
  request: import('@playwright/test').APIRequestContext,
  documentId: string,
): Promise<boolean> {
  const res = await request.get(`/api/file/list?id=${documentId}`)
  if (!res.ok()) return false
  const body = await res.json()
  return body.files.length > 0 && body.files.every((f: { processing: boolean }) => !f.processing)
}

// The natural (intrinsic) dimensions of a served image raster, decoded in the page. A cache-buster
// is appended so a rotation-changed raster is re-fetched rather than read from the HTTP cache.
async function rasterDimensions(
  page: import('@playwright/test').Page,
  fileId: string,
  bust: string,
): Promise<{ width: number; height: number }> {
  const url = new URL(`api/file/${fileId}/data?size=web&probe=${bust}`, page.url()).toString()
  return page.evaluate(
    (src) =>
      new Promise<{ width: number; height: number }>((res, rej) => {
        const img = new Image()
        img.onload = () => res({ width: img.naturalWidth, height: img.naturalHeight })
        img.onerror = () => rej(new Error('raster load failed'))
        img.src = src
      }),
    url,
  )
}

test('image rotation persists to the server and survives a reload', async ({ page }) => {
  const title = unique('rotate-img')
  const { id } = await createDocument(page, title)

  await page.goto(`/#/document/view/${id}/content`)

  const input = page.locator('.p-fileupload-advanced input[type="file"]')
  await input.setInputFiles(widePng)
  await expect(page.getByText('Files uploaded').first()).toBeVisible()

  const card = page.locator('.file-preview-card').first()
  const img = card.locator('.rotatable-image')
  await expect(img).toBeVisible()

  // Wait for the async raster to be produced (else the server serves a square placeholder and the
  // rotate endpoint returns a retriable "still processing" error).
  await expect.poll(() => processingDone(page.request, id)).toBe(true)

  // Baseline: server rotation 0, and the real upright web raster is landscape (60x20 → 1280x427).
  expect(await persistedRotation(page.request, id)).toBe(0)
  const upright = await rasterDimensions(page, await fileIdOf(page.request, id), 'up')
  expect(upright.width).toBeGreaterThan(upright.height)

  // Rotate right 90°. AUTHORITATIVE persistence: the server now reports rotation 90.
  await card.getByRole('button', { name: 'Rotate right' }).click()
  await expect.poll(() => persistedRotation(page.request, id)).toBe(90)

  // The <img src> carries the ?v=90 cache-bust so the freshly-oriented raster loads.
  await expect.poll(async () => (await img.getAttribute('src'))?.includes('v=90')).toBe(true)

  // The regenerated web raster's axes are SWAPPED — full content preserved, no crop (a cropping
  // rotation would keep the landscape box).
  const rotated = await rasterDimensions(page, await fileIdOf(page.request, id), 'rot')
  expect(rotated.width).toBe(upright.height)
  expect(rotated.height).toBe(upright.width)
  expect(rotated.height).toBeGreaterThan(rotated.width)

  // Reload: the rotation is PERSISTED, not display-only → the server still reports 90.
  await page.reload()
  expect(await persistedRotation(page.request, id)).toBe(90)
  const afterReload = page.locator('.file-preview-card').first().locator('.rotatable-image')
  await expect(afterReload).toBeVisible()
  await expect.poll(async () => (await afterReload.getAttribute('src'))?.includes('v=90')).toBe(true)

  // Cleanup.
  await page.goto(`/#/document/view/${id}`)
  await page.getByRole('button', { name: 'Delete', exact: true }).click()
  await confirmDanger(page)
})

test('a second session sees the persisted image rotation (server-stored, not per-session)', async ({
  page,
  browser,
}) => {
  const title = unique('rotate-img-2')
  const { id } = await createDocument(page, title)

  await page.goto(`/#/document/view/${id}/content`)
  await page.locator('.p-fileupload-advanced input[type="file"]').setInputFiles(widePng)
  await expect(page.getByText('Files uploaded').first()).toBeVisible()
  await expect.poll(() => processingDone(page.request, id)).toBe(true)

  await page.locator('.file-preview-card').first().getByRole('button', { name: 'Rotate right' }).click()
  await expect.poll(() => persistedRotation(page.request, id)).toBe(90)

  // A DISTINCT authenticated browser context (its own cookie jar — a genuinely separate session,
  // not just another tab of the same session) reads the rotation from the server, proving it is
  // stored server-side and not held in the first session's memory. The admin storageState re-auths
  // this second context.
  const otherContext = await browser.newContext({ storageState: 'e2e/.auth/admin.json' })
  try {
    const res = await otherContext.request.get(`/api/document/${id}?files=true`)
    expect(res.ok()).toBeTruthy()
    const body = await res.json()
    expect(body.files[0].rotation).toBe(90)
  } finally {
    await otherContext.close()
  }

  // Cleanup.
  await page.goto(`/#/document/view/${id}`)
  await page.getByRole('button', { name: 'Delete', exact: true }).click()
  await confirmDanger(page)
})

test('PDF rotation persists to the server and re-renders the canvas landscape', async ({ page }) => {
  const title = unique('rotate-pdf')
  const { id } = await createDocument(page, title)

  await page.goto(`/#/document/view/${id}/content`)
  await page.locator('.p-fileupload-advanced input[type="file"]').setInputFiles(samplePdf)
  await expect(page.getByText('Files uploaded').first()).toBeVisible()
  await expect.poll(() => processingDone(page.request, id)).toBe(true)

  const canvas = page.locator('.pdf-canvas-container canvas')
  await expect(canvas).toBeVisible()
  await expect
    .poll(async () => {
      const box = await canvas.boundingBox()
      return box ? box.width > 0 && box.height > 0 : false
    })
    .toBe(true)

  // sample.pdf is portrait → the rendered canvas is taller than wide.
  const portrait = await canvas.boundingBox()
  expect(portrait!.height).toBeGreaterThan(portrait!.width)

  // Rotate RIGHT 90deg. The canvas re-renders landscape (pdf.js applies the rotation) AND the
  // rotation is PERSISTED to the server (the PDF original is not baked; the stored value seeds
  // pdf.js on the next open).
  await page.getByRole('button', { name: 'Rotate right' }).click()
  await expect
    .poll(async () => {
      const box = await canvas.boundingBox()
      return box ? box.width > box.height : false
    })
    .toBe(true)
  await expect.poll(() => persistedRotation(page.request, id)).toBe(90)

  // Reopen: the stored rotation seeds the viewer, so the canvas renders landscape from the first
  // frame without any user interaction.
  await page.reload()
  const reopened = page.locator('.pdf-canvas-container canvas')
  await expect(reopened).toBeVisible()
  await expect
    .poll(async () => {
      const box = await reopened.boundingBox()
      return box ? box.width > box.height : false
    })
    .toBe(true)

  // Cleanup.
  await page.goto(`/#/document/view/${id}`)
  await page.getByRole('button', { name: 'Delete', exact: true }).click()
  await confirmDanger(page)
})

// The first (main) file id of a document, from the authoritative list.
async function fileIdOf(
  request: import('@playwright/test').APIRequestContext,
  documentId: string,
): Promise<string> {
  const res = await request.get(`/api/document/${documentId}?files=true`)
  const body = await res.json()
  return body.files[0].id
}
