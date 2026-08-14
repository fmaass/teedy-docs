import {
  test,
  expect,
  type APIRequestContext,
  type Download,
  type Locator,
  type Page,
} from './fixtures'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'
import { readFileSync } from 'node:fs'
import {
  unique,
  login,
  openFileList,
  deleteDocApi,
  deleteUserApi,
  ROUTE_ROOT,
  gotoRouteReady,
} from './helpers'

// #58 — the enriched file panel: a grid⇄list toggle (grid default, per-user), and in
// list mode a DataTable with optional columns, inline rename (double-click + F2 +
// pencil), drag-handle reorder persisted server-side, a client-side quick filter, and
// read-only gating of every write affordance.

const here = dirname(fileURLToPath(import.meta.url))
const txt = resolve(here, 'fixtures/sample.txt')
const png = resolve(here, 'fixtures/pixel.png')
const pdf = resolve(here, 'fixtures/sample.pdf')
// A REAL zip archive. application/zip is the genuinely unsupported case: the preview
// dialog can render no safe representation of it, so it is the only seed that actually
// drives previewMode === 'unavailable'. text/plain does NOT — it previews as extracted
// text — so an assertion made against a .txt seed would pass without exercising the
// state under test (#181).
const zip = resolve(here, 'fixtures/placeholder.zip')

async function seedDoc(
  request: APIRequestContext,
  title: string,
  files: Array<{ name: string; mimeType: string; path: string }>,
): Promise<string> {
  const docRes = await request.put('/api/document', {
    headers: { 'content-type': 'application/x-www-form-urlencoded' },
    data: new URLSearchParams([['title', title], ['language', 'eng']]).toString(),
  })
  const id = (await docRes.json()).id as string
  for (const f of files) {
    await request.put('/api/file', {
      multipart: { id, file: { name: f.name, mimeType: f.mimeType, buffer: readFileSync(f.path) } },
    })
  }
  return id
}

function txtFile(name: string) {
  return { name, mimeType: 'text/plain', path: txt }
}

function pngFile(name: string) {
  return { name, mimeType: 'image/png', path: png }
}

function pdfFile(name: string) {
  return { name, mimeType: 'application/pdf', path: pdf }
}

function zipFile(name: string) {
  return { name, mimeType: 'application/zip', path: zip }
}

// #235 seeds. wide.png (60x20) is used rather than the 1x1 pixel: the moved-press below has to
// travel real pixels INSIDE the painted image, which a 1px raster cannot offer. multipage.pdf is
// used rather than sample.pdf because the page-nav controls only render above one page — and
// "page-nav still navigates and never opens" is half the contract under test.
const widePng = resolve(here, 'fixtures/wide.png')
const multiPagePdf = resolve(here, 'fixtures/multipage.pdf')

function widePngFile(name: string) {
  return { name, mimeType: 'image/png', path: widePng }
}

function multiPagePdfFile(name: string) {
  return { name, mimeType: 'application/pdf', path: multiPagePdf }
}

// #229 — what gates a reorder spec is the order the SERVER stored, never the toast.
//
// The toast is a self-dismissing element (`life: 2000` in DocumentViewContent's reorder
// handler), so an assertion that only starts looking for it after `dragTo` resolves is a race
// the test can lose while the reorder itself succeeded: on a contended runner the drop/POST
// timing shifts and the 2s window closes before the first poll samples the DOM. That is a false
// red — four consecutive desktop failures in tag-run context, green on push runs and locally.
// The stored order cannot evaporate, so it is what gates; the toast is still asserted, but from
// a recording armed BEFORE the drag, so it cannot lose a race it has already won.

const REORDER_SAVED = 'File order saved'

/**
 * The document's file order as the server stores it: `/api/file/list` returns rows ordered by
 * the persisted `order` column, which is exactly what a reorder writes.
 */
async function persistedFileOrder(
  request: APIRequestContext,
  documentId: string,
): Promise<string[]> {
  const res = await request.get(`/api/file/list?id=${documentId}`)
  const files = (await res.json()).files as Array<{ name: string }>
  return files.map((f) => f.name)
}

/**
 * Record every toast that appears, armed before the page's own scripts run — so it is in place
 * ahead of any drag, survives a reload, and keeps its record after the toast auto-dismisses.
 * PrimeVue mounts each toast as a `.p-toast-message` carrying a `.p-toast-summary`; each element
 * is stamped once, and the array it fills outlives the element. `characterData` is observed too:
 * an element inserted before its text is patched would otherwise be recorded blank or missed,
 * which would trade the flake being fixed here for a new one.
 */
async function armToastRecorder(page: Page): Promise<void> {
  await page.addInitScript(() => {
    const seen: string[] = []
    ;(window as unknown as { __toastsSeen: string[] }).__toastsSeen = seen
    const recorded = new WeakSet<Element>()
    const record = () => {
      for (const el of Array.from(document.querySelectorAll('.p-toast-message'))) {
        if (recorded.has(el)) continue
        const summary = el.querySelector('.p-toast-summary')?.textContent?.trim()
        // Text not patched in yet: leave the element unstamped so a later mutation records it
        // properly instead of burning it as an empty string.
        if (!summary) continue
        recorded.add(el)
        seen.push(summary)
      }
    }
    const start = () => {
      new MutationObserver(record).observe(document.documentElement, {
        childList: true,
        subtree: true,
        characterData: true,
      })
      record()
    }
    if (document.documentElement) start()
    else document.addEventListener('DOMContentLoaded', start, { once: true })
  })
}

/**
 * Toast summaries recorded since the current page load. Throws rather than reporting an empty
 * list when the recorder is not installed: "armed, and no toast fired" and "never armed" both
 * look like `[]`, so without this the NEGATIVE assertion below would pass vacuously.
 */
function toastsSeen(page: Page): Promise<string[]> {
  return page.evaluate(() => {
    const seen = (window as unknown as { __toastsSeen?: string[] }).__toastsSeen
    if (!seen) throw new Error('the toast recorder was never armed on this page')
    return seen
  })
}

