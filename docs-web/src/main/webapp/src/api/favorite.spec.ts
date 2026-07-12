import { describe, it, expect, beforeEach, vi } from 'vitest'

// Mock the shared axios client. The unit under test is favorite.ts's own verb/path
// selection, exercised for real against the mock.
const mock = vi.hoisted(() => ({
  get: vi.fn((..._args: unknown[]) => Promise.resolve({ data: {} })),
  put: vi.fn((..._args: unknown[]) => Promise.resolve({ data: {} })),
  delete: vi.fn((..._args: unknown[]) => Promise.resolve({ data: {} })),
}))

vi.mock('./client', () => ({ default: mock }))

import { addFavorite, removeFavorite, listFavorites } from './favorite'

describe('favorite api client', () => {
  beforeEach(() => {
    mock.get.mockClear()
    mock.put.mockClear()
    mock.delete.mockClear()
  })

  it('addFavorite PUTs /favorite/:id', () => {
    addFavorite('doc-1')
    expect(mock.put).toHaveBeenCalledTimes(1)
    expect(mock.put.mock.calls[0][0]).toBe('/favorite/doc-1')
  })

  it('removeFavorite DELETEs /favorite/:id', () => {
    removeFavorite('doc-2')
    expect(mock.delete).toHaveBeenCalledTimes(1)
    expect(mock.delete.mock.calls[0][0]).toBe('/favorite/doc-2')
  })

  it('listFavorites GETs /favorite', () => {
    listFavorites()
    expect(mock.get).toHaveBeenCalledTimes(1)
    expect(mock.get.mock.calls[0][0]).toBe('/favorite')
  })
})
