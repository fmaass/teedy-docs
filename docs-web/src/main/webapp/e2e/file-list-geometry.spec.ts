import { test, expect, type APIRequestContext, type Page, type Locator } from './fixtures'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'
import { readFileSync } from 'node:fs'
import { unique, login, openFileList, deleteDocApi, deleteUserApi } from './helpers'

// #196 + #170 + #192 — the file panel's ROW/CARD GEOMETRY, measured against the running app.
//
// The row that has to work is the worst case, not the average one: a writable PDF row
// carries TEN controls (FileActionMenu's nine — history, preview, copy-link, download,
// cover, move, rename, delete, plus the version upload — and the PDF page organizer),
// roughly 378px of icons at the theme's small size, which is wider than the entire action
// area of a 360px phone. The redesign therefore lets the cluster WRAP inside its column
// instead of hiding controls behind an overlay, collapses the metadata columns as the
// viewport narrows, and pins the name column to an 8rem floor with an ellipsis.
//
// This spec is the standing gate for that policy. It asserts, at 360px, 393px and desktop,
// for three different clusters (writable PDF = 10 controls, writable non-PDF = 9, read-only
// = 4: history, preview, copy-link, download): no horizontal page overflow, the name column
// at or above its 8rem floor with the name genuinely ellipsized, every control painted
// inside both its row and the viewport, and — the property the earlier revert (`bee6cfda`)
// missed — that the LAST control of the cluster takes a REAL interaction. For a writable row
// that last control is the Delete button (clicked, opening the danger confirm); for a
// read-only row it is the Download ANCHOR, which is exercised by actually starting its
// download.
//
// Three surfaces, not one, because the tenth control (#192) put pressure on all three:
//   * the LIST rows at 360 / 393 / 1280 (the historical case);
//   * the LIST row with the optional Uploader column ENABLED, across the 1024–1440 band.
//     `.doc-view` caps the page content at 960px, so a wider window buys no room at all —
//     which is why that band is asserted at both ends: if it passed only because 1440px is
//     wide, the cap would have been misread;
//   * the GRID cards at 360 / 393. FileActionMenu mounts in the tiles too, and a tile is
//     ~336px wide at 360px (and no wider on a big screen — the grid is
//     `auto-fill, minmax(280px, 1fr)` inside a 960px-capped page). Ten 36px controls do not
//     fit on one line there, and because the row is a flex row the failure is not an
//     overflow that something else would catch: the controls SHRINK. Measured under a
//     deliberately broken build: 30px each instead of 36px, every grid control 17% smaller
//     than the identical control in the list, and the squeeze deepens with each control
//     added until it does clip. So the card test measures the painted WIDTH of each
//     control, not only its containment.
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

// The band in which the optional Uploader column is offered at all (it is display:none
// below 1024px, and so is its chooser entry). Both ends are measured: the page content is
// capped at 960px, so 1440px has exactly as much room for the row as 1024px does — a gate
// that only checked the wide end would prove nothing about the narrow one.
const UPLOADER_WIDTHS = [1024, 1440] as const

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