// #229 (second half) — the drop itself is what the contended runner loses.
//
// Both views make a row/tile a drag source IMPERATIVELY, from the mousedown handler: PrimeVue's
// DataTable sets `draggable` on the row when the press originates on the reorder handle
// (datatable/index.mjs:5580), and the grid does the same for its card. So the gesture only becomes
// a drag if the app's mousedown handler has already run when the pointer starts moving. Under a
// 2-core pin it frequently has not: the element is still `draggable=false`, Chromium starts no
// drag at all, and NOTHING happens — no dragstart, no POST, no toast. `dragTo` cannot express
// that wait, which is why it loses the drop; the sequence below presses, WAITS for the arming to
// land in the DOM, and only then moves.
const REORDER_FAILED = 'Failed to save file order'

function countToast(toasts: string[], summary: string): number {
  return toasts.filter((t) => t === summary).length
}

/** Everything the drop is judged against, sampled once before the first drag. */
interface ReorderBaseline {
  order: string[]
  saved: number
  failed: number
}

async function reorderBaseline(page: Page, documentId: string): Promise<ReorderBaseline> {
  const toasts = await toastsSeen(page)
  return {
    order: await persistedFileOrder(page.request, documentId),
    saved: countToast(toasts, REORDER_SAVED),
    failed: countToast(toasts, REORDER_FAILED),
  }
}

type DropState = 'none' | 'registered' | 'failed'

/**
 * What the product has done since `base` — always the ORIGINAL baseline, never a fresher one.
 * A failure outcome outranks everything: it is a product signal, so it must neither be retried
 * away nor overtaken by a success that a later attempt produced.
 */
async function dropStateSince(
  page: Page,
  documentId: string,
  base: ReorderBaseline,
): Promise<DropState> {
  const toasts = await toastsSeen(page)
  if (countToast(toasts, REORDER_FAILED) > base.failed) return 'failed'
  if (countToast(toasts, REORDER_SAVED) > base.saved) return 'registered'
  const now = await persistedFileOrder(page.request, documentId)
  return now.join(' ') !== base.order.join(' ') ? 'registered' : 'none'
}

/**
 * A point that is inside `locator` AND inside the viewport. `page.mouse` takes raw viewport
 * coordinates and — unlike `dragTo`/`click` — scrolls nothing, so an element's own centre is
 * useless when it hangs below the fold: a grid tile is ~500px tall, and pressing its handle's
 * true centre lands on nothing at all (measured: `elementFromPoint` → null, so no mousedown, no
 * arming, no drag). Clamping to the visible rectangle keeps the whole gesture on screen, which
 * also avoids the mid-drag scroll that cancels the drop.
 */
async function visiblePoint(page: Page, locator: Locator, what: string) {
  const box = await locator.boundingBox()
  if (!box) throw new Error(`${what} is not laid out (no bounding box)`)
  const vp = page.viewportSize()
  if (!vp) return { x: box.x + box.width / 2, y: box.y + box.height / 2 }
  const left = Math.max(box.x, 0)
  const right = Math.min(box.x + box.width, vp.width)
  const top = Math.max(box.y, 0)
  const bottom = Math.min(box.y + box.height, vp.height)
  if (right <= left || bottom <= top) {
    throw new Error(`${what} is entirely outside the viewport — cannot drive a pointer to it`)
  }
  return { x: (left + right) / 2, y: (top + bottom) / 2 }
}

/**
 * One drag attempt: press the handle, wait for the app to arm the row/tile, then move in small
 * steps. Two moves onto the target, because a single one need not produce the `dragover` the
 * drop position is computed from.
 */
async function dragOnce(page: Page, source: Locator, handle: Locator, target: Locator) {
  // Both ends must be on screen BEFORE the press — scrolling mid-drag cancels the drop. The
  // source is scrolled last so its handle is certainly visible; the target is then adjacent.
  await target.scrollIntoViewIfNeeded()
  await source.scrollIntoViewIfNeeded()
  const from = await visiblePoint(page, handle, 'the drag handle')
  await page.mouse.move(from.x, from.y)
  await page.mouse.down()
  // The deterministic readiness signal — not a sleep.
  await expect(
    source,
    'the press armed the row/tile as a drag source before the pointer moved',
  ).toHaveAttribute('draggable', 'true')
  const to = await visiblePoint(page, target, 'the drop target')
  await page.mouse.move(to.x, to.y, { steps: 12 })
  await page.mouse.move(to.x, to.y, { steps: 2 })
  await page.mouse.up()
}

/**
 * Drag `handle` onto `target` and return only once the drop has REGISTERED — i.e. the product
 * reacted: a reorder outcome toast was recorded, or the stored order changed.
 *
 * A registered drop is never retried, whatever its outcome: a POST that fired and failed is a
 * product signal, and this throws on it rather than dragging again. A retry happens only when
 * nothing whatsoever registered (no outcome toast AND a byte-identical stored order), which can
 * only be the harness losing the gesture.
 *
 * Every judgement is made against one immutable baseline taken before the first drag, so an
 * outcome that lands after its attempt's window still counts: the next attempt re-checks before
 * it drags, and a late failure fails the test instead of being retried past.
 */
