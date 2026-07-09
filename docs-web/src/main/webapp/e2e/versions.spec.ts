import { test, expect } from '@playwright/test'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'
import { unique, createDocument, confirmDanger } from './helpers'

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

  await page.goto(`/#/document/view/${id}/content`)
  await page.locator('.p-fileupload-advanced input[type="file"]').setInputFiles(v1)
  await expect(page.getByText('Files uploaded').first()).toBeVisible()
  await expect(page.locator('.file-row', { hasText: 'sample.txt' })).toBeVisible()

  const row = page.locator('.file-row', { hasText: 'sample.txt' }).first()
  await row.getByRole('button', { name: 'Version history' }).click()

  const dialog = page.getByRole('dialog', { name: /Version history/ })
  await expect(dialog).toBeVisible()
  // The read-only history hint and the current version row (v1) are shown.
  await expect(dialog.getByText(/read-only/i)).toBeVisible()
  await expect(dialog.getByText('v1')).toBeVisible()

  // The footer "Close" button (a text button, distinct from the header X icon).
  await dialog.locator('.p-dialog-footer').getByRole('button', { name: 'Close' }).click()
  await expect(dialog).toBeHidden()

  // Cleanup the document.
  await page.goto(`/#/document/view/${id}`)
  await page.getByRole('button', { name: 'Delete', exact: true }).click()
  await confirmDanger(page)
})

// The multi-version case is intentionally skipped: it cannot be driven through the
// SPA, which never uploads a file with `previousFileId` set. Documented here so the
// coverage gap is explicit rather than silent.
test.skip('lists two versions after a same-file re-upload', async () => {
  // Unreachable via the UI under test — see the note above.
})
