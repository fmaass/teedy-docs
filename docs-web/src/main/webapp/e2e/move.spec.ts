import { test, expect, type APIRequestContext, type Page } from './fixtures'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'
import { readFileSync } from 'node:fs'
import { unique, confirmDanger, openFileList } from './helpers'

// #175 — move a file to another document. The moved file leaves the source's file list and appears in
// the target; a source cover that pointed at the moved file falls back, and a previously empty target
// gains the moved file as its served thumbnail.

const here = dirname(fileURLToPath(import.meta.url))
const png = resolve(here, 'fixtures/pixel.png')

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

async function fileIds(request: APIRequestContext, documentId: string): Promise<Record<string, string>> {
  const res = await request.get(`/api/file/list?id=${documentId}`)
  const files = (await res.json()).files as Array<{ id: string; name: string }>
  return Object.fromEntries(files.map((f) => [f.name, f.id]))
}

async function fileCount(request: APIRequestContext, documentId: string): Promise<number> {
  const res = await request.get(`/api/file/list?id=${documentId}`)
  return ((await res.json()).files as unknown[]).length
}

async function servedFileId(request: APIRequestContext, documentId: string): Promise<string | null> {
  const res = await request.get(`/api/document/${documentId}`)
  return (await res.json()).file_id as string | null
}

async function coverFileId(request: APIRequestContext, documentId: string): Promise<string | null> {
  const res = await request.get(`/api/document/${documentId}`)
  return (await res.json()).file_id_cover as string | null
}

async function deleteDoc(page: Page, id: string) {
  await page.goto(`/#/document/view/${id}`)
  const del = page.getByRole('button', { name: 'Delete', exact: true })
  if (await del.isVisible().catch(() => false)) {
    await del.click()
    await confirmDanger(page)
  }
}

test('move a file to another document: it leaves the source, the source cover falls back, and the empty target gains it', async ({ page }) => {
  const targetTitle = unique('move-target')
  const sourceId = await seedDoc(page.request, unique('move-source'), [
    { name: 'moved.png', mimeType: 'image/png', path: png },
    { name: 'stays.png', mimeType: 'image/png', path: png },
  ])
  const targetId = await seedDoc(page.request, targetTitle, [])
  try {
    const ids = await fileIds(page.request, sourceId)

    // Pin the file we are about to move as the source's explicit cover, so the move must fall the cover back.
    await page.goto(`/#/document/view/${sourceId}/content`)
    await openFileList(page)
    await page.locator('.file-data-table tbody tr', { hasText: 'moved.png' })
      .getByRole('button', { name: 'Set as cover' }).click()
    await expect.poll(() => coverFileId(page.request, sourceId)).toBe(ids['moved.png'])

    // Move it via the file-list action + document picker.
    const movedRow = page.locator('.file-data-table tbody tr', { hasText: 'moved.png' })
    await movedRow.getByRole('button', { name: /Move to document/ }).click()
    const dialog = page.getByRole('dialog', { name: /Move to document/ })
    await dialog.getByRole('combobox').fill(targetTitle)
    await page.getByRole('option', { name: targetTitle }).click()
    await dialog.getByRole('button', { name: 'Move', exact: true }).click()

    // A successful move dismisses the picker. Assert that here rather than moving straight on
    // to the API checks: those read the server directly and settle while the dialog may still
    // be up, and an undismissed modal's mask blocks every later click on the page.
    await expect(dialog).toBeHidden()

    // The moved file is gone from the source and present in the target.
    await expect.poll(() => fileCount(page.request, sourceId)).toBe(1)
    await expect.poll(() => fileCount(page.request, targetId)).toBe(1)
    await expect.poll(async () => (await fileIds(page.request, targetId))['moved.png']).toBe(ids['moved.png'])

    // The source's dangling cover is cleared and falls back to the remaining file; the empty target gains
    // the moved file as its served thumbnail.
    await expect.poll(() => coverFileId(page.request, sourceId)).toBeNull()
    await expect.poll(() => servedFileId(page.request, sourceId)).toBe(ids['stays.png'])
    await expect.poll(() => servedFileId(page.request, targetId)).toBe(ids['moved.png'])
  } finally {
    await deleteDoc(page, sourceId)
    await deleteDoc(page, targetId)
  }
})
