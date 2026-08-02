import { test, expect, type APIRequestContext } from './fixtures'
import {
  unique,
  createDocument,
  confirmDanger,
  deleteDocApi,
  ROUTE_ROOT,
  gotoRouteReady,
} from './helpers'

// Delete every remaining entry of a vocabulary namespace via the admin API. A
// vocabulary ceases to exist once it has no entries, so this fully removes `name`.
// Idempotent: no entries -> nothing to do.
async function purgeVocabulary(request: APIRequestContext, name: string): Promise<void> {
  const res = await request.get(`/api/vocabulary/${name}`)
  if (!res.ok()) return
  const entries = (await res.json()).entries as Array<{ id: string }>
  for (const entry of entries ?? []) {
    await request.delete(`/api/vocabulary/${entry.id}`).catch(() => {})
  }
}

// Vocabulary CRUD (SettingsVocabulary) + its consumer surface on a document.
//
// Part 1 proves the admin vocabulary editor: create a brand-new namespace (a
// vocabulary exists only while it has >=1 entry, so "create" = first entry under a
// new name), add a second entry, rename an entry inline, reorder the two, then delete
// them (deleting the last entry removes the whole namespace).
//
// Part 2 proves the vocabulary actually BACKS a document dropdown: a value added to
// the built-in `type` namespace shows up as an option in the document editor's Type
// Select (#edit-type, options = vocabularyOptionsFor('type')). The added value is
// removed again in teardown so the built-in namespace is left as found.

test('admin vocabulary CRUD: create namespace, entries, rename, reorder, delete', async ({ page, request, cleanup }) => {
  // A namespace name must match ^[a-z0-9-]+$.
  const ns = unique('voc').replace(/[^a-z0-9]/gi, '-').toLowerCase()
  const first = `first-${Date.now()}`
  const second = `second-${Date.now()}`
  const firstRenamed = `${first}-edited`

  // Failure-safe purge: if the test threw before deleting its entries, the namespace
  // would leak. Delete any remaining entries of `ns` via the admin API (idempotent —
  // a clean pass leaves nothing to delete). Registered up front, keyed only on `ns`, so
  // it covers a failure at any point in the body.
  cleanup.defer('purge any surviving entries of the test vocabulary', () => purgeVocabulary(request, ns))

  await gotoRouteReady(page, '/#/settings/vocabulary', ROUTE_ROOT.settingsVocabulary)
  await expect(page.getByRole('heading', { name: 'Vocabularies' })).toBeVisible()

  // --- Create a new vocabulary namespace with its first entry ---
  await page.getByRole('button', { name: 'New vocabulary' }).click()
  const newDialog = page.getByRole('dialog', { name: 'Create vocabulary' })
  await newDialog.locator('#new-vocabulary-name').fill(ns)
  await newDialog.locator('#new-vocabulary-value').fill(first)
  await newDialog.getByRole('button', { name: 'Create', exact: true }).click()
  await expect(page.getByText('Vocabulary created')).toBeVisible()

  // The picker now focuses the new namespace and lists its first entry.
  await expect(page.getByRole('cell', { name: first })).toBeVisible()

  // --- Add a second entry ---
  await page.getByRole('button', { name: 'Add entry' }).click()
  const addDialog = page.getByRole('dialog', { name: 'Add vocabulary entry' })
  await addDialog.locator('#vocabulary-value').fill(second)
  await addDialog.getByRole('button', { name: 'Add', exact: true }).click()
  await expect(page.getByText('Entry added')).toBeVisible()
  await expect(page.getByRole('cell', { name: second })).toBeVisible()

  // --- Rename the first entry inline ---
  const firstRow = page.getByRole('row', { name: new RegExp(first) })
  await firstRow.getByRole('button', { name: 'Rename' }).click()
  await firstRow.locator('input').fill(firstRenamed)
  await firstRow.getByRole('button', { name: 'Confirm rename' }).click()
  await expect(page.getByText('Entry updated')).toBeVisible()
  await expect(page.getByRole('cell', { name: firstRenamed, exact: true })).toBeVisible()

  // --- Reorder: move the second entry up so it precedes the first ---
  const rows = page.locator('.vocabulary-table tbody tr')
  await expect(rows.nth(0)).toContainText(firstRenamed)
  await rows.nth(1).getByRole('button', { name: 'Move up' }).click()
  await expect(rows.nth(0)).toContainText(second)
  await expect(rows.nth(1)).toContainText(firstRenamed)

  // --- Delete both entries; deleting the last one removes the namespace ---
  // (Assert on the row disappearing rather than the toast — success toasts stack.)
  await page.getByRole('row', { name: new RegExp(second) }).getByRole('button', { name: 'Delete vocabulary entry' }).click()
  await confirmDanger(page)
  await expect(page.getByRole('cell', { name: second })).toHaveCount(0)

  await page.getByRole('row', { name: new RegExp(firstRenamed) }).getByRole('button', { name: 'Delete vocabulary entry' }).click()
  await confirmDanger(page)
  await expect(page.getByRole('cell', { name: firstRenamed, exact: true })).toHaveCount(0)

  // The namespace no longer appears in the picker options (it had no more entries).
  await page.reload()
  await page.locator('#vocabulary-name').click()
  await expect(page.getByRole('option', { name: ns, exact: true })).toHaveCount(0)
})

