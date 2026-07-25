import { test, expect, type APIRequestContext, type Page } from './fixtures'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'
import { readFileSync } from 'node:fs'
import { unique, openFileList, deleteDocApi } from './helpers'

// #117 — the file-revision upload UI:
//   117.1  a per-file "Upload new version" action (FileExtraActions, in every file
//          row/tile) that posts through the version pipeline (previousFileId) so the
//          upload becomes v(n+1) of the same file, NOT a second attachment.
//   117.2  the manual upload bar intercepts a drop whose name matches an existing
//          active file of THIS document (case-insensitive) and offers a choice:
//          add-as-new-version (default) / keep-both / cancel, with apply-to-all for
//          multi-file drops. Non-conflicting files upload immediately.

const here = dirname(fileURLToPath(import.meta.url))
const txt = resolve(here, 'fixtures/sample.txt')
const txtV2 = resolve(here, 'fixtures/sample-v2.txt')
const png = resolve(here, 'fixtures/pixel.png')

// Seed a document with files through the REST API (the importer-style path, which is
// deliberately left untouched by the conflict UI — seeding a same-named file here
// never triggers a dialog).
async function seedDoc(
  request: APIRequestContext,
  title: string,
  files: Array<{ name: string }>,
): Promise<string> {
  const docRes = await request.put('/api/document', {
    headers: { 'content-type': 'application/x-www-form-urlencoded' },
    data: new URLSearchParams([['title', title], ['language', 'eng']]).toString(),
  })
  const id = (await docRes.json()).id as string
  for (const f of files) {
    await request.put('/api/file', {
      multipart: { id, file: { name: f.name, mimeType: 'text/plain', buffer: readFileSync(txt) } },
    })
  }
  return id
}

// Set files on the manual upload bar's hidden <input>. `named` uploads buffers with an
// explicit name so a test can force (or avoid) a name collision with a seeded file.
async function dropOnUploadBar(page: Page, named: Array<{ name: string; from: string }>) {
  await page.locator('.p-fileupload-advanced input[type="file"]').setInputFiles(
    named.map((n) => ({ name: n.name, mimeType: 'text/plain', buffer: readFileSync(n.from) })),
  )
}

// Open the version-history dialog from the file list section's action menu (first row).
async function openVersions(page: Page, rowText?: string) {
  const scope = rowText
    ? page.locator('.file-data-table tbody tr', { hasText: rowText })
    : page.locator('.file-list-section')
  await scope.getByRole('button', { name: 'Version history' }).click()
  const dialog = page.getByRole('dialog', { name: /Version history/ })
  await expect(dialog).toBeVisible()
  return dialog
}

// --- 117.1 : per-file "Upload new version" ----------------------------------------

test('uploading a new version of a file creates v2, not a second file', async ({ page, cleanup }) => {
  const id = await seedDoc(page.request, unique('rev'), [{ name: 'report.txt' }])
  cleanup.defer('purge the seeded document', () => deleteDocApi(page.request, id))

  await page.goto(`/#/document/view/${id}/content`)
  await openFileList(page)
  await expect(page.locator('.file-data-table tbody tr')).toHaveCount(1)

  // Drive the per-file "Upload new version" input (same NAME → stable row identity).
  const row = page.locator('.file-data-table tbody tr', { hasText: 'report.txt' })
  await row.locator('input.upload-version-input').setInputFiles({
    name: 'report.txt', mimeType: 'text/plain', buffer: readFileSync(txtV2),
  })
  await expect(page.getByText('New version uploaded').first()).toBeVisible()

  // It replaced the file in place: still exactly ONE row, but now with two versions.
  await expect(page.locator('.file-data-table tbody tr')).toHaveCount(1)
  const dialog = await openVersions(page)
  await expect(dialog.getByText('v2')).toBeVisible()
  await expect(dialog.getByText('v1')).toBeVisible()
})

