import { test, expect, type APIRequestContext } from './fixtures'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'
import { readFileSync } from 'node:fs'
import { unique, deleteDocApi, expectResponseOk, gotoRouteReady, ROUTE_ROOT } from './helpers'

// #233 (partial) — WebP uploads get a server-side preview, decoded by the TwelveMonkeys
// imageio-webp reader.
//
// Two claims that only a full-stack run can prove, because both depend on the deployed
// container rather than on unit-testable code:
//
//   1. TYPE DETECTION. FileUtil.createFile types an upload with MimeTypeUtil.guessMimeType
//      — the multipart part's declared Content-Type is discarded. The fixture is therefore
//      uploaded under an EXTENSION-LESS name and an explicitly WRONG declared type
//      (application/octet-stream); the file can only come back as image/webp if the server
//      read the RIFF/WEBP signature out of the bytes. This also pins the deployment reality
//      that motivated the content-first check: the image is built on a base whose
//      /etc/mime.types the app does not control.
//   2. THE READER IS ON THE RUNTIME CLASSPATH. A declared-but-unshipped ImageIO plugin
//      (missing from the WAR, or shaded away) fails exactly here and nowhere else.
//
// FIXTURE CHOICE: wide.webp is 60x20, mirroring wide.png in gallery.spec.ts. Its
// aspect-preserving 256-box thumbnail is ~256x85 — NOT square — while the fallback served for
// a file with no generated raster is the exact 256x256 placeholder. Asserting on the DECODED
// dimensions of the fetched bytes therefore distinguishes a real decode from the placeholder;
// a square fixture would make that assertion pass vacuously.
const here = dirname(fileURLToPath(import.meta.url))
const wideWebp = resolve(here, 'fixtures/wide.webp')

const NEUTRAL_UPLOAD_NAME = 'scan-without-any-extension'

async function seedWebpDoc(request: APIRequestContext, title: string): Promise<string> {
  const docRes = await request.put('/api/document', {
    headers: { 'content-type': 'application/x-www-form-urlencoded' },
    data: new URLSearchParams([
      ['title', title],
      ['language', 'eng'],
    ]).toString(),
  })
  await expectResponseOk(docRes, 'create document')
  const id = (await docRes.json()).id as string

  const up = await request.put('/api/file', {
    multipart: {
      id,
      file: {
        name: NEUTRAL_UPLOAD_NAME,
        mimeType: 'application/octet-stream',
        buffer: readFileSync(wideWebp),
      },
    },
  })
  await expectResponseOk(up, 'upload the WebP file')
  return id
}

async function listFiles(
  request: APIRequestContext,
  documentId: string,
): Promise<Array<{ id: string; mimetype: string; processing: boolean }>> {
  const res = await request.get(`/api/file/list?id=${documentId}`)
  await expectResponseOk(res, 'list files')
  return (await res.json()).files
}

// Decode the pixel dimensions of a PNG or JPEG buffer. The 256x256 fallback is a PNG; a real
// generated thumbnail is served as JPEG (RasterGenerationUtil re-encodes every raster).
function imageDimensions(buf: Buffer): { width: number; height: number } {
  if (buf.length >= 24 && buf[0] === 0x89 && buf[1] === 0x50 && buf[2] === 0x4e && buf[3] === 0x47) {
    return { width: buf.readUInt32BE(16), height: buf.readUInt32BE(20) }
  }
  if (buf.length >= 4 && buf[0] === 0xff && buf[1] === 0xd8) {
    let off = 2
    while (off + 9 < buf.length) {
      if (buf[off] !== 0xff) {
        off++
        continue
      }
      const marker = buf[off + 1]
      if (marker >= 0xc0 && marker <= 0xcf && marker !== 0xc4 && marker !== 0xc8 && marker !== 0xcc) {
        return { width: buf.readUInt16BE(off + 7), height: buf.readUInt16BE(off + 5) }
      }
      off += 2 + buf.readUInt16BE(off + 2)
    }
  }
  throw new Error('unrecognized image format for dimension decode')
}

test('a WebP upload is typed from its bytes and renders a real preview (#233)', async ({ page, cleanup }) => {
  const request = page.request
  const id = await seedWebpDoc(request, unique('webp-preview'))
  cleanup.defer('purge the seeded document', () => deleteDocApi(request, id))

  // (1) Typed from the SIGNATURE: neither the extension-less name nor the wrong declared
  // Content-Type could have produced this.
  const [file] = await listFiles(request, id)
  expect(file.mimetype, 'the server must type the upload from its RIFF/WEBP signature').toBe('image/webp')

  await expect
    .poll(async () => (await listFiles(request, id)).every((f) => !f.processing), {
      message: 'async raster generation should finish',
    })
    .toBe(true)

  // (2) A REAL raster, not the 256x256 fallback: the served thumbnail decodes to the
  // fixture's 3:1 landscape aspect, which only exists if the WebP bytes were decoded.
  const thumbRes = await request.get(`/api/file/${file.id}/data?size=thumb`)
  await expectResponseOk(thumbRes, 'fetch the generated thumbnail')
  const thumb = imageDimensions(Buffer.from(await thumbRes.body()))
  expect(thumb.width, 'a wide source must not produce the square placeholder').toBeGreaterThan(thumb.height)
  expect({ width: thumb.width, height: thumb.height }).not.toEqual({ width: 256, height: 256 })

  // (3) The browser actually paints it: the file panel gives a WebP the image tile (its
  // mimetype matches the generic image/* test) and the tile's <img> decodes to real pixels.
  await gotoRouteReady(page, `/#/document/view/${id}/content`, ROUTE_ROOT.documentContent)
  const preview = page.locator(`.file-preview-card[data-file-id="${file.id}"] img.rotatable-image`)
  await expect(preview).toBeVisible()
  await expect
    .poll(() => preview.evaluate((img: HTMLImageElement) => img.naturalWidth), {
      message: 'the preview image should finish decoding in the browser',
    })
    .toBeGreaterThan(0)
})