test('deleting a referenced vocabulary entry warns with the usage count and proceeds on confirm', async ({ page, request, cleanup }) => {
  // A namespace name must match ^[a-z0-9-]+$.
  const ns = unique('vocref').replace(/[^a-z0-9]/gi, '-').toLowerCase()
  const value = `ref-${Date.now()}`

  // Teardown of the vocabulary namespace is keyed only on `ns` and is idempotent, so it is
  // registered up front — the entry created on the very next lines is then covered whatever
  // the body does. Metadata and document teardown follow their own creation below.
  cleanup.defer('purge any surviving entries of the test vocabulary', () => purgeVocabulary(request, ns))

  // --- Seed a referenced value entirely via the admin API ---
  // 1) Create the vocabulary entry (a namespace with one value).
  const vocRes = await request.put('/api/vocabulary', {
    form: { name: ns, value, order: '0' },
  })
  expect(vocRes.ok()).toBeTruthy()
  const entryId = (await vocRes.json()).id as string

  // 2) A VOCABULARY metadata field referencing that namespace.
  const metaRes = await request.put('/api/metadata', {
    form: { name: `${ns}-field`, type: 'VOCABULARY', vocabulary: ns },
  })
  expect(metaRes.ok()).toBeTruthy()
  const metadataId = (await metaRes.json()).id as string
  cleanup.defer('delete the seeded metadata field', () => request.delete(`/api/metadata/${metadataId}`))

  // 3) A document carrying that value under the field — this is the reference.
  const docRes = await request.put('/api/document', {
    form: { title: unique('vocref-doc'), language: 'eng', metadata_id: metadataId, metadata_value: value },
  })
  expect(docRes.ok()).toBeTruthy()
  const docId = (await docRes.json()).id as string
  cleanup.defer('purge the seeded document', () => deleteDocApi(page.request, docId))

  // --- Delete the entry through the admin UI; the confirm must carry the count ---
  await gotoRouteReady(page, '/#/settings/vocabulary', ROUTE_ROOT.settingsVocabulary)
  await page.locator('#vocabulary-name').click()
  await page.getByRole('option', { name: ns, exact: true }).click()

  await page.getByRole('row', { name: new RegExp(value) })
    .getByRole('button', { name: 'Delete vocabulary entry' }).click()

  // The reference-count confirm dialog names the value and the (single) document.
  const dialog = page.getByRole('alertdialog')
  await expect(dialog).toBeVisible()
  await expect(dialog).toContainText(value)
  await expect(dialog).toContainText('1 document')

  // Proceeds only on confirm.
  await dialog.getByRole('button', { name: 'Yes' }).click()
  await expect(dialog).toBeHidden()
  await expect(page.getByText('Entry deleted')).toBeVisible()
  await expect(page.getByRole('cell', { name: value, exact: true })).toHaveCount(0)
})

