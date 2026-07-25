import { test, expect, type APIRequestContext, type Page, type Locator } from './fixtures'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'
import { readFileSync } from 'node:fs'
import { unique, login, openFileList, deleteDocApi, deleteUserApi } from './helpers'

// #196 + #170 — the file list's ROW GEOMETRY, measured against the running app.
//
// The row that has to work is the worst case, not the average one: a writable PDF row
// carries NINE controls (FileActionMenu's eight plus the PDF page organizer) — about
// 304px of icons at the theme's small size, which is wider than the entire action area of
// a 360px phone. The redesign therefore lets the cluster WRAP inside its column below
// 820px instead of hiding controls behind an overlay, collapses the metadata columns as
// the viewport narrows, and pins the name column to an 8rem floor with an ellipsis.
//
// This spec is the standing gate for that policy. It asserts, at 360px, 393px and desktop,
// for three different clusters (writable PDF = 9 controls, writable non-PDF = 8, read-only
// = 3): no horizontal page overflow, the name column at or above its 8rem floor with the
// name genuinely ellipsized, every control painted inside both its row and the viewport,
// and — the property the earlier revert (`bee6cfda`) missed — that the LAST control of the
// cluster takes a REAL interaction. For a writable row that last control is the Delete
// button (clicked, opening the danger confirm); for a read-only row it is the Download
// ANCHOR, which is exercised by actually starting its download.
//
// It also asserts the page-flow half of #196: a >100-file document renders EVERY row and
// never grows an inner scroll container, at both viewports.

const here = dirname(fileURLToPath(import.meta.url))
const txt = resolve(here, 'fixtures/sample.txt')
const pdf = resolve(here, 'fixtures/sample.pdf')

// Long enough to overflow the name column at every tested width — the ellipsis assertion
// would pass vacuously with a short name.
const LONG_PDF = 'a-really-extremely-long-file-name-that-must-ellipsize-2026-invoice.pdf'
const LONG_TXT = 'another-really-extremely-long-file-name-that-must-ellipsize-notes.txt'

// The three normative widths. 360 and 393 are the phone widths the redesign is measured
// against (393 = the Pixel 5 the mobile project emulates); 1280 is the desktop baseline.
const WIDTHS = [360, 393, 1280] as const

// The 8rem name floor, in CSS pixels.
const NAME_FLOOR = 128

interface Box {
  x: number
  y: number
  width: number
  height: number
}

interface RowGeometry {
  viewportWidth: number
  docScrollWidth: number
  docClientWidth: number
  // PrimeVue puts `overflow: auto` on the table container unconditionally, so a table that
  // is too wide scrolls INSIDE that box instead of widening the page — which would make a
  // page-level overflow check pass while the controls sat off-screen. Measured explicitly.
  containerScrollWidth: number
  containerClientWidth: number
  rowBox: Box
  nameCellBox: Box
  nameTextBox: Box
  nameEllipsized: boolean
  nameStyle: { textOverflow: string; whiteSpace: string; overflowX: string }
  controls: { label: string | null; tag: string; box: Box }[]
}

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

// Wait until the app answers a RUN of cheap calls cleanly. Seeding (and later purging) a
// hundred files leaves a backlog of async processing that starves the JDBC pool, and a
// saturated app answers /api/user with a 500 — which bounces the SPA to the login screen
// and, left unattended at teardown, fails whatever spec runs next. A single 200 proves
// nothing: it can slip through between two saturated moments.
async function waitForAppSettled(request: APIRequestContext): Promise<void> {
  await expect
    .poll(
      async () => {
        let clean = 0
        for (let i = 0; i < 5; i++) {
          if (!(await request.get('/api/user')).ok()) return 0
          clean++
          await new Promise((resolve) => setTimeout(resolve, 300))
        }
        return clean
      },
      { timeout: 120_000, intervals: [1_000, 2_000, 5_000] },
    )
    .toBe(5)
}

