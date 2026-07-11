import { describe, it, expect, beforeEach, vi } from 'vitest'
import { ref, reactive, nextTick } from 'vue'
import { setActivePinia, createPinia } from 'pinia'
import type { Tag } from '../api/tag'

// --- Dependency mocks (NOT the unit under test) ---
//
// The store instantiates vue-router (useRouter/useRoute) and vue-query
// (useQuery) at setup time. We mock those infrastructure dependencies so the
// store's OWN action/getter logic runs for real against controllable data.
//
// The route is `reactive` so the store's route-query-signature watcher fires
// when a test reassigns `mockRoute.query` — modelling a real back-button /
// in-session navigation to a filtered URL.

const mockRoute = reactive({
  path: '/document',
  name: 'documents' as string,
  query: {} as Record<string, string>,
})
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
// The tags query's SETTLED-SUCCESS signal (TanStack isSuccess): true once the
// query has SUCCESSFULLY returned at least once, including an empty [] result. An
// ERROR does NOT set it (unlike isFetched) — that is the BL-024 gate. Defaults to
// settled; tests exercising the pre-settle / loaded-empty / error paths drive it
// explicitly.
const tagsFetchedRef = ref(true)
const statsRef = ref<Record<string, number> | undefined>(undefined)
const coOccurrenceRef = ref<Array<{ tagA: string; tagB: string; count: number }> | undefined>(
  undefined,
)
// Captured options of the `tagFacets` useQuery call so tests can inspect its
// (reactive) query key and invoke its queryFn to observe the params it sends.
let facetsQueryOpts: any = null
vi.mock('@tanstack/vue-query', () => ({
  useQuery: (opts: any) => {
    const key = opts.queryKey
    // queryKey may be an array or a computed ref of an array.
    const resolved = typeof key === 'object' && 'value' in key ? key.value : key
    const name = Array.isArray(resolved) ? resolved[0] : resolved
    if (name === 'tags') return { data: tagsRef, isSuccess: tagsFetchedRef }
    if (name === 'tagStats') return { data: statsRef }
    if (name === 'tagCoOccurrence') return { data: coOccurrenceRef }
    if (name === 'tagFacets') {
      facetsQueryOpts = opts
      return { data: ref(undefined) }
    }
    return { data: ref(undefined) }
  },
}))

