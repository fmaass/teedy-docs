import { test, expect, type APIRequestContext, type Page } from './fixtures'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'
import { readFileSync } from 'node:fs'
import { unique, confirmDanger } from './helpers'

// #58 — the anonymous/shared view is READ-ONLY and GRID-ONLY: files render as open/
// download cards with no list toggle and none of the authenticated write affordances.

const here = dirname(fileURLToPath(import.meta.url))
const txt = resolve(here, 'fixtures/sample.txt')
const png = resolve(here, 'fixtures/pixel.png')

async function seedDoc(request: APIRequestContext, title: string): Promise<string> {
  const docRes = await request.put('/api/document', {
    headers: { 'content-type': 'application/x-www-form-urlencoded' },
    data: new URLSearchParams([['title', title], ['language', 'eng']]).toString(),
  })
  const id = (await docRes.json()).id as string
  await request.put('/api/file', {
    multipart: { id, file: { name: 'photo.png', mimeType: 'image/png', buffer: readFileSync(png) } },
  })
  await request.put('/api/file', {
    multipart: { id, file: { name: 'notes.txt', mimeType: 'text/plain', buffer: readFileSync(txt) } },
  })
  return id
}

async function deleteDoc(page: Page, id: string) {
  await page.goto(`/#/document/view/${id}`)
  const del = page.getByRole('button', { name: 'Delete', exact: true })
  if (await del.isVisible().catch(() => false)) {
    await del.click()
    await confirmDanger(page)
  }
}

test('anonymous share view renders files read-only and grid-only', async ({ page, browser }) => {
  const title = unique('sharefiles')
  const id = await seedDoc(page.request, title)
  try {
    // Create a public share link on the document.
    await page.goto(`/#/document/view/${id}/permissions`)
    await page.getByPlaceholder('Link name (optional)').fill('e2e-grid')
    await page.getByRole('button', { name: 'Create link' }).click()
    await expect(page.getByText('Share link created')).toBeVisible()
    const shareUrl = await page.locator('.share-url').first().innerText()

    // Anonymous (logged-out) visitor.
    const anonCtx = await browser.newContext({ storageState: { cookies: [], origins: [] } })
    const anon = await anonCtx.newPage()
    await anon.goto(shareUrl.substring(shareUrl.indexOf('#')))
    await expect(anon.getByRole('heading', { name: title })).toBeVisible()

    // GRID-ONLY: file cards render in the grid, and BOTH files are present (image +
    // non-image), so nothing is hidden. There is NO list/table and NO view toggle.
    await expect(anon.locator('.share-file-grid')).toBeVisible()
    await expect(anon.locator('.share-file-card')).toHaveCount(2)
    await expect(anon.getByText('photo.png')).toBeVisible()
    await expect(anon.getByText('notes.txt')).toBeVisible()
    await expect(anon.locator('.file-view-toggle')).toHaveCount(0)
    await expect(anon.locator('.file-data-table')).toHaveCount(0)

    // READ-ONLY: none of the authenticated write affordances exist.
    await expect(anon.locator('input[type="file"]')).toHaveCount(0)
    await expect(anon.getByRole('button', { name: 'Rename' })).toHaveCount(0)
    await expect(anon.getByRole('button', { name: 'Remove file' })).toHaveCount(0)
    await expect(anon.getByRole('button', { name: 'Edit' })).toHaveCount(0)

    // Each card is a button that opens the in-app preview — NOT a link to the original
    // file URL, which the backend serves as an attachment (clicking a link would just
    // download it, #144). So the card has no href.
    expect(await anon.locator('.share-file-card').first().getAttribute('href')).toBeNull()

    // Clicking the card opens the preview dialog; its Download control targets the original
    // file and carries the share credential (share=<id>), so an ANONYMOUS fetch actually
    // succeeds (a broad /api/file/ match would pass even if the credential were dropped and
    // the link 403'd). Assert both the token is present and the fetch is authorized.
    await anon.locator('.share-file-card').first().click()
    const download = anon.locator('.file-preview-download').first()
    await expect(download).toBeVisible()
    const href = await download.getAttribute('href')
    expect(href).toMatch(/[?&]share=[^&]+/)
    const fileRes = await anonCtx.request.get(new URL(href!, anon.url()).toString())
    expect(fileRes.ok(), 'anonymous share download must be authorized').toBeTruthy()

    await anonCtx.close()
  } finally {
    await deleteDoc(page, id)
  }
})