// How many LINES the action cluster occupies, by distinct painted top edge. This is what
// makes "the cluster wraps instead of overflowing" an assertion rather than a hope: a row
// that silently kept everything on one line would still satisfy every containment check
// below if the column happened to be wide enough.
function clusterLines(controls: { box: Box }[]): number {
  return new Set(controls.map((c) => Math.round(c.box.y))).size
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

      // WORST CASE: a writable PDF row — FileActionMenu's nine controls plus the PDF
      // page organizer from the #file-extra slot.
      const pdfRow = page.locator('.file-data-table tbody tr', { hasText: LONG_PDF })
      await expect(pdfRow).toHaveCount(1)
      const pdfGeom = await measureRow(page, pdfRow)
      assertRowGeometry(pdfGeom, 10, `writable PDF @${width}`)
      // Below 900px the column holds THREE 36px icons per line, so ten controls occupy
      // four lines and the row has to grow by one more than it did before #192 — the
      // "no fixed row height" property, stated as a number. Above it, one line.
      expect(clusterLines(pdfGeom.controls), `writable PDF @${width}: cluster lines`).toBe(
        width < 900 ? 4 : 1,
      )

      // Writable non-PDF: the same cluster without the page organizer.
      const txtRow = page.locator('.file-data-table tbody tr', { hasText: LONG_TXT })
      await expect(txtRow).toHaveCount(1)
      const txtGeom = await measureRow(page, txtRow)
      assertRowGeometry(txtGeom, 9, `writable non-PDF @${width}`)
      expect(clusterLines(txtGeom.controls), `writable non-PDF @${width}: cluster lines`).toBe(
        width < 900 ? 3 : 1,
      )

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
      // A read-only row offers exactly the four READ controls, in this order:
      // history, preview, copy-link, download.
      const geom = await measureRow(viewer, row)
      // Four controls leave a wide name column on a desktop viewport — wide enough for the
      // whole name — so the ellipsis is only demanded at the phone widths.
      assertRowGeometry(geom, 4, `read-only @${width}`, width < 900)
      expect(
        geom.controls.map((c) => c.label),
        `read-only @${width}: the read cluster, in order`,
      ).toEqual(['Version history', 'Open ' + LONG_PDF, 'Copy link to file', 'Download'])
      expect(clusterLines(geom.controls), `read-only @${width}: cluster lines`).toBe(
        width < 900 ? 2 : 1,
      )
      // Its LAST control is the Download ANCHOR, not a button — exercise it as one.
      const last = geom.controls[geom.controls.length - 1]
      expect(last.tag, `read-only @${width}: last control is an anchor`).toBe('A')
      expect(last.label, `read-only @${width}: last control is Download`).toBe('Download')

      const download = row.getByRole('link', { name: 'Download' })
      const [event] = await Promise.all([viewer.waitForEvent('download'), download.click()])
      expect(event.suggestedFilename(), `read-only @${width}: downloads its own file`).toBe(LONG_PDF)
    }
  })

  // #192/F3 — the OPTIONAL Uploader column, the widest of the three, together with the
  // ten-icon cluster. This combination was already the tightest one in the list before the
  // copy-link control existed (the halved action gutters are what made it fit); the tenth
  // control costs ~38px more, which the 960px content cap cannot supply at ANY window
  // width. The row is expected to absorb it by wrapping the cluster, not by dropping the
  // column — so this test enables the column for real (through the chooser a user would
  // use) and measures the worst-case row at both ends of the band.
  test('the Uploader column and the full cluster coexist across the 1024-1440 band', async ({
    page,
    cleanup,
  }) => {
    const id = await seedDoc(page.request, unique('geom-up'), [
      { name: LONG_PDF, mimeType: 'application/pdf', path: pdf },
    ])
    cleanup.defer('purge the seeded document', () => deleteDocApi(page.request, id))

    await page.setViewportSize({ width: UPLOADER_WIDTHS[0], height: 800 })
    await page.goto(`/#/document/view/${id}/content`)
    await openFileList(page)

    // Enable it the way a user does. The chooser itself is only offered above 640px, and
    // the Uploader entry only above 1023px — both true at this viewport.
    await page.locator('.file-columns-btn').click()
    await page.locator('label[for="file-col-uploader"]').click()
    await expect(page.locator('th.file-col-uploader')).toBeVisible()
    await page.keyboard.press('Escape')

    for (const width of UPLOADER_WIDTHS) {
      await page.setViewportSize({ width, height: 800 })
      const row = page.locator('.file-data-table tbody tr', { hasText: LONG_PDF })
      await expect(row).toHaveCount(1)
      // The column really is in play at this width — otherwise the measurement below would
      // be the ordinary desktop row wearing a different label.
      await expect(page.locator('td.file-col-uploader').first()).toBeVisible()
      // A single-file document leaves the name column roomy at these widths, so the name
      // need not be truncated for the row to be correct.
      const geom = await measureRow(page, row)
      assertRowGeometry(geom, 10, `uploader @${width}`, false)
      // THE F3 MEASUREMENT. With the Uploader column on there is not enough room for ten
      // controls on one line at EITHER end of the band — the 960px content cap means 1440px
      // is no roomier than 1024px — so the row must absorb the cluster on more than one
      // line. A single line here would mean the column had been silently dropped.
      expect(clusterLines(geom.controls), `uploader @${width}: the cluster wraps`).toBeGreaterThan(1)

      // …and the last control still takes a real click with the extra column present.
      const remove = row.getByRole('button', { name: 'Remove file' })
      await expect(remove).toBeVisible()
      await remove.click()
      const confirm = page.getByRole('alertdialog')
      await expect(confirm).toBeVisible()
      await confirm.getByRole('button', { name: 'No' }).click()
      await expect(confirm).toBeHidden()
    }

    // THE BOUNDARY CASE. The Uploader PREFERENCE is stored per user and outlives the width
    // it was set at, but the column is force-hidden below 1024px. At 1023px there is
    // therefore no Uploader column on screen and the row has exactly the room the ordinary
    // desktop row has — so it must keep the ONE-LINE cluster. Tying the preference's
    // narrower floor to the one-line band (960px) instead of to the band the column
    // actually paints in (1024px) opens a dead zone here: rows wrapping to make space for a
    // column that is not there.
    await page.setViewportSize({ width: 1023, height: 800 })
    const hiddenRow = page.locator('.file-data-table tbody tr', { hasText: LONG_PDF })
    await expect(hiddenRow).toHaveCount(1)
    // The preference is still on — the class is still applied — but nothing renders.
    await expect(page.locator('.file-data-table.has-uploader')).toHaveCount(1)
    await expect(page.locator('th.file-col-uploader')).toBeHidden()
    const hiddenGeom = await measureRow(page, hiddenRow)
    assertRowGeometry(hiddenGeom, 10, 'uploader preference @1023 (column hidden)', false)
    expect(
      clusterLines(hiddenGeom.controls),
      'uploader preference @1023: no hidden column, so no wrap',
    ).toBe(1)
  })
})