async function dragHandleWithRetry(
  page: Page,
  opts: { source: Locator; handle: Locator; target: Locator; documentId: string },
): Promise<{ attempts: number; lost: number }> {
  const { source, handle, target, documentId } = opts
  const maxAttempts = 3
  const windowMs = 6000
  // Sampled ONCE, before the first drag, and never re-sampled. A per-attempt baseline would
  // absorb an outcome that arrived late from a PREVIOUS attempt: the retry would start from a
  // baseline that already contained it, so a genuine product failure could vanish behind a retry
  // that happens to succeed. Because this baseline never moves, a late outcome is still a delta
  // whenever it surfaces.
  const base = await reorderBaseline(page, documentId)
  let lost = 0

  for (let attempt = 1; attempt <= maxAttempts; attempt++) {
    if (attempt > 1) {
      // Whatever is visible now belongs to the PREVIOUS attempt - it merely arrived after that
      // attempt's window closed. Settle it before dragging anything again.
      const late = await dropStateSince(page, documentId, base)
      if (late === 'failed') {
        throw new Error(
          `the reorder POST failed: attempt ${attempt - 1} reported "${REORDER_FAILED}" after its ` +
            `${windowMs}ms window closed. A product failure is never retried away.`,
        )
      }
      if (late === 'registered') {
        lost--
        console.log(
          `[#229 drag] LATE REGISTRATION: attempt ${attempt - 1} did land, after its ${windowMs}ms ` +
            `window closed - not retrying`,
        )
        return { attempts: attempt - 1, lost }
      }
    }

    await dragOnce(page, source, handle, target)

    const deadline = Date.now() + windowMs
    let state: DropState = 'none'
    for (;;) {
      state = await dropStateSince(page, documentId, base)
      if (state !== 'none' || Date.now() >= deadline) break
      await page.waitForTimeout(150)
    }
    if (state === 'failed') {
      throw new Error(
        `the reorder POST failed ("${REORDER_FAILED}") - a product failure is never retried away.`,
      )
    }
    if (state === 'registered') return { attempts: attempt, lost }

    lost++
    console.log(
      `[#229 drag] LOST DROP: attempt ${attempt}/${maxAttempts} on document ${documentId} ` +
        `produced no reorder outcome toast and left the stored order unchanged - retrying`,
    )
    await page.waitForTimeout(250)
  }
  throw new Error(
    `the reorder drag never registered after ${maxAttempts} attempts - the harness lost every ` +
      `drop (no dragstart), which is a test-harness failure, not a product one`,
  )
}

test('grid is the default file view; the toggle switches to list and persists per user', async ({ page, cleanup }) => {
  const id = await seedDoc(page.request, unique('panel'), [txtFile('alpha.txt'), txtFile('beta.txt')])
  cleanup.defer('purge the seeded document', () => deleteDocApi(page.request, id))
  await gotoRouteReady(page, `/#/document/view/${id}/content`, ROUTE_ROOT.documentContent)
  // Grid is the default view.
  await expect(page.locator('.file-preview-grid')).toBeVisible()
  await expect(page.locator('.file-data-table')).toHaveCount(0)

  await openFileList(page)
  await expect(page.locator('.file-preview-grid')).toHaveCount(0)

  // The choice is remembered — a reload comes back in list mode.
  await page.reload()
  await expect(page.locator('.file-data-table')).toBeVisible()
  await expect(page.locator('.file-preview-grid')).toHaveCount(0)
})

test('list shows Name+Created+Size by default with Uploader optional', async ({ page, cleanup }) => {
  const id = await seedDoc(page.request, unique('cols'), [txtFile('alpha.txt')])
  cleanup.defer('purge the seeded document', () => deleteDocApi(page.request, id))
  await gotoRouteReady(page, `/#/document/view/${id}/content`, ROUTE_ROOT.documentContent)
  await openFileList(page)

  const heads = page.locator('.file-data-table thead')
  // #170: the metadata columns collapse as the viewport narrows — below 640px the row has
  // no width to spare for them beside the name and the action cluster (F2), and the column
  // chooser goes with them rather than offering choices that cannot take effect. This spec
  // runs under both Playwright projects, so it asserts the policy that applies to its own
  // viewport instead of assuming the desktop one.
  const narrow = page.viewportSize()!.width <= 639
  if (narrow) {
    await expect(heads.getByText('Name', { exact: true })).toBeVisible()
    await expect(heads.getByText('Created', { exact: true })).toBeHidden()
    await expect(heads.getByText('Size', { exact: true })).toBeHidden()
    await expect(page.locator('.file-columns-btn')).toBeHidden()
    return
  }

  await expect(heads.getByText('Created', { exact: true })).toBeVisible()
  await expect(heads.getByText('Size', { exact: true })).toBeVisible()
  await expect(heads.getByText('Uploader', { exact: true })).toHaveCount(0)

  // The optional Uploader column can be enabled from the columns chooser.
  await page.locator('.file-columns-btn').click()
  await page.locator('label[for="file-col-uploader"]').click()
  await expect(heads.getByText('Uploader', { exact: true })).toBeVisible()
})

test('rename a file inline via double-click, F2 and the pencil', async ({ page, cleanup }) => {
  const id = await seedDoc(page.request, unique('rename'), [txtFile('original.txt')])
  cleanup.defer('purge the seeded document', () => deleteDocApi(page.request, id))
  await gotoRouteReady(page, `/#/document/view/${id}/content`, ROUTE_ROOT.documentContent)
  await openFileList(page)

  // 1. Double-click the name cell → inline editor → Enter commits.
  await page.locator('.file-name-text', { hasText: 'original.txt' }).dblclick()
  const input = page.locator('input.rename-input')
  await expect(input).toBeVisible()
  await input.fill('renamed.txt')
  await input.press('Enter')
  await expect(page.getByText('File renamed').first()).toBeVisible()
  await expect(page.locator('.file-name-text', { hasText: 'renamed.txt' })).toBeVisible()

  // 2. F2 on the focused name cell opens the editor.
  const nameCell = page.locator('.file-name-text', { hasText: 'renamed.txt' })
  await nameCell.focus()
  await nameCell.press('F2')
  await expect(page.locator('input.rename-input')).toBeVisible()
  await page.locator('input.rename-input').press('Escape')
  await expect(page.locator('input.rename-input')).toHaveCount(0)

  // 3. The pencil in the action menu opens the editor. Match the Rename control exactly:
  // the file icon is now an "Open {name}" preview button (#144), whose accessible name
  // contains "rename" for a file named renamed.txt — a non-exact match would be ambiguous.
  await page.getByRole('button', { name: 'Rename', exact: true }).click()
  await expect(page.locator('input.rename-input')).toBeVisible()
})