// ONE atomic layout read of a row: the row box, the name cell, whether the name's glyphs
// are actually clipped, and every action control's painted box — all at a single scroll
// position, so nothing shifts between measurements.
async function measureRow(page: Page, row: Locator): Promise<RowGeometry> {
  return row.evaluate((tr) => {
    const box = (el: Element): Box => {
      const r = el.getBoundingClientRect()
      return { x: r.x, y: r.y, width: r.width, height: r.height }
    }
    const nameCell = tr.querySelector('td.file-col-name') as HTMLElement
    const nameText = nameCell.querySelector('.file-name-text') as HTMLElement
    const actionCell = tr.querySelector('td.file-col-actions') as HTMLElement
    const container = tr.closest('.p-datatable-table-container') as HTMLElement
    const controls = Array.from(actionCell.querySelectorAll('button, a')).map((el) => ({
      label: el.getAttribute('aria-label'),
      tag: el.tagName,
      box: box(el),
    }))
    return {
      viewportWidth: window.innerWidth,
      docScrollWidth: document.documentElement.scrollWidth,
      docClientWidth: document.documentElement.clientWidth,
      containerScrollWidth: container.scrollWidth,
      containerClientWidth: container.clientWidth,
      rowBox: box(tr),
      nameCellBox: box(nameCell),
      nameTextBox: box(nameText),
      nameEllipsized: nameText.scrollWidth > nameText.clientWidth,
      nameStyle: {
        textOverflow: getComputedStyle(nameText).textOverflow,
        whiteSpace: getComputedStyle(nameText).whiteSpace,
        overflowX: getComputedStyle(nameText).overflowX,
      },
      controls,
    }
  })
}

function assertRowGeometry(
  geom: RowGeometry,
  expectedControls: number,
  label: string,
  // A row whose action cluster is short (a read-only row on a wide screen) leaves the name
  // column wide enough for the whole name, so there is nothing to truncate there. Where the
  // name provably cannot fit, the caller demands the ellipsis.
  requireEllipsis = true,
) {
  // (a) nothing scrolls sideways — neither the page nor the table's own container (#170).
  expect(geom.docScrollWidth, `${label}: no horizontal page overflow`).toBeLessThanOrEqual(
    geom.docClientWidth + 1,
  )
  expect(
    geom.containerScrollWidth,
    `${label}: the table does not overflow its container sideways`,
  ).toBeLessThanOrEqual(geom.containerClientWidth + 1)
  // (b) the worst case really is under test (a shorter cluster passing proves nothing).
  expect(geom.controls.length, `${label}: control count`).toBe(expectedControls)
  // (c) the name column holds its 8rem floor and the name is genuinely truncated.
  expect(geom.nameCellBox.width, `${label}: name column at or above its 8rem floor`).toBeGreaterThanOrEqual(
    NAME_FLOOR,
  )
  // The truncation contract is on the name span itself, and it holds at every width…
  expect(geom.nameStyle, `${label}: the name is set to truncate, not to wrap or overflow`).toEqual({
    textOverflow: 'ellipsis',
    whiteSpace: 'nowrap',
    overflowX: 'hidden',
  })
  // …the painted name never escapes its own cell (that escape IS #170)…
  expect(
    geom.nameTextBox.x + geom.nameTextBox.width,
    `${label}: the painted name stays inside its column`,
  ).toBeLessThanOrEqual(geom.nameCellBox.x + geom.nameCellBox.width + 1)
  // …and where the name cannot fit, it really is truncated.
  if (requireEllipsis) {
    expect(
      geom.nameEllipsized,
      `${label}: long name ellipsized rather than pushing the row wide`,
    ).toBe(true)
  }
  // (d) every control is painted, inside the viewport, and inside its own row (not clipped).
  for (const c of geom.controls) {
    const where = `${label}: control ${c.label ?? '(unlabelled)'}`
    expect(c.box.width, `${where} has a painted box`).toBeGreaterThan(0)
    expect(c.box.height, `${where} has a painted box`).toBeGreaterThan(0)
    expect(c.box.x, `${where} not off-screen left`).toBeGreaterThanOrEqual(-1)
    expect(c.box.x + c.box.width, `${where} right edge within the viewport`).toBeLessThanOrEqual(
      geom.viewportWidth + 1,
    )
    expect(c.box.y, `${where} top within its row`).toBeGreaterThanOrEqual(geom.rowBox.y - 1)
    expect(
      c.box.y + c.box.height,
      `${where} bottom within its row`,
    ).toBeLessThanOrEqual(geom.rowBox.y + geom.rowBox.height + 1)
  }
}

