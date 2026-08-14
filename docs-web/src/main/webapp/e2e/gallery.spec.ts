import { test, expect, type Page, type APIRequestContext } from './fixtures'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'
import { readFileSync } from 'node:fs'
import {
  unique,
  uniqueTag,
  deleteDocApi,
  deleteTagApi,
  gotoDocumentList,
  expectRouteReady,
  ROUTE_ROOT,
} from './helpers'

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

function cardThumb(page: Page, title: string) {
  // The thumbnail REGION of the card (#235). It lives inside the open link, so its
  // own click handler has to stop the click from reaching the link's slide-over path.
  return card(page, title).locator('.card-thumb')
}

async function switchToGallery(page: Page) {
  await page.locator('.view-mode-toggle').getByText('Gallery', { exact: true }).click()
  await expect(page.locator('.doc-gallery')).toBeVisible()
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
  cleanup,
}) => {
  const imageTitle = unique('gal-image')
  const otherTitle = unique('gal-other')

  // An image document (wide.png → a real, aspect-preserving thumbnail).
  const imageId = await apiCreateDocument(request, imageTitle)
  cleanup.defer('purge the image document', () => deleteDocApi(request, imageId))
  const imageFileId = await apiAttachFile(request, imageId, widePng, 'wide.png', 'image/png')
  // A non-convertible document (application/zip → no format handler → placeholder).
  const otherId = await apiCreateDocument(request, otherTitle)
  cleanup.defer('purge the non-convertible document', () => deleteDocApi(request, otherId))
  const otherFileId = await apiAttachFile(request, otherId, placeholderZip, 'archive.zip', 'application/zip')

  // Switch to gallery mode and assert both cards render (browse/open surface).
  await gotoDocumentList(page)
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
})

test('gallery mode persists across a reload and re-renders a tag-filtered set (#39)', async ({
  page,
  request,
  cleanup,
}) => {
  const tagName = uniqueTag('galtag')
  const inTitle = unique('gal-in')
  const outTitle = unique('gal-out')

  const tagId = await apiCreateTag(request, tagName)
  cleanup.defer('delete the filter tag', () => deleteTagApi(request, tagId))
  const inId = await apiCreateDocumentWithTag(request, inTitle, tagId)
  cleanup.defer('purge the tagged document', () => deleteDocApi(request, inId))
  const outId = await apiCreateDocument(request, outTitle)
  cleanup.defer('purge the untagged document', () => deleteDocApi(request, outId))

  await gotoDocumentList(page)
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
})

test('a list multi-selection does NOT leave the bulk toolbar reachable in gallery mode (#39/B2)', async ({
  page,
  request,
  cleanup,
}) => {
  const titleA = unique('gal-selA')
  const titleB = unique('gal-selB')

  const idA = await apiCreateDocument(request, titleA)
  cleanup.defer('purge document A', () => deleteDocApi(request, idA))
  const idB = await apiCreateDocument(request, titleB)
  cleanup.defer('purge document B', () => deleteDocApi(request, idB))

  // Start in list mode (default) and select a row via its checkbox — the bulk
  // toolbar appears (it renders solely from the selection count).
  await gotoDocumentList(page)
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
})

// --- #235: the thumbnail is a shortcut to the document itself -----------------
//
// The card's open link is otherwise one flat interaction surface: a plain click anywhere on
// it opens the slide-over, a double-click opens the full view. The reporter asked for the
// part of the card that LOOKS like the document — the thumbnail — to take him straight
// there, and only that part changes.
//
// The navigation must go through the list's own full-open path, not the bare router-link
// href: that path attaches the returnTo/filterLabel history state the document view's Back
// bar reads. A bare link navigation would land on the same URL with NO state, so Back would
// return to an UNFILTERED list and the filter context would vanish from the back bar. That
// difference is what the `.back-filter` and post-Back assertions below pin down — a
// "navigate()"-style implementation reaches the right URL and still fails them.

test('a single click on a gallery card thumbnail opens the document and keeps the filtered-list return context (#235)', async ({
  page,
  request,
  cleanup,
}) => {
  const tagName = uniqueTag('thumbtag')
  const title = unique('gal-thumb')

  const tagId = await apiCreateTag(request, tagName)
  cleanup.defer('delete the filter tag', () => deleteTagApi(request, tagId))
  const docId = await apiCreateDocumentWithTag(request, title, tagId)
  cleanup.defer('purge the thumbnail document', () => deleteDocApi(request, docId))
  // A real attached image, so the click lands on an actual rendered thumbnail rather
  // than the file-icon fallback.
  await apiAttachFile(request, docId, widePng, 'wide.png', 'image/png')

  await gotoDocumentList(page)
  await switchToGallery(page)

  // Filter the list by the document's tag from its own card chip, so the gallery the
  // click starts from is a FILTERED one (the state the bare-href path would lose).
  await cardContainer(page, title).getByRole('button', { name: new RegExp(tagName) }).click()
  await expect(page).toHaveURL(new RegExp(`tags=${tagId}`))
  await expect(card(page, title)).toBeVisible()

  // #235: the thumbnail image is pointer-transparent, so a real click lands on the
  // interactive .card-thumb span instead of starting a native image-drag that swallows the
  // click. A bare <img> is draggable, so on real hardware the tiny movement in an ordinary
  // click ate the click on cards WITH a preview (PDF/JPEG) while icon cards, which have no
  // <img>, still opened — reporter vmario89. A regression re-enabling pointer events here
  // brings that back.
  await expect(
    cardThumb(page, title).locator('img'),
    'the thumbnail image is pointer-transparent (#235)',
  ).toHaveCSS('pointer-events', 'none')

  // THE ASK: one plain click on the thumbnail region — no double-click, no slide-over
  // "Open" hop — lands on the document's content view.
  await cardThumb(page, title).click()
  await expectRouteReady(page, `/#/document/view/${docId}/content`, ROUTE_ROOT.documentContent)
  await expect(page.getByRole('heading', { name: title })).toBeVisible()
  // No slide-over was left behind by the click that navigated.
  await expect(page.locator('.slide-over-title')).toHaveCount(0)

  // The full-open path's history state arrived with it: the back bar names the filter…
  await expect(page.locator('.back-filter')).toContainText(tagName)
  // …and Back returns to the list WITH that filter still applied (the returnTo query).
  await page.locator('.back-link').click()
  await expect(page).toHaveURL(new RegExp(`#/document\\?.*tags=${tagId}`))
  await expect(page.locator(ROUTE_ROOT.documentList)).toBeVisible()
})

