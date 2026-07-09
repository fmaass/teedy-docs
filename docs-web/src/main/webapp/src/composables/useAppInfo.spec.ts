import { describe, it, expect, beforeEach, vi } from 'vitest'

// --- Dependency mocks (NOT the unit under test) ---
//
// useAppInfo wraps vue-query and the /app API. We mock useQuery/useQueryClient so
// we can assert the composable's OWN contract: it fetches under the ONE shared key
// (queryKeys.app()), applies staleTime Infinity, and its invalidator targets the
// same key — the whole point of the consolidation.

const useQueryMock = vi.fn()
const invalidateQueries = vi.fn()
vi.mock('@tanstack/vue-query', () => ({
  useQuery: (opts: unknown) => useQueryMock(opts),
  useQueryClient: () => ({ invalidateQueries }),
}))

vi.mock('../api/app', () => ({
  getAppInfo: vi.fn(),
}))

import { useAppInfo, useInvalidateAppInfo } from './useAppInfo'
import { queryKeys } from '../api/queryKeys'

beforeEach(() => {
  useQueryMock.mockReset()
  invalidateQueries.mockReset()
})

describe('useAppInfo', () => {
  it('queries under the single shared app key with staleTime Infinity', () => {
    useAppInfo()
    expect(useQueryMock).toHaveBeenCalledTimes(1)
    const opts = useQueryMock.mock.calls[0][0]
    expect(opts.queryKey).toEqual(queryKeys.app())
    expect(opts.staleTime).toBe(Infinity)
  })

  it('invalidates exactly the shared app key', () => {
    const invalidate = useInvalidateAppInfo()
    invalidate()
    expect(invalidateQueries).toHaveBeenCalledWith({ queryKey: queryKeys.app() })
  })

  it('uses the same key literal both sides depend on (app-info)', () => {
    expect(queryKeys.app()).toEqual(['app-info'])
  })
})