test.describe('file list row geometry (#170)', () => {
  test('every writable action stays visible, unclipped and clickable at 360px, 393px and desktop', async ({
    page,
    cleanup,
  }) => {
    const id = await seedDoc(page.request, unique('geom'), [
      { name: LONG_PDF, mimeType: 'application/pdf', path: pdf },
      { name: LONG_TXT, mimeType: 'text/plain', path: txt },
    ])
    cleanup.defer('purge the seeded document', () => deleteDocApi(page.request, id))
    await page.goto(`/#/document/view/${id}/content`)
    await openFileList(page)

    for (const width of WIDTHS) {
      await page.setViewportSize({ width, height: 800 })

      // WORST CASE: a writable PDF row — FileActionMenu's eight controls plus the PDF
      // page organizer from the #file-extra slot.
      const pdfRow = page.locator('.file-data-table tbody tr', { hasText: LONG_PDF })
      await expect(pdfRow).toHaveCount(1)
      assertRowGeometry(await measureRow(page, pdfRow), 9, `writable PDF @${width}`)

      // Writable non-PDF: the same cluster without the page organizer.
      const txtRow = page.locator('.file-data-table tbody tr', { hasText: LONG_TXT })
      await expect(txtRow).toHaveCount(1)
      assertRowGeometry(await measureRow(page, txtRow), 8, `writable non-PDF @${width}`)

      // The LAST control of a writable cluster is Delete. Geometry is not enough: it has
      // to take a real click (an overlapping neighbour would intercept the pointer and
      // Playwright's actionability check would time out here).
      const remove = pdfRow.getByRole('button', { name: 'Remove file' })
      await expect(remove).toBeVisible()
      await remove.click()
      const confirm = page.getByRole('alertdialog')
      await expect(confirm).toBeVisible()
      // Decline: the row must survive for the remaining widths.
      await confirm.getByRole('button', { name: 'No' }).click()
      await expect(confirm).toBeHidden()
      await expect(pdfRow).toHaveCount(1)
    }
  })

  test('a read-only row keeps its Download anchor reachable and working at 360px, 393px and desktop', async ({
    page,
    browser,
    cleanup,
  }) => {
    const username = unique('geomro').replace(/[^a-z0-9]/gi, '').toLowerCase()
    await page.goto('/#/settings/users')
    await page.getByRole('button', { name: 'Add user' }).click()
    const dialog = page.getByRole('dialog', { name: 'Add user' })
    await dialog.locator('#add-user-name').fill(username)
    await dialog.locator('#add-user-email').fill(`${username}@example.com`)
    await dialog.locator('#add-user-pass').fill('Password1e2e')
    await dialog.getByRole('button', { name: 'Create' }).click()
    await expect(page.getByText('User created')).toBeVisible()
    cleanup.defer('delete the read-only viewer account', () => deleteUserApi(page.request, username))

    const id = await seedDoc(page.request, unique('geom-ro'), [
      { name: LONG_PDF, mimeType: 'application/pdf', path: pdf },
    ])
    cleanup.defer('purge the shared document', () => deleteDocApi(page.request, id))
    await page.goto(`/#/document/view/${id}/permissions`)
    const addForm = page.locator('.add-acl-form', { hasText: 'Add permission' })
    await addForm.locator('input').first().fill(username)
    await page.getByRole('option', { name: new RegExp(username) }).click()
    await addForm.getByRole('button', { name: 'Add', exact: true }).click()
    await expect(page.getByText('Permission added')).toBeVisible()

    const ctx = await browser.newContext({
      storageState: { cookies: [], origins: [] },
      acceptDownloads: true,
    })
    cleanup.defer('close the read-only viewer context', () => ctx.close())
    const viewer = await ctx.newPage()
    await login(viewer, username, 'Password1e2e')
    await viewer.goto(`/#/document/view/${id}/content`)
    await openFileList(viewer)

    for (const width of WIDTHS) {
      await viewer.setViewportSize({ width, height: 800 })
      const row = viewer.locator('.file-data-table tbody tr', { hasText: LONG_PDF })
      await expect(row).toHaveCount(1)
      // A read-only row offers exactly the three READ controls: history, preview, download.
      const geom = await measureRow(viewer, row)
      // Three controls leave a wide name column on a desktop viewport — wide enough for the
      // whole name — so the ellipsis is only demanded at the phone widths.
      assertRowGeometry(geom, 3, `read-only @${width}`, width < 900)
      // Its LAST control is the Download ANCHOR, not a button — exercise it as one.
      const last = geom.controls[geom.controls.length - 1]
      expect(last.tag, `read-only @${width}: last control is an anchor`).toBe('A')
      expect(last.label, `read-only @${width}: last control is Download`).toBe('Download')

      const download = row.getByRole('link', { name: 'Download' })
      const [event] = await Promise.all([viewer.waitForEvent('download'), download.click()])
      expect(event.suggestedFilename(), `read-only @${width}: downloads its own file`).toBe(LONG_PDF)
    }
  })
})

