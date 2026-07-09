import { test, expect } from '@playwright/test'

// Runs authenticated. Creates a document via the real Add-document form. On save,
// Teedy routes to the full document view (DocumentEdit -> document-view). We then
// return to the list, verify the new document appears there, and open it.
test('creates a document, sees it in the list, and opens it', async ({ page }) => {
  const title = `E2E doc ${Date.now()}`

  await page.goto('/#/document/add')
  await expect(page.getByRole('heading', { name: 'New document' })).toBeVisible()

  await page.locator('#edit-title').fill(title)
  await page.getByRole('button', { name: 'Save' }).click()

  // Save routes to the full document view, whose header shows the new title.
  await expect(page).toHaveURL(/#\/document\/view\//)
  await expect(page.getByRole('heading', { name: title })).toBeVisible()

  // Return to the list — the new document appears in the table.
  await page.goto('/#/document')
  const titleCell = page.getByText(title, { exact: true })
  await expect(titleCell).toBeVisible()

  // Open it: clicking the row opens the slide-over panel showing the title, whose
  // "Open" button routes back to the full document view.
  await titleCell.click()
  await page.getByRole('button', { name: 'Open', exact: true }).click()
  await expect(page).toHaveURL(/#\/document\/view\//)
  await expect(page.getByRole('heading', { name: title })).toBeVisible()
})
