import { describe, it, expect, beforeEach, vi } from 'vitest'

// Mock the shared axios client. The unit under test is access.ts's own verb/path/param
// selection, exercised for real against the mock.
const mock = vi.hoisted(() => ({
  get: vi.fn((..._args: unknown[]) => Promise.resolve({ data: {} })),
}))

vi.mock('./client', () => ({ default: mock }))

import { getDocumentAccessCounts, getAccessStats } from './access'

describe('access api client (#300)', () => {
  beforeEach(() => {
    mock.get.mockClear()
  })

  it('getDocumentAccessCounts GETs /access/document/:id', () => {
    getDocumentAccessCounts('doc-1')
    expect(mock.get).toHaveBeenCalledTimes(1)
    expect(mock.get.mock.calls[0][0]).toBe('/access/document/doc-1')
  })

  it('never sends a user parameter — personal counts are the caller identity, not a request field', () => {
    getDocumentAccessCounts('doc-1')
    const config = mock.get.mock.calls[0][1] as { params?: Record<string, unknown> } | undefined
    expect(config?.params).toBeUndefined()
  })

  it('getAccessStats GETs /access/stats with no params when no limit is given', () => {
    getAccessStats()
    expect(mock.get.mock.calls[0][0]).toBe('/access/stats')
    expect((mock.get.mock.calls[0][1] as { params?: unknown }).params).toBeUndefined()
  })

  it('getAccessStats passes an explicit limit through as a query param', () => {
    getAccessStats(25)
    expect(mock.get.mock.calls[0][0]).toBe('/access/stats')
    expect((mock.get.mock.calls[0][1] as { params?: unknown }).params).toEqual({ limit: 25 })
  })
})
