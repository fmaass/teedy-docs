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

import { getFileVersions, getFileUrl, uploadFile, toPercent, type FileVersion } from './file'

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

describe('getFileUrl (version data)', () => {
  it('builds a bare data url for a specific version id with no size', () => {
    expect(getFileUrl('version-7')).toBe('api/file/version-7/data')
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
