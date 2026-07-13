import { test, expect, type Page, type APIRequestContext } from './fixtures'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'
import { readFileSync } from 'node:fs'
import { unique } from './helpers'

// #39: the gallery VIEW MODE. A pure render mode over the SAME paginated list — the
// list⇄gallery toggle persists to localStorage, cards render the document thumbnail
// (real thumb for a convertible image, the 256x256 placeholder otherwise), the active
// tag filter applies identically in both modes, and gallery is browse/open-only so a
// list multi-selection cannot leave the bulk toolbar reachable there.
//
// DETERMINISM: every assertion is against POST-refresh state (barrier expectations),
// and the real-vs-placeholder thumbnail claim is proven on FETCHED image BYTES
// (decoded dimensions), never on the URL — the same /data?size=thumb URL serves both
// the encrypted real thumb and the fallback placeholder, and DocumentListItem exposes
// no mimetype. wide.png is 60x20, so its aspect-preserving 256-box thumb is NOT square;
// the placeholder file-thumb.png is exactly 256x256 — an asymmetric, falsifiable fixture.
//
// The placeholder fixture is a ZIP (application/zip), NOT a text file: text/plain HAS a
// format handler (TextPlainFormatHandler renders text → a real thumbnail), so sample.txt
// does NOT fall back to the placeholder. application/zip has no FormatHandler, so
// FileProcessingAsyncListener generates no _thumb and FileResource serves the 256x256
// placeholder — the genuine non-convertible case.

const here = dirname(fileURLToPath(import.meta.url))
const widePng = resolve(here, 'fixtures/wide.png')
const placeholderZip = resolve(here, 'fixtures/placeholder.zip')

const PLACEHOLDER_SIZE = 256

function card(page: Page, title: string) {
  // The card's open action is a real LINK whose accessible name is the document
  // title (the card container is a non-interactive <article>; the star and tag
  // controls are siblings of this link, so the card has no nested interactive
  // elements). This locator is the primary open control.
  return page.getByRole('link', { name: title, exact: true })
}

