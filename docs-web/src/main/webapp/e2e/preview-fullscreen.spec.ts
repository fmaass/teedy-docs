import { test, expect, type APIRequestContext } from './fixtures'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'
import { readFileSync } from 'node:fs'
import { unique, deleteDocApi, gotoRaw } from './helpers'

// #205 — the in-app file preview can be taken FULLSCREEN.
//
// The windowed dialog is deliberately modest: 92vw clamped to 960px, with the media capped
// at 70vh. On a large screen that leaves a document unreadably small. The PrimeVue
// `maximizable` affordance now drives BOTH relaxations together — a maximization that
// lifted the 70vh media cap but kept the 960px width clamp (or vice versa) would still not
// be fullscreen — so this spec measures both: the dialog box fills the viewport width, and
// the media grows past the height it was pinned to while windowed.
//
// FIXTURE CHOICE: tall.png is 20x60, whose aspect-preserving `web` raster is 427x1280. In
// the windowed dialog that image is limited by the 70vh CAP, not by the dialog's width —
// which is the quantity this feature relaxes. A wide or square fixture would be
// width-limited instead and the cap assertions below would pass vacuously.
const here = dirname(fileURLToPath(import.meta.url))
const tallPng = resolve(here, 'fixtures/tall.png')

async function seedImageDoc(request: APIRequestContext, title: string): Promise<string> {
  const docRes = await request.put('/api/document', {
    headers: { 'content-type': 'application/x-www-form-urlencoded' },
    data: new URLSearchParams([['title', title], ['language', 'eng']]).toString(),
  })
  const id = (await docRes.json()).id as string
  await request.put('/api/file', {
    multipart: { id, file: { name: 'tall.png', mimeType: 'image/png', buffer: readFileSync(tallPng) } },
  })
  return id
}

// True once the file's async processing has produced its real raster. Until then the server
// serves a SQUARE 1280x1280 placeholder, whose geometry would not be height-limited.
async function processingDone(request: APIRequestContext, documentId: string): Promise<boolean> {
  const res = await request.get(`/api/file/list?id=${documentId}`)
  if (!res.ok()) return false
  const body = await res.json()
  return body.files.length > 0 && body.files.every((f: { processing: boolean }) => !f.processing)
}

async function fileIdOf(request: APIRequestContext, documentId: string): Promise<string> {
  const res = await request.get(`/api/file/list?id=${documentId}`)
  expect(res.ok()).toBeTruthy()
  return (await res.json()).files[0].id as string
}

test('maximizing the preview dialog lifts BOTH the 960px width clamp and the 70vh media cap (#205)', async ({
  page,
  cleanup,
}) => {
  const id = await seedImageDoc(page.request, unique('fullscreen'))
  cleanup.defer('purge the seeded document', () => deleteDocApi(page.request, id))
  await expect.poll(() => processingDone(page.request, id)).toBe(true)
  const fileId = await fileIdOf(page.request, id)

  // The file deep link opens the preview dialog directly on that file (#192).
  // raw: the `?file=` deep link IS the subject — its param lifecycle is what this test measures.
  await gotoRaw(page, `/#/document/view/${id}/content?file=${fileId}`)
  const dialog = page.getByRole('dialog')
  await expect(dialog).toBeVisible()

  const image = dialog.locator('img.file-preview-image')
  await expect(image).toBeVisible()
  // The raster must be DECODED before any geometry is read: an undecoded <img> has no
  // intrinsic size, so its box would be measured against the wrong constraints.
  await expect
    .poll(() => image.evaluate((el: HTMLImageElement) => el.naturalHeight))
    .toBeGreaterThan(0)

  const viewport = page.viewportSize()!
  const cap = viewport.height * 0.7

  // WINDOWED BASELINE — asserted, not assumed: it is what the maximized measurements are
  // compared against. The windowed dialog's own height budget (PrimeVue caps a dialog at 90%
  // of the viewport, minus its header/footer) lands just under the 70vh media cap, so the
  // image measures at or below `cap` here — 493px against a 504px cap at 720px viewport
  // height, measured. Both ceilings are viewport-relative and both have to give way before
  // the media can grow, which is what the maximized assertions below measure.
  const windowedBox = (await image.boundingBox())!
  expect(windowedBox.height, 'the windowed preview is limited by the 70vh cap').toBeLessThanOrEqual(
    cap + 1,
  )

  // MAXIMIZE.
  await dialog.getByRole('button', { name: 'Toggle full screen' }).click()
  await expect(dialog).toHaveClass(/p-dialog-maximized/)

  // (1) The dialog fills the viewport width — proving the inline 960px clamp is dropped
  //     while maximized (on the desktop project the viewport is 1280px wide, so a surviving
  //     clamp would leave this ~320px short).
  await expect
    .poll(async () => (await dialog.boundingBox())!.width)
    .toBeGreaterThanOrEqual(viewport.width - 1)

  // (2) The media grows PAST the 70vh cap it was pinned to, and still fits the viewport —
  //     fullscreen means bigger, not an overflowing image the user has to scroll.
  await expect
    .poll(async () => (await image.boundingBox())!.height, {
      message: 'the maximized preview grows past the windowed 70vh cap',
    })
    .toBeGreaterThan(windowedBox.height)
  const maximizedBox = (await image.boundingBox())!
  expect(maximizedBox.height).toBeGreaterThan(cap)
  expect(maximizedBox.height, 'the maximized preview still fits the viewport').toBeLessThanOrEqual(
    viewport.height,
  )

  // RESTORE — the affordance is a toggle: unmaximizing puts the windowed caps back, so the
  // relaxation is scoped to the maximized state and does not leak into the normal dialog.
  await dialog.getByRole('button', { name: 'Toggle full screen' }).click()
  await expect(dialog).not.toHaveClass(/p-dialog-maximized/)
  await expect
    .poll(async () => (await image.boundingBox())!.height)
    .toBeLessThanOrEqual(cap + 1)
})
