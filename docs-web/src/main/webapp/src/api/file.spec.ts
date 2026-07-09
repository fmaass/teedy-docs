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

import { getFileVersions, getFileUrl, type FileVersion } from './file'

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
