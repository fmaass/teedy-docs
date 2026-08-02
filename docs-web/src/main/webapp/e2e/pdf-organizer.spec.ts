import { test, expect, type APIRequestContext } from './fixtures'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'
import { readFileSync } from 'node:fs'
import {
  unique,
  login,
  openFileList,
  deleteDocApi,
  deleteUserApi,
  ROUTE_ROOT,
  gotoRouteReady,
} from './helpers'

// #73 — the PDF page organizer: an "Edit pages" launcher in the #58 file panel that opens a
// client-rendered (pdf.js) thumbnail grid; reorder / rotate / delete pages, then save as a new
// version by posting the v1 page-operations manifest with the expected base version. Runs under both
// the desktop and mobile Playwright projects.

const here = dirname(fileURLToPath(import.meta.url))
const pdf = resolve(here, 'fixtures/multipage.pdf')
const txt = resolve(here, 'fixtures/sample.txt')

async function seedDoc(
  request: APIRequestContext,
  title: string,
  files: Array<{ name: string; mimeType: string; path: string }>,
): Promise<string> {
  const docRes = await request.put('/api/document', {
    headers: { 'content-type': 'application/x-www-form-urlencoded' },
    data: new URLSearchParams([['title', title], ['language', 'eng']]).toString(),
  })
  const id = (await docRes.json()).id as string
  for (const f of files) {
    await request.put('/api/file', {
      multipart: { id, file: { name: f.name, mimeType: f.mimeType, buffer: readFileSync(f.path) } },
    })
  }
  return id
}

const pdfFile = { name: 'multipage.pdf', mimeType: 'application/pdf', path: pdf }
const txtFile = { name: 'notes.txt', mimeType: 'text/plain', path: txt }

test('Edit pages launches a populated organizer for a PDF; a non-PDF file has none', async ({ page, cleanup }) => {
  const id = await seedDoc(page.request, unique('org'), [pdfFile, txtFile])
  cleanup.defer('purge the seeded document', () => deleteDocApi(page.request, id))

  await gotoRouteReady(page, `/#/document/view/${id}/content`, ROUTE_ROOT.documentContent)
  await openFileList(page)

  // Exactly one "Edit pages" control: the PDF row has it, the .txt row does not.
  const editPages = page.getByRole('button', { name: 'Edit pages' })
  await expect(editPages).toHaveCount(1)
  await expect(
    page.getByRole('row', { name: /notes\.txt/ }).getByRole('button', { name: 'Edit pages' }),
  ).toHaveCount(0)

  await editPages.scrollIntoViewIfNeeded()
  await editPages.click()

  // The organizer opens and renders one card per source page (the 3-page fixture).
  const dialog = page.getByRole('dialog', { name: /Edit pages/ })
  await expect(dialog).toBeVisible()
  await expect(dialog.locator('.pdf-page-card')).toHaveCount(3)
  // Populated: pdf.js actually rasterized the pages into canvases.
  await expect(dialog.locator('.pdf-page-card canvas').first()).toBeVisible()
})

test('reorder + rotate + delete saves a new version and preserves the original', async ({ page, cleanup }) => {
  const id = await seedDoc(page.request, unique('org-save'), [pdfFile])
  cleanup.defer('purge the seeded document', () => deleteDocApi(page.request, id))

  await gotoRouteReady(page, `/#/document/view/${id}/content`, ROUTE_ROOT.documentContent)
  await openFileList(page)

  // Capture the manifest the client posts, then let the REAL backend create the new version.
  let manifestJson: string | null = null
  await page.route('**/api/file/*/pages', async (route) => {
    manifestJson = new URLSearchParams(route.request().postData() ?? '').get('manifest')
    await route.continue()
  })

  const edit = page.getByRole('button', { name: 'Edit pages' })
  await edit.scrollIntoViewIfNeeded()
  await edit.click()
  const dialog = page.getByRole('dialog', { name: /Edit pages/ })
  await expect(dialog.locator('.pdf-page-card')).toHaveCount(3)

  // Rotate page 1, delete page 3, then move page 1 after page 2 — all via keyboard-operable
  // (labelled) controls, never drag.
  await dialog.getByRole('button', { name: 'Rotate page 1 right' }).click()
  await dialog.getByRole('button', { name: 'Remove page 3' }).click()
  await expect(dialog.locator('.pdf-page-card')).toHaveCount(2)
  await dialog.getByRole('button', { name: 'Move page 1 later' }).click()

  await dialog.locator('[data-test="organizer-save"]').click()
  await expect(page.getByText('Pages saved as a new version')).toBeVisible()

  // The client built the manifest from the operations: order [source 1, source 0], the deleted
  // page absent, base version = 0. Rotation is ABSOLUTE: source 0 (0°-intrinsic) rotated once → 90;
  // source 1 keeps its intrinsic 90° (untouched) — proving the previewed angle is what is posted.
  expect(manifestJson).not.toBeNull()
  const manifest = JSON.parse(manifestJson as unknown as string)
  expect(manifest).toEqual({
    version: 1,
    baseVersion: 0,
    pages: [
      { source: 1, rotate: 90 },
      { source: 0, rotate: 90 },
    ],
  })

  // The save produced a real new version; the original is preserved in history (v1 + v2).
  await page.reload()
  await openFileList(page)
  await page.getByRole('button', { name: 'Version history' }).click()
  const versions = page.getByRole('dialog', { name: /Version history/ })
  await expect(versions.getByText('v1')).toBeVisible()
  await expect(versions.getByText('v2')).toBeVisible()
})