// #196 — the page-flow half. The list used to switch to a 480px-tall inner scroller with a
// virtual window above 100 files, so a long file list scrolled inside a box while the page
// around it stood still (and only a slice of the rows existed in the DOM). It now flows
// with the page exactly like the grid. Runs under both Playwright projects, so the
// assertion is made at the desktop AND the mobile viewport.
test('a >100-file document renders every row and never grows an inner scroll container', async ({
  page,
  cleanup,
}) => {
  test.setTimeout(240_000)
  const COUNT = 101
  const id = await seedDoc(page.request, unique('flow'), [])
  cleanup.defer('purge the seeded document', () => deleteDocApi(page.request, id))
  // Deleting a hundred files is itself a burst of work. Hand the app back healthy —
  // otherwise this spec's load, not their own code, is what the next specs measure. The
  // step's own timeout MUST exceed waitForAppSettled's internal budget (120s of polling):
  // the fixture's 10s default would abort the settle long before it can succeed and leave
  // exactly the contamination this step exists to prevent.
  cleanup.defer('let the app drain before the next spec', () => waitForAppSettled(page.request), {
    timeout: 130_000,
  })

  // STRICTLY sequential, and backing off on failure. Each upload fans out to the async
  // processing listeners (extraction + indexing), and a hundred of those in flight
  // saturate the app's JDBC pool — a parallel seeding loop exhausted it and took the rest
  // of the run down with it (observed 2026-07-25). So: one file at a time, retry on a
  // saturated server rather than hammering it, and let the queue drain before driving the
  // UI.
  const buffer = readFileSync(txt)
  for (let i = 0; i < COUNT; i++) {
    const name = `flow-${String(i).padStart(3, '0')}.txt`
    let uploaded = false
    for (let attempt = 0; attempt < 10 && !uploaded; attempt++) {
      const res = await page.request.put('/api/file', {
        multipart: { id, file: { name, mimeType: 'text/plain', buffer } },
      })
      uploaded = res.ok()
      if (!uploaded) await new Promise((resolve) => setTimeout(resolve, 1_000))
    }
    expect(uploaded, `seeded ${name}`).toBe(true)
    // Pace the loop to the server's own processing rate (~100-300ms per file): an
    // unpaced loop uploads far faster than the listeners drain and the backlog starves
    // the connection pool, which then fails unrelated requests — including the SPA's own
    // boot call, which lands the browser back on the login screen.
    await new Promise((resolve) => setTimeout(resolve, 120))
  }
  // The seeding queue has to be drained before the UI is driven: a still-saturated app
  // answers /api/user with a 500, the SPA bounces to the login screen, and every later
  // assertion would be about that instead of about the layout. Waiting for the document's
  // own file list to come back complete is both the settle signal and a check that all 101
  // files really landed.
  await expect
    .poll(
      async () => {
        const res = await page.request.get(`/api/file/list?id=${id}`)
        if (!res.ok()) return -1
        return ((await res.json()).files as unknown[]).length
      },
      { timeout: 120_000, intervals: [1_000, 2_000, 5_000] },
    )
    .toBe(COUNT)

  await waitForAppSettled(page.request)

  // Boot straight into list mode. The grid branch would mount 101 preview cards and pull a
  // thumbnail for each, which re-saturates the very pool the settle above waited for — and
  // this test is about the list, not the toggle.
  await page.addInitScript(() => {
    for (const key of ['teedy_file_view_mode:admin']) localStorage.setItem(key, 'list')
  })
  await page.goto(`/#/document/view/${id}/content`)
  await expect(page.locator('.file-data-table')).toBeVisible()
  await expect(page.locator('.file-data-table tbody tr').first()).toBeVisible()

  const geom = await page.locator('.file-data-table').evaluate((root) => {
    const container = root.querySelector('.p-datatable-table-container') as HTMLElement
    return {
      rows: root.querySelectorAll('tbody tr').length,
      virtualScrollers: root.querySelectorAll('.p-datatable-virtualscroller, .p-virtualscroller').length,
      scrollable: root.classList.contains('p-datatable-scrollable'),
      containerScrollHeight: container.scrollHeight,
      containerClientHeight: container.clientHeight,
      // PrimeVue only sets a max-height on this box in scrollable mode; that inline cap is
      // exactly what turned the list into a 480px window.
      containerMaxHeight: container.style.maxHeight,
      rootScrollHeight: (root as HTMLElement).scrollHeight,
      rootClientHeight: (root as HTMLElement).clientHeight,
    }
  })

  // Every row exists — a windowed list renders only the slice in view.
  expect(geom.rows, 'every file row is rendered').toBe(COUNT)
  expect(geom.virtualScrollers, 'no virtual scroller is mounted').toBe(0)
  expect(geom.scrollable, 'the table is not in PrimeVue scrollable mode').toBe(false)
  // The table's own boxes never scroll internally: the page is the scroller.
  expect(
    geom.containerScrollHeight,
    'table container does not scroll internally',
  ).toBeLessThanOrEqual(geom.containerClientHeight + 1)
  expect(geom.rootScrollHeight, 'table root does not scroll internally').toBeLessThanOrEqual(
    geom.rootClientHeight + 1,
  )
  expect(geom.containerMaxHeight, 'table container carries no height cap').toBe('')
  // …and the list really is longer than the viewport, so "no inner scroll" is not true for
  // the trivial reason that everything fits. The nearest scrolling ancestor must be the
  // app's own page scroller (`.app-content`), never the table: that is what "the list flows
  // with the page" means.
  const scroller = await page.locator('.file-data-table').evaluate((root) => {
    let el: HTMLElement | null = root as HTMLElement
    while (el) {
      if (el.scrollHeight > el.clientHeight + 1) {
        return { isTable: el === (root as HTMLElement), className: String(el.className) }
      }
      el = el.parentElement
    }
    return null
  })
  expect(scroller, 'the seeded list is long enough that something has to scroll').not.toBeNull()
  expect(scroller!.isTable, 'the scroller is not the table itself').toBe(false)
  expect(scroller!.className, 'the scroller is the app page scroller').toContain('app-content')
})
