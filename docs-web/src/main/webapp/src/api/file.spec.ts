import { describe, it, expect, beforeEach, vi } from 'vitest'

// Mock the shared axios client (a dependency of file.ts). The unit under test is
// file.ts's own path building and response unwrapping, exercised for real.
const mock = vi.hoisted(() => ({
  get: vi.fn((..._args: unknown[]) => Promise.resolve({ data: {} })),
  put: vi.fn((..._args: unknown[]) => Promise.resolve({ data: {} })),
  post: vi.fn((..._args: unknown[]) => Promise.resolve({ data: {} })),
  delete: vi.fn((..._args: unknown[]) => Promise.resolve({ data: {} })),
}))

vi.mock('./client', () => ({ default: mock }))

import { getFileVersions, getFileList, getFileUrl, setRotation, uploadFile, toPercent, type FileVersion } from './file'

describe('getFileVersions', () => {
  beforeEach(() => {
    mock.get.mockClear()
  })

  it('GETs /file/:id/versions', async () => {
    mock.get.mockResolvedValueOnce({ data: { files: [] } })
    await getFileVersions('file-1')
    expect(mock.get).toHaveBeenCalledTimes(1)
    expect(mock.get.mock.calls[0][0]).toBe('/file/file-1/versions')
  })

  it('unwraps the files array from the response', async () => {
    const files: FileVersion[] = [
      { id: 'f2', name: 'doc.pdf', version: 1, mimetype: 'application/pdf', create_date: 200 },
      { id: 'f1', name: 'doc.pdf', version: 0, mimetype: 'application/pdf', create_date: 100 },
    ]
    mock.get.mockResolvedValueOnce({ data: { files } })
    const result = await getFileVersions('file-1')
    expect(result).toEqual(files)
  })

  it('returns an empty array when the response has no files field', async () => {
    mock.get.mockResolvedValueOnce({ data: {} })
    expect(await getFileVersions('file-1')).toEqual([])
  })

  it('returns an empty array when files is null', async () => {
    mock.get.mockResolvedValueOnce({ data: { files: null } })
    expect(await getFileVersions('file-1')).toEqual([])
  })
})

describe('getFileList', () => {
  beforeEach(() => {
    mock.get.mockClear()
  })

  it('GETs /file/list with the document id as a query param', async () => {
    mock.get.mockResolvedValueOnce({ data: { files: [] } })
    await getFileList('doc-1')
    expect(mock.get).toHaveBeenCalledTimes(1)
    expect(mock.get.mock.calls[0][0]).toBe('/file/list')
    expect(mock.get.mock.calls[0][1]).toEqual({ params: { id: 'doc-1' } })
  })

  it('unwraps the files array (including the processing flag)', async () => {
    const files = [
      { id: 'f1', processing: true, name: 'a.pdf', version: 0, mimetype: 'application/pdf', document_id: 'doc-1', create_date: 1, size: 10 },
    ]
    mock.get.mockResolvedValueOnce({ data: { files } })
    expect(await getFileList('doc-1')).toEqual(files)
  })

  it('returns an empty array when the response has no files field', async () => {
    mock.get.mockResolvedValueOnce({ data: {} })
    expect(await getFileList('doc-1')).toEqual([])
  })
})

describe('getFileUrl (version data)', () => {
  it('builds a bare data url for a specific version id with no size', () => {
    expect(getFileUrl('version-7')).toBe('api/file/version-7/data')
  })

  it('appends the size query param', () => {
    expect(getFileUrl('f1', 'web')).toBe('api/file/f1/data?size=web')
    expect(getFileUrl('f1', 'thumb')).toBe('api/file/f1/data?size=thumb')
  })

  it('appends a ?v=<rotation> cache-bust key for web/thumb when rotation is non-zero', () => {
    expect(getFileUrl('f1', 'web', undefined, 90)).toBe('api/file/f1/data?size=web&v=90')
    expect(getFileUrl('f1', 'thumb', undefined, 270)).toBe('api/file/f1/data?size=thumb&v=270')
  })

  it('omits the cache-bust key when rotation is 0/absent (keeps existing URLs stable)', () => {
    expect(getFileUrl('f1', 'web', undefined, 0)).toBe('api/file/f1/data?size=web')
    expect(getFileUrl('f1', 'web')).toBe('api/file/f1/data?size=web')
  })

  it('does NOT append a cache-bust key for the original file (no size) even with a rotation', () => {
    // The original bytes are never baked/rotated; only the derived web/thumb rasters are.
    expect(getFileUrl('f1', undefined, undefined, 90)).toBe('api/file/f1/data')
  })

  it('threads the share id alongside size and rotation', () => {
    expect(getFileUrl('f1', 'web', 'share-9', 90)).toBe('api/file/f1/data?size=web&share=share-9&v=90')
  })
})

