import { test, expect } from './fixtures'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'
import { unique, createDocument, confirmDanger } from './helpers'

const here = dirname(fileURLToPath(import.meta.url))
const txtFixture = resolve(here, 'fixtures/sample.txt')

// Upload a file to a document, see it listed, then remove it. OCR-dependent
// assertions are intentionally avoided (OCR is async/slow) — we only assert the
// file appears in the file list and can be removed.

test('upload a file to a document, see it listed, and remove it', async ({ page }) => {
  const title = unique('files')
  const { id } = await createDocument(page, title)

  // The document-view Files tab hosts the advanced FileUpload dropzone (auto
  // customUpload — files upload immediately on selection).
  await page.goto(`/#/document/view/${id}/content`)

  // Drive the hidden <input type=file> directly (robust vs. the styled dropzone).
  await page.locator('.p-fileupload-advanced input[type="file"]').setInputFiles(txtFixture)
  await expect(page.getByText('Files uploaded').first()).toBeVisible()

  // The uploaded file appears in the file list, linked by name.
  const fileLink = page.locator('.file-list-section').getByRole('link', { name: 'sample.txt' })
  await expect(fileLink).toBeVisible()

  // Remove it: the row's trash button + confirm; the file leaves the list.
  const row = page.locator('.file-row', { hasText: 'sample.txt' })
  await row.getByRole('button', { name: 'Remove file' }).click()
  await confirmDanger(page)
  await expect(page.locator('.file-list-section').getByRole('link', { name: 'sample.txt' })).toHaveCount(0)

  // Cleanup the document.
  await page.goto(`/#/document/view/${id}`)
  await page.getByRole('button', { name: 'Delete', exact: true }).click()
  await confirmDanger(page)
})