// Capture the args getTagFacets is called with, without hitting the network.
const getTagFacetsMock = vi.fn(() => Promise.resolve({ data: { facets: {}, total: 0 } }))
vi.mock('../api/tag', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/tag')>()
  return { ...actual, getTagFacets: (...args: any[]) => getTagFacetsMock(...(args as [])) }
})

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
    // The store persists viewMode to localStorage via a watcher; a shared
    // MemoryStorage polyfill makes the suite execution-order-dependent unless
    // reset between tests.
    localStorage.clear()
    setActivePinia(createPinia())
    mockRoute.path = '/document'
    mockRoute.name = 'documents'
    mockRoute.query = {}
    push.mockClear()
    replace.mockClear()
    getTagFacetsMock.mockClear()
    facetsQueryOpts = null
    tagsRef.value = TAGS
    tagsFetchedRef.value = true
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

  describe('tag exclusions (facet counts)', () => {
    it('excluded tag disappears from the relatedTags suggestion pills', () => {
      // alpha selected; beta and gamma both have co-occurring counts, but gamma
      // is excluded and must not be suggested.
      statsRef.value = { a: 5, b: 3, c: 2 }
      const store = useTagFilterStore()
      store.selectedTagIds = new Set(['a'])
      store.excludedTagIds = new Set(['c']) // gamma excluded
      const suggested = store.relatedTags.map((e) => e.tag.id)
      expect(suggested).toContain('b')
      expect(suggested).not.toContain('c')
    })

    it('includes the excluded ids in the tagFacets query key so a change refetches', () => {
      const store = useTagFilterStore()
      store.selectedTagIds = new Set(['a'])
      store.excludedTagIds = new Set(['c'])
      // facetsQueryOpts.queryKey is a computed ref of the reactive key.
      const key = facetsQueryOpts.queryKey.value
      expect(key[0]).toBe('tagFacets')
      // selected array, mode, then the excluded array.
      expect(key).toContainEqual(['c'])
      expect(key).toContainEqual(['a'])
    })

    it('passes the excluded ids through to getTagFacets when the query runs', () => {
      const store = useTagFilterStore()
      store.selectedTagIds = new Set(['a'])
      store.excludedTagIds = new Set(['c'])
      // Invoke the captured queryFn (vue-query is mocked, so it never auto-runs).
      facetsQueryOpts.queryFn()
      expect(getTagFacetsMock).toHaveBeenCalledWith(['a'], 'and', ['c'])
    })
  })

  describe('buildFilterQuery (single canonical serializer)', () => {
    it('serializes tags, exclude, mode and search', () => {
      const store = useTagFilterStore()
      store.selectedTagIds = new Set(['a', 'c'])
      store.excludedTagIds = new Set(['b'])
      store.tagMode = 'or'
      store.debouncedText = 'invoice'
      expect(store.buildFilterQuery()).toEqual({
        tags: 'a,c',
        exclude: 'b',
        mode: 'or',
        search: 'invoice',
      })
    })

    it('omits empty dimensions (no tags/exclude/mode/search keys)', () => {
      const store = useTagFilterStore()
      expect(store.buildFilterQuery()).toEqual({})
    })

    it('round-trips exclude: buildFilterQuery -> initFromUrl restores the excluded set', () => {
      // This is the P6 regression: full-view navigation dropped `exclude`.
      const store = useTagFilterStore()
      store.selectedTagIds = new Set(['a'])
      store.excludedTagIds = new Set(['c'])
      const query = store.buildFilterQuery()
      expect(query.exclude).toBe('c')

      // Simulate navigating away and back: a fresh store initialised from that URL.
      setActivePinia(createPinia())
      mockRoute.query = query
      const restored = useTagFilterStore()
      expect([...restored.selectedTagIds]).toEqual(['a'])
      expect([...restored.excludedTagIds]).toEqual(['c'])
    })
  })

  // --- #28: workflow=me is component-owned but the store's canonical rewrite must
  //     PRESERVE it. `buildFilterQuery` is the single serializer every navigation
  //     and the syncUrl `router.replace` build from; if it drops `workflow=me`, a
  //     tag/text/mode change silently strips the active "Assigned to me" filter
  //     from the URL. The store never OWNS the toggle (that lives in DocumentList),
  //     it only preserves the validated key present in the current route query. ---
  describe('workflow=me preservation (component-owned key, #28)', () => {
    it('buildFilterQuery preserves a validated workflow=me from the route query', () => {
      mockRoute.query = { workflow: 'me' }
      const store = useTagFilterStore()
      store.selectedTagIds = new Set(['a'])
      store.debouncedText = 'invoice'
      const query = store.buildFilterQuery()
      expect(query.workflow).toBe('me')
      // ...alongside the normal dimensions, not instead of them.
      expect(query.tags).toBe('a')
      expect(query.search).toBe('invoice')
    })

    it('buildFilterQuery canonicalizes away a non-"me" / array / unknown workflow value', () => {
      mockRoute.query = { workflow: 'them' } as unknown as Record<string, string>
      const store = useTagFilterStore()
      expect(store.buildFilterQuery().workflow).toBeUndefined()

      mockRoute.query = { workflow: ['me', 'me'] } as unknown as Record<string, string>
      const store2 = useTagFilterStore()
      expect(store2.buildFilterQuery().workflow).toBeUndefined()

      mockRoute.query = { workflow: '' } as unknown as Record<string, string>
      const store3 = useTagFilterStore()
      expect(store3.buildFilterQuery().workflow).toBeUndefined()
    })

    it('a user-driven tag change does NOT strip workflow=me from the canonical URL rewrite', async () => {
      // The blocking gap: syncUrl's router.replace(buildFilterQuery()) owns the
      // FULL query. With workflow=me live in the URL, changing the tag selection
      // must re-emit a query that STILL carries workflow=me.
      mockRoute.query = { workflow: 'me' } // settled at creation
      const store = useTagFilterStore()
      await nextTick()
      replace.mockClear()

      store.selectedTagIds = new Set(['a', 'c'])
      await nextTick()

      expect(replace).toHaveBeenCalled()
      const lastQuery = replace.mock.calls.at(-1)![0].query
      expect(lastQuery.tags).toBe('a,c')
      expect(lastQuery.workflow).toBe('me')
    })
  })

  describe('route-driven hydration (data re-emit must not clobber; route change must re-hydrate)', () => {
    it('a tagsData re-emit with an UNCHANGED route query does NOT reset the user selection', async () => {
      mockRoute.query = { tags: 'a' }
      const store = useTagFilterStore()
      // Initial hydration read `tags=a` from the URL.
      expect([...store.selectedTagIds]).toEqual(['a'])

      // User changes their live selection after load.
      store.selectedTagIds = new Set(['c'])

      // A ['tags'] refetch settles again (tag CRUD invalidates it; 60s stale) —
      // the route query is UNCHANGED, so hydration must NOT re-run and clobber
      // the live selection. (The original data-driven bug.) Toggle the settled
      // signal to model a real refetch cycle firing the retry watch.
      tagsFetchedRef.value = false
      await nextTick()
      tagsRef.value = [...TAGS]
      tagsFetchedRef.value = true
      await nextTick()
      expect([...store.selectedTagIds]).toEqual(['c'])
    })

    it('a genuine route-query change (back-button / in-session nav) RE-HYDRATES', async () => {
      mockRoute.query = { tags: 'a' }
      const store = useTagFilterStore()
      expect([...store.selectedTagIds]).toEqual(['a'])

      // User drifts the live selection, then navigates (back-button) to a
      // DIFFERENT filtered URL — the store must re-hydrate from the new query.
      store.selectedTagIds = new Set(['b'])
      mockRoute.query = { tags: 'c', exclude: 'a' }
      await nextTick()

      expect([...store.selectedTagIds]).toEqual(['c'])
      expect([...store.excludedTagIds]).toEqual(['a'])
    })

    it('back-nav that DROPS dimensions clears the stale selection/exclusion/mode/search', async () => {
      mockRoute.query = { tags: 'a', exclude: 'c', mode: 'or', search: 'invoice' }
      const store = useTagFilterStore()
      expect([...store.selectedTagIds]).toEqual(['a'])
      expect([...store.excludedTagIds]).toEqual(['c'])
      expect(store.tagMode).toBe('or')
      expect(store.debouncedText).toBe('invoice')

      // Navigate back to a BARE /document — every dimension is absent and must
      // reset to its default (the route query is the source of truth).
      mockRoute.query = {}
      await nextTick()

      expect([...store.selectedTagIds]).toEqual([])
      expect([...store.excludedTagIds]).toEqual([])
      expect(store.tagMode).toBe('and')
      expect(store.searchText).toBe('')
      expect(store.debouncedText).toBe('')
    })

    it('route query changes BEFORE tags settle, then tags settle → hydration still applies (not skipped)', async () => {
      // Tags query still in flight: NOT settled, so ids cannot be resolved yet.
      tagsFetchedRef.value = false
      tagsRef.value = []
      mockRoute.query = {}
      const store = useTagFilterStore()
      expect([...store.selectedTagIds]).toEqual([])

      // A route-query change arrives while the tags query is STILL in flight. The
      // ids can't resolve yet — the signature must NOT be committed, or the later
      // settle is skipped and this query's hydration is permanently missed.
      mockRoute.query = { tags: 'a', exclude: 'c' }
      await nextTick()
      expect([...store.selectedTagIds]).toEqual([]) // deferred

      // Tags query settles.
      tagsRef.value = [...TAGS]
      tagsFetchedRef.value = true
      await nextTick()

      // Hydration retried against the settled tags and applied.
      expect([...store.selectedTagIds]).toEqual(['a'])
      expect([...store.excludedTagIds]).toEqual(['c'])
    })

    it('tags settle to an EMPTY [] with a stale id in the URL → search still applies, no permanent pending', async () => {
      // The whack-a-mole root case: a legitimately loaded-empty tag list looks
      // identical to "still loading" if you infer from tagMap.size. Driving off
      // the SETTLED signal, an empty result must still complete hydration.
      tagsFetchedRef.value = false
      tagsRef.value = []
      mockRoute.query = { tags: 'stale', search: 'invoice' }
      const store = useTagFilterStore()
      // Tag-independent search applies IMMEDIATELY (above the defer); only the
      // tag-id resolution waits for the settled signal.
      expect(store.debouncedText).toBe('invoice')
      expect([...store.selectedTagIds]).toEqual([])

      // Tags query SETTLES to an empty list (no tags exist / stale id).
      tagsFetchedRef.value = true
      await nextTick()

      // Signature committed on a settled-empty result: the stale id resolves to
      // nothing, but the tag-independent search dimension IS applied.
      expect([...store.selectedTagIds]).toEqual([])
      expect(store.searchText).toBe('invoice')
      expect(store.debouncedText).toBe('invoice')

      // No permanent pending: a subsequent bare ['tags'] re-emit (unchanged
      // route) does not re-run hydration and clobber a later live selection.
      store.selectedTagIds = new Set(['a'])
      tagsRef.value = [...TAGS]
      await nextTick()
      expect([...store.selectedTagIds]).toEqual(['a'])
    })

    it('cold-load with ids present but tags NOT settled → search/mode apply immediately; tag selection resolves after settle', async () => {
      // The document query keys off debouncedText/tagMode — those must be live on
      // the very first render, BEFORE tags settle, or a cold-load runs the query
      // without the known search/mode until the tag list arrives.
      tagsFetchedRef.value = false
      tagsRef.value = []
      mockRoute.query = { tags: 'a', mode: 'or', search: 'invoice' }
      const store = useTagFilterStore()

      // Tag-independent dims already applied (query can run correctly now).
      expect(store.debouncedText).toBe('invoice')
      expect(store.tagMode).toBe('or')
      // Tag id resolution deferred until settle.
      expect([...store.selectedTagIds]).toEqual([])

      // Tags settle → the selection resolves, search/mode unchanged.
      tagsRef.value = [...TAGS]
      tagsFetchedRef.value = true
      await nextTick()
      expect([...store.selectedTagIds]).toEqual(['a'])
      expect(store.debouncedText).toBe('invoice')
      expect(store.tagMode).toBe('or')
    })

    it('cold-load does NOT rewrite the URL to drop the not-yet-resolved tag (no write-back loop)', async () => {
      // The feedback loop: initFromUrl applies search/mode before the tag id
      // resolves → the syncUrl watcher fires → router.replace with a query that
      // has NO tags → the tag is lost from the URL before it can hydrate.
      tagsFetchedRef.value = false
      tagsRef.value = []
      mockRoute.query = { tags: 'a', search: 'invoice' }
      const store = useTagFilterStore()
      await nextTick()

      // While hydrating, syncUrl is suppressed: any router.replace this early must
      // NOT drop the tag (i.e. no replace carrying a tag-less query).
      const droppedTag = replace.mock.calls.some((c) => {
        const q = (c[0]?.query ?? {}) as Record<string, string>
        return q.search === 'invoice' && !q.tags
      })
      expect(droppedTag).toBe(false)

      // Tags settle → hydration completes; state fully reflects the URL.
      tagsRef.value = [...TAGS]
      tagsFetchedRef.value = true
      await nextTick()
      expect([...store.selectedTagIds]).toEqual(['a'])
      expect(store.debouncedText).toBe('invoice')
      // The fully-resolved state serializes back to the original query.
      expect(store.buildFilterQuery()).toEqual({ tags: 'a', search: 'invoice' })
    })

    it('after hydration completes, a user-driven filter change still syncs to the URL (suppression lifted)', async () => {
      mockRoute.query = { tags: 'a' } // settled at creation (tagsFetchedRef true)
      const store = useTagFilterStore()
      await nextTick()
      replace.mockClear()

      // A genuine user change AFTER hydration must reach the URL.
      store.selectedTagIds = new Set(['a', 'c'])
      await nextTick()

      expect(replace).toHaveBeenCalled()
      const lastQuery = replace.mock.calls.at(-1)![0].query
      expect(lastQuery.tags).toBe('a,c')
    })

    // --- BL-024: a transient /api/tag/list failure must NOT rewrite the URL ---
    it('cold deep-link while the tags query ERRORS keeps the raw ids and never rewrites the URL', async () => {
      // Cold deep-link with ids + search. The tags query FAILS (500): isSuccess
      // stays false (an error is NOT a successful settle). Gating on isSuccess must
      // DEFER id resolution — the empty tagMap must not resolve the ids to nothing
      // and let syncUrl rewrite the URL to a bare ?search=.
      tagsFetchedRef.value = false // isSuccess=false models the error (never succeeded)
      tagsRef.value = []
      mockRoute.query = { tags: 'a', exclude: 'c', search: 'invoice' }
      const store = useTagFilterStore()
      await nextTick()

      // Tag-independent search still applies, but the URL must NOT be rewritten to
      // the bare "?search=invoice" that drops the not-yet-resolvable tags/exclude
      // (the exact BL-024 symptom — a rewrite carrying THIS store's search but
      // missing its tag ids, which a later refetch could never restore). We match
      // that specific shape so unrelated bare-{} replaces from other test stores'
      // lingering watchers (the harness does not tear stores down) don't mask it.
      const rewroteBareSearch = replace.mock.calls.some((c) => {
        const q = (c[0]?.query ?? {}) as Record<string, string>
        return q.search === 'invoice' && (!q.tags || !q.exclude)
      })
      expect(rewroteBareSearch).toBe(false)

      // A later SUCCESSFUL refetch settles → the ids finally resolve, unchanged URL.
      tagsRef.value = [...TAGS]
      tagsFetchedRef.value = true
      await nextTick()
      expect([...store.selectedTagIds]).toEqual(['a'])
      expect([...store.excludedTagIds]).toEqual(['c'])
      expect(store.buildFilterQuery()).toEqual({ tags: 'a', exclude: 'c', search: 'invoice' })
    })
  })

  // --- BL-023: the filter store must survive navigation to non-document routes ---
  describe('route-scoped hydration (BL-023)', () => {
    it('does NOT hydrate (wipe) the store when the route is NOT the documents list', async () => {
      // Seed a live filter on the documents list.
      mockRoute.query = { tags: 'a', exclude: 'c' }
      const store = useTagFilterStore()
      expect([...store.selectedTagIds]).toEqual(['a'])
      expect([...store.excludedTagIds]).toEqual(['c'])

      // Navigate to Settings/Tags/doc-add: vue-router replaces the query with the
      // new route's (empty) query. Hydration must NOT run here, or it would clear
      // the selection and kill the "Back to documents" filter-preserving affordance.
      mockRoute.name = 'settings-account'
      mockRoute.path = '/settings/account'
      mockRoute.query = {}
      await nextTick()

      expect([...store.selectedTagIds]).toEqual(['a'])
      expect([...store.excludedTagIds]).toEqual(['c'])
    })

    it('re-hydrates on ENTRY back to the documents route', async () => {
      // On the documents list with a filter.
      mockRoute.query = { tags: 'a' }
      const store = useTagFilterStore()
      expect([...store.selectedTagIds]).toEqual(['a'])

      // Leave to a non-document route (store preserved, per the test above).
      mockRoute.name = 'document-add'
      mockRoute.path = '/document/add'
      mockRoute.query = {}
      await nextTick()
      expect([...store.selectedTagIds]).toEqual(['a'])

      // Return to the documents list carrying a DIFFERENT filter query — entry to
      // the documents route must re-hydrate from that query.
      mockRoute.name = 'documents'
      mockRoute.path = '/document'
      mockRoute.query = { tags: 'c', exclude: 'a' }
      await nextTick()
      expect([...store.selectedTagIds]).toEqual(['c'])
      expect([...store.excludedTagIds]).toEqual(['a'])
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
