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

import { importEml } from './document'

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
