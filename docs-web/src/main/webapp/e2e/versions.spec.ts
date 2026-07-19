import { test, expect } from './fixtures'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'
import { unique, createDocument, confirmDanger, openFileList } from './helpers'

const here = dirname(fileURLToPath(import.meta.url))
const v1 = resolve(here, 'fixtures/sample.txt')

// File version history. Teedy creates a NEW version when a file is uploaded with an
// explicit `previousFileId` (FileResource.add) — a "replace with new version" flow.
// This spec covers the read-only history dialog opening and listing the current
// version (v1) with its read-only hint. The multi-version path (uploading v2 through
// the #117 "Upload new version" UI and asserting v1 + v2) is covered end-to-end in
// revision-upload.spec.ts.

test('the version-history dialog opens and lists the current version', async ({ page }) => {
  const title = unique('ver')
  const { id } = await createDocument(page, title)
  try {
    await page.goto(`/#/document/view/${id}/content`)
    await page.locator('.p-fileupload-advanced input[type="file"]').setInputFiles(v1)
    await expect(page.getByText('Files uploaded').first()).toBeVisible()
    // Version history lives in the list's per-file action menu.
    await openFileList(page)
    await expect(page.locator('.file-list-section .file-name-text', { hasText: 'sample.txt' })).toBeVisible()

    await page.locator('.file-list-section').getByRole('button', { name: 'Version history' }).click()

    const dialog = page.getByRole('dialog', { name: /Version history/ })
    await expect(dialog).toBeVisible()
    // The read-only history hint and the current version row (v1) are shown.
    await expect(dialog.getByText(/read-only/i)).toBeVisible()
    await expect(dialog.getByText('v1')).toBeVisible()

    // The footer "Close" button (a text button, distinct from the header X icon).
    await dialog.locator('.p-dialog-footer').getByRole('button', { name: 'Close' }).click()
    await expect(dialog).toBeHidden()
  } finally {
    // Cleanup the document (runs even if an assertion above fails).
    await page.goto(`/#/document/view/${id}`)
    const del = page.getByRole('button', { name: 'Delete', exact: true })
    if (await del.isVisible().catch(() => false)) {
      await del.click()
      await confirmDanger(page)
    }
  }
})

// The "add a second file version" flow (uploading v2 so history lists v1 + v2) is now
// wired in the SPA (#117 "Upload new version") and is verified end-to-end — including
// the upload-bar name-conflict "add as new version" path — in revision-upload.spec.ts.