test('a stale base is surfaced distinctly and keeps the organizer open', async ({ page, cleanup }) => {
  const id = await seedDoc(page.request, unique('org-stale'), [pdfFile])
  cleanup.defer('purge the seeded document', () => deleteDocApi(page.request, id))

  await gotoRouteReady(page, `/#/document/view/${id}/content`, ROUTE_ROOT.documentContent)
  await openFileList(page)

  // Force the version-chain conflict the backend returns when the base is no longer current.
  await page.route('**/api/file/*/pages', (route) =>
    route.fulfill({
      status: 409,
      contentType: 'application/json',
      body: JSON.stringify({ type: 'VersionConflict', message: 'stale' }),
    }),
  )

  const edit = page.getByRole('button', { name: 'Edit pages' })
  await edit.scrollIntoViewIfNeeded()
  await edit.click()
  const dialog = page.getByRole('dialog', { name: /Edit pages/ })
  await expect(dialog.locator('.pdf-page-card')).toHaveCount(3)

  await dialog.getByRole('button', { name: 'Rotate page 1 right' }).click()
  await dialog.locator('[data-test="organizer-save"]').click()

  // The stale conflict is surfaced with its own message and the dialog stays open to retry.
  await expect(dialog.locator('[data-test="organizer-error"]')).toContainText(
    'changed since you opened it',
  )
  await expect(dialog.locator('.pdf-page-card')).toHaveCount(3)
})

test('a read-only viewer has no Edit pages control', async ({ page, browser, cleanup }) => {
  const username = unique('rouser').replace(/[^a-z0-9]/gi, '').toLowerCase()

  // A second user granted READ on a document that has a PDF.
  await gotoRouteReady(page, '/#/settings/users', ROUTE_ROOT.settingsUsers)
  await page.getByRole('button', { name: 'Add user' }).click()
  const addDialog = page.getByRole('dialog', { name: 'Add user' })
  await addDialog.locator('#add-user-name').fill(username)
  await addDialog.locator('#add-user-email').fill(`${username}@example.com`)
  await addDialog.locator('#add-user-pass').fill('Password1e2e')
  await addDialog.getByRole('button', { name: 'Create' }).click()
  await expect(page.getByText('User created')).toBeVisible()
  // `page` is the admin storageState context (the read-only viewer gets its own context
  // below), so this is the admin request context the user delete requires.
  cleanup.defer('delete the read-only viewer account', () => deleteUserApi(page.request, username))

  const id = await seedDoc(page.request, unique('ro-pdf'), [pdfFile])
  cleanup.defer('purge the seeded document', () => deleteDocApi(page.request, id))
  await gotoRouteReady(page, `/#/document/view/${id}/permissions`, ROUTE_ROOT.documentPermissions)
  const addForm = page.locator('.add-acl-form', { hasText: 'Add permission' })
  await addForm.locator('input').first().fill(username)
  await page.getByRole('option', { name: new RegExp(username) }).click()
  await addForm.getByRole('button', { name: 'Add', exact: true }).click()
  await expect(page.getByText('Permission added')).toBeVisible()

  const ctx = await browser.newContext({ storageState: { cookies: [], origins: [] } })
  const viewer = await ctx.newPage()
  await login(viewer, username, 'Password1e2e')
  await gotoRouteReady(viewer, `/#/document/view/${id}/content`, ROUTE_ROOT.documentContent)
  await openFileList(viewer)

  // Version history (read-only affordance) is present, but Edit pages (a write action) is not.
  await expect(viewer.getByRole('button', { name: 'Version history' })).toBeVisible()
  await expect(viewer.getByRole('button', { name: 'Edit pages' })).toHaveCount(0)

  await ctx.close()
})
