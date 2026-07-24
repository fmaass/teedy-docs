import { test, expect, type APIRequestContext } from './fixtures'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'
import { readFileSync } from 'node:fs'
import { unique, openFileList } from './helpers'

// #184 — duplicate a document from the document-view header. Duplicating a 2-file document produces a
// new document (a different id) owned by the requester, titled "<title> (copy)", carrying fresh copies
// of both files whose thumbnails materialize through the existing file-processing preview poll.

const here = dirname(fileURLToPath(import.meta.url))
const png = resolve(here, 'fixtures/pixel.png')

async function seedTwoFileDoc(request: APIRequestContext, title: string): Promise<string> {
  const docRes = await request.put('/api/document', {
    headers: { 'content-type': 'application/x-www-form-urlencoded' },
    data: new URLSearchParams([['title', title], ['language', 'eng']]).toString(),
  })
  const id = (await docRes.json()).id as string
  for (const name of ['first.png', 'second.png']) {
    await request.put('/api/file', {
      multipart: { id, file: { name, mimeType: 'image/png', buffer: readFileSync(png) } },
    })
  }
  return id
}

test('duplicates a 2-file document from the header; the copy opens with both files', async ({ page, request }) => {
  const title = unique('E2E dup')
  const sourceId = await seedTwoFileDoc(request, title)

  await page.goto(`/#/document/view/${sourceId}`)
  await expect(page.getByRole('heading', { name: title })).toBeVisible()

  await page.getByRole('button', { name: 'Duplicate', exact: true }).click()

  await expect(page).toHaveURL(/#\/document\/view\//)
  await expect.poll(() => page.url()).not.toContain(sourceId)
  await expect(page.getByRole('heading', { name: `${title} (copy)` })).toBeVisible()

  await openFileList(page)
  await expect(page.getByText('first.png', { exact: true })).toBeVisible()
  await expect(page.getByText('second.png', { exact: true })).toBeVisible()
  await expect
    .poll(async () => await page.locator('img[src*="/file/"]').count(), { timeout: 15000 })
    .toBeGreaterThanOrEqual(2)
})
