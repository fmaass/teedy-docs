import { test, expect, type APIRequestContext, type Page } from './fixtures'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'
import { readFileSync } from 'node:fs'
import { unique, confirmDanger } from './helpers'

// #119 content-hash duplicate detection — the SPA hint. When the backend has a dedup master secret
// (DOCS_DEDUP_MASTER_KEY / _FILE) it flags a content-identical upload with duplicateKind='content', and
// runUploads shows a non-blocking informational toast pointing at "upload new version".
//
// The default e2e harness boots WITHOUT a master secret (dedup off), so this spec is a no-op there and is
// gated on E2E_DEDUP — set it (and boot the app container with a DOCS_DEDUP_MASTER_KEY) to exercise the
// hint end-to-end. It runs unchanged under both the desktop and mobile projects.
test.skip(!process.env.E2E_DEDUP, 'requires a dedup-enabled backend (set E2E_DEDUP and DOCS_DEDUP_MASTER_KEY)')

const here = dirname(fileURLToPath(import.meta.url))
const txt = resolve(here, 'fixtures/sample.txt')

async function seedDocWithFile(request: APIRequestContext, title: string, fileName: string): Promise<string> {
  const docRes = await request.put('/api/document', {
    headers: { 'content-type': 'application/x-www-form-urlencoded' },
    data: new URLSearchParams([['title', title], ['language', 'eng']]).toString(),
  })
  const id = (await docRes.json()).id as string
  await request.put('/api/file', {
    multipart: { id, file: { name: fileName, mimeType: 'text/plain', buffer: readFileSync(txt) } },
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

test('a renamed identical upload shows the non-blocking content-duplicate hint', async ({ page }) => {
  const id = await seedDocWithFile(page.request, unique('dedup'), 'original.txt')
  try {
    await page.goto(`/#/document/view/${id}/content`)
    // Drop the SAME bytes under a DIFFERENT (non-conflicting) name so it uploads straight away and the
    // backend recognises the identical content within this document.
    await page.locator('.p-fileupload-advanced input[type="file"]').setInputFiles({
      name: 'renamed.txt',
      mimeType: 'text/plain',
      buffer: readFileSync(txt),
    })
    await expect(page.getByText('Files uploaded').first()).toBeVisible()
    // The advisory, non-blocking hint toast.
    await expect(page.getByText(/identical to/i).first()).toBeVisible()
  } finally {
    await deleteDoc(page, id)
  }
})
