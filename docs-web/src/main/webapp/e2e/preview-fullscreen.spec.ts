import { test, expect, type Locator, type Page, type APIRequestContext } from './fixtures'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'
import { readFileSync } from 'node:fs'
import { unique, deleteDocApi, gotoRaw, ROUTE_ROOT } from './helpers'

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
// 3 pages, each a 300x300 MediaBox carrying its own page number in 96pt type — so a
// rendered page is unmistakably non-blank, and every page is the SAME square shape
// (a page taller than the scroll viewport at both project viewports, so a later page
// is genuinely out of view until something scrolls).
const multiPagePdf = resolve(here, 'fixtures/multipage.pdf')

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

// --- #237: the maximized PDF preview scrolls -------------------------------------
//
// Windowed, the PDF viewer is a single canvas with prev/next — deliberately unchanged.
// Maximized, the dialog IS the screen and a one-page-at-a-time viewer is the wrong shape:
// the reporter asked for pagination AND vertical scrolling. So maximizing turns the viewer
// into a continuous scroll of per-page canvases, and prev/next become jumps within it.
//
// REALNESS: every assertion below is against a REAL rendered canvas — the element exists,
// carries a non-zero bitmap, and its pixels are non-uniform (pdf.js actually painted the
// page). A page indicator alone proves nothing here: the windowed viewer counts to 3 just
// as well, over one canvas it re-renders in place.

async function seedPdfDoc(request: APIRequestContext, title: string): Promise<string> {
  const docRes = await request.put('/api/document', {
    headers: { 'content-type': 'application/x-www-form-urlencoded' },
    data: new URLSearchParams([['title', title], ['language', 'eng']]).toString(),
  })
  const id = (await docRes.json()).id as string
  await request.put('/api/file', {
    multipart: {
      id,
      file: { name: 'multipage.pdf', mimeType: 'application/pdf', buffer: readFileSync(multiPagePdf) },
    },
  })
  return id
}

/** Open the preview dialog on the seeded PDF, windowed, with its first page rendered. */
async function openPdfPreview(page: Page, documentId: string, fileId: string): Promise<Locator> {
  // raw: the `?file=` deep link opens the dialog during mount and is consumed there.
  await gotoRaw(page, `/#/document/view/${documentId}/content?file=${fileId}`)
  // The document view mounting and the dialog opening are two separate waits on purpose:
  // the deep link is consumed DURING mount, so folding them into one expectation makes a
  // slow SPA boot and a dialog that never opened the same failure — and gives the pair a
  // single timeout budget to share.
  await expect(page.locator(ROUTE_ROOT.documentContent)).toBeVisible()
  const dialog = page.getByRole('dialog')
  await expect(dialog).toBeVisible()
  // The windowed viewer is single-canvas — the baseline the maximized mode changes.
  await expect(dialog.locator('canvas')).toHaveCount(1)
  await expect(dialog.locator('.pdf-page-info')).toHaveText('1 / 3')
  return dialog
}

async function toggleMaximize(dialog: Locator): Promise<void> {
  await dialog.getByRole('button', { name: 'Toggle full screen' }).click()
}

/** True when the page's box overlaps the scroll container's own viewport. */
async function pageInView(dialog: Locator, pageNum: number): Promise<boolean> {
  return dialog.locator(`.pdf-page[data-page="${pageNum}"]`).evaluate((el) => {
    const root = el.closest('.pdf-pages')!
    const box = el.getBoundingClientRect()
    const viewport = root.getBoundingClientRect()
    return box.bottom > viewport.top + 1 && box.top < viewport.bottom - 1
  })
}

/**
 * True when the canvas holds a REAL rendering: a non-zero bitmap whose sampled pixels are
 * not all identical. An allocated-but-never-rendered canvas is uniformly transparent, so
 * this separates "pdf.js painted this page" from "an element exists".
 */
async function canvasIsPainted(canvas: Locator): Promise<boolean> {
  return canvas.evaluate((el: HTMLCanvasElement) => {
    if (!el.width || !el.height) return false
    const ctx = el.getContext('2d')
    if (!ctx) return false
    const { data } = ctx.getImageData(0, 0, el.width, el.height)
    let first: number | null = null
    // Every 16th pixel: the fixture's page number is set in 96pt on a 300pt page, so a
    // painted page cannot slip through the stride.
    for (let i = 0; i < data.length; i += 64) {
      const px = (data[i] << 24) | (data[i + 1] << 16) | (data[i + 2] << 8) | data[i + 3]
      if (first === null) first = px
      else if (px !== first) return true
    }
    return false
  })
}

/** The scroll container's own scroll offset — proof that something actually scrolled. */
function scrollTopOf(dialog: Locator): Promise<number> {
  return dialog.locator('.pdf-pages').evaluate((el) => el.scrollTop)
}

