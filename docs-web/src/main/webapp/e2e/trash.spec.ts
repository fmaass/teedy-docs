import { test, expect } from '@playwright/test'
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

  await page.getByRole('button', { name: 'Empty trash' }).click()
  await confirmDanger(page)

  // After emptying, the seeded rows are gone. (Other runs' docs may remain, so
  // assert on our own titles rather than a global empty state.)
  for (const title of titles) {
    await expect(page.getByRole('row', { name: new RegExp(title) })).toHaveCount(0)
  }
})
