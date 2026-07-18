import { test, expect } from './fixtures'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'
import { unique, createDocument, confirmDanger, openFileList } from './helpers'

// End-to-end acceptance for the office-document -> PDF conversion pipeline behind
// PdfUtil.convertToPdf (the XDocReport ODT/DOCX converters + the text/CSV writer).
//
// Each format class is uploaded through the REAL FileUpload dropzone of a real
// document, then the document is exported via GET /document/:id/pdf. That endpoint
// runs PdfUtil.convertToPdf synchronously in the request thread, so a conversion
// failure (e.g. an iText/OpenPDF binary incompatibility in an XDocReport converter)
// produces an EMPTY/truncated body — convertToPdf throws before doc.save() ever
// writes a byte. We therefore assert the returned body is a STRUCTURALLY VALID PDF
// (%PDF- header + %%EOF trailer), not merely HTTP 200: the 200 header is already
// flushed before streaming begins, so status alone cannot prove the render.
//
// Covers, one document per class:
//   - text/CSV  (TextPlainFormatHandler direct writer)
//   - DOCX      (fr.opensagres.poi.xwpf.converter.pdf.openpdf)
//   - ODT       (fr.opensagres.odfdom.converter.pdf.openpdf)
//   - ODT with a page-background image (XDocReport background-image insertion — the
//     highest-risk converter surface)
//
// Runs under both the desktop and mobile Playwright projects unchanged.

const here = dirname(fileURLToPath(import.meta.url))

const CASES = [
  { label: 'text/CSV', fixture: 'conversion-sample.csv' },
  { label: 'DOCX', fixture: 'conversion-sample.docx' },
  { label: 'ODT', fixture: 'conversion-sample.odt' },
  { label: 'ODT with a page-background image', fixture: 'conversion-page-background.odt' },
] as const

for (const { label, fixture } of CASES) {
  test(`upload and convert ${label} to PDF`, async ({ page }) => {
    const fixturePath = resolve(here, 'fixtures', fixture)
    const title = unique('convert')
    const { id } = await createDocument(page, title)

    // Upload the fixture through the advanced FileUpload dropzone (auto customUpload).
    await page.goto(`/#/document/view/${id}/content`)
    await page.locator('.p-fileupload-advanced input[type="file"]').setInputFiles(fixturePath)

    // UI success surface: the upload toast appears and the file is listed, with no
    // error toast.
    await expect(page.getByText('Files uploaded').first()).toBeVisible()
    await openFileList(page)
    await expect(page.locator('.file-list-section .file-name-text', { hasText: fixture })).toBeVisible()

    // Server-side conversion acceptance: export the document to PDF. This runs
    // PdfUtil.convertToPdf over the uploaded file synchronously.
    const response = await page.request.get(
      `/api/document/${id}/pdf?margin=10&metadata=false&fitimagetopage=true`,
    )
    expect(response.status(), `${label} export HTTP status`).toBe(200)
    const body = await response.body()

    // A successful render is a complete PDF; a conversion throw leaves an empty body.
    expect(body.length, `${label} exported PDF byte length`).toBeGreaterThan(200)
    const head = body.subarray(0, 5).toString('latin1')
    expect(head, `${label} exported PDF header`).toBe('%PDF-')
    expect(body.includes(Buffer.from('%%EOF')), `${label} exported PDF has an EOF trailer`).toBe(true)

    // Cleanup the document.
    await page.goto(`/#/document/view/${id}`)
    await page.getByRole('button', { name: 'Delete', exact: true }).click()
    await confirmDanger(page)
  })
}
