import { test, expect, type APIRequestContext } from './fixtures'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'
import { readFileSync } from 'node:fs'
import {
  createDocument,
  deleteDocApi,
  expectResponseOk,
  gotoDocumentList,
  openFileList,
  ROUTE_ROOT,
  gotoRouteReady,
} from './helpers'

// #143 / #207 — the standing encoding guardrail.
//
// #143 was a real ingest defect: a browser transmits the multipart Content-Disposition
// filename as UTF-8 bytes while Jersey decodes that header as ISO-8859-1, so a file named
// `Körper.pdf` was STORED as `KÃ¶rper.pdf`. It was fixed at upload time
// (FileResource.repairMultipartFilename) and shipped with NO regression test, which is why
// #207 — the same damage, still sitting in rows written before the fix — had to be
// rediscovered by hand months later.
//
// This spec is that missing test. It seeds through the REAL surfaces (the add-document form
// types the title; the document-view dropzone uploads the file, so Chromium builds the
// multipart body exactly as a user's browser does) and then compares every surface the name
// reaches against the SOURCE LITERAL, character for character. Nothing here normalizes,
// lowercases, trims or substring-matches: one mojibake byte anywhere on the path has to
// fail it.
//
// Surfaces asserted per fixture class: the API bytes (document title, file name), the
// download name the browser would save under (RFC 5987 `filename*`), the file panel in BOTH
// its modes (grid label, list cell), the list cell's native `title` tooltip — the #207
// mechanism, FileListTable.vue:365, where hover is the only way to read a name the cell has
// ellipsized — the document-list title cell, and fulltext search by a non-ASCII term.
//
// Document TITLES get no tooltip assertion: the list's title cell (DocumentTitleCell.vue) is
// a plain link with no `title` binding, so there is no tooltip surface to assert for them.

const here = dirname(fileURLToPath(import.meta.url))

// The bytes are the repo's existing small PDF; only the NAME is under test.
//
// The upload deliberately supplies that name as a source LITERAL instead of committing a
// fixture file that carries the umlauts in its own filename. Both reasons are about keeping
// the test honest: the uploaded string and the expected string are then provably the same
// literal (a committed fixture could only ever be compared against a second, hand-retyped
// copy of its name), and the spec does not depend on a checkout preserving a non-ASCII
// filename byte-for-byte (macOS checks out NFD where Linux has NFC — a difference that has
// nothing to do with Teedy and would fail this test for the wrong reason). Chromium builds
// the multipart part from the File object's name either way, so the bytes on the wire — the
// thing #143 is about — are identical.
const PDF_BYTES = readFileSync(resolve(here, 'fixtures/sample.pdf'))

interface EncodingClass {
  /** Test-name suffix; also the label in a failure message. */
  label: string
  /**
   * The non-ASCII core that must survive byte-exactly. It is used for BOTH the uploaded
   * file name and the document title, so one fixture covers both name paths.
   */
  core: string
  /**
   * The term the fulltext search is driven with. It is drawn FROM the core, so a mojibake
   * title cannot satisfy it: `Prüfung` analyzes to the single term `prüfung`, while a
   * mangled `PrÃ¼fung` tokenizes around the `¼` and can never produce it.
   */
  searchTerm: string
}

const CLASSES: EncodingClass[] = [
  { label: 'umlauts + ß', core: 'Körper-Prüfung-groß', searchTerm: 'Prüfung' },
  { label: 'CJK', core: '日本語ファイル', searchTerm: '日本語' },
]

let tokenCounter = 0

/**
 * A run-unique discriminator, alphanumeric ONLY.
 *
 * The shared `unique()` helper is not used here because its `-` separators split the token:
 * the analyzer breaks a hyphenated token into its fragments on both the index and the query
 * side, so the discriminator would stop being the single opaque term these assertions rely on.
 * Uniqueness is structural exactly as in `unique()` — timestamp, pid, counter.
 */
function runToken(): string {
  return `enc${Date.now().toString(36)}${process.pid.toString(36)}${tokenCounter++}`
}

/**
 * The file name a browser would SAVE the download as: FileResource serves the original with
 * `Content-Disposition: attachment; filename*=utf-8''<percent-encoded UTF-8>` (RFC 5987), so
 * the round-tripped name is recovered by percent-decoding. A missing `filename*` is a hard
 * error rather than a fallback — the header is the surface under test.
 */
function downloadFilename(header: string | undefined): string {
  const match = /filename\*=utf-8''(\S+)/i.exec(header ?? '')
  if (!match) {
    throw new Error(
      `Content-Disposition carries no RFC 5987 filename* parameter: ${JSON.stringify(header)}`,
    )
  }
  return decodeURIComponent(match[1])
}

/** Document ids the server's fulltext search returns for `query`. */
async function searchHitIds(request: APIRequestContext, query: string): Promise<string[]> {
  const res = await request.get('/api/document/list', { params: { search: query, limit: 50 } })
  await expectResponseOk(res, `search the document list for ${JSON.stringify(query)}`)
  const documents = (await res.json()).documents as Array<{ id: string }>
  return documents.map((d) => d.id)
}

