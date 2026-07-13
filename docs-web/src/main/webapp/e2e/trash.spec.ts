import { test, expect } from './fixtures'
import { unique, createDocument, confirmDanger } from './helpers'

// Trash lifecycle: delete a document to trash, restore it, permanent-delete, and
// empty trash. Also asserts the retention countdown column renders (auto-purge
// state is observable per row).

test('delete to trash, restore, then permanent-delete', async ({ page }) => {
  const title = unique('trash')
  await createDocument(page, title)

  // Delete from the document view routes to the list and moves it to trash.
  await page.getByRole('button', { name: 'Delete', exact: true }).click()
  await confirmDanger(page)
  await expect(page).toHaveURL(/#\/document$/)

  // The trash page lists it, with the retention countdown column populated.
  await page.goto('/#/document/trash')
  await expect(page.getByRole('heading', { name: 'Trash' })).toBeVisible()
  const row = page.getByRole('row', { name: new RegExp(title) })
  await expect(row).toBeVisible()
  // The "purges in" cell shows either a countdown or the disabled state — either
  // way the column body rendered a Tag for this row.
  await expect(row.locator('.p-tag')).toBeVisible()

  // Restore: the document returns to the main list and leaves the trash.
  await row.getByRole('button', { name: 'Restore' }).click()
  await expect(page.getByText('Document restored').first()).toBeVisible()
  await expect(page.getByRole('row', { name: new RegExp(title) })).toHaveCount(0)

  await page.goto('/#/document')
  await expect(page.getByText(title, { exact: true })).toBeVisible()

  // Delete again, then permanent-delete from trash.
  await page.getByText(title, { exact: true }).click()
  await page.getByRole('button', { name: 'Open', exact: true }).click()
  await expect(page).toHaveURL(/#\/document\/view\//)
  await page.getByRole('button', { name: 'Delete', exact: true }).click()
  await confirmDanger(page)

  await page.goto('/#/document/trash')
  const row2 = page.getByRole('row', { name: new RegExp(title) })
  await expect(row2).toBeVisible()
  await row2.getByRole('button', { name: 'Delete' }).click()
  await confirmDanger(page)
  // The row leaving the trash table is the authoritative signal (toasts can stack
  // across actions, so don't assert a single toast instance here).
  await expect(page.getByRole('row', { name: new RegExp(title) })).toHaveCount(0)
})

test('empty trash removes all trashed documents', async ({ page }) => {
  // Seed two documents and trash both so Empty trash has something to clear.
  const titles = [unique('empty-a'), unique('empty-b')]
  for (const title of titles) {
    await createDocument(page, title)
    await page.getByRole('button', { name: 'Delete', exact: true }).click()
    await confirmDanger(page)
    await expect(page).toHaveURL(/#\/document$/)
  }

  await page.goto('/#/document/trash')
  for (const title of titles) {
    await expect(page.getByRole('row', { name: new RegExp(title) })).toBeVisible()
  }

  // Empty the trash. The original CI failure on this spec looked like an overlay race,
  // but the real cause was a SERVER bug: DELETE /api/document/trash returned 500 whenever
  // the trash held a file owned by a since-deleted user (the user-reassign specs delete a
  // user upstream), so the trash was never emptied and the seeded rows stayed (fixed in
  // FileUtil.reclaimUserQuota). Toasts covering the button on mobile were a secondary
  // hazard, now neutralised GLOBALLY by the click-through fixture (e2e/fixtures.ts).
  //
  // The gesture is written to prove the AUTHORITATIVE server effect rather than a UI
  // side-effect: each attempt clicks "Empty trash", accepts the ConfirmDialog if it
  // opened, and waits for the DELETE /api/document/trash response — the request the empty
  // actually issues. A dropped click sends no DELETE, so the outer toPass retries; the op
  // is idempotent (emptying an already-empty trash is a no-op), so retrying is safe. The
  // button is v-if'd on documents.length, so once the trash is empty it disappears — that
  // absence is itself the success signal and we stop. The strict toHaveCount(0) below is
  // unchanged and remains the final authoritative proof the seeded rows are gone.
  const emptyBtn = page.getByRole('button', { name: 'Empty trash' })
  const confirm = page.getByRole('alertdialog')
  await expect(async () => {
    if (!(await emptyBtn.isVisible().catch(() => false))) return // trash already empty
    const deleteResponse = page.waitForResponse(
      (r) => r.url().endsWith('/api/document/trash') && r.request().method() === 'DELETE',
      { timeout: 8000 },
    )
    await emptyBtn.click()
    if (await confirm.isVisible({ timeout: 4000 }).catch(() => false)) {
      await confirm.getByRole('button', { name: 'Yes' }).click()
      await expect(confirm).toBeHidden()
    }
    // Prove the empty actually fired server-side (not just that a dialog closed); if the
    // click/confirm was dropped no DELETE was sent and this rejects, so toPass retries.
    await deleteResponse
    // Then our seeded rows must clear from the (invalidated + refetched) list.
    for (const title of titles) {
      await expect(page.getByRole('row', { name: new RegExp(title) })).toHaveCount(0, { timeout: 5000 })
    }
  }).toPass({ timeout: 30000 })

  // Final authoritative assertion: the seeded rows are gone. (Other runs' docs may
  // remain, so assert on our own titles rather than a global empty state.)
  for (const title of titles) {
    await expect(page.getByRole('row', { name: new RegExp(title) })).toHaveCount(0)
  }
})
