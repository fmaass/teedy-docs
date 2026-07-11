import { describe, it, expect, beforeEach, vi } from 'vitest'

// Mock the shared axios client (a dependency of document.ts). The unit under test is
// document.ts's own path/body building, exercised for real against the mock.
const mock = vi.hoisted(() => ({
  get: vi.fn((..._args: unknown[]) => Promise.resolve({ data: {} })),
  put: vi.fn((..._args: unknown[]) => Promise.resolve({ data: {} })),
  post: vi.fn((..._args: unknown[]) => Promise.resolve({ data: {} })),
  delete: vi.fn((..._args: unknown[]) => Promise.resolve({ data: {} })),
}))

vi.mock('./client', () => ({ default: mock }))

import { importEml, buildRelationsParams } from './document'

describe('importEml', () => {
  beforeEach(() => mock.put.mockClear())

  it('PUTs multipart form-data to /document/eml with the file under `file`', async () => {
    const file = new File(['From: a@b'], 'mail.eml', { type: 'message/rfc822' })
    mock.put.mockResolvedValueOnce({ data: { id: 'doc-9' } })
    const res = await importEml(file)

    expect(mock.put).toHaveBeenCalledTimes(1)
    const [url, body, config] = mock.put.mock.calls[0]
    expect(url).toBe('/document/eml')
    expect(body).toBeInstanceOf(FormData)
    expect((body as FormData).get('file')).toBe(file)
    expect((config as { headers: Record<string, string> }).headers['Content-Type']).toBe(
      'multipart/form-data',
    )
    expect(res.data.id).toBe('doc-9')
  })
})

describe('buildRelationsParams', () => {
  it('always sends the required title and language alongside the relations', () => {
    const params = buildRelationsParams('My Doc', 'eng', ['b', 'c'])
    // title + language are REQUIRED by the backend before relations are processed.
    expect(params.get('title')).toBe('My Doc')
    expect(params.get('language')).toBe('eng')
    expect(params.getAll('relations')).toEqual(['b', 'c'])
    expect(params.get('relations_reset')).toBeNull()
  })

  it('sends every surviving outgoing id (full list, not a delta)', () => {
    const params = buildRelationsParams('T', 'fra', ['x', 'y', 'z'])
    expect(params.getAll('relations')).toEqual(['x', 'y', 'z'])
    expect(params.get('relations_reset')).toBeNull()
  })

  it('sends relations_reset=true and no relations when the last one is removed', () => {
    const params = buildRelationsParams('T', 'eng', [])
    expect(params.get('relations_reset')).toBe('true')
    expect(params.getAll('relations')).toEqual([])
    // title + language must STILL be present on a full-clear so the request validates.
    expect(params.get('title')).toBe('T')
    expect(params.get('language')).toBe('eng')
  })

  it('does not include any unrelated document field (relations-only edit is non-clobbering)', () => {
    const params = buildRelationsParams('T', 'eng', ['a'])
    // Only title, language, relations may be present — nothing that would overwrite tags,
    // description, metadata, etc. on the partial-update backend.
    const keys: string[] = []
    params.forEach((_value, key) => keys.push(key))
    expect([...new Set(keys)].sort()).toEqual(['language', 'relations', 'title'])
  })
})