test('drag-handle reorder persists the file order and survives a reload', async ({ page, cleanup }) => {
  const id = await seedDoc(page.request, unique('reorder'), [
    txtFile('a-first.txt'),
    txtFile('b-second.txt'),
    txtFile('c-third.txt'),
  ])
  cleanup.defer('purge the seeded document', () => deleteDocApi(page.request, id))
  await armToastRecorder(page)
  await gotoRouteReady(page, `/#/document/view/${id}/content`, ROUTE_ROOT.documentContent)
  await openFileList(page)

  const UPLOADED = ['a-first.txt', 'b-second.txt', 'c-third.txt']
  const names = page.locator('.file-data-table tbody tr .file-name-text')
  await expect(names).toHaveText(UPLOADED)

  const rows = page.locator('.file-data-table tbody tr')
  await dragHandleWithRetry(page, {
    source: rows.nth(0),
    handle: rows.nth(0).locator('.p-datatable-reorderable-row-handle'),
    target: rows.nth(2),
    documentId: id,
  })

  // The dragged row moved down; the order is no longer the original upload order. This also
  // waits out the optimistic re-render, so the sequence read next is the settled one.
  await expect(names.first()).not.toHaveText('a-first.txt')
  const afterDrag = await names.allInnerTexts()
  expect(afterDrag, 'the drag produced a genuinely different order').not.toEqual(UPLOADED)

  // THE GATE: the server stored what the table is showing. A reorder whose POST never landed
  // (or was rolled back) leaves the stored order at the upload sequence and fails here.
  await expect
    .poll(() => persistedFileOrder(page.request, id), {
      message: 'the reorder is persisted server-side in the order the table shows',
    })
    .toEqual(afterDrag)

  // The toast is covered too, from the pre-armed recording rather than a post-hoc poll of a
  // 2-second element (#229).
  await expect
    .poll(() => toastsSeen(page), { message: 'the reorder raised its saved toast' })
    .toContain(REORDER_SAVED)

  // The saved order survives a full reload (it was persisted server-side).
  await page.reload()
  await openFileList(page)
  await expect(page.locator('.file-data-table tbody tr .file-name-text')).toHaveText(afterDrag)
})

// #211 — the SAME persisted order, reached from the grid. The grid has no DataTable and so no
// rowReorder: it drags natively off a per-tile handle and keeps its optimistic order in
// DocumentViewContent (FileListTable, which owns the list's, is not mounted in grid mode). The
// spec therefore proves all three: the drag reorders, the order is server-side (it survives a
// full reload), and the two views agree — one order per document, not one per view.
test('grid drag-handle reorder persists the file order and matches the list view', async ({
  page,
  cleanup,
}) => {
  const id = await seedDoc(page.request, unique('gridorder'), [
    txtFile('a-first.txt'),
    txtFile('b-second.txt'),
    txtFile('c-third.txt'),
  ])
  cleanup.defer('purge the seeded document', () => deleteDocApi(page.request, id))
  // Grid is the default, but the mode is a per-user localStorage preference and an earlier spec
  // may have left "list" there — pin it so this test measures the grid.
  await page.addInitScript(() => localStorage.setItem('teedy_file_view_mode:admin', 'grid'))
  await armToastRecorder(page)
  await gotoRouteReady(page, `/#/document/view/${id}/content`, ROUTE_ROOT.documentContent)
  await expect(page.locator('.file-preview-grid')).toBeVisible()

  const UPLOADED = ['a-first.txt', 'b-second.txt', 'c-third.txt']
  const names = page.locator('.file-preview-grid .file-preview-label')
  await expect(names).toHaveText(UPLOADED)

  const cards = page.locator('.file-preview-card')
  // Onto the NEIGHBOUR, not across the grid: a tile is ~500px tall, so at the mobile project's
  // 393×851 single-column viewport any further target sits below the fold, and the scroll
  // Playwright would need mid-drag cancels the drag (measured: the drop never lands). The
  // neighbour is a real reorder at both viewports, which is what this spec is about.
  await dragHandleWithRetry(page, {
    source: cards.nth(0),
    handle: cards.nth(0).locator('.file-card-drag-handle'),
    target: cards.nth(1),
    documentId: id,
  })

  await expect(names.first()).not.toHaveText('a-first.txt')
  const afterDrag = await names.allInnerTexts()
  expect(afterDrag, 'the drag produced a genuinely different order').not.toEqual(UPLOADED)

  // THE GATE: the grid's drop persisted server-side, in the order the tiles show (#229).
  await expect
    .poll(() => persistedFileOrder(page.request, id), {
      message: 'the grid reorder is persisted server-side in the order the tiles show',
    })
    .toEqual(afterDrag)

  await expect
    .poll(() => toastsSeen(page), { message: 'the grid reorder raised its saved toast' })
    .toContain(REORDER_SAVED)

  // A completed drop must not also open the preview of whatever tile it landed on.
  await expect(page.getByRole('dialog')).toHaveCount(0)

  // Persisted server-side: the order survives a full reload.
  await page.reload()
  await expect(page.locator('.file-preview-grid .file-preview-label')).toHaveText(afterDrag)

  // …and it is THE document's order, not a grid-local one: the list shows the same sequence.
  await openFileList(page)
  await expect(page.locator('.file-data-table tbody tr .file-name-text')).toHaveText(afterDrag)
})

