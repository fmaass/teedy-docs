import { test, expect } from './fixtures'
import { unique, createDocument, confirmDanger } from './helpers'

// Document relations end to end via the "Related documents" section on the
// document Content tab (DocumentViewContent):
//   1. Create documents A and B.
//   2. From A's view, search B in the relation AutoComplete and add it — A links to B.
//   3. Assert the relation renders on BOTH views after a fresh reload: A shows B under
//      "Links to" with a remove control; B shows A under "Linked from" WITHOUT a remove
//      control (the incoming side is read-only — it must be removed from its source).
//   4. Remove the relation from A; after reload it is gone from BOTH views — exercising
//      the last-relation removal (relations_reset) path.
// Every title is timestamped and both documents are removed in teardown so reruns never
// collide with leftovers.

test('add a relation A→B, see it on both views, then remove the last relation', async ({ page }) => {
  let idA: string | null = null
  let idB: string | null = null
  try {
    const titleA = unique('rel-A')
    const titleB = unique('rel-B')
    idA = (await createDocument(page, titleA)).id
    idB = (await createDocument(page, titleB)).id

    // --- Add the relation A → B from A's Content tab ---
    await page.goto(`/#/document/view/${idA}`)
    await expect(page.getByRole('heading', { name: 'Related documents' })).toBeVisible()
    const addRow = page.locator('.relation-add')
    await addRow.locator('input').first().fill(titleB)
    await page.getByRole('option', { name: new RegExp(titleB) }).click()
    // Scope the toast assertion to the alert role: the add and the later removal each
    // fire an identical "Relations updated" toast, and a fast run can stack them. Wait
    // for THIS toast to appear THEN expire before the removal step so the post-removal
    // assertion below matches only the new toast, never the residual stacked one.
    const relationsToast = page.getByRole('alert').filter({ hasText: 'Relations updated' })
    await addRow.getByRole('button', { name: 'Add', exact: true }).click()
    await expect(relationsToast).toBeVisible()
    await expect(relationsToast).toBeHidden({ timeout: 3_000 })

    // --- In-app propagation (NO reload): follow the new outgoing link straight to B ---
    // This guards the cross-document cache invalidation: B's detail query must not serve
    // a stale (pre-mutation) relations list on in-app navigation. A full page.goto reload
    // would mask that defect by resetting the SPA query cache.
    await page
      .locator('.relation-group', { hasText: 'Links to' })
      .locator('.relation-row', { hasText: titleB })
      .locator('a.relation-link')
      .click()
    await expect(page).toHaveURL(new RegExp(idB))
    const linkedFromInApp = page.locator('.relation-group', { hasText: 'Linked from' })
    await expect(linkedFromInApp).toBeVisible()
    await expect(linkedFromInApp.locator('.relation-row', { hasText: titleA })).toBeVisible()

    // --- Assert on A's view after a fresh reload: B under "Links to", removable ---
    await page.goto(`/#/document/view/${idA}`)
    const linksToGroup = page.locator('.relation-group', { hasText: 'Links to' })
    await expect(linksToGroup).toBeVisible()
    const outgoingRow = linksToGroup.locator('.relation-row', { hasText: titleB })
    await expect(outgoingRow).toBeVisible()
    await expect(outgoingRow.getByRole('button', { name: 'Remove relation' })).toBeVisible()

    // --- Assert on B's view after a fresh reload: A under "Linked from", NO remove control ---
    await page.goto(`/#/document/view/${idB}`)
    const linkedFromGroup = page.locator('.relation-group', { hasText: 'Linked from' })
    await expect(linkedFromGroup).toBeVisible()
    const incomingRow = linkedFromGroup.locator('.relation-row', { hasText: titleA })
    await expect(incomingRow).toBeVisible()
    // The incoming relation is read-only: no remove control on B's side.
    await expect(incomingRow.getByRole('button', { name: 'Remove relation' })).toHaveCount(0)

    // --- Remove the relation from A (the last one) ---
    await page.goto(`/#/document/view/${idA}`)
    await page
      .locator('.relation-group', { hasText: 'Links to' })
      .locator('.relation-row', { hasText: titleB })
      .getByRole('button', { name: 'Remove relation' })
      .click()
    await confirmDanger(page)
    await expect(relationsToast).toBeVisible()

    // --- After a fresh reload it is gone from BOTH views ---
    await page.goto(`/#/document/view/${idA}`)
    await expect(page.getByRole('heading', { name: 'Related documents' })).toBeVisible()
    await expect(page.locator('.relation-group', { hasText: 'Links to' })).toHaveCount(0)

    await page.goto(`/#/document/view/${idB}`)
    await expect(page.getByRole('heading', { name: 'Related documents' })).toBeVisible()
    await expect(page.locator('.relation-group', { hasText: 'Linked from' })).toHaveCount(0)
  } finally {
    for (const id of [idA, idB]) {
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
