import { describe, it, expect, beforeEach, vi } from 'vitest'
import { ref } from 'vue'
import { setActivePinia, createPinia } from 'pinia'
import type { Tag } from '../api/tag'

// --- Dependency mocks (NOT the unit under test) ---
//
// The store instantiates vue-router (useRouter/useRoute) and vue-query
// (useQuery) at setup time. We mock those infrastructure dependencies so the
// store's OWN action/getter logic runs for real against controllable data.

const mockRoute = { path: '/document', query: {} as Record<string, string> }
const push = vi.fn()
const replace = vi.fn()

vi.mock('vue-router', () => ({
  useRouter: () => ({ push, replace }),
  useRoute: () => mockRoute,
}))

// Each useQuery call returns a data ref; we hand back tags for the first call
// (listTags) and empty data for the stats/facets/co-occurrence calls. The store
// distinguishes them only by consuming `.data`, so a shared tags ref for the
// tag list plus empty refs for the rest is sufficient for action-logic tests.
const tagsRef = ref<Tag[]>([])
vi.mock('@tanstack/vue-query', () => ({
  useQuery: (opts: any) => {
    const key = opts.queryKey
    // queryKey may be an array or a computed ref of an array.
    const resolved = typeof key === 'object' && 'value' in key ? key.value : key
    const name = Array.isArray(resolved) ? resolved[0] : resolved
    if (name === 'tags') return { data: tagsRef }
    return { data: ref(undefined) }
  },
}))

import { useTagFilterStore } from './tagFilter'

const TAGS: Tag[] = [
  { id: 'a', name: 'alpha', color: '#111', parent: null },
  { id: 'b', name: 'beta', color: '#222', parent: 'a' },
  { id: 'c', name: 'gamma', color: '#333', parent: null },
]

describe('tagFilter store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    mockRoute.path = '/document'
    mockRoute.query = {}
    push.mockClear()
    replace.mockClear()
    tagsRef.value = TAGS
  })

  describe('resolveCompoundKey', () => {
    it('strips a "parent__child" prefix to the child id', () => {
      const store = useTagFilterStore()
      expect(store.resolveCompoundKey('a__b')).toBe('b')
    })

    it('returns a plain key unchanged', () => {
      const store = useTagFilterStore()
      expect(store.resolveCompoundKey('b')).toBe('b')
    })
  })

  describe('toggleTag', () => {
    it('first toggle selects the tag (tree mode also pulls in ancestors)', () => {
      const store = useTagFilterStore()
      store.toggleTag('b') // b has parent a
      expect([...store.selectedTagIds]).toEqual(expect.arrayContaining(['a', 'b']))
      expect(store.selectedTagIds.has('b')).toBe(true)
      expect(store.selectedTagIds.has('a')).toBe(true)
    })

    it('second toggle moves a selected tag into the excluded set', () => {
      const store = useTagFilterStore()
      store.toggleTag('c')
      expect(store.selectedTagIds.has('c')).toBe(true)
      store.toggleTag('c')
      expect(store.selectedTagIds.has('c')).toBe(false)
      expect(store.excludedTagIds.has('c')).toBe(true)
    })

    it('third toggle clears an excluded tag entirely', () => {
      const store = useTagFilterStore()
      store.toggleTag('c')
      store.toggleTag('c')
      store.toggleTag('c')
      expect(store.selectedTagIds.has('c')).toBe(false)
      expect(store.excludedTagIds.has('c')).toBe(false)
    })

    it('navigates to documents when toggled from a non-document route', () => {
      mockRoute.path = '/settings'
      const store = useTagFilterStore()
      store.toggleTag('c')
      expect(push).toHaveBeenCalled()
    })
  })

  describe('removeTag', () => {
    it('removes a tag from both selected and excluded sets', () => {
      const store = useTagFilterStore()
      store.toggleTag('c') // select c
      store.removeTag('c')
      expect(store.selectedTagIds.has('c')).toBe(false)
      expect(store.excludedTagIds.has('c')).toBe(false)
    })
  })

  describe('combinedSearch getter', () => {
    it('builds "tag:" / "!tag:" tokens plus free text', () => {
      const store = useTagFilterStore()
      store.selectedTagIds = new Set(['c']) // gamma
      store.excludedTagIds = new Set(['a']) // alpha
      store.debouncedText = 'invoice'
      expect(store.combinedSearch).toBe('tag:gamma !tag:alpha invoice')
    })

    it('is empty when nothing is selected and no text is entered', () => {
      const store = useTagFilterStore()
      expect(store.combinedSearch).toBe('')
    })
  })

  describe('hasActiveFilters getter', () => {
    it('is false with no selection, exclusion, or text', () => {
      const store = useTagFilterStore()
      expect(store.hasActiveFilters).toBe(false)
    })

    it('is true when a tag is selected', () => {
      const store = useTagFilterStore()
      store.selectedTagIds = new Set(['c'])
      expect(store.hasActiveFilters).toBe(true)
    })

    it('is true when only free text is present', () => {
      const store = useTagFilterStore()
      store.debouncedText = 'hello'
      expect(store.hasActiveFilters).toBe(true)
    })
  })

  describe('clearFilters', () => {
    it('resets selection, exclusion, text and mode back to defaults', () => {
      const store = useTagFilterStore()
      store.selectedTagIds = new Set(['c'])
      store.excludedTagIds = new Set(['a'])
      store.searchText = 'x'
      store.debouncedText = 'x'
      store.tagMode = 'or'

      store.clearFilters()

      expect(store.selectedTagIds.size).toBe(0)
      expect(store.excludedTagIds.size).toBe(0)
      expect(store.searchText).toBe('')
      expect(store.debouncedText).toBe('')
      expect(store.tagMode).toBe('and')
    })
  })
})
