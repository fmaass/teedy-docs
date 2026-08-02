import { test, expect, type APIRequestContext } from './fixtures'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'
import { readFileSync } from 'node:fs'
import {
  unique,
  openFileList,
  deleteDocApi,
  gotoDocumentList,
  ROUTE_ROOT,
  gotoRaw,
  gotoRouteReady,
} from './helpers'

// #192 — a shareable link to ONE file of a document, driven against the running app.
//
// The link is the ordinary authenticated content route plus `?file=<id>`; it carries no
// token, so the recipient still needs their own READ on the document. Two directions are
// exercised end to end here: the copy control really puts a working URL on the clipboard,
// and following that URL cold really opens the preview on that exact file. The param is
// then kept while the preview is open and removed when it closes.
//
// The clipboard is exercised for REAL — not stubbed. The harness serves the app from
// http://localhost:<port>, which IS a secure context, so `navigator.clipboard` exists; the
// context below grants read+write so the SUCCESS path (not the pattern's error fallback) is
// what these tests measure. Reading it back is also the only way to prove the URL the app
// built is the URL a user would paste.
test.use({ permissions: ['clipboard-read', 'clipboard-write'] })

const here = dirname(fileURLToPath(import.meta.url))
const txt = resolve(here, 'fixtures/sample.txt')

async function seedDoc(
  request: APIRequestContext,
  title: string,
  names: string[],
): Promise<string> {
  const docRes = await request.put('/api/document', {
    headers: { 'content-type': 'application/x-www-form-urlencoded' },
    data: new URLSearchParams([['title', title], ['language', 'eng']]).toString(),
  })
  const id = (await docRes.json()).id as string
  for (const name of names) {
    await request.put('/api/file', {
      multipart: { id, file: { name, mimeType: 'text/plain', buffer: readFileSync(txt) } },
    })
  }
  return id
}

test('copying a file link and following it opens the preview on that exact file (#192)', async ({
  page,
  cleanup,
}) => {
  const id = await seedDoc(page.request, unique('deeplink'), ['alpha.txt', 'beta.txt'])
  cleanup.defer('purge the seeded document', () => deleteDocApi(page.request, id))
  await gotoRouteReady(page, `/#/document/view/${id}/content`, ROUTE_ROOT.documentContent)
  await openFileList(page)

  // Copy the link of the SECOND file: a spec that copies the first would pass even if the
  // control ignored its row and always linked the document's leading file.
  const row = page.locator('.file-data-table tbody tr', { hasText: 'beta.txt' })
  await expect(row).toHaveCount(1)
  await row.getByRole('button', { name: 'Copy link to file' }).click()
  await expect(page.getByText('File link copied to clipboard')).toBeVisible()

  const copied = await page.evaluate(() => navigator.clipboard.readText())
  expect(copied, 'the clipboard holds a deep link into THIS document').toContain(
    `#/document/view/${id}/content?file=`,
  )

  // Follow it COLD: leave the document entirely first, so what is measured is the link
  // hydrating a fresh page load, not a dialog that never closed. The list must be MOUNTED
  // before the deep link is issued — a hash navigation that overtakes the first one is
  // clobbered when it finalizes (#215, see gotoDocumentList).
  await gotoDocumentList(page)
  // raw: following the clipboard deep link IS the subject — its `?file=` param is consumed during mount.
  await gotoRaw(page, copied)

  const dialog = page.getByRole('dialog')
  await expect(dialog).toBeVisible()
  await expect(dialog, 'the preview shows the file the link named').toContainText('beta.txt')
  await expect(dialog).not.toContainText('alpha.txt')
  // The param is KEPT while the preview is open — the URL stays shareable/reloadable.
  await expect(page).toHaveURL(/[?&]file=/)

  // …and is removed again when the preview closes, so a copied URL never carries a stale
  // preview the user has already dismissed.
  await page.keyboard.press('Escape')
  await expect(page.getByRole('dialog')).toHaveCount(0)
  await expect(page).not.toHaveURL(/[?&]file=/)
})

test('a link to a file this document no longer has degrades with one warning (#192)', async ({
  page,
  cleanup,
}) => {
  const id = await seedDoc(page.request, unique('deadlink'), ['alpha.txt'])
  cleanup.defer('purge the seeded document', () => deleteDocApi(page.request, id))

  // raw: an unknown `?file=` id is dropped during mount, so the landing URL is deliberately not this one.
  await gotoRaw(page, `/#/document/view/${id}/content?file=no-such-file-id`)

  // The document itself still loads…
  await expect(page.locator('.file-preview-grid, .file-data-table').first()).toBeVisible()
  // …the dead id opens nothing…
  await expect(page.getByRole('dialog')).toHaveCount(0)
  // …it is reported once…
  await expect(page.getByText('That file is no longer part of this document.')).toBeVisible()
  // …and it is cleaned out of the URL, so a reload or a re-share does not repeat it.
  await expect(page).not.toHaveURL(/[?&]file=/)
})

test('the preview param is written by replace, never pushed onto history (#192)', async ({
  page,
  cleanup,
}) => {
  const id = await seedDoc(page.request, unique('deeplink-hist'), ['alpha.txt'])
  cleanup.defer('purge the seeded document', () => deleteDocApi(page.request, id))

  // Two history entries: the document list, then this document's content tab. The list is
  // waited for, not just navigated to: a hash navigation issued while the first one is
  // still resolving gets clobbered when it finalizes, leaving the app on the list with the
  // deep link's URL undone (#215, see gotoDocumentList).
  await gotoDocumentList(page)
  await gotoRouteReady(page, `/#/document/view/${id}/content`, ROUTE_ROOT.documentContent)
  await openFileList(page)

  const row = page.locator('.file-data-table tbody tr', { hasText: 'alpha.txt' })
  // Pinned by class: since #178 the row's action menu carries a preview control with the
  // same accessible name as the icon column's, so role+name alone is ambiguous.
  await row.locator('.file-open-link').click()
  await expect(page.getByRole('dialog')).toBeVisible()
  await expect(page).toHaveURL(/[?&]file=/)

  // Back must leave the document. If opening the preview had PUSHED, Back would only strip
  // the param and strand the user on the content tab — the whole reason the param is written
  // with replace.
  await page.goBack()
  await expect(page).toHaveURL(/#\/document$/)
})
