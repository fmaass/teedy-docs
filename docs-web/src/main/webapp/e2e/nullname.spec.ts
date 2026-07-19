import { test, expect, type APIRequestContext, type Page } from './fixtures'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'
import { readFileSync } from 'node:fs'
import { unique, confirmDanger, openFileList } from './helpers'

// rc.8 Phase G (#131/#132): the backend serializes a file `name` via JsonUtil.nullable, so a
// name-less file must render a stable localized fallback ("Untitled file") — never a blank cell /
// empty alt — everywhere a file name is shown: the authenticated file panel (grid + list), the
// slide-over, and the anonymous share view. All four route through the single displayName() helper.
//
// A file with an EMPTY name ("") is the reproducible proxy for the nullable name: it drives the
// exact same `name || fallback` branch a JSON null does (displayName treats both as falsy). A true
// null cannot be produced through the app's write paths — a filename-LESS multipart part 500s on a
// pre-existing server-side URLDecoder NPE (FileResource#add) — so the empty name is used, and the
// null path itself is pinned by document.test-d.ts + utils/fileName.spec.ts.

const here = dirname(fileURLToPath(import.meta.url))
const txt = resolve(here, 'fixtures/sample.txt')

const UNTITLED = 'Untitled file'

// Seed a document carrying ONE file whose stored name is "". Playwright's `multipart` helper always
// emits a `filename`, so the body is hand-built with `filename=""` (which the backend stores as an
// empty name) and sent as a raw multipart/form-data buffer.
async function seedEmptyNameDoc(request: APIRequestContext, title: string): Promise<string> {
  const docRes = await request.put('/api/document', {
    headers: { 'content-type': 'application/x-www-form-urlencoded' },
    data: new URLSearchParams([
      ['title', title],
      ['language', 'eng'],
    ]).toString(),
  })
  const id = (await docRes.json()).id as string

  const boundary = '----teedyNullName' + Math.random().toString(16).slice(2)
  const body = Buffer.concat([
    Buffer.from(`--${boundary}\r\nContent-Disposition: form-data; name="id"\r\n\r\n${id}\r\n`),
    Buffer.from(
      `--${boundary}\r\nContent-Disposition: form-data; name="file"; filename=""\r\n` +
        `Content-Type: text/plain\r\n\r\n`,
    ),
    readFileSync(txt),
    Buffer.from(`\r\n--${boundary}--\r\n`),
  ])
  const up = await request.put('/api/file', {
    headers: { 'content-type': `multipart/form-data; boundary=${boundary}` },
    data: body,
  })
  expect(up.ok(), 'empty-name file upload must succeed').toBeTruthy()
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

test('file panel (grid + list) shows the Untitled-file fallback for a name-less file', async ({ page }) => {
  const id = await seedEmptyNameDoc(page.request, unique('nullname-panel'))
  try {
    await page.goto(`/#/document/view/${id}/content`)

    // Grid (default): the name-less file's label is the localized fallback, not an empty node.
    const gridLabel = page.locator('.file-preview-label')
    await expect(gridLabel).toHaveText(UNTITLED)

    // List mode: the name cell is the same fallback.
    await openFileList(page)
    await expect(page.locator('.file-name-text')).toHaveText(UNTITLED)
  } finally {
    await deleteDoc(page, id)
  }
})

test('slide-over files tab shows the Untitled-file fallback for a name-less file', async ({ page }) => {
  const title = unique('nullname-slide')
  const id = await seedEmptyNameDoc(page.request, title)
  try {
    await page.goto('/#/document')
    // A single click on the row opens the slide-over drawer.
    await page.getByText(title, { exact: true }).click()
    const drawer = page.getByRole('dialog')
    await expect(drawer).toBeVisible()

    // Activate the Files tab (its label carries the file count) and assert the fallback.
    await drawer.getByRole('tab', { name: /file/i }).click()
    await expect(drawer.locator('.slide-file-list .file-name')).toHaveText(UNTITLED)
  } finally {
    await deleteDoc(page, id)
  }
})

test('anonymous share view shows the Untitled-file fallback for a name-less file', async ({ page, browser }) => {
  const title = unique('nullname-share')
  const id = await seedEmptyNameDoc(page.request, title)
  try {
    // Create a public share on the document via the API and build its anonymous URL.
    const shareRes = await page.request.put('/api/share', {
      headers: { 'content-type': 'application/x-www-form-urlencoded' },
      data: new URLSearchParams([['id', id]]).toString(),
    })
    expect(shareRes.ok(), 'share creation must succeed').toBeTruthy()
    const shareId = (await shareRes.json()).id as string

    const anonCtx = await browser.newContext({ storageState: { cookies: [], origins: [] } })
    const anon = await anonCtx.newPage()
    await anon.goto(`/#/share/${id}/${shareId}`)
    await expect(anon.getByRole('heading', { name: title })).toBeVisible()

    // The share-file card renders the fallback (never a blank name).
    await expect(anon.locator('.share-file-name')).toHaveText(UNTITLED)
    await anonCtx.close()
  } finally {
    await deleteDoc(page, id)
  }
})
