import { test, expect } from './fixtures'
import { unique, createDocument, confirmDanger, fillDescription, isMobileViewport } from './helpers'

// Per-document activity (DocumentViewActivity -> GET /auditlog?document=<id>). The
// Activity tab renders audit rows in a table (date / user / message columns, where the
// message is the loggable's own title). This spec proves the tab is genuinely SCOPED
// to one document — not just that it renders SOME rows:
//   - A TARGET document and a separate DECOY document are both created and edited, so
//     each has its own audit rows with a unique title.
//   - The TARGET's activity table MUST show the target's title and MUST NOT show the
//     decoy's title. If the backend ignored document=<id> and returned all logs, the
//     decoy's title would leak in — so its absence is the scoping proof.
//   - The count grows by one after a further edit of the target, and rows are
//     attributed to admin.
// Both documents are removed in teardown.

test("a document's activity tab shows audit entries scoped to that document", async ({ page }) => {
  const targetTitle = unique('act-target')
  const decoyTitle = unique('act-decoy')
  const rowsFor = () => page.locator('.p-datatable tbody tr')

  let targetId: string | null = null
  let decoyId: string | null = null
  try {
    // Create the DECOY first and edit it, so it has its own audit rows that MUST NOT
    // appear in the target's activity table.
    const decoy = await createDocument(page, decoyTitle)
    decoyId = decoy.id
    await page.goto(`/#/document/edit/${decoyId}`)
    await expect(page.locator('#edit-title')).toBeVisible()
    await fillDescription(page, `decoy-edit-${Date.now()}`)
    await page.getByRole('button', { name: 'Save' }).click()
    await expect(page).toHaveURL(new RegExp(`#/document/view/${decoyId}`))

    // Create the TARGET.
    const target = await createDocument(page, targetTitle)
    targetId = target.id

    // Baseline: the create already produced at least one row for the target.
    await page.goto(`/#/document/view/${targetId}/activity`)
    await expect(page.locator('.p-datatable')).toBeVisible()
    await expect(page.getByText('No activity recorded')).toHaveCount(0)
    await expect(rowsFor().first()).toBeVisible()
    const baselineCount = await rowsFor().count()

    // Edit the target — this writes another audit row scoped to the target.
    await page.goto(`/#/document/edit/${targetId}`)
    await expect(page.locator('#edit-title')).toBeVisible()
    await fillDescription(page, `target-edit-${Date.now()}`)
    await page.getByRole('button', { name: 'Save' }).click()
    await expect(page).toHaveURL(new RegExp(`#/document/view/${targetId}`))

    // Back on the Activity tab (reload, not SPA-navigate, so the cached auditlog query
    // refetches): an extra row is present, rows are attributed to admin, the TARGET's
    // title IS shown, and the DECOY's title is ABSENT — the scoping guarantee.
    await page.goto(`/#/document/view/${targetId}/activity`)
    await page.reload()
    const table = page.locator('.p-datatable')
    await expect(rowsFor()).toHaveCount(baselineCount + 1)
    await expect(table.getByText('admin', { exact: true }).first()).toBeVisible()
    await expect(table.getByText(targetTitle, { exact: true }).first()).toBeVisible()
    // SCOPING: the decoy's audit rows must NOT leak into the target's activity.
    await expect(table.getByText(decoyTitle, { exact: true })).toHaveCount(0)
  } finally {
    for (const id of [targetId, decoyId]) {
      if (!id) continue
      await page.goto(`/#/document/view/${id}`)
      const del = page.getByRole('button', { name: 'Delete', exact: true })
      if (await del.isVisible().catch(() => false)) {
        await del.click()
        await confirmDanger(page)
      }
    }
  }
})

// #113 display work. The Activity tab renders each audit row's event TYPE as a
// localized label (docs-core AuditLogType: CREATE/UPDATE/DELETE/AUTHENTICATION),
// keeps the row `message` verbatim, renders the date at the SAME type scale as the
// rest of the row (no shrunken `text-xs`), and offers a purely client-side event-type
// filter whose options are the types actually observed in the loaded rows.
//
// Each test below seeds its own document with a CREATE row (the create) and an UPDATE
// row (a description edit) — two DISTINCT audit types scoped to that document — then
// reloads the Activity tab (not SPA-navigate) so the cached auditlog query refetches
// past its 30s staleTime.
const rowsSelector = '.p-datatable tbody tr'

