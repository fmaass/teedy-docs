import { describe, it, expect, beforeEach, vi } from 'vitest'
import { ref, computed } from 'vue'

// --- Dependency mocks (NOT the unit under test) ---
//
// useAccessCounts wraps vue-query and the /access API. Mocking useQuery lets the test assert the
// composable's OWN contract: one shared key so both consumers of a document view share a single
// request, and an `enabled` gate so the count is never read before the open that produced it.
const useQueryMock = vi.fn()
vi.mock('@tanstack/vue-query', () => ({
  useQuery: (opts: unknown) => useQueryMock(opts),
}))

vi.mock('../api/access', () => ({
  getDocumentAccessCounts: vi.fn(),
}))

import { useAccessCounts, fileAccessCountMap } from './useAccessCounts'
import { queryKeys } from '../api/queryKeys'

beforeEach(() => {
  useQueryMock.mockReset()
})

describe('useAccessCounts (#300)', () => {
  it('queries under the one shared access-counts key for the document', () => {
    useAccessCounts(ref('doc-7'), ref(true))
    expect(useQueryMock).toHaveBeenCalledTimes(1)
    const opts = useQueryMock.mock.calls[0][0]
    expect(opts.queryKey.value).toEqual(queryKeys.accessCounts('doc-7'))
  })

  it('re-keys when the document changes, so two documents never share a cached count', () => {
    const id = ref('doc-a')
    useAccessCounts(id, ref(true))
    const opts = useQueryMock.mock.calls[0][0]
    expect(opts.queryKey.value).toEqual(queryKeys.accessCounts('doc-a'))
    id.value = 'doc-b'
    expect(opts.queryKey.value).toEqual(queryKeys.accessCounts('doc-b'))
  })

  it('passes the caller gate straight through, so the counts wait for the document read', () => {
    const docLoaded = ref(false)
    useAccessCounts(ref('doc-7'), computed(() => docLoaded.value))
    const opts = useQueryMock.mock.calls[0][0]
    expect(opts.enabled.value).toBe(false)
    docLoaded.value = true
    expect(opts.enabled.value).toBe(true)
  })

  it('uses the key literal both consumers depend on', () => {
    expect(queryKeys.accessCounts('doc-7')).toEqual(['access-counts', 'doc-7'])
  })
})

describe('fileAccessCountMap', () => {
  it('indexes each file id to its own count', () => {
    const map = fileAccessCountMap({
      count: 9,
      files: [
        { id: 'f1', count: 3 },
        { id: 'f2', count: 0 },
      ],
    })
    expect(map).toEqual({ f1: 3, f2: 0 })
  })

  it('is empty while the counts are still loading, so no row shows a premature zero', () => {
    expect(fileAccessCountMap(undefined)).toEqual({})
    // A row asking for an unknown file gets undefined, which is what the badge treats as "loading".
    expect(fileAccessCountMap(undefined)['f1']).toBeUndefined()
  })
})