test('a new version can be uploaded from the versions dialog', async ({ page, cleanup }) => {
  const id = await seedDoc(page.request, unique('vdlg'), [{ name: 'report.txt' }])
  cleanup.defer('purge the seeded document', () => deleteDocApi(page.request, id))

  await page.goto(`/#/document/view/${id}/content`)
  await openFileList(page)
  const dialog = await openVersions(page)
  await expect(dialog.getByText('v1')).toBeVisible()

  // Upload a new version from the dialog footer's "Upload new version" action.
  await dialog.locator('input.upload-version-input').setInputFiles({
    name: 'report.txt', mimeType: 'text/plain', buffer: readFileSync(txtV2),
  })
  await expect(page.getByText('New version uploaded').first()).toBeVisible()

  // The open dialog reloads to show v2 alongside v1.
  await expect(dialog.getByText('v2')).toBeVisible()
  await expect(dialog.getByText('v1')).toBeVisible()
})

// --- 117.2 : upload-bar name-conflict interception --------------------------------

test('a name-conflicting drop offers, and applies, "add as new version"', async ({ page, cleanup }) => {
  const id = await seedDoc(page.request, unique('conf-ver'), [{ name: 'dup.txt' }])
  cleanup.defer('purge the seeded document', () => deleteDocApi(page.request, id))

  await page.goto(`/#/document/view/${id}/content`)
  await dropOnUploadBar(page, [{ name: 'dup.txt', from: txtV2 }])

  const dialog = page.getByRole('dialog', { name: 'File already exists' })
  await expect(dialog).toBeVisible()
  await dialog.getByRole('button', { name: 'Add as new version' }).click()
  await expect(page.getByText('Files uploaded').first()).toBeVisible()

  await openFileList(page)
  await expect(page.locator('.file-data-table tbody tr')).toHaveCount(1)
  const vd = await openVersions(page)
  await expect(vd.getByText('v2')).toBeVisible()
})

test('a name-conflicting drop can be kept as a separate file', async ({ page, cleanup }) => {
  const id = await seedDoc(page.request, unique('conf-keep'), [{ name: 'dup.txt' }])
  cleanup.defer('purge the seeded document', () => deleteDocApi(page.request, id))

  await page.goto(`/#/document/view/${id}/content`)
  await dropOnUploadBar(page, [{ name: 'dup.txt', from: txtV2 }])

  const dialog = page.getByRole('dialog', { name: 'File already exists' })
  await expect(dialog).toBeVisible()
  await dialog.getByRole('button', { name: 'Keep both' }).click()
  await expect(page.getByText('Files uploaded').first()).toBeVisible()

  await openFileList(page)
  // Two separate files now carry the same name (no version chain).
  await expect(page.locator('.file-data-table .file-name-text', { hasText: 'dup.txt' })).toHaveCount(2)
})

test('a name-conflicting drop can be cancelled, leaving the file untouched', async ({ page, cleanup }) => {
  const id = await seedDoc(page.request, unique('conf-cancel'), [{ name: 'dup.txt' }])
  cleanup.defer('purge the seeded document', () => deleteDocApi(page.request, id))

  await page.goto(`/#/document/view/${id}/content`)
  await dropOnUploadBar(page, [{ name: 'dup.txt', from: txtV2 }])

  const dialog = page.getByRole('dialog', { name: 'File already exists' })
  await expect(dialog).toBeVisible()
  await dialog.getByRole('button', { name: 'Cancel' }).click()
  await expect(dialog).toBeHidden()

  await openFileList(page)
  await expect(page.locator('.file-data-table tbody tr')).toHaveCount(1)
  const vd = await openVersions(page)
  await expect(vd.getByText('v1')).toBeVisible()
  await expect(vd.getByText('v2')).toHaveCount(0)
})

test('a mixed multi-drop uploads fresh files and applies one choice to every conflict', async ({ page, cleanup }) => {
  const id = await seedDoc(page.request, unique('conf-multi'), [{ name: 'a.txt' }, { name: 'b.txt' }])
  cleanup.defer('purge the seeded document', () => deleteDocApi(page.request, id))

  await page.goto(`/#/document/view/${id}/content`)
  // a.txt + b.txt collide with the seeded files; c.txt is fresh.
  await dropOnUploadBar(page, [
    { name: 'a.txt', from: txtV2 },
    { name: 'b.txt', from: txtV2 },
    { name: 'c.txt', from: txt },
  ])

  // One dialog for the first conflict, with an apply-to-all option (2 conflicts).
  const dialog = page.getByRole('dialog', { name: 'File already exists' })
  await expect(dialog).toBeVisible()
  await dialog.getByLabel(/Apply to all/).check()
  await dialog.getByRole('button', { name: 'Add as new version' }).click()
  await expect(dialog).toBeHidden()

  await openFileList(page)
  // Three rows: a + b (each now a version chain) and the fresh c.
  await expect(page.locator('.file-data-table tbody tr')).toHaveCount(3)
  const vd = await openVersions(page, 'a.txt')
  await expect(vd.getByText('v2')).toBeVisible()
})

