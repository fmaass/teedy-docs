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

// Each useQuery call returns a data ref keyed by the query name so the store's
// OWN getters (facet tree building, tagCounts) run for real against controllable
// data. `tags` -> the tag list, `tagStats` -> per-tag doc counts, and
// `tagCoOccurrence` -> the co-occurrence pairs that drive the facet children.
const tagsRef = ref<Tag[]>([])
const statsRef = ref<Record<string, number> | undefined>(undefined)
const coOccurrenceRef = ref<Array<{ tagA: string; tagB: string; count: number }> | undefined>(
  undefined,
)
vi.mock('@tanstack/vue-query', () => ({
  useQuery: (opts: any) => {
    const key = opts.queryKey
    // queryKey may be an array or a computed ref of an array.
    const resolved = typeof key === 'object' && 'value' in key ? key.value : key
    const name = Array.isArray(resolved) ? resolved[0] : resolved
    if (name === 'tags') return { data: tagsRef }
    if (name === 'tagStats') return { data: statsRef }
    if (name === 'tagCoOccurrence') return { data: coOccurrenceRef }
    return { data: ref(undefined) }
  },
}))

import { useTagFilterStore } from './tagFilter'

const TAGS: Tag[] = [
  { id: 'a', name: 'alpha', color: '#111', parent: null },
  { id: 'b', name: 'beta', color: '#222', parent: 'a' },
  { id: 'c', name: 'gamma', color: '#333', parent: null },
]

// A flat set (no parent nesting) exercised in facets mode, where the tree is
// built from co-occurrence pairs rather than parent/child hierarchy.
const FLAT_TAGS: Tag[] = [
  { id: 'a', name: 'alpha', color: '#111', parent: null },
  { id: 'b', name: 'beta', color: '#222', parent: null },
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
    statsRef.value = undefined
    coOccurrenceRef.value = undefined
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

  describe('facets mode', () => {
    // Co-occurrence: alpha+beta (3), beta+gamma (2). All three tags have docs.
    function seedFacets() {
      tagsRef.value = FLAT_TAGS
      statsRef.value = { a: 5, b: 6, c: 4 }
      coOccurrenceRef.value = [
        { tagA: 'a', tagB: 'b', count: 3 },
        { tagA: 'b', tagB: 'c', count: 2 },
      ]
    }

    it('builds one top-level node per tag with docCount>0, keyed by tag id', () => {
      seedFacets()
      const store = useTagFilterStore()
      store.viewMode = 'facets'
      const nodes = store.activeTreeNodes
      expect(nodes.map((n) => n.key).sort()).toEqual(['a', 'b', 'c'])
      expect(nodes.map((n) => n.label).sort()).toEqual(['alpha', 'beta', 'gamma'])
    })

    it('omits tags with a zero doc count from the facet tree', () => {
      tagsRef.value = FLAT_TAGS
      statsRef.value = { a: 5, b: 0, c: 4 } // beta has no docs
      coOccurrenceRef.value = []
      const store = useTagFilterStore()
      store.viewMode = 'facets'
      expect(store.activeTreeNodes.map((n) => n.key).sort()).toEqual(['a', 'c'])
    })

    it('gives each top-level node compound co-occurrence children keyed `parent__child`', () => {
      seedFacets()
      const store = useTagFilterStore()
      store.viewMode = 'facets'
      const beta = store.activeTreeNodes.find((n) => n.key === 'b')!
      // beta co-occurs with alpha and gamma
      expect(beta.children.map((c: any) => c.key).sort()).toEqual(['b__a', 'b__c'])
      const childA = beta.children.find((c: any) => c.key === 'b__a')!
      expect(childA.label).toBe('alpha')
    })

    it('clicking a top-level facet selects exactly that tag', () => {
      seedFacets()
      const store = useTagFilterStore()
      store.viewMode = 'facets'
      store.toggleTag('a') // top-level node key === tag id
      expect([...store.selectedTagIds]).toEqual(['a'])
    })

    it("clicking a co-occurrence child key 'A__B' selects B (not A, not a fixed tag)", () => {
      seedFacets()
      const store = useTagFilterStore()
      store.viewMode = 'facets'
      store.toggleTag('a__b') // child under alpha, representing beta
      expect([...store.selectedTagIds]).toEqual(['b'])
      expect(store.selectedTagIds.has('a')).toBe(false)
    })

    it('does NOT pull in ancestors in facets mode (that is tree-only behavior)', () => {
      tagsRef.value = TAGS // b has parent a
      statsRef.value = { a: 1, b: 1, c: 1 }
      coOccurrenceRef.value = []
      const store = useTagFilterStore()
      store.viewMode = 'facets'
      store.toggleTag('b')
      expect([...store.selectedTagIds]).toEqual(['b'])
      expect(store.selectedTagIds.has('a')).toBe(false)
    })

    it('resolves the child key to the child tag for exclusion on second toggle', () => {
      seedFacets()
      const store = useTagFilterStore()
      store.viewMode = 'facets'
      store.toggleTag('a__c') // select gamma via a compound key
      expect(store.selectedTagIds.has('c')).toBe(true)
      store.toggleTag('a__c') // second toggle excludes the SAME resolved tag
      expect(store.selectedTagIds.has('c')).toBe(false)
      expect(store.excludedTagIds.has('c')).toBe(true)
    })
  })

  describe('meta-tag hiding (facets-scoped)', () => {
    // alpha, beta (normal) + __recent (meta), all with docs.
    const META_TAGS: Tag[] = [
      { id: 'a', name: 'alpha', color: '#111', parent: null },
      { id: 'b', name: 'beta', color: '#222', parent: null },
      { id: 'm', name: '__recent', color: '#999', parent: null },
    ]

    it('Tree mode nodes STILL include `__`-prefixed tags (filter is facets-only)', () => {
      tagsRef.value = META_TAGS
      const store = useTagFilterStore()
      store.viewMode = 'tree'
      const keys = store.tagTreeNodes.map((n) => n.key)
      expect(keys).toContain('m')
      expect(store.tagTreeNodes.find((n) => n.key === 'm')!.label).toBe('__recent')
    })

    it('facet suggestions (relatedTags) exclude `__`-prefixed tags', () => {
      tagsRef.value = META_TAGS
      statsRef.value = { a: 3, b: 2, m: 9 }
      const store = useTagFilterStore()
      store.viewMode = 'facets'
      store.selectedTagIds = new Set(['a']) // drives relatedTags off tagCounts
      const suggested = store.relatedTags.map((e) => e.tag.id)
      expect(suggested).toContain('b')
      expect(suggested).not.toContain('m') // __recent hidden from suggestions
    })

    it('an already-selected `__` tag still renders as an active chip (representable)', () => {
      tagsRef.value = META_TAGS
      const store = useTagFilterStore()
      store.viewMode = 'facets'
      store.selectedTagIds = new Set(['m']) // meta-tag selected (e.g. from Tree/URL)
      // selectedTags powers the removable active chips — must include the meta-tag.
      expect(store.selectedTags.map((t) => t.id)).toContain('m')
      // ...but it is NOT re-suggested to itself.
      expect(store.relatedTags.map((e) => e.tag.id)).not.toContain('m')
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