async function seedActivityDoc(page: import('@playwright/test').Page, title: string): Promise<string> {
  const doc = await createDocument(page, title)
  await page.goto(`/#/document/edit/${doc.id}`)
  await expect(page.locator('#edit-title')).toBeVisible()
  await fillDescription(page, `edit-${Date.now()}`)
  await page.getByRole('button', { name: 'Save' }).click()
  await expect(page).toHaveURL(new RegExp(`#/document/view/${doc.id}`))
  await page.goto(`/#/document/view/${doc.id}/activity`)
  await page.reload()
  await expect(page.locator('.p-datatable')).toBeVisible()
  await expect(page.locator(rowsSelector).first()).toBeVisible()
  return doc.id
}

async function removeDoc(page: import('@playwright/test').Page, docId: string | null) {
  if (!docId) return
  await page.goto(`/#/document/view/${docId}`)
  const del = page.getByRole('button', { name: 'Delete', exact: true })
  if (await del.isVisible().catch(() => false)) {
    await del.click()
    await confirmDanger(page)
  }
}

// DATE SCALE: the date cell renders at the same computed font-size as the username
// cell in the same row. Pre-fix the date carried `.text-xs` (0.75rem) against the
// table default, so this equality is red until the wrap is removed. Positional cell
// selectors keep it valid against both the pre-fix and post-fix DOM.
test('#113 the activity date renders at the same type scale as the rest of the row', async ({ page }) => {
  let docId: string | null = null
  try {
    docId = await seedActivityDoc(page, unique('act-scale'))
    const firstRow = page.locator(rowsSelector).first()
    const dateFont = await firstRow
      .locator('td')
      .first()
      .locator('span')
      .first()
      .evaluate((el) => getComputedStyle(el as HTMLElement).fontSize)
    const userFont = await firstRow
      .locator('td')
      .nth(1)
      .evaluate((el) => getComputedStyle(el as HTMLElement).fontSize)
    expect(dateFont).toBe(userFont)
  } finally {
    await removeDoc(page, docId)
  }
})

// READABLE ROW: the localized UPDATE label is shown (NOT the raw enum token) and the
// row's verbatim message (the loggable's own title) renders.
test('#113 activity rows show a localized event-type label and a verbatim message', async ({ page }) => {
  const title = unique('act-label')
  let docId: string | null = null
  try {
    docId = await seedActivityDoc(page, title)
    const typeCells = page.locator(`${rowsSelector} .activity-type`)
    await expect(typeCells.filter({ hasText: 'Updated' }).first()).toBeVisible()
    // The raw enum token must NOT leak into any type cell (mapping, not passthrough).
    // Case-sensitive regex so the localized label "Updated" is not miscounted as "UPDATE".
    await expect(typeCells.filter({ hasText: /^(CREATE|UPDATE|DELETE|AUTHENTICATION)$/ })).toHaveCount(0)
    await expect(page.locator('.p-datatable').getByText(title, { exact: true }).first()).toBeVisible()
  } finally {
    await removeDoc(page, docId)
  }
})