function cardContainer(page: Page, title: string) {
  // The whole card <article> — used to reach the star/tag controls that are
  // SIBLINGS of (not inside) the open link.
  return page.locator('article.doc-card').filter({ has: card(page, title) })
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

async function apiCreateDocumentWithTag(
  request: APIRequestContext,
  title: string,
  tagId: string,
): Promise<string> {
  const body = new URLSearchParams([
    ['title', title],
    ['language', 'eng'],
    ['tags', tagId],
  ])
  const res = await request.put('/api/document', {
    headers: { 'content-type': 'application/x-www-form-urlencoded' },
    data: body.toString(),
  })
  expect(res.ok(), `create tagged document ${title}`).toBeTruthy()
  return (await res.json()).id as string
}

async function apiAttachFile(
  request: APIRequestContext,
  documentId: string,
  filePath: string,
  name: string,
  mimeType: string,
): Promise<string> {
  const res = await request.put('/api/file', {
    multipart: { id: documentId, file: { name, mimeType, buffer: readFileSync(filePath) } },
  })
  expect(res.ok(), `attach ${name}`).toBeTruthy()
  return (await res.json()).id as string
}

// Decode the pixel dimensions of a PNG or JPEG buffer. The placeholder is a PNG; the
// real generated thumbnail is a JPEG (FileResource serves the encrypted thumb decrypted
// as image/jpeg). We need BOTH so the assertion works whichever the server returns.
function imageDimensions(buf: Buffer): { width: number; height: number } {
  // PNG: signature 89 50 4E 47, IHDR width@16 height@20 (big-endian).
  if (buf.length >= 24 && buf[0] === 0x89 && buf[1] === 0x50 && buf[2] === 0x4e && buf[3] === 0x47) {
    return { width: buf.readUInt32BE(16), height: buf.readUInt32BE(20) }
  }
  // JPEG: scan the segments for a SOFn frame marker carrying height/width.
  if (buf.length >= 4 && buf[0] === 0xff && buf[1] === 0xd8) {
    let off = 2
    while (off + 9 < buf.length) {
      if (buf[off] !== 0xff) {
        off++
        continue
      }
      const marker = buf[off + 1]
      // SOF0..SOF15 except DHT(0xc4)/DAA(0xc8)/DAC(0xcc) carry frame geometry.
      if (marker >= 0xc0 && marker <= 0xcf && marker !== 0xc4 && marker !== 0xc8 && marker !== 0xcc) {
        const height = buf.readUInt16BE(off + 5)
        const width = buf.readUInt16BE(off + 7)
        return { width, height }
      }
      const segLen = buf.readUInt16BE(off + 2)
      off += 2 + segLen
    }
  }
  throw new Error('unrecognized image format for dimension decode')
}

// Fetch the served thumbnail bytes and decode their pixel dimensions. Polls because
// thumbnail generation is async server-side (FileProcessingAsyncListener); until the
// real thumb exists the endpoint serves the 256x256 placeholder.
async function thumbDimensions(
  request: APIRequestContext,
  fileId: string,
): Promise<{ width: number; height: number }> {
  const res = await request.get(`/api/file/${fileId}/data?size=thumb`)
  expect(res.ok(), `fetch thumb ${fileId}`).toBeTruthy()
  return imageDimensions(Buffer.from(await res.body()))
}

test('@flaky gallery renders cards; real thumb vs placeholder is proven on fetched bytes (#39, quarantined #80)', async ({
  page,
  request,
}) => {
  const imageTitle = unique('gal-image')
  const otherTitle = unique('gal-other')
  let imageId: string | undefined
  let otherId: string | undefined

  try {
    // An image document (wide.png → a real, aspect-preserving thumbnail).
    imageId = await apiCreateDocument(request, imageTitle)
    const imageFileId = await apiAttachFile(request, imageId, widePng, 'wide.png', 'image/png')
    // A non-convertible document (application/zip → no format handler → placeholder).
    otherId = await apiCreateDocument(request, otherTitle)
    const otherFileId = await apiAttachFile(request, otherId, placeholderZip, 'archive.zip', 'application/zip')

    // Switch to gallery mode and assert both cards render (browse/open surface).
    await page.goto('/#/document')
    await page.locator('.view-mode-toggle').getByText('Gallery', { exact: true }).click()
    await expect(card(page, imageTitle)).toBeVisible()
    await expect(card(page, otherTitle)).toBeVisible()
    // Each card carries a thumbnail IMG element sourced from its file.
    await expect(card(page, imageTitle).locator('img')).toBeVisible()

    // ACCEPTANCE (fetched-byte evidence): the image doc's thumbnail decodes to a
    // NON-square, NON-256 raster once generation settles — a real thumbnail, not the
    // placeholder. wide.png is 60x20 → a 256-box thumb is ~256x85, so height < width
    // and it is NOT the 256x256 placeholder.
    await expect
      .poll(async () => {
        const d = await thumbDimensions(request, imageFileId)
        // Real thumb: not the square placeholder, and aspect-preserving (wider than tall).
        return d.width !== PLACEHOLDER_SIZE || d.height !== PLACEHOLDER_SIZE
      }, { message: 'image thumbnail should become a real (non-256x256) raster' })
      .toBe(true)
    const imageThumb = await thumbDimensions(request, imageFileId)
    expect(imageThumb.width).not.toBe(imageThumb.height) // wide source → non-square thumb
    expect(imageThumb.height).toBeLessThan(imageThumb.width)

    // The non-convertible doc serves the exact 256x256 placeholder (image/file-thumb.png)
    // — no real thumbnail is ever generated for a type with no format handler.
    const otherThumb = await thumbDimensions(request, otherFileId)
    expect(otherThumb.width).toBe(PLACEHOLDER_SIZE)
    expect(otherThumb.height).toBe(PLACEHOLDER_SIZE)
  } finally {
    for (const id of [imageId, otherId]) {
      if (id) await request.delete(`/api/document/${id}`).catch(() => {})
    }
  }
})

test('gallery mode persists across a reload and re-renders a tag-filtered set (#39)', async ({
  page,
  request,
}) => {
  const tagName = unique('galtag')
  const inTitle = unique('gal-in')
  const outTitle = unique('gal-out')
  let tagId: string | undefined
  let inId: string | undefined
  let outId: string | undefined

  try {
    tagId = await apiCreateTag(request, tagName)
    inId = await apiCreateDocumentWithTag(request, inTitle, tagId)
    outId = await apiCreateDocument(request, outTitle)

    await page.goto('/#/document')
    await page.locator('.view-mode-toggle').getByText('Gallery', { exact: true }).click()
    await expect(card(page, inTitle)).toBeVisible()
    await expect(card(page, outTitle)).toBeVisible()

    // Reload: the gallery mode was persisted to localStorage, so the cards (not a
    // table) render immediately on a cold load — a post-reload barrier.
    await page.reload()
    await expect(card(page, inTitle)).toBeVisible()
    await expect(page.locator('.doc-gallery')).toBeVisible()

    // The favorite star is a SIBLING control on the card (not nested in the open
    // link) and is togglable from gallery mode: star it, then read the favorited
    // state back after a full reload (authoritative server-side persistence).
    await cardContainer(page, inTitle).getByRole('button', { name: 'Add to favorites' }).click()
    await expect(
      cardContainer(page, inTitle).getByRole('button', { name: 'Remove from favorites' }),
    ).toHaveAttribute('aria-pressed', 'true')
    await page.reload()
    await expect(
      cardContainer(page, inTitle).getByRole('button', { name: 'Remove from favorites' }),
    ).toHaveAttribute('aria-pressed', 'true')
    // Unstar again to leave no favorite behind for other assertions/cleanup.
    await cardContainer(page, inTitle).getByRole('button', { name: 'Remove from favorites' }).click()
    await expect(
      cardContainer(page, inTitle).getByRole('button', { name: 'Add to favorites' }),
    ).toHaveAttribute('aria-pressed', 'false')

    // Filter by the tag by clicking OUR document's tag chip on its card. The list
    // re-queries server-side; the gallery re-renders the FILTERED set.
    // The tag chip is a sibling of the open link inside the card container.
    await cardContainer(page, inTitle).getByRole('button', { name: new RegExp(tagName) }).click()
    await expect(page).toHaveURL(new RegExp(`tags=${tagId}`))
    // POST-refresh barrier: the untagged doc detaches, the tagged doc survives — the
    // filter genuinely drove the query, and it did so while still in gallery mode.
    await expect(card(page, outTitle)).toBeHidden()
    await expect(card(page, inTitle)).toBeVisible()
    await expect(page.locator('.doc-gallery')).toBeVisible()
  } finally {
    for (const id of [inId, outId]) {
      if (id) await request.delete(`/api/document/${id}`).catch(() => {})
    }
    if (tagId) await request.delete(`/api/tag/${tagId}`).catch(() => {})
  }
})

test('a list multi-selection does NOT leave the bulk toolbar reachable in gallery mode (#39/B2)', async ({
  page,
  request,
}) => {
  const titleA = unique('gal-selA')
  const titleB = unique('gal-selB')
  let idA: string | undefined
  let idB: string | undefined

  try {
    idA = await apiCreateDocument(request, titleA)
    idB = await apiCreateDocument(request, titleB)

    // Start in list mode (default) and select a row via its checkbox — the bulk
    // toolbar appears (it renders solely from the selection count).
    await page.goto('/#/document')
    const rowA = page.getByRole('row', { name: new RegExp(titleA) })
    await expect(rowA).toBeVisible()
    await rowA.getByRole('checkbox').first().check()
    const bulkBar = page.getByRole('toolbar', { name: 'Bulk actions' })
    await expect(bulkBar).toBeVisible()

    // Switch to gallery: the selection is cleared (B2), so the bulk toolbar detaches —
    // no bulk-mutation control is reachable in the browse/open-only gallery.
    await page.locator('.view-mode-toggle').getByText('Gallery', { exact: true }).click()
    await expect(page.locator('.doc-gallery')).toBeVisible()
    await expect(bulkBar).toBeHidden()

    // Switching back to list confirms the selection stayed empty (nothing to act on).
    await page.locator('.view-mode-toggle').getByText('List', { exact: true }).click()
    await expect(page.getByRole('toolbar', { name: 'Bulk actions' })).toHaveCount(0)
  } finally {
    for (const id of [idA, idB]) {
      if (id) await request.delete(`/api/document/${id}`).catch(() => {})
    }
  }
})
