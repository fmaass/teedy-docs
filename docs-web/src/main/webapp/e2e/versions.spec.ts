import { test, expect } from './fixtures'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'
import { unique, createDocument, confirmDanger, openFileList } from './helpers'

const here = dirname(fileURLToPath(import.meta.url))
const v1 = resolve(here, 'fixtures/sample.txt')

// File version history. NOTE: Teedy only creates a NEW version when a file is
// uploaded with an explicit `previousFileId` (FileResource.add) — a "replace with
// new version" flow. The current SPA's plain uploadFile() never sets it, so there
// is no UI path to produce a multi-version file from the frontend. We therefore
// verify the version-history dialog opens and lists the current version (v1) with
// its read-only hint. The "two versions listed" case is not asserted because it is
// unreachable via the UI under test (see skipped test below for the explicit reason).

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

// PRODUCT GAP (not a test defect): the "add a second file version" flow is
// intentionally skipped because NO UI PATH produces one.
//
//   - The backend DOES support versions: PUT /api/file accepts an optional
//     `previousFileId`; when present, FileResource.add supersedes that file and the
//     new upload becomes v2 of the same logical file (Version history then lists v1
//     + v2).
//   - The frontend NEVER sets `previousFileId`. Both upload surfaces
//     (DocumentViewContent's advanced FileUpload and the DocumentEdit attach flow)
//     call the plain `uploadFile(documentId, file)` client, which posts only
//     `id` (=documentId) + `file`. There is no "replace with new version" / "upload
//     new version of THIS file" control anywhere in the SPA.
//
// Therefore any spec that "adds a second version" through the UI could only upload a
// SEPARATE new attachment — which produces a second v1 file, NOT a v2 of the first.
// Asserting "two versions" off that would be a FAKE test (asserting a state the UI
// cannot actually reach). We keep the skip until a real add-version UI wires
// `previousFileId`. See the runnable test above for the reachable coverage (the
// history dialog opening and listing the current version).
test.skip('lists two versions after adding a second file version (no UI path)', async () => {
  // Unreachable via the UI under test: no SPA control sets `previousFileId`, so a
  // multi-version file cannot be created from the frontend. See the note above.
})