test('a second drop is blocked while a conflict is being resolved; the first batch still completes', async ({ page, cleanup }) => {
  const id = await seedDoc(page.request, unique('reentry'), [{ name: 'dup.txt' }])
  cleanup.defer('purge the seeded document', () => deleteDocApi(page.request, id))

  await page.goto(`/#/document/view/${id}/content`)
  await dropOnUploadBar(page, [{ name: 'dup.txt', from: txtV2 }])

  const dialog = page.getByRole('dialog', { name: 'File already exists' })
  await expect(dialog).toBeVisible()
  // The upload bar is disabled WHILE the conflict is being resolved, so a second drop
  // cannot start a batch that would clobber the single pending resolver.
  await expect(page.locator('.p-fileupload-advanced input[type="file"]')).toBeDisabled()

  // Resolve the first batch — it completes correctly and the bar re-enables.
  await dialog.getByRole('button', { name: 'Add as new version' }).click()
  await expect(page.getByText('Files uploaded').first()).toBeVisible()
  await expect(page.locator('.p-fileupload-advanced input[type="file"]')).toBeEnabled()

  await openFileList(page)
  await expect(page.locator('.file-data-table tbody tr')).toHaveCount(1)
  const vd = await openVersions(page)
  await expect(vd.getByText('v2')).toBeVisible()
})

test('a camera capture uploads immediately with no conflict dialog, even on a name match', async ({ page, cleanup }) => {
  const id = await seedDoc(page.request, unique('cam'), [{ name: 'shot.png' }])
  cleanup.defer('purge the seeded document', () => deleteDocApi(page.request, id))

  await page.goto(`/#/document/view/${id}/content`)
  // Capture a photo whose name collides with the existing file. Camera capture
  // bypasses the manual-upload-bar conflict prompt entirely.
  await page.locator('input.camera-input').setInputFiles({
    name: 'shot.png', mimeType: 'image/png', buffer: readFileSync(png),
  })
  await expect(page.getByText('Files uploaded').first()).toBeVisible()
  await expect(page.getByRole('dialog', { name: 'File already exists' })).toHaveCount(0)

  await openFileList(page)
  // Kept as a SEPARATE second file (no version chain) — the prompt was skipped.
  await expect(page.locator('.file-data-table .file-name-text', { hasText: 'shot.png' })).toHaveCount(2)
})

test('a version upload against a stale base surfaces the reload path', async ({ page, cleanup }) => {
  // Seed one file, then replace it OUT OF BAND via the API so the page's cached file
  // id becomes a stale version base. A new-version upload against that id loses the
  // compare-and-swap (HTTP 409) and must surface "the file changed, reload".
  const id = await seedDoc(page.request, unique('stale'), [{ name: 'race.txt' }])
  cleanup.defer('purge the seeded document', () => deleteDocApi(page.request, id))

  await page.goto(`/#/document/view/${id}/content`)
  await openFileList(page)
  const row = page.locator('.file-data-table tbody tr', { hasText: 'race.txt' })
  await expect(row).toHaveCount(1)

  // Read the current latest file id the page is holding, then supersede it via the
  // API so the page's id is no longer the chain head.
  const listRes = await page.request.get(`/api/file/list?id=${id}`)
  const staleId = (await listRes.json()).files[0].id as string
  await page.request.put('/api/file', {
    multipart: {
      id,
      previousFileId: staleId,
      file: { name: 'race.txt', mimeType: 'text/plain', buffer: readFileSync(txtV2) },
    },
  })

  // Now the page still thinks `staleId` is current; uploading a new version against
  // it hits the 409 stale-base guard.
  await row.locator('input.upload-version-input').setInputFiles({
    name: 'race.txt', mimeType: 'text/plain', buffer: readFileSync(txt),
  })
  await expect(page.getByText(/reload/i).first()).toBeVisible()
})