test('a vocabulary value backs the document Type dropdown', async ({ page, cleanup }) => {
  // Add a unique value to the built-in `type` namespace, then confirm the document
  // editor's Type Select offers it as an option. Cleaned up afterwards.
  const typeValue = `e2e-type-${Date.now()}`

  await gotoRouteReady(page, '/#/settings/vocabulary', ROUTE_ROOT.settingsVocabulary)
  await page.locator('#vocabulary-name').click()
  await page.getByRole('option', { name: 'type', exact: true }).click()

  await page.getByRole('button', { name: 'Add entry' }).click()
  const addDialog = page.getByRole('dialog', { name: 'Add vocabulary entry' })
  await addDialog.locator('#vocabulary-value').fill(typeValue)
  await addDialog.getByRole('button', { name: 'Add', exact: true }).click()
  await expect(page.getByText('Entry added')).toBeVisible()

  // Cleanup the vocabulary value (leave `type` as found). Registered as soon as the value
  // exists — a failure between here and the document seed would otherwise leak it into the
  // built-in namespace permanently. There is no API teardown path for a single entry of a
  // built-in namespace, so this stays the admin UI the test itself drives.
  cleanup.defer('remove the added `type` vocabulary value', async () => {
    await gotoRouteReady(page, '/#/settings/vocabulary', ROUTE_ROOT.settingsVocabulary)
    await page.locator('#vocabulary-name').click()
    await page.getByRole('option', { name: 'type', exact: true }).click()
    const row = page.getByRole('row', { name: new RegExp(typeValue) })
    if (await row.isVisible().catch(() => false)) {
      await row.getByRole('button', { name: 'Delete vocabulary entry' }).click()
      await confirmDanger(page)
    }
  })

  // Open a fresh document editor and assert the Type Select surfaces the new value.
  const { id } = await createDocument(page, unique('voc-doc'))
  cleanup.defer('delete the seeded document', async () => {
    // Go through the readiness barrier, on the post-redirect URL. A bare goto here would leave the
    // Delete probe racing the route transition: `isVisible()` reads ONCE, so on a slow transition
    // it reads the PREVIOUS route's DOM, answers false, and the document leaks silently.
    //
    // A readiness failure is VERIFIED before it is tolerated, never assumed. The one acceptable
    // cause is a document the body already deleted, and the API is authoritative for that:
    // `GET /api/document/:id` answers 200 while the document is alive and 404 once it is trashed,
    // purged or absent (measured; same read-back deleteDocApi uses before accepting a failed
    // delete). Absent -> return quietly. Present -> rethrow, so a lost route or a view that never
    // mounts stays a LOUD cleanup failure instead of being filed as "already gone".
    try {
      await gotoRouteReady(page, `/#/document/view/${id}/content`, ROUTE_ROOT.documentContent)
    } catch (readyError: unknown) {
      const readBack = await page.request.get(`/api/document/${id}`)
      if (!readBack.ok()) return
      const detail = readyError instanceof Error ? (readyError.stack ?? readyError.message) : String(readyError)
      throw new Error(
        `teardown: the document view never became ready for ${id}, and GET /api/document/${id} ` +
          `answered ${readBack.status()} — the document is still there, so this is a real ` +
          `readiness failure, not a document the body had already deleted.\n${detail}`,
      )
    }
    await page.getByRole('button', { name: 'Delete', exact: true }).click()
    await confirmDanger(page)
  })

  await gotoRouteReady(page, `/#/document/edit/${id}`, ROUTE_ROOT.documentEdit)
  await expect(page.locator('#edit-title')).toBeVisible()
  // The Type Select lives in the collapsible "Additional metadata" section.
  await page.getByRole('button', { name: 'Additional metadata' }).click()
  await expect(page.locator('#edit-type')).toBeVisible()
  await page.locator('#edit-type').click()
  await expect(page.getByRole('option', { name: typeValue, exact: true })).toBeVisible()
  // Select it so we exercise the real bind, then close the overlay.
  await page.getByRole('option', { name: typeValue, exact: true }).click()
  await expect(page.locator('#edit-type')).toContainText(typeValue)
})