for (const fx of CLASSES) {
  test(`non-ASCII titles and file names survive ingest byte-exactly (${fx.label})`, async ({
    page,
    cleanup,
  }) => {
    const token = runToken()
    const title = `${fx.core} ${token}`
    const fileName = `${fx.core}.pdf`

    // --- Seed through the real write paths -----------------------------------------
    const { id } = await createDocument(page, title)
    cleanup.defer('purge the seeded document', () => deleteDocApi(page.request, id))

    await gotoRouteReady(page, `/#/document/view/${id}/content`, ROUTE_ROOT.documentContent)
    // The hidden input of the advanced dropzone: selecting a file uploads it immediately
    // (customUpload), through the app's own FormData PUT — the #143 code path.
    await page.locator('.p-fileupload-advanced input[type="file"]').setInputFiles({
      name: fileName,
      mimeType: 'application/pdf',
      buffer: PDF_BYTES,
    })
    await expect(page.getByText('Files uploaded').first()).toBeVisible()

    // --- Surface 1: the bytes the API serves ---------------------------------------
    const docRes = await page.request.get(`/api/document/${id}`)
    await expectResponseOk(docRes, 'read the seeded document back')
    expect(
      (await docRes.json()).title,
      'API surface: GET /api/document/:id title, byte for byte',
    ).toBe(title)

    const listRes = await page.request.get('/api/file/list', { params: { id } })
    await expectResponseOk(listRes, 'list the seeded document files')
    const files = (await listRes.json()).files as Array<{ id: string; name: string }>
    expect(files, 'the upload produced exactly one file').toHaveLength(1)
    expect(
      files[0].name,
      'API surface: GET /api/file/list name, byte for byte (the #143 ingest path)',
    ).toBe(fileName)

    // --- Surface 2: the name the browser would save the download under -------------
    const dataRes = await page.request.get(`/api/file/${files[0].id}/data`)
    await expectResponseOk(dataRes, 'download the seeded file')
    expect(
      downloadFilename(dataRes.headers()['content-disposition']),
      'download surface: Content-Disposition filename*, byte for byte',
    ).toBe(fileName)

    // --- Surface 3: the file panel, both modes + the #207 tooltip ------------------
    // Grid is the panel's default mode, so its label is the first name a user sees.
    const gridLabel = page.locator('.file-preview-label')
    await expect(gridLabel).toHaveCount(1)
    expect(
      await gridLabel.textContent(),
      'file panel (grid) surface: rendered file name, byte for byte',
    ).toBe(fileName)

    await openFileList(page)
    const nameCell = page.locator('.file-list-section .file-name-text')
    await expect(nameCell).toHaveCount(1)
    expect(
      await nameCell.textContent(),
      'file panel (list) surface: rendered file name, byte for byte',
    ).toBe(fileName)
    // The list cell ellipsizes, so hovering it is the only way to read the full name — the
    // surface #207 was reported against.
    expect(
      await nameCell.getAttribute('title'),
      'tooltip surface: FileListTable name-cell title attribute (#207), byte for byte',
    ).toBe(fileName)

    // --- Surface 4: the document-list title cell ----------------------------------
    await gotoDocumentList(page)
    // Located by the document ID the row links to, never by its text: a locator that
    // matched on the title would silently pass by finding SOME row, and the assertion has
    // to be about the string this exact document renders.
    const titleCell = page.locator(`a.doc-title[href$="/document/view/${id}"]`)
    await expect(titleCell).toBeVisible()
    expect(
      await titleCell.textContent(),
      'document-list surface: rendered title cell, byte for byte',
    ).toBe(title)

    // --- Surface 5: fulltext search by a non-ASCII term ---------------------------
    // Both terms are MUST-combined by the query builder, so a hit proves the non-ASCII term
    // itself matched the indexed title — the run token alone could never satisfy it.
    const query = `${fx.searchTerm} ${token}`
    // Indexing is asynchronous (DocumentCreatedAsyncListener), so the SERVER is polled until
    // it answers — re-issuing the query each attempt. The UI search below fires once, and
    // would keep rendering an empty result set forever if it raced the indexer.
    await expect
      .poll(() => searchHitIds(page.request, query), {
        message: `the fulltext index answers ${JSON.stringify(query)} with the seeded document`,
        timeout: 20_000,
      })
      .toEqual([id])

    // The same query through the real search box. The list is ALREADY showing this document
    // (surface 4 above), so waiting for the search request itself is what makes this an
    // assertion about the RESULT SET rather than about the row that was on screen anyway.
    const search = page.getByPlaceholder('Search')
    const searched = page.waitForResponse(
      (r) =>
        r.url().includes('/api/document/list') &&
        new URL(r.url()).searchParams.get('search') === query,
      { timeout: 15_000 },
    )
    await search.fill(query)
    await searched
    await expect(
      page.locator('a.doc-title'),
      'the non-ASCII search narrowed the list to the one seeded document',
    ).toHaveCount(1)
    expect(
      await titleCell.textContent(),
      'search surface: the non-ASCII term returns the document and its title renders byte for byte',
    ).toBe(title)
    await search.fill('')
  })
}