// CLIENT-SIDE FILTER: options are the types OBSERVED in the loaded rows — Created and
// Updated are present; Deleted and Authentication (never emitted for this document)
// are ABSENT (that absence proves observed-not-fixed-enum). Selecting narrows the
// table; clearing restores every row.
test('#113 the client-side event-type filter offers observed types, narrows, and clears', async ({ page }) => {
  let docId: string | null = null
  try {
    docId = await seedActivityDoc(page, unique('act-filter'))
    const typeCells = page.locator(`${rowsSelector} .activity-type`)

    await page.locator('.activity-type-filter').click()
    // An explicit "All types" entry is always offered as the operable path back to the
    // unfiltered state; the observed types follow it.
    await expect(page.getByRole('option', { name: 'All event types', exact: true })).toBeVisible()
    await expect(page.getByRole('option', { name: 'Created', exact: true })).toBeVisible()
    await expect(page.getByRole('option', { name: 'Updated', exact: true })).toBeVisible()
    await expect(page.getByRole('option', { name: 'Deleted', exact: true })).toHaveCount(0)
    await expect(page.getByRole('option', { name: 'Authentication', exact: true })).toHaveCount(0)

    // Selecting Updated narrows the table to UPDATE rows only.
    await page.getByRole('option', { name: 'Updated', exact: true }).click()
    await expect(typeCells.filter({ hasText: 'Created' })).toHaveCount(0)
    await expect(typeCells.first()).toHaveText('Updated')

    // Selecting "All types" brings every row back, including the hidden Created row.
    await page.locator('.activity-type-filter').click()
    await page.getByRole('option', { name: 'All event types', exact: true }).click()
    await expect(typeCells.filter({ hasText: 'Created' }).first()).toBeVisible()
  } finally {
    await removeDoc(page, docId)
  }
})

// #113 accessibility: a keyboard user who narrowed the table by type must be able to
// return to all rows without a mouse. There is no focusable clear icon; the explicit
// "All types" option is the operable route — reachable via the combobox with Enter to
// open, Home to land on the first option, and Enter to select.
test('#113 the event-type filter can be cleared to all rows with the keyboard', async ({ page }) => {
  // Desktop concern: PrimeVue Select disables desktop keyboard grid-navigation on
  // Android (its onKeyDown handles only Backspace/Enter there), and the mobile project
  // runs the Android Pixel 5 descriptor. On touch, the same "All event types" option is
  // cleared by tapping it — exercised on both projects by the filter test above.
  test.skip(isMobileViewport(page), 'keyboard grid-navigation is a desktop interaction')
  let docId: string | null = null
  try {
    docId = await seedActivityDoc(page, unique('act-kbd'))
    const typeCells = page.locator(`${rowsSelector} .activity-type`)
    const combo = page.locator('.activity-type-filter').getByRole('combobox')

    // Narrow to Updated first so there is a filtered state to escape from.
    await page.locator('.activity-type-filter').click()
    await page.getByRole('option', { name: 'Updated', exact: true }).click()
    await expect(typeCells.filter({ hasText: 'Created' })).toHaveCount(0)

    // KEYBOARD CLEAR: focus the combobox, open it, move to the first option
    // ("All types") and select it — no pointer, no clear icon.
    await combo.focus()
    await expect(combo).toBeFocused()
    await combo.press('Enter')
    await expect(page.getByRole('option', { name: 'All event types', exact: true })).toBeVisible()
    await combo.press('Home')
    await combo.press('Enter')

    // Every row returns, including the Created row the filter had hidden.
    await expect(typeCells.filter({ hasText: 'Created' }).first()).toBeVisible()
  } finally {
    await removeDoc(page, docId)
  }
})

// #113 regression: an empty auditlog response renders the friendly empty state, not a
// broken/false-populated table. Intercepted so it is deterministic and self-contained
// (no seeding); this is base-green by design and guards the empty branch against the
// filter/type-column additions.
test('#113 empty activity response renders the empty state', async ({ page }) => {
  const title = unique('act-empty')
  let docId: string | null = null
  try {
    const doc = await createDocument(page, title)
    docId = doc.id

    await page.route('**/api/auditlog**', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ logs: [], total: 0 }),
      }),
    )

    await page.goto(`/#/document/view/${docId}/activity`)
    await page.reload()
    await expect(page.locator('.p-datatable')).toBeVisible()
    await expect(page.getByText('No activity recorded')).toBeVisible()
    await expect(page.locator('.p-datatable tbody tr .activity-type')).toHaveCount(0)
  } finally {
    await page.unroute('**/api/auditlog**').catch(() => {})
    if (docId) {
      await page.goto(`/#/document/view/${docId}`)
      const del = page.getByRole('button', { name: 'Delete', exact: true })
      if (await del.isVisible().catch(() => false)) {
        await del.click()
        await confirmDanger(page)
      }
    }
  }
})
