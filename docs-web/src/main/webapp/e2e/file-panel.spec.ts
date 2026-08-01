import { test, expect, type APIRequestContext } from './fixtures'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'
import { readFileSync } from 'node:fs'
import { unique, login, openFileList, deleteDocApi, deleteUserApi } from './helpers'

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

test('grid is the default file view; the toggle switches to list and persists per user', async ({ page, cleanup }) => {
  const id = await seedDoc(page.request, unique('panel'), [txtFile('alpha.txt'), txtFile('beta.txt')])
  cleanup.defer('purge the seeded document', () => deleteDocApi(page.request, id))
  await page.goto(`/#/document/view/${id}/content`)
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
  await page.goto(`/#/document/view/${id}/content`)
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
  await page.goto(`/#/document/view/${id}/content`)
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
  await page.goto(`/#/document/view/${id}/content`)
  await openFileList(page)

  const names = page.locator('.file-data-table tbody tr .file-name-text')
  await expect(names).toHaveText(['a-first.txt', 'b-second.txt', 'c-third.txt'])

  const rows = page.locator('.file-data-table tbody tr')
  const handle = rows.nth(0).locator('.p-datatable-reorderable-row-handle')
  await handle.dragTo(rows.nth(2))

  await expect(page.getByText('File order saved').first()).toBeVisible()
  // The dragged row moved down; the order is no longer the original upload order.
  await expect(names.first()).not.toHaveText('a-first.txt')
  const afterDrag = await names.allInnerTexts()

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
  await page.goto(`/#/document/view/${id}/content`)
  await expect(page.locator('.file-preview-grid')).toBeVisible()

  const names = page.locator('.file-preview-grid .file-preview-label')
  await expect(names).toHaveText(['a-first.txt', 'b-second.txt', 'c-third.txt'])

  const cards = page.locator('.file-preview-card')
  // Onto the NEIGHBOUR, not across the grid: a tile is ~500px tall, so at the mobile project's
  // 393×851 single-column viewport any further target sits below the fold, and the scroll
  // Playwright would need mid-drag cancels the drag (measured: the drop never lands). The
  // neighbour is a real reorder at both viewports, which is what this spec is about.
  await cards.nth(0).locator('.file-card-drag-handle').dragTo(cards.nth(1))

  await expect(page.getByText('File order saved').first()).toBeVisible()
  await expect(names.first()).not.toHaveText('a-first.txt')
  const afterDrag = await names.allInnerTexts()

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
  await page.goto(`/#/document/view/${id}/content`)
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

  // View-only: nothing was persisted at any point. A reload — which re-reads the server's order —
  // still shows the manual sequence, and no order-saved toast ever appeared.
  await expect(page.getByText('File order saved')).toHaveCount(0)
  await page.reload()
  await expect(page.locator('.file-preview-grid .file-preview-label')).toHaveText(MANUAL)
})

test('the client-side quick filter narrows the list by name and mimetype', async ({ page, cleanup }) => {
  const id = await seedDoc(page.request, unique('filter'), [
    txtFile('alpha.txt'),
    { name: 'beta.png', mimeType: 'image/png', path: png },
  ])
  cleanup.defer('purge the seeded document', () => deleteDocApi(page.request, id))
  await page.goto(`/#/document/view/${id}/content`)
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
  await page.goto('/#/settings/users')
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
  await page.goto(`/#/document/view/${id}/permissions`)
  const addForm = page.locator('.add-acl-form', { hasText: 'Add permission' })
  await addForm.locator('input').first().fill(username)
  await page.getByRole('option', { name: new RegExp(username) }).click()
  await addForm.getByRole('button', { name: 'Add', exact: true }).click()
  await expect(page.getByText('Permission added')).toBeVisible()

  // As the read-only viewer, in a fresh (non-admin) context.
  const ctx = await browser.newContext({ storageState: { cookies: [], origins: [] } })
  const viewer = await ctx.newPage()
  await login(viewer, username, 'Password1e2e')
  await viewer.goto(`/#/document/view/${id}/content`)

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
  await page.goto(`/#/document/view/${id}/content`)
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

// #181 — the footer Download is now the SINGLE affordance, so it has to be operable
// with the keyboard alone: if Tab could not reach it, or Enter did not fire it, removing
// the inline duplicate would have taken the only reachable control away from
// keyboard-only and screen-reader users. This runs under both Playwright projects, so
// the mobile (Pixel 5) viewport is covered as well as desktop.
test('the single Download is reachable AND activatable by keyboard alone (#181)', async ({ page, cleanup }) => {
  const id = await seedDoc(page.request, unique('kbd-dl'), [zipFile('archive.zip')])
  cleanup.defer('purge the seeded document', () => deleteDocApi(page.request, id))
  await page.goto(`/#/document/view/${id}/content`)
  // Pinned by class: the tile's action menu offers a preview control with the same
  // accessible name since #178, so role+name alone is ambiguous here.
  await page.locator('.generic-open', { hasText: 'archive.zip' }).click()

  const dialog = page.getByRole('dialog')
  await expect(dialog).toBeVisible()
  await expect(dialog.getByText("Preview isn't available for this file type.")).toBeVisible()

  const download = dialog.locator('a.file-preview-download')
  await expect(download).toHaveCount(1)

  // Tab through the dialog's focus trap until focus lands on the Download anchor. The
  // budget is generous rather than tight: focus starts on the tile control that opened the
  // dialog, so the number of presses tracks how many focusable controls the tile carries —
  // #178 added two per tile (preview + Download), which overran the original budget of 10.
  // The invariant under test is reachability by keyboard alone, not the exact press count.
  let focused = false
  for (let i = 0; i < 30 && !focused; i++) {
    await page.keyboard.press('Tab')
    focused = await download.evaluate((el) => el === document.activeElement)
  }
  expect(focused, 'Tab reaches the footer Download link').toBe(true)
  expect(await download.getAttribute('href')).toMatch(/\/file\/[^/]+\/data/)

  // ACTIVATION, not merely reachability: Enter on the focused anchor must actually
  // start the download of the original file. Focus + a correct href would still pass
  // if the control were inert.
  const [downloadEvent] = await Promise.all([
    page.waitForEvent('download'),
    page.keyboard.press('Enter'),
  ])
  expect(downloadEvent.suggestedFilename()).toBe('archive.zip')

  await page.keyboard.press('Escape')
  await expect(page.getByRole('dialog')).toHaveCount(0)
})
