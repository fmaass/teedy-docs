import { test, expect, type APIRequestContext } from './fixtures'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'
import { readFileSync } from 'node:fs'
import { unique } from './helpers'

// #89 — surface the three EXISTING download/export capabilities on the UI:
//   89a  the per-document header Download button links to the whole-document ZIP
//        (GET /api/file/zip?id=…) when the document has more than one file; a
//        single-file document keeps the direct file download.
//   89b  a bulk "Download ZIP" action over a multi-selection zips the UNION of every
//        selected document's files (client-side union → POST /api/file/zip), not just
//        each document's main file.
//   89c  a Settings › Account "Export my documents" button downloads the account ZIP
//        (GET /api/document/export) — an export (manifest + files), never a backup.

const here = dirname(fileURLToPath(import.meta.url))
const txt = resolve(here, 'fixtures/sample.txt')
const png = resolve(here, 'fixtures/pixel.png')

/**
 * Read a ZIP's authoritative entry count from its End-Of-Central-Directory record
 * (signature PK\x05\x06; the "total entries" field is 2 bytes LE at offset +10).
 * Dependency-free and exact — it does not guess by scanning for local headers.
 */
function zipTotalEntries(buf: Buffer): number {
  const sig = 0x06054b50
  for (let i = buf.length - 22; i >= 0; i--) {
    if (buf.readUInt32LE(i) === sig) {
      return buf.readUInt16LE(i + 10)
    }
  }
  throw new Error('buffer is not a ZIP (no End-Of-Central-Directory record)')
}

async function seedDoc(
  request: APIRequestContext,
  title: string,
  files: Array<{ name: string; mimeType: string; path: string }>,
): Promise<{ id: string; fileIds: string[] }> {
  const docRes = await request.put('/api/document', {
    headers: { 'content-type': 'application/x-www-form-urlencoded' },
    data: new URLSearchParams([['title', title], ['language', 'eng']]).toString(),
  })
  const id = (await docRes.json()).id as string
  const fileIds: string[] = []
  for (const f of files) {
    const res = await request.put('/api/file', {
      multipart: { id, file: { name: f.name, mimeType: f.mimeType, buffer: readFileSync(f.path) } },
    })
    fileIds.push((await res.json()).id as string)
  }
  return { id, fileIds }
}

async function trashDoc(request: APIRequestContext, id: string) {
  await request.delete(`/api/document/${id}`).catch(() => {})
}

test('89a: multi-file document Download links to the whole-document ZIP', async ({ page }) => {
  const title = unique('dl-multi')
  const { id } = await seedDoc(page.request, title, [
    { name: 'photo.png', mimeType: 'image/png', path: png },
    { name: 'notes.txt', mimeType: 'text/plain', path: txt },
  ])
  try {
    await page.goto(`/#/document/view/${id}`)
    await expect(page.getByRole('heading', { name: title })).toBeVisible()

    // The header Download affordance points at the document ZIP endpoint (all files),
    // NOT a single file's data URL.
    const dl = page.locator('.doc-header-actions a[href*="api/file"]')
    await expect(dl).toBeVisible()
    const href = await dl.getAttribute('href')
    expect(href).toContain(`api/file/zip?id=${id}`)

    // Clicking it downloads a real ZIP that contains BOTH files.
    const [download] = await Promise.all([page.waitForEvent('download'), dl.click()])
    expect(download.suggestedFilename()).toMatch(/\.zip$/)
    const buf = readFileSync(await download.path())
    expect(zipTotalEntries(buf)).toBe(2)
  } finally {
    await trashDoc(page.request, id)
  }
})

test('89a: single-file document keeps the direct file download (unchanged)', async ({ page }) => {
  const title = unique('dl-single')
  const { id, fileIds } = await seedDoc(page.request, title, [
    { name: 'notes.txt', mimeType: 'text/plain', path: txt },
  ])
  try {
    await page.goto(`/#/document/view/${id}`)
    await expect(page.getByRole('heading', { name: title })).toBeVisible()

    const dl = page.locator('.doc-header-actions a[href*="api/file"]')
    await expect(dl).toBeVisible()
    const href = await dl.getAttribute('href')
    // Direct single-file download, NOT the ZIP endpoint.
    expect(href).toContain(`api/file/${fileIds[0]}/data`)
    expect(href).not.toContain('zip')
  } finally {
    await trashDoc(page.request, id)
  }
})

test('89b: bulk Download ZIP zips the union of every selected document\'s files', async ({ page }) => {
  // Doc A carries TWO files, doc B one — a correct union is 3 entries. Zipping only the
  // main file of each would give 2, so the count proves the per-file union, not main-only.
  const titleA = unique('bulk-dl-a')
  const titleB = unique('bulk-dl-b')
  const a = await seedDoc(page.request, titleA, [
    { name: 'a1.png', mimeType: 'image/png', path: png },
    { name: 'a2.txt', mimeType: 'text/plain', path: txt },
  ])
  const b = await seedDoc(page.request, titleB, [
    { name: 'b1.txt', mimeType: 'text/plain', path: txt },
  ])
  try {
    await page.goto('/#/document')
    await expect(page.getByRole('row', { name: new RegExp(titleA) })).toBeVisible()
    await expect(page.getByRole('row', { name: new RegExp(titleB) })).toBeVisible()

    await page.getByRole('row', { name: new RegExp(titleA) }).getByRole('checkbox').check()
    await page.getByRole('row', { name: new RegExp(titleB) }).getByRole('checkbox').check()

    const bar = page.locator('.bulk-bar')
    await expect(bar.getByText('2 selected')).toBeVisible()

    const [download] = await Promise.all([
      page.waitForEvent('download'),
      bar.getByRole('button', { name: 'Download ZIP' }).click(),
    ])
    expect(download.suggestedFilename()).toMatch(/\.zip$/)
    const buf = readFileSync(await download.path())
    expect(zipTotalEntries(buf)).toBe(3)
  } finally {
    await trashDoc(page.request, a.id)
    await trashDoc(page.request, b.id)
  }
})

test('89c: Settings account exports all documents as a ZIP with a manifest', async ({ page }) => {
  // Seed at least one document so the export is non-trivial.
  const title = unique('export-doc')
  const { id } = await seedDoc(page.request, title, [
    { name: 'doc.txt', mimeType: 'text/plain', path: txt },
  ])
  try {
    await page.goto('/#/settings/account')
    const exportBtn = page.getByRole('button', { name: 'Export my documents' })
    await expect(exportBtn).toBeVisible()

    const [download] = await Promise.all([page.waitForEvent('download'), exportBtn.click()])
    expect(download.suggestedFilename()).toBe('teedy-export.zip')
    const buf = readFileSync(await download.path())
    // A ZIP (PK signature) that carries the manifest.json entry (its name is stored
    // uncompressed in the archive's directory, so a raw byte search is authoritative).
    expect(buf.subarray(0, 2).toString('latin1')).toBe('PK')
    expect(buf.includes(Buffer.from('manifest.json'))).toBeTruthy()
  } finally {
    await trashDoc(page.request, id)
  }
})