// #211 (second half) — the grid's TRANSIENT sort. The list sorts from its column headers; the
// grid has none, so it sorts from a control. What this pins is that the sort is a view, not an
// order: it reorders the tiles, it withdraws the drag handles while it is on (a drop into a
// sorted projection has no meaningful target index), and clearing it puts the document's own
// order back — with nothing persisted along the way.
test('grid sort reorders the tiles transiently and restores the manual order', async ({
  page,
  cleanup,
}) => {
  // Seeded so the manual (upload) order is neither name- nor reverse-name-ordered: sorting by
  // name MUST move the first tile, which an already-sorted seed could not prove.
  const id = await seedDoc(page.request, unique('gridsort'), [
    txtFile('c-gamma.txt'),
    txtFile('a-alpha.txt'),
    txtFile('b-beta.txt'),
  ])
  cleanup.defer('purge the seeded document', () => deleteDocApi(page.request, id))
  await page.addInitScript(() => localStorage.setItem('teedy_file_view_mode:admin', 'grid'))
  await armToastRecorder(page)
  await gotoRouteReady(page, `/#/document/view/${id}/content`, ROUTE_ROOT.documentContent)
  await expect(page.locator('.file-preview-grid')).toBeVisible()

  const names = page.locator('.file-preview-grid .file-preview-label')
  const MANUAL = ['c-gamma.txt', 'a-alpha.txt', 'b-beta.txt']
  await expect(names).toHaveText(MANUAL)
  await expect(page.locator('.file-card-drag-handle')).toHaveCount(3)

  // Sort by name ascending.
  await page.getByTestId('grid-sort').click()
  await page.getByRole('option', { name: /^Name \(A/ }).click()
  await expect(names).toHaveText(['a-alpha.txt', 'b-beta.txt', 'c-gamma.txt'])
  // The handles go with it — the reorder contract needs the complete MANUAL order.
  await expect(page.locator('.file-card-drag-handle')).toHaveCount(0)

  // Clear it: the document's own order returns, and so do the handles.
  await page.getByTestId('grid-sort').click()
  await page.getByRole('option', { name: /^Manual/ }).click()
  await expect(names).toHaveText(MANUAL)
  await expect(page.locator('.file-card-drag-handle')).toHaveCount(3)

  // View-only: nothing was persisted at any point. The server's own order is still the manual
  // sequence, and no order-saved toast ever appeared — asserted from the recording armed at page
  // start, because a toast that had fired and self-dismissed would leave a bare DOM count at 0
  // and pass this vacuously (#229: the same 2s lifetime, failing the other way round).
  expect(await persistedFileOrder(page.request, id), 'a transient sort persisted nothing').toEqual(
    MANUAL,
  )
  expect(await toastsSeen(page), 'a transient sort raised no order-saved toast').not.toContain(
    REORDER_SAVED,
  )
  await page.reload()
  await expect(page.locator('.file-preview-grid .file-preview-label')).toHaveText(MANUAL)
})

test('the client-side quick filter narrows the list by name and mimetype', async ({ page, cleanup }) => {
  const id = await seedDoc(page.request, unique('filter'), [
    txtFile('alpha.txt'),
    { name: 'beta.png', mimeType: 'image/png', path: png },
  ])
  cleanup.defer('purge the seeded document', () => deleteDocApi(page.request, id))
  await gotoRouteReady(page, `/#/document/view/${id}/content`, ROUTE_ROOT.documentContent)
  await openFileList(page)

  const rows = page.locator('.file-data-table tbody tr')
  await expect(rows).toHaveCount(2)

  await page.locator('input.file-filter-input').fill('beta')
  await expect(rows).toHaveCount(1)
  await expect(rows.first()).toContainText('beta.png')

  // Mimetype match (image/png) also narrows.
  await page.locator('input.file-filter-input').fill('image')
  await expect(rows).toHaveCount(1)

  await page.locator('input.file-filter-input').fill('')
  await expect(rows).toHaveCount(2)
})

test('a read-only viewer sees files but no rename/delete/upload affordances', async ({ page, browser, cleanup }) => {
  const username = unique('rouser').replace(/[^a-z0-9]/gi, '').toLowerCase()
  // Create a second user.
  await gotoRouteReady(page, '/#/settings/users', ROUTE_ROOT.settingsUsers)
  await page.getByRole('button', { name: 'Add user' }).click()
  const dialog = page.getByRole('dialog', { name: 'Add user' })
  await dialog.locator('#add-user-name').fill(username)
  await dialog.locator('#add-user-email').fill(`${username}@example.com`)
  await dialog.locator('#add-user-pass').fill('Password1e2e')
  await dialog.getByRole('button', { name: 'Create' }).click()
  await expect(page.getByText('User created')).toBeVisible()
  // `page` is the ADMIN storageState context, so it is the context that can delete the user.
  cleanup.defer('delete the read-only viewer account', () => deleteUserApi(page.request, username))

  // A document with a file, granted READ to the second user.
  const id = await seedDoc(page.request, unique('ro-doc'), [txtFile('shared.txt')])
  // Admin seeds and therefore OWNS the document; the viewer only ever gets a READ ACL, so
  // the admin context is the one that can permanently delete it.
  cleanup.defer('purge the shared document', () => deleteDocApi(page.request, id))
  await gotoRouteReady(page, `/#/document/view/${id}/permissions`, ROUTE_ROOT.documentPermissions)
  const addForm = page.locator('.add-acl-form', { hasText: 'Add permission' })
  await addForm.locator('input').first().fill(username)
  await page.getByRole('option', { name: new RegExp(username) }).click()
  await addForm.getByRole('button', { name: 'Add', exact: true }).click()
  await expect(page.getByText('Permission added')).toBeVisible()

  // As the read-only viewer, in a fresh (non-admin) context.
  const ctx = await browser.newContext({ storageState: { cookies: [], origins: [] } })
  const viewer = await ctx.newPage()
  await login(viewer, username, 'Password1e2e')
  await gotoRouteReady(viewer, `/#/document/view/${id}/content`, ROUTE_ROOT.documentContent)

  // The file is visible (grid default) but there is NO upload dropzone / camera.
  await expect(viewer.locator('.file-preview-grid')).toBeVisible()
  await expect(viewer.locator('input[type="file"]')).toHaveCount(0)

  // In list mode: version history stays, but rename/delete are gone, and there is no
  // drag-reorder handle.
  await openFileList(viewer)
  await expect(viewer.getByRole('button', { name: 'Version history' })).toBeVisible()
  await expect(viewer.getByRole('button', { name: 'Rename' })).toHaveCount(0)
  await expect(viewer.getByRole('button', { name: 'Remove file' })).toHaveCount(0)
  await expect(viewer.locator('.p-datatable-reorderable-row-handle')).toHaveCount(0)

  await ctx.close()
})

// #144 — "open" must show the file in an in-app preview, never navigate to the original
// file URL. The backend serves the original with Content-Disposition: attachment under a
// locked-down CSP (a stored-XSS control), so any affordance pointing at it triggers a
// browser download instead of showing the file. Every open affordance now routes to the
// preview dialog; only a labelled Download control targets the original.
test('opening a file previews it in-app; only Download targets the original (#144)', async ({ page, cleanup }) => {
  const id = await seedDoc(page.request, unique('preview'), [
    pngFile('photo.png'),
    txtFile('readme.txt'),
    pdfFile('report.pdf'),
    zipFile('archive.zip'),
  ])
  cleanup.defer('purge the seeded document', () => deleteDocApi(page.request, id))
  await gotoRouteReady(page, `/#/document/view/${id}/content`, ROUTE_ROOT.documentContent)
  await expect(page.locator('.file-preview-grid')).toBeVisible()

  // Images load through the preview queue (blob URLs), not direct API links.
  await expect(page.locator('.file-preview-card img').first()).toHaveAttribute('src', /^blob:/)

  // The generic (non-image/non-PDF) card is a BUTTON, not a link to the original /data URL.
  // Two files here are generic (readme.txt and archive.zip), so the card is pinned by class
  // AND file name: since #178 the tile's action menu carries a preview control with the SAME
  // accessible name, so a bare role+name locator now matches two elements.
  const genericCard = page.locator('.generic-open', { hasText: 'readme.txt' })
  await expect(genericCard).toBeVisible()
  await expect(genericCard).toHaveJSProperty('tagName', 'BUTTON')
  // …and the #144 invariant the role locator used to carry, asserted directly: NOTHING named
  // "Open <file>" is a link to the original URL — every such affordance is a button.
  await expect(page.getByRole('link', { name: 'Open readme.txt', exact: true })).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Open readme.txt', exact: true })).toHaveCount(2)

  // Invariant: no affordance exposes the original URL as an unlabelled "open" — the only
  // links to /data are Download-labelled (the grid PDF card offers a Download), and the
  // old "Open in new tab" control is gone everywhere.
  await expect(page.getByText('Open in new tab')).toHaveCount(0)
  await expect(
    page.locator('.file-preview-grid a[href*="/data"]:not([aria-label="Download"])'),
  ).toHaveCount(0)

  // Clicking a generic card opens the in-app preview dialog (it does NOT download).
  // The ZIP card is picked deliberately: application/zip has no safe representation,
  // so the dialog opens in the "preview unavailable" state — the one state that used
  // to render a SECOND, inline Download beside the footer's (#181). Picking the .txt
  // card instead would open the text preview and the count below would prove nothing.
  await page.locator('.generic-open', { hasText: 'archive.zip' }).click()
  const dialog = page.getByRole('dialog')
  await expect(dialog).toBeVisible()
  await expect(dialog.getByText("Preview isn't available for this file type.")).toBeVisible()

  // EXACTLY ONE control targets the original file: a single labelled Download in the
  // dialog footer, pointing at the original (no size=… derived variant). Counted, not
  // found — `.first()` is blind to a duplicate.
  const download = dialog.locator('a.file-preview-download')
  await expect(download).toHaveCount(1)
  await expect(download).toBeVisible()
  let href = await download.getAttribute('href')
  expect(href).toMatch(/\/file\/[^/]+\/data/)
  expect(href).not.toContain('size=')
  await page.keyboard.press('Escape')
  await expect(page.getByRole('dialog')).toHaveCount(0)

  // LIST mode: double-clicking the PDF row opens the in-app pdf.js preview (not a download).
  // The dialog exposes no unlabelled original-URL control — the viewer's own Download is
  // suppressed in favour of the dialog's explicit Download.
  await openFileList(page)
  // Invariant RELAXED with maintainer approval (#178): the list now carries a legitimate
  // per-row Download, so "no /data links at all" is no longer expressible. The surviving
  // invariant is the same one the grid has carried since #144/#181 — no *unlabelled*
  // link to the original URL — plus a positive assertion so the relaxed form cannot pass
  // vacuously: every seeded row exposes exactly one Download-labelled anchor.
  await expect(
    page.locator('.file-data-table a[href*="/data"]:not([aria-label="Download"])'),
  ).toHaveCount(0)
  const rowDownloads = page.locator('.file-data-table a[href*="/data"][aria-label="Download"]')
  await expect(rowDownloads).toHaveCount(4)
  await expect(rowDownloads.first()).toBeVisible()
  expect(await rowDownloads.first().getAttribute('href')).not.toContain('size=')
  await page.locator('.file-data-table tbody tr', { hasText: 'report.pdf' }).dblclick()
  await expect(page.getByRole('dialog')).toBeVisible()
  await expect(page.locator('.pdf-viewer')).toBeVisible()
  await expect(page.getByRole('dialog').getByText('Open in new tab')).toHaveCount(0)
  const dialogDownload = page.getByRole('dialog').locator('a.file-preview-download')
  await expect(dialogDownload).toHaveCount(1)
  await expect(dialogDownload).toBeVisible()
  href = await dialogDownload.getAttribute('href')
  expect(href).toMatch(/\/file\/[^/]+\/data/)
  expect(href).not.toContain('size=')

  // Escape closes the preview — asserted, not merely attempted. (It no longer has to clear
  // the way for teardown: cleanup is API-based and never touches this page.)
  await page.keyboard.press('Escape')
  await expect(page.getByRole('dialog')).toHaveCount(0)
})

// #246 — what the flaky run loses is the keyboard ACTIVATION, not the download event.
//
// Focus and the key that follows are two separate driver round trips, and the dialog is free to
// move focus in between: its focus trap re-focuses the header's maximize button (the first control
// in the dialog) when something inside mutates. When that lands in the gap, Enter activates the
// maximize button instead, the anchor never sees a keydown, the browser never requests the file,
// and the test times out waiting for a download that was never going to start. Measured on this
// tree at 6/60 runs (10%) across both viewports with the app otherwise idle; the trace of a failing
// run contains no request for the original file at all — only the tile thumbnail — so nothing was
// lost in delivery.
//
// The activation is therefore retried, and ONLY when it provably went elsewhere. A keydown
// recorder armed BEFORE the key goes down records which element actually received the Enter: a
// different element means the harness lost the press and may press again; the anchor itself means
// the control is inert, which is a PRODUCT failure and is never retried away. The download waiter
// is armed before the first press and outlives the retries, so it cannot lose a race it has
// already won.

/** The original file's own URL — the derived `?size=…` thumbnail variants are not it. */
function isOriginalFileRequest(url: string): boolean {
  return /\/api\/file\/[^/]+\/data/.test(url) && !url.includes('size=')
}

/**
 * Tab until `target` holds focus, within `budget` presses; whether it got there. The budget is
 * generous rather than tight: focus starts on the tile control that opened the dialog, so the
 * number of presses tracks how many focusable controls the tile carries — #178 added two per tile
 * (preview + Download), which overran the original budget of 10. The invariant under test is
 * reachability by keyboard alone, not the exact press count.
 */
async function tabUntilFocused(page: Page, target: Locator, budget = 30): Promise<boolean> {
  for (let i = 0; i < budget; i++) {
    await page.keyboard.press('Tab')
    if (await target.evaluate((el) => el === document.activeElement)) return true
  }
  return false
}

/**
 * Arm (once per page) a capture-phase recorder for the element that receives the next Enter, and
 * clear whatever a previous attempt recorded. Capture phase so it sees the event whoever the
 * target is, and first-Enter-wins so the reading is unambiguous.
 */
async function armEnterRecorder(page: Page): Promise<void> {
  await page.evaluate(() => {
    const w = window as unknown as { __enterTarget?: EventTarget | null; __enterArmed?: boolean }
    w.__enterTarget = null
    if (w.__enterArmed) return
    w.__enterArmed = true
    document.addEventListener(
      'keydown',
      (event) => {
        if (event.key === 'Enter' && w.__enterTarget == null) w.__enterTarget = event.target
      },
      { capture: true },
    )
  })
}

/**
 * Press Enter on `anchor` — which Tab must be able to reach — and return the download it starts.
 *
 * Throws rather than retrying when the Enter did reach the anchor and no download followed: that
 * is the control being inert, which is exactly what this test exists to catch.
 */
async function activateWithEnter(page: Page, anchor: Locator): Promise<Download> {
  const maxAttempts = 3
  // Armed before the first press, and deliberately not re-armed per attempt: a download that
  // arrives late still counts. Both settle to a value rather than rejecting, so neither can
  // surface as an unhandled rejection while the loop is busy pressing keys.
  const started = page
    .waitForEvent('download', { timeout: 15_000 })
    .then((event) => event, () => null)
  const requested = page
    .waitForRequest((request) => isOriginalFileRequest(request.url()), { timeout: 15_000 })
    .then(() => true, () => false)

  let lost = 0
  for (let attempt = 1; attempt <= maxAttempts; attempt++) {
    if (!(await anchor.evaluate((el) => el === document.activeElement))) {
      expect(
        await tabUntilFocused(page, anchor),
        'Tab reaches the footer Download link',
      ).toBe(true)
    }
    await armEnterRecorder(page)
    await page.keyboard.press('Enter')

    const reachedAnchor = await anchor.evaluate(
      (el) => (window as unknown as { __enterTarget?: EventTarget | null }).__enterTarget === el,
    )
    if (reachedAnchor) {
      const download = await started
      if (download) return download
      throw new Error(
        'Enter reached the focused Download anchor but no download started within 15s — the ' +
          `control is inert (the browser ${(await requested) ? 'did' : 'never'} request the ` +
          'original file). A product failure is never retried away.',
      )
    }

    lost++
    const stoleFocus = await page.evaluate(() => {
      const target = (window as unknown as { __enterTarget?: EventTarget | null }).__enterTarget
      return target instanceof Element ? target.className : String(target)
    })
    console.log(
      `[#246 keyboard-download] LOST ACTIVATION: attempt ${attempt}/${maxAttempts} — Enter was ` +
        `delivered to "${stoleFocus}" instead of the Download anchor, so focus moved between the ` +
        'assertion and the key. Re-tabbing and pressing again.',
    )
  }
  throw new Error(
    `the Enter never reached the Download anchor in ${maxAttempts} attempts (${lost} lost to a ` +
      'focus move) — the harness cannot keep focus on the control long enough to activate it',
  )
}

// #181 — the footer Download is now the SINGLE affordance, so it has to be operable
// with the keyboard alone: if Tab could not reach it, or Enter did not fire it, removing
// the inline duplicate would have taken the only reachable control away from
// keyboard-only and screen-reader users. This runs under both Playwright projects, so
// the mobile (Pixel 5) viewport is covered as well as desktop.
test('the single Download is reachable AND activatable by keyboard alone (#181)', async ({ page, cleanup }) => {
  const id = await seedDoc(page.request, unique('kbd-dl'), [zipFile('archive.zip')])
  cleanup.defer('purge the seeded document', () => deleteDocApi(page.request, id))
  await gotoRouteReady(page, `/#/document/view/${id}/content`, ROUTE_ROOT.documentContent)
  // Pinned by class: the tile's action menu offers a preview control with the same
  // accessible name since #178, so role+name alone is ambiguous here.
  await page.locator('.generic-open', { hasText: 'archive.zip' }).click()

  const dialog = page.getByRole('dialog')
  await expect(dialog).toBeVisible()
  await expect(dialog.getByText("Preview isn't available for this file type.")).toBeVisible()

  const download = dialog.locator('a.file-preview-download')
  await expect(download).toHaveCount(1)

  // Tab through the dialog's focus trap until focus lands on the Download anchor.
  expect(await tabUntilFocused(page, download), 'Tab reaches the footer Download link').toBe(true)
  expect(await download.getAttribute('href')).toMatch(/\/file\/[^/]+\/data/)

  // ACTIVATION, not merely reachability: Enter on the focused anchor must actually
  // start the download of the original file. Focus + a correct href would still pass
  // if the control were inert.
  const downloadEvent = await activateWithEnter(page, download)
  expect(downloadEvent.suggestedFilename()).toBe('archive.zip')

  await page.keyboard.press('Escape')
  await expect(page.getByRole('dialog')).toHaveCount(0)
})

// #235 — clicking the PICTURE on an in-document grid card opens the file.
//
// Only the GENERIC tile ever had an open affordance on its media (`.generic-open`, #144). The
// image and PDF tiles carried mouse/drag handlers and nothing else, so the two card types a user
// actually points at — the photo, the page — answered a click with nothing at all. (Three earlier
// fixes were verified against the document GALLERY, which is a different component; this spec
// pins the surface the report is about.)
//
// The contract has two halves and both are asserted here, because a fix for the first that broke
// the second would be a worse regression than the bug: the MEDIA opens, and every control that
// sits on or beside the media — image rotation, the viewer's page-nav — keeps doing its own job
// and never opens.

/** True once every file's async processing has produced its real raster/preview. */
async function filesProcessed(request: APIRequestContext, documentId: string): Promise<boolean> {
  const res = await request.get(`/api/file/list?id=${documentId}`)
  if (!res.ok()) return false
  const body = await res.json()
  return body.files.length > 0 && body.files.every((f: { processing: boolean }) => !f.processing)
}

/** The AUTHORITATIVE persisted rotation of a named file, straight from the API. */
async function persistedRotationOf(
  request: APIRequestContext,
  documentId: string,
  name: string,
): Promise<number | undefined> {
  const res = await request.get(`/api/document/${documentId}?files=true`)
  if (!res.ok()) return undefined
  const body = await res.json()
  return body.files.find((f: { name: string; rotation: number }) => f.name === name)?.rotation
}

test('#235 — the image and PDF grid media open the preview; their controls still do not', async ({
  page,
  cleanup,
}) => {
  const id = await seedDoc(page.request, unique('grid-open'), [
    widePngFile('photo.png'),
    multiPagePdfFile('report.pdf'),
  ])
  cleanup.defer('purge the seeded document', () => deleteDocApi(page.request, id))
  await gotoRouteReady(page, `/#/document/view/${id}/content`, ROUTE_ROOT.documentContent)
  await expect(page.locator('.file-preview-grid')).toBeVisible()
  // The real rasters, not the square placeholder: the presses below are geometric.
  await expect.poll(() => filesProcessed(page.request, id)).toBe(true)

  const imageCard = page.locator('.file-preview-card', { hasText: 'photo.png' })
  const pdfCard = page.locator('.file-preview-card', { hasText: 'report.pdf' })
  const stage = imageCard.locator('.image-preview-stage')
  const pageArea = pdfCard.locator('.pdf-canvas-container')
  await expect(imageCard.locator('img.rotatable-image')).toBeVisible()
  await expect(pdfCard.locator('.pdf-canvas-container canvas')).toBeVisible()

  // (a) A plain click on the image opens the in-app preview ON THAT FILE.
  await stage.click()
  let dialog = page.getByRole('dialog')
  await expect(dialog).toBeVisible()
  await expect(dialog.locator('.p-dialog-title')).toHaveText('photo.png')
  await page.keyboard.press('Escape')
  await expect(page.getByRole('dialog')).toHaveCount(0)

  // (b) THE REPORTED GESTURE: a press that travels a few pixels before release. An <img> is a
  // native drag source, so without draggable="false" the browser turns this into an image drag
  // and delivers NO click — the affordance is there and the user still gets nothing. The
  // dragstart counter is what distinguishes "the click worked" from "the click worked because
  // this run happened not to trip the drag".
  const box = (await imageCard.locator('img.rotatable-image').boundingBox())!
  await page.evaluate(() => {
    const w = window as unknown as { __dragstarts: number }
    w.__dragstarts = 0
    document.addEventListener('dragstart', () => (w.__dragstarts += 1), true)
  })
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2)
  await page.mouse.down()
  await page.mouse.move(box.x + box.width / 2 + 10, box.y + box.height / 2 + 4, { steps: 8 })
  await page.mouse.up()
  dialog = page.getByRole('dialog')
  await expect(dialog).toBeVisible()
  await expect(dialog.locator('.p-dialog-title')).toHaveText('photo.png')
  expect(
    await page.evaluate(() => (window as unknown as { __dragstarts: number }).__dragstarts),
    'the press never became a native image drag',
  ).toBe(0)
  await page.keyboard.press('Escape')
  await expect(page.getByRole('dialog')).toHaveCount(0)

  // (c) The PDF page area opens the same way.
  await pageArea.click()
  dialog = page.getByRole('dialog')
  await expect(dialog).toBeVisible()
  await expect(dialog.locator('.p-dialog-title')).toHaveText('report.pdf')
  await page.keyboard.press('Escape')
  await expect(page.getByRole('dialog')).toHaveCount(0)

  // Both media are named, keyboard-reachable open controls — the same affordance the generic
  // card has had since #144 (each card also carries the action-menu preview of the same name,
  // hence two per card).
  await expect(imageCard.getByRole('button', { name: 'Open photo.png', exact: true })).toHaveCount(2)
  await expect(pdfCard.getByRole('button', { name: 'Open report.pdf', exact: true })).toHaveCount(2)

  // …and by keyboard, which is the half a click handler on a bare div would have missed.
  await stage.focus()
  await page.keyboard.press('Enter')
  await expect(page.getByRole('dialog')).toBeVisible()
  await page.keyboard.press('Escape')
  await expect(page.getByRole('dialog')).toHaveCount(0)
  await pageArea.focus()
  await page.keyboard.press('Enter')
  await expect(page.getByRole('dialog')).toBeVisible()
  await page.keyboard.press('Escape')
  await expect(page.getByRole('dialog')).toHaveCount(0)

  // (d) NON-HIJACK: the image rotation control rotates and does NOT open. Asserted against the
  // server's stored rotation, not a spinner — the control has to still DO its job.
  expect(await persistedRotationOf(page.request, id, 'photo.png')).toBe(0)
  await imageCard.getByRole('button', { name: 'Rotate right' }).click()
  await expect.poll(() => persistedRotationOf(page.request, id, 'photo.png')).toBe(90)
  await expect(page.getByRole('dialog')).toHaveCount(0)

  // (e) NON-HIJACK: the viewer's page-nav navigates and does NOT open.
  const pageInfo = pdfCard.locator('.pdf-page-info')
  await expect(pageInfo).toHaveText(/^1 \/ [2-9]/)
  await pdfCard.getByRole('button', { name: 'Next page' }).click()
  await expect(pageInfo).toHaveText(/^2 \/ [2-9]/)
  await expect(page.getByRole('dialog')).toHaveCount(0)

  // #283 stays intact: the media band is one shared height, so the filename sits at the same
  // offset WITHIN every card type. Turning the image stage into a button is exactly the kind of
  // change that could have re-introduced the misalignment. Measured as an offset from each
  // card's own top rather than an absolute y, so it holds in the one-column mobile grid too.
  async function labelOffset(card: Locator): Promise<number> {
    const cardBox = (await card.boundingBox())!
    const labelBox = (await card.locator('.file-preview-label').boundingBox())!
    return labelBox.y - cardBox.y
  }
  expect(Math.abs((await labelOffset(imageCard)) - (await labelOffset(pdfCard)))).toBeLessThanOrEqual(1)
})