test('a maximized multi-page PDF reaches a below-the-fold page by SCROLLING alone (#237)', async ({
  page,
  cleanup,
}) => {
  const id = await seedPdfDoc(page.request, unique('pdfscroll'))
  cleanup.defer('purge the seeded PDF document', () => deleteDocApi(page.request, id))
  await expect.poll(() => processingDone(page.request, id)).toBe(true)
  const fileId = await fileIdOf(page.request, id)

  const dialog = await openPdfPreview(page, id, fileId)
  await toggleMaximize(dialog)
  await expect(dialog).toHaveClass(/p-dialog-maximized/)

  // Maximized, the pages are a scrollable column: taller than its own viewport.
  const pages = dialog.locator('.pdf-pages')
  await expect(pages).toBeVisible()
  await expect
    .poll(() => pages.evaluate((el) => el.scrollHeight - el.clientHeight), {
      message: 'the maximized page column overflows its viewport (there is something to scroll)',
    })
    .toBeGreaterThan(1)

  // The first page that starts BELOW the fold at rest — page 2 at a desktop viewport,
  // page 3 at a mobile one. Derived rather than hard-coded so the test asserts the same
  // fact under both Playwright projects instead of passing vacuously under one.
  const target = await pages.evaluate((root) => {
    const viewport = root.getBoundingClientRect()
    for (const el of Array.from(root.querySelectorAll('.pdf-page'))) {
      if (el.getBoundingClientRect().top >= viewport.bottom) {
        return Number(el.getAttribute('data-page'))
      }
    }
    return 0
  })
  expect(target, 'a 3-page PDF does not fit the maximized viewport — a later page is below the fold')
    .toBeGreaterThan(1)
  expect(await pageInView(dialog, target)).toBe(false)

  // SCROLL — no button, no jump: page the container down (as a wheel would) until the
  // indicator says the target page is the one on screen.
  const indicator = dialog.locator('.pdf-page-info')
  for (let i = 0; i < 12 && ((await indicator.textContent()) ?? '').trim() !== `${target} / 3`; i++) {
    await pages.evaluate((el) => {
      el.scrollTop += el.clientHeight * 0.9
    })
  }
  expect(await scrollTopOf(dialog), 'the container really scrolled').toBeGreaterThan(0)
  // The page indicator follows the scroll position rather than staying pinned at 1.
  await expect(indicator).toHaveText(`${target} / 3`)
  expect(await pageInView(dialog, target), `page ${target} is reachable by scrolling`).toBe(true)

  // …and it is a genuinely rendered page, not a reserved placeholder box.
  const targetCanvas = dialog.locator(`.pdf-page[data-page="${target}"] canvas`)
  await expect(targetCanvas).toBeVisible()
  await expect
    .poll(() => canvasIsPainted(targetCanvas), { message: `page ${target} rendered real content` })
    .toBe(true)
})

test('the next-page button scrolls page 2 CANVAS into view in the maximized PDF (#237)', async ({
  page,
  cleanup,
}) => {
  const id = await seedPdfDoc(page.request, unique('pdfnext'))
  cleanup.defer('purge the seeded PDF document', () => deleteDocApi(page.request, id))
  await expect.poll(() => processingDone(page.request, id)).toBe(true)
  const fileId = await fileIdOf(page.request, id)

  const dialog = await openPdfPreview(page, id, fileId)
  await toggleMaximize(dialog)
  await expect(dialog.locator('.pdf-pages')).toBeVisible()
  expect(await scrollTopOf(dialog)).toBe(0)

  await dialog.getByRole('button', { name: 'Next page' }).click()

  // An indicator that merely counts up is not the claim. What must be true is that page 2's
  // OWN canvas is in the scroll viewport and painted — i.e. the button scrolled the column.
  await expect(dialog.locator('.pdf-page-info')).toHaveText('2 / 3')
  await expect
    .poll(() => scrollTopOf(dialog), { message: 'the next button scrolled the page column' })
    .toBeGreaterThan(0)
  const page2Canvas = dialog.locator('.pdf-page[data-page="2"] canvas')
  await expect(page2Canvas).toBeVisible()
  await expect
    .poll(() => canvasIsPainted(page2Canvas), { message: 'page 2 rendered real content' })
    .toBe(true)
  expect(await pageInView(dialog, 2)).toBe(true)
})

test('un-maximizing a scrolled PDF returns to ONE canvas on the same page (#237)', async ({
  page,
  cleanup,
}) => {
  const id = await seedPdfDoc(page.request, unique('pdfrestore'))
  cleanup.defer('purge the seeded PDF document', () => deleteDocApi(page.request, id))
  await expect.poll(() => processingDone(page.request, id)).toBe(true)
  const fileId = await fileIdOf(page.request, id)

  const dialog = await openPdfPreview(page, id, fileId)
  await toggleMaximize(dialog)
  const pages = dialog.locator('.pdf-pages')
  await expect(pages).toBeVisible()

  // Scroll off page 1: several pages are now rendered CONCURRENTLY — the shape the old
  // single shared render task could not hold (each render cancelled the last).
  const indicator = dialog.locator('.pdf-page-info')
  for (let i = 0; i < 12 && ((await indicator.textContent()) ?? '').trim() === '1 / 3'; i++) {
    await pages.evaluate((el) => {
      el.scrollTop += el.clientHeight * 0.9
    })
  }
  await expect
    .poll(() => dialog.locator('canvas').count(), {
      message: 'the maximized viewer keeps more than one page canvas alive at a time',
    })
    .toBeGreaterThanOrEqual(2)

  await expect(indicator).not.toHaveText('1 / 3')
  const scrolledPage = ((await indicator.textContent()) ?? '').trim()

  // RESTORE: back to the windowed single-page viewer — and NOT reset to page 1. Unmounting
  // the column collapses every page box, so nothing is measurably on screen during the
  // switch; that must not be read as "the reader is on page 1 now".
  await toggleMaximize(dialog)
  await expect(dialog).not.toHaveClass(/p-dialog-maximized/)
  await expect(pages).toHaveCount(0)
  await expect(dialog.locator('canvas')).toHaveCount(1)
  await expect(indicator).toHaveText(scrolledPage)
  await expect.poll(() => canvasIsPainted(dialog.locator('canvas'))).toBe(true)
})

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