test('a REAL (moved) click on a gallery thumbnail with a rendered image opens the document — the card link must not native-drag the click away (#235)', async ({
  page,
  request,
  cleanup,
}) => {
  const title = unique('gal-thumbdrag')
  const docId = await apiCreateDocument(request, title)
  cleanup.defer('purge the thumbnail-drag document', () => deleteDocApi(request, docId))
  // A real attached image, so the click lands on an actual rendered thumbnail — the exact case
  // the reporter saw fail (cards WITH a preview did not open; icon cards did).
  await apiAttachFile(request, docId, widePng, 'wide.png', 'image/png')

  await gotoDocumentList(page)
  await switchToGallery(page)

  const thumb = cardThumb(page, title)
  await expect(thumb.locator('img')).toBeVisible()

  // REPRODUCE A REAL CLICK. A bare Playwright `.click()` presses and releases on the SAME pixel
  // with zero travel, so it never triggers the native drag this bug is about — which is why the
  // sibling #235 test (a clean .click()) passed while the feature stayed broken and the fix
  // shipped twice. A hand always moves the pointer a few px between press and release; the card's
  // open region is an <a href>, a native drag SOURCE, so that travel starts a native link-drag on
  // the anchor that SWALLOWS the click (no `click` event fires) and the document never opens. The
  // fix is draggable="false" on that anchor. This gesture — press, a few px of travel INSIDE the
  // thumbnail, release — fails against the pre-fix build (no navigation) and passes after it.
  const box = await thumb.boundingBox()
  if (!box) throw new Error('the thumbnail region has no bounding box')
  const cx = box.x + box.width / 2
  const cy = box.y + box.height / 2
  await page.mouse.move(cx, cy)
  await page.mouse.down()
  await page.mouse.move(cx + 12, cy + 12, { steps: 6 })
  await page.mouse.up()

  // ACCEPTANCE: the moved click opened the document's content view (a real navigation, not a
  // slide-over and not a no-op).
  await expectRouteReady(page, `/#/document/view/${docId}/content`, ROUTE_ROOT.documentContent)
  await expect(page.getByRole('heading', { name: title })).toBeVisible()
  await expect(page.locator('.slide-over-title')).toHaveCount(0)
})

test('the rest of the gallery card keeps its contract: title click opens the slide-over, a modified thumbnail click is not intercepted (#235)', async ({
  page,
  context,
  request,
  cleanup,
}) => {
  const title = unique('gal-contract')
  const docId = await apiCreateDocument(request, title)
  cleanup.defer('purge the contract document', () => deleteDocApi(request, docId))
  await apiAttachFile(request, docId, widePng, 'wide.png', 'image/png')

  await gotoDocumentList(page)
  await switchToGallery(page)

  // GUARD 1 — the NON-thumbnail part of the card is untouched: a plain click on the
  // title still opens the slide-over (debounced 250 ms) and does not navigate.
  await card(page, title).locator('.card-title').click()
  await expect(page.locator('.slide-over-title')).toHaveText(title)
  await expect(page).toHaveURL(/#\/document(\?|$)/)
  await page.keyboard.press('Escape')
  await expect(page.locator('.slide-over-title')).toHaveCount(0)

  // GUARD 2 — a MODIFIED click on the thumbnail is left to the browser (the link's
  // href opens in a new tab): the thumbnail handler must ignore it rather than
  // preventDefault it. Whatever the browser does with the new tab, THIS page must
  // neither navigate nor open the slide-over.
  await cardThumb(page, title).click({ modifiers: ['ControlOrMeta'] })
  // The settle barrier is a POSITIVE one, not a timeout: a plain title click must still
  // reach the slide-over, which can only happen from the list — and it costs the 250 ms
  // debounce to arrive, which is time a wrongly-intercepted navigation would have used to
  // show up. Asserting the URL straight after the click would have passed on the
  // still-unchanged URL of a navigation already under way.
  await card(page, title).locator('.card-title').click()
  await expect(page.locator('.slide-over-title')).toHaveText(title)
  await expect(page).toHaveURL(/#\/document(\?|$)/)
  await expect(page.locator('.doc-gallery')).toBeVisible()
  // Close anything the modified click opened so it cannot leak into the next test.
  for (const openTab of context.pages()) {
    if (openTab !== page) await openTab.close()
  }
})