// #192/F2 — the GRID card's own action row. FileActionMenu mounts in the tiles too, and a
// tile is ~336px wide at 360px and no wider on a large screen (the grid is
// `auto-fill, minmax(280px, 1fr)` inside a 960px-capped page), while a ten-icon cluster is
// ~378px. The row is a flex row, so a non-wrapping cluster neither widens the card nor
// spills out of it: the controls SHRINK to fit. Measured against a deliberately unwrapped
// build at 360px — 30px per control instead of 36 — so the assertion that holds the fix is
// the painted WIDTH of each control, alongside containment in the card and a real click on
// the last one.
test.describe('file grid card geometry (#192)', () => {
  test('every writable action stays inside its card and clickable at 360px and 393px', async ({
    page,
    cleanup,
  }) => {
    const id = await seedDoc(page.request, unique('geomgrid'), [
      { name: LONG_PDF, mimeType: 'application/pdf', path: pdf },
    ])
    cleanup.defer('purge the seeded document', () => deleteDocApi(page.request, id))
    // Grid is the default view, but the mode is remembered per user in localStorage and an
    // earlier spec may have left "list" there — pin it so this test measures the grid.
    await page.addInitScript(() => localStorage.setItem('teedy_file_view_mode:admin', 'grid'))
    await page.goto(`/#/document/view/${id}/content`)
    await expect(page.locator('.file-preview-grid')).toBeVisible()

    for (const width of [360, 393] as const) {
      await page.setViewportSize({ width, height: 800 })
      const card = page.locator('.file-preview-card').first()
      await expect(card).toBeVisible()

      const geom = await card.evaluate((el) => {
        const box = (target: Element) => {
          const r = target.getBoundingClientRect()
          return { x: r.x, y: r.y, width: r.width, height: r.height }
        }
        const actions = el.querySelector('.file-card-actions') as HTMLElement
        return {
          viewportWidth: window.innerWidth,
          docScrollWidth: document.documentElement.scrollWidth,
          docClientWidth: document.documentElement.clientWidth,
          cardBox: box(el),
          cardOverflow: getComputedStyle(el).overflow,
          actionsBox: box(actions),
          // Read alongside the boxes so a squeeze and its cause are reported together.
          menuWrap: getComputedStyle(actions.querySelector('.file-action-menu') as HTMLElement).flexWrap,
          controls: Array.from(actions.querySelectorAll('button, a')).map((c) => ({
            label: c.getAttribute('aria-label'),
            tag: c.tagName,
            box: box(c),
          })),
        }
      })

      const where = `grid card @${width}`
      expect(geom.docScrollWidth, `${where}: no horizontal page overflow`).toBeLessThanOrEqual(
        geom.docClientWidth + 1,
      )
      // The worst case really is under test.
      expect(geom.controls.length, `${where}: control count`).toBe(10)
      // The card clips its overflow, so nothing that escapes it would be reachable — which
      // is why containment is asserted even though the measured failure mode is a squeeze.
      expect(geom.cardOverflow, `${where}: the card still clips its overflow`).toBe('hidden')
      // Geometry before declarations: a failure here has to be the one reported.
      for (const c of geom.controls) {
        const label = `${where}: control ${c.label ?? '(unlabelled)'}`
        expect(c.box.width, `${label} has a painted box`).toBeGreaterThan(0)
        expect(c.box.height, `${label} has a painted box`).toBeGreaterThan(0)
        // …AT ITS NATURAL SIZE. This is the assertion that actually holds the fix: the
        // cluster is a flex row, so without the wrap the ten controls do not overflow the
        // card at all — they SHRINK to fit it. Measured at 360px: 36px each when the row
        // wraps, 30px each when it does not, i.e. every grid control silently becomes 17%
        // smaller than the identical control in the list, and worse with each control
        // added. NAME_FLOOR-style constant deliberately avoided: 34 is the measured 36
        // minus sub-pixel slack, and it separates the two states cleanly.
        expect(
          c.box.width,
          `${label} keeps its natural width (not squeezed onto one line)`,
        ).toBeGreaterThanOrEqual(34)
        expect(c.box.x, `${label} not off the card's left edge`).toBeGreaterThanOrEqual(
          geom.cardBox.x - 1,
        )
        expect(
          c.box.x + c.box.width,
          `${label} right edge inside the card`,
        ).toBeLessThanOrEqual(geom.cardBox.x + geom.cardBox.width + 1)
        expect(
          c.box.x + c.box.width,
          `${label} right edge within the viewport`,
        ).toBeLessThanOrEqual(geom.viewportWidth + 1)
        expect(c.box.y, `${label} top inside the action row`).toBeGreaterThanOrEqual(
          geom.actionsBox.y - 1,
        )
        expect(
          c.box.y + c.box.height,
          `${label} bottom inside the action row`,
        ).toBeLessThanOrEqual(geom.actionsBox.y + geom.actionsBox.height + 1)
      }

      // The declaration, and the fact that it took effect: at these widths ten 36px
      // controls cannot share one line inside a ~336px card, so the row MUST be more than
      // one line. A single line would mean they had been squeezed after all.
      expect(geom.menuWrap, `${where}: the action cluster is allowed to wrap`).toBe('wrap')
      expect(clusterLines(geom.controls), `${where}: the cluster wraps`).toBeGreaterThan(1)

      // Geometry is not enough: the LAST control of a writable cluster is Delete, and a
      // clipped or overlapped neighbour would intercept the pointer here.
      const remove = card.getByRole('button', { name: 'Remove file' })
      await expect(remove).toBeVisible()
      await remove.click()
      const confirm = page.getByRole('alertdialog')
      await expect(confirm).toBeVisible()
      // Decline: the card must survive for the remaining width.
      await confirm.getByRole('button', { name: 'No' }).click()
      await expect(confirm).toBeHidden()
      await expect(page.locator('.file-preview-card')).toHaveCount(1)
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