describe('setRotation', () => {
  beforeEach(() => {
    mock.post.mockClear()
  })

  it('POSTs the absolute rotation form-encoded to /file/:id/rotation', async () => {
    await setRotation('file-1', 90)
    expect(mock.post).toHaveBeenCalledTimes(1)
    const [url, body] = mock.post.mock.calls[0]
    expect(url).toBe('/file/file-1/rotation')
    expect(body).toBeInstanceOf(URLSearchParams)
    expect((body as URLSearchParams).get('rotation')).toBe('90')
  })

  it('sends 0 as a real value (reset to upright)', async () => {
    await setRotation('file-1', 0)
    const body = mock.post.mock.calls[0][1] as URLSearchParams
    expect(body.get('rotation')).toBe('0')
  })
})

describe('toPercent (bytes -> percent)', () => {
  it('maps loaded/total to a rounded integer percentage', () => {
    expect(toPercent(0, 200)).toBe(0)
    expect(toPercent(50, 200)).toBe(25)
    expect(toPercent(200, 200)).toBe(100)
    expect(toPercent(1, 3)).toBe(33)
  })

  it('returns 0 when the total is unknown or non-positive', () => {
    expect(toPercent(50, undefined)).toBe(0)
    expect(toPercent(50, 0)).toBe(0)
    expect(toPercent(50, -1)).toBe(0)
  })

  it('clamps to the 0..100 range', () => {
    expect(toPercent(-5, 100)).toBe(0)
    expect(toPercent(150, 100)).toBe(100)
  })
})

describe('uploadFile (progress plumbing)', () => {
  beforeEach(() => {
    mock.put.mockClear()
  })

  it('PUTs multipart form-data to /file with the document id and file', async () => {
    const file = new File(['x'], 'photo.jpg', { type: 'image/jpeg' })
    await uploadFile('doc-1', file)
    expect(mock.put).toHaveBeenCalledTimes(1)
    const [url, body, config] = mock.put.mock.calls[0]
    expect(url).toBe('/file')
    expect(body).toBeInstanceOf(FormData)
    expect((body as FormData).get('id')).toBe('doc-1')
    expect((body as FormData).get('file')).toBe(file)
    expect((config as { headers: Record<string, string> }).headers['Content-Type']).toBe(
      'multipart/form-data',
    )
  })

  it('threads axios onUploadProgress and reports bytes as a 0..100 percent', async () => {
    const file = new File(['x'], 'photo.jpg', { type: 'image/jpeg' })
    const seen: number[] = []
    await uploadFile('doc-1', file, (pct) => seen.push(pct))

    const config = mock.put.mock.calls[0][2] as {
      onUploadProgress: (e: { loaded: number; total?: number }) => void
    }
    expect(typeof config.onUploadProgress).toBe('function')
    config.onUploadProgress({ loaded: 0, total: 400 })
    config.onUploadProgress({ loaded: 100, total: 400 })
    config.onUploadProgress({ loaded: 400, total: 400 })
    expect(seen).toEqual([0, 25, 100])
  })

  it('omits onUploadProgress when no callback is given', async () => {
    const file = new File(['x'], 'photo.jpg', { type: 'image/jpeg' })
    await uploadFile('doc-1', file)
    const config = mock.put.mock.calls[0][2] as { onUploadProgress?: unknown }
    expect(config.onUploadProgress).toBeUndefined()
  })
})
