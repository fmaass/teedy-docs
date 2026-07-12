import { test, expect } from '@playwright/test'
import { unique, createDocument, confirmDanger, fillDescription } from './helpers'

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
