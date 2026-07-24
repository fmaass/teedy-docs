import { test, expect, type APIRequestContext, type Page } from './fixtures'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'
import { readFileSync } from 'node:fs'
import { unique, confirmDanger, openFileList } from './helpers'

// #174 — explicit per-document cover. Picking a non-first file as the cover overrides the default
// first-file-by-order thumbnail the list/gallery render; clearing restores it.

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

test('set a non-first file as cover: the badge appears and the gallery/table renders the chosen cover', async ({ page }) => {
  const id = await seedDoc(page.request, unique('cover'), [
    { name: 'first.png', mimeType: 'image/png', path: png },
    { name: 'second.png', mimeType: 'image/png', path: png },
  ])
  try {
    const ids = await fileIds(page.request, id)
    expect(await coverFileId(page.request, id)).toBeNull()
    await expect.poll(() => servedFileId(page.request, id)).toBe(ids['first.png'])

    await page.goto(`/#/document/view/${id}/content`)
    await openFileList(page)

    const secondRow = page.locator('.file-data-table tbody tr', { hasText: 'second.png' })
    const firstRow = page.locator('.file-data-table tbody tr', { hasText: 'first.png' })
    await expect(secondRow.locator('.cover-badge')).toHaveCount(0)

    await secondRow.getByRole('button', { name: 'Set as cover' }).click()
    await expect(secondRow.locator('.cover-badge')).toBeVisible()
    await expect(firstRow.locator('.cover-badge')).toHaveCount(0)
    await expect.poll(() => coverFileId(page.request, id)).toBe(ids['second.png'])
    await expect.poll(() => servedFileId(page.request, id)).toBe(ids['second.png'])

    await page.goto('/#/document')
    await expect(page.locator(`img[src*="/api/file/${ids['second.png']}/data"]`).first()).toBeVisible()
    await expect(page.locator(`img[src*="/api/file/${ids['first.png']}/data"]`)).toHaveCount(0)

    await page.goto(`/#/document/view/${id}/content`)
    await openFileList(page)
    await page.locator('.file-data-table tbody tr', { hasText: 'second.png' })
      .getByRole('button', { name: 'Remove as cover' }).click()
    await expect.poll(() => coverFileId(page.request, id)).toBeNull()
    await expect.poll(() => servedFileId(page.request, id)).toBe(ids['first.png'])

    await page.goto('/#/document')
    await expect(page.locator(`img[src*="/api/file/${ids['first.png']}/data"]`).first()).toBeVisible()
  } finally {
    await deleteDoc(page, id)
  }
})
