import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { defineComponent, h, computed, watch as vueWatch } from 'vue'

// --- Router under mock: assert the dblclick navigation target + history state ---
const routerPush = vi.hoisted(() => vi.fn())
const routerReplace = vi.hoisted(() => vi.fn())
// A hoisted holder for the mutable reactive route. The reactive object itself is
// created inside the (async) vue-router mock factory — where a dynamic import of
// vue is allowed — and stashed on the holder so tests can drive workflow=me
// hydration by reassigning `routeHolder.route.query`. `reactive` lets the
// component's immediate route-query watcher fire on that reassignment, modelling a
// real Back / cold-load navigation.
const routeHolder = vi.hoisted(() => ({ route: { query: {} as Record<string, unknown> } }))
vi.mock('vue-router', async () => {
  const { reactive } = await vi.importActual<typeof import('vue')>('vue')
  routeHolder.route = reactive({ query: {} as Record<string, unknown> })
  return {
    // resolve echoes the query into fullPath so a test can assert returnTo carries
    // the active filter (buildFilterQuery output), not just a bare route.
    useRouter: () => ({
      push: routerPush,
      replace: routerReplace,
      resolve: (to: { query?: Record<string, string> }) => ({
        fullPath: '/documents?' + new URLSearchParams(to.query ?? {}).toString(),
      }),
    }),
    useRoute: () => routeHolder.route,
  }
})
// Always read through the holder — the factory swaps `routeHolder.route` for a
// reactive object at mock-init time, so a captured reference would go stale.
const mockRoute = { get query() { return routeHolder.route.query }, set query(v: Record<string, unknown>) { routeHolder.route.query = v } }

// Mutable filter state so a test can switch between "no filters" and "active
// filter" without a second global mock. Read by the tagFilter store mock below.
// `filterQuery` mirrors the real store contract: buildFilterQuery preserves a
// validated workflow=me present in the route query (component-owned key).
// Same holder pattern as the route: the store mock factory swaps `state` for a
// Vue-reactive object so a test mutation of debouncedText propagates through the
// store's `combinedSearch` getter into the component's reactive queryKey — which
// makes the mocked list query REFETCH (see the vue-query mock), so a test can
// assert the params of a genuinely NEW request, not the mount-time one.
const filterHolder = vi.hoisted(() => ({
  state: {
    selectedTags: [] as { id: string; name: string }[],
    debouncedText: '',
    filterQuery: {} as Record<string, string>,
  },
}))
// Delegating accessor so tests keep the ergonomic `filterState.x = y` writes while
// always hitting the (swapped-in) reactive object.
const filterState = {
  get selectedTags() { return filterHolder.state.selectedTags },
  set selectedTags(v: { id: string; name: string }[]) { filterHolder.state.selectedTags = v },
  get debouncedText() { return filterHolder.state.debouncedText },
  set debouncedText(v: string) { filterHolder.state.debouncedText = v },
  get filterQuery() { return filterHolder.state.filterQuery },
  set filterQuery(v: Record<string, string>) { filterHolder.state.filterQuery = v },
}

vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (k: string) => k }) }))

// --- Document API: two documents in the list, none of the mutation paths exercised ---
function makeDoc(id: string) {
  return {
    id,
    title: `Doc ${id}`,
    description: '',
    create_date: 0,
    update_date: 0,
    language: 'eng',
    file_id: null,
    file_count: 0,
    tags: [],
    shared: false,
  }
}
const doc = makeDoc('doc-42')
const docB = makeDoc('doc-99')
vi.mock('../../api/document', () => ({
  listDocuments: vi.fn(() => Promise.resolve({ data: { documents: [doc, docB], total: 2 } })),
  getDocument: vi.fn((id: string) => Promise.resolve({ data: makeDoc(id) })),
  updateDocument: vi.fn(),
  deleteDocument: vi.fn(),
}))

// --- Tag filter store: minimal surface the view reads ---
vi.mock('../../stores/tagFilter', async () => {
  const { reactive } = await vi.importActual<typeof import('vue')>('vue')
  filterHolder.state = reactive(filterHolder.state)
  return {
    useTagFilterStore: () => ({
      // LIVE getter over the reactive state (like the real store's computed):
      // a debouncedText mutation changes the component's queryKey and triggers
      // the mocked query's refetch — required for non-vacuous "params survive a
      // filter change" assertions.
      get combinedSearch() { return filterHolder.state.debouncedText },
      tagMode: 'and',
      selectedTagIds: new Set(),
      get selectedTags() { return filterHolder.state.selectedTags },
      excludedTags: [],
      relatedTags: [],
      allTags: [],
      get debouncedText() { return filterHolder.state.debouncedText },
      hasActiveFilters: false,
      searchText: '',
      clearFilters: vi.fn(),
      removeTag: vi.fn(),
      toggleTag: vi.fn(),
      // Mirrors the real store: the canonical serializer preserves a validated
      // workflow=me present in the route query (component-owned key), so returnTo
      // and the syncUrl rewrite never strip it.
      buildFilterQuery: () => ({
        ...filterHolder.state.filterQuery,
        ...(mockRoute.query.workflow === 'me' ? { workflow: 'me' } : {}),
      }),
    }),
  }
})

vi.mock('../../composables/useDocumentTags', () => ({
  useDocumentTags: () => ({ addTag: vi.fn(), removeTag: vi.fn() }),
}))
vi.mock('../../composables/useConfirmDanger', () => ({
  useConfirmDanger: () => ({ confirmDanger: vi.fn() }),
}))
vi.mock('../../composables/useClampedOffset', () => ({ useClampedOffset: vi.fn() }))
vi.mock('primevue/usetoast', () => ({ useToast: () => ({ add: vi.fn() }) }))

// --- vue-query: return the mocked list synchronously ---
vi.mock('@tanstack/vue-query', () => ({
  useQuery: (opts: {
    queryKey?: { value: unknown }
    enabled?: { value: boolean }
    queryFn?: () => unknown
  }) => {
    // The slide-over query carries an `enabled` ref; its queryKey is
    // ['document', id]. Reactively derive the currently-open doc from the key so
    // the rendered slide-over reflects WHICH row won a coalesced debounce.
    if (opts.enabled) {
      const data = computed(() => {
        if (!opts.enabled!.value) return null
        const key = opts.queryKey?.value as [string, string] | undefined
        const id = key?.[1]
        return id ? makeDoc(id) : null
      })
      return {
        data,
        isLoading: { value: false },
        isError: { value: false },
        error: { value: null },
        refetch: vi.fn(),
      }
    }
    // The list query resolves to our documents. Invoke the real queryFn so the
    // listDocuments mock records the ACTUAL params the view sends (proving
    // search[searchworkflow]=me is threaded when the workflow filter is active).
    // Re-run on every reactive queryKey change to mirror TanStack Query's
    // refetch-on-key-change — this is what makes a Back-nav (which flips
    // workflowMe, a queryKey member) re-issue listDocuments with the new params.
    opts.queryFn?.()
    if (opts.queryKey && 'value' in opts.queryKey) {
      vueWatch(() => opts.queryKey!.value, () => opts.queryFn?.())
    }
    return {
      data: { value: { documents: [doc, docB], total: 2 } },
      isLoading: { value: false },
      isError: { value: false },
      error: { value: null },
      refetch: vi.fn(),
    }
  },
  useQueryClient: () => ({ invalidateQueries: vi.fn() }),
  keepPreviousData: undefined,
}))

// --- Stub child components. DocumentTable re-emits row-click / row-dblclick so we
//     can drive both interaction paths from the test without PrimeVue internals. ---
const DocumentTableStub = defineComponent({
  props: ['documents', 'selection'],
  emits: ['rowClick', 'rowDblclick', 'rowContextMenu', 'sort', 'update:selection'],
  setup(props, { emit }) {
    return () =>
      h('div', { class: 'doc-table-stub' }, [
        h('button', { class: 'single', onClick: () => emit('rowClick', props.documents[0]) }, 'single'),
        h('button', { class: 'single-b', onClick: () => emit('rowClick', props.documents[1]) }, 'single-b'),
        h('button', { class: 'double', onClick: () => emit('rowDblclick', props.documents[0]) }, 'double'),
        // Drive a real multi-selection through the v-model:selection binding so the
        // parent's selectedDocs (and thus the bulk toolbar) reflects it — used by the
        // B2 gallery-clears-selection test.
        h('button', { class: 'select-one', onClick: () => emit('update:selection', [props.documents[0]]) }, 'select'),
      ])
  },
})
const passthrough = defineComponent({ setup: () => () => h('div') })

// Gallery stub mirrors the table stub's re-emit contract so a test can drive the
// open paths in gallery mode too, and so a test can assert WHICH mode is rendered.
const DocumentGalleryStub = defineComponent({
  props: ['documents'],
  emits: ['cardClick', 'cardDblclick'],
  setup(props, { emit }) {
    return () =>
      h('div', { class: 'doc-gallery-stub' }, [
        h('button', { class: 'card-single', onClick: () => emit('cardClick', props.documents[0]) }, 'card'),
        h('button', { class: 'card-double', onClick: () => emit('cardDblclick', props.documents[0]) }, 'card-dbl'),
      ])
  },
})

// SelectButton stub: exposes the current model value and lets a test switch modes
// by emitting update:modelValue (the real control's contract).
const SelectButtonStub = defineComponent({
  props: ['modelValue', 'options'],
  emits: ['update:modelValue'],
  setup(props, { emit }) {
    return () =>
      h('div', { class: 'view-toggle-stub', 'data-mode': props.modelValue }, [
        h('button', { class: 'to-gallery', onClick: () => emit('update:modelValue', 'gallery') }, 'gallery'),
        h('button', { class: 'to-list', onClick: () => emit('update:modelValue', 'list') }, 'list'),
      ])
  },
})

import DocumentList from './DocumentList.vue'
import { listDocuments } from '../../api/document'
const listDocumentsMock = listDocuments as unknown as ReturnType<typeof vi.fn>

function mountView() {
  return mount(DocumentList, {
    global: {
      stubs: {
        DocumentTable: DocumentTableStub,
        DocumentGallery: DocumentGalleryStub,
        SelectButton: SelectButtonStub,
        Paginator: passthrough,
        DocumentSlideOver: {
          props: ['visible', 'document'],
          template: '<div class="slide-over" v-if="visible">{{ document?.id }}</div>',
        },
        DocumentSearchBar: passthrough,
        SavedFilters: passthrough,
        TagFilterChips: passthrough,
        BulkActionBar: { template: '<div class="bulk-bar-stub" />' },
        EmptyState: passthrough,
        ErrorState: passthrough,
        ContextMenu: passthrough,
        ToggleButton: passthrough,
      },
      directives: { tooltip: {} },
    },
  })
}

describe('DocumentList — single vs double click (#25)', () => {
  beforeEach(() => {
    routerPush.mockReset()
    routerReplace.mockReset()
    mockRoute.query = {}
    filterState.selectedTags = []
    filterState.debouncedText = ''
    filterState.filterQuery = {}
    vi.useFakeTimers()
  })

  it('double-click navigates to the full document view route (carrying returnTo state)', async () => {
    const wrapper = mountView()
    await wrapper.find('button.double').trigger('click')
    expect(routerPush).toHaveBeenCalledWith({
      name: 'document-view',
      params: { id: 'doc-42' },
      state: { returnTo: '/documents?', filterLabel: undefined },
    })
    vi.useRealTimers()
  })

  it('double-click carries the ACTIVE filter as returnTo + filterLabel (no context loss)', async () => {
    // An active tag filter + free-text search — the same state openFullView sends.
    filterState.selectedTags = [{ id: 't1', name: 'invoice' }]
    filterState.debouncedText = 'report'
    filterState.filterQuery = { tags: 't1', q: 'report' }
    const wrapper = mountView()
    await wrapper.find('button.double').trigger('click')
    expect(routerPush).toHaveBeenCalledWith({
      name: 'document-view',
      params: { id: 'doc-42' },
      state: {
        returnTo: '/documents?tags=t1&q=report',
        filterLabel: 'invoice · "report"',
      },
    })
    vi.useRealTimers()
  })

  it('a single click (after the debounce window) opens the slide-over', async () => {
    const wrapper = mountView()
    await wrapper.find('button.single').trigger('click')
    // Slide-over must NOT open synchronously — it is debounced.
    expect(wrapper.find('.slide-over').exists()).toBe(false)
    vi.advanceTimersByTime(300)
    await flushPromises()
    expect(wrapper.find('.slide-over').exists()).toBe(true)
    vi.useRealTimers()
  })

  it('a double-click preceded by two single-clicks does NOT leave a stale slide-over open', async () => {
    const wrapper = mountView()
    // Simulate the native sequence: click, click, then dblclick.
    await wrapper.find('button.single').trigger('click')
    await wrapper.find('button.single').trigger('click')
    await wrapper.find('button.double').trigger('click')
    vi.advanceTimersByTime(300)
    await flushPromises()
    // Navigation happened; the pending slide-over was cancelled.
    expect(routerPush).toHaveBeenCalledWith(
      expect.objectContaining({ name: 'document-view', params: { id: 'doc-42' } }),
    )
    expect(wrapper.find('.slide-over').exists()).toBe(false)
    vi.useRealTimers()
  })

  it('the LATEST clicked row wins a coalesced debounce (click A then B → B opens)', async () => {
    const wrapper = mountView()
    // Two single-clicks on DIFFERENT rows within the debounce window.
    await wrapper.find('button.single').trigger('click')     // doc-42
    await wrapper.find('button.single-b').trigger('click')   // doc-99
    vi.advanceTimersByTime(300)
    await flushPromises()
    const slide = wrapper.find('.slide-over')
    expect(slide.exists()).toBe(true)
    // The debounce coalesced to a single open of the LAST-clicked row.
    expect(slide.text()).toBe('doc-99')
    vi.useRealTimers()
  })

  it('a SLOW double-click (full native sequence) neither strands NOR reopens the slide-over', async () => {
    const wrapper = mountView()
    // Full slow-user native sequence:
    // 1. single-click, 250ms elapses → T1 fires → slide-over OPENS (pending=null).
    await wrapper.find('button.single').trigger('click')
    vi.advanceTimersByTime(300)
    await flushPromises()
    expect(wrapper.find('.slide-over').exists()).toBe(true)
    // 2. second single-click while open → schedules a NEW debounce timer T2.
    await wrapper.find('button.single').trigger('click')
    // 3. the browser recognises the double-click → openDocumentFull runs.
    await wrapper.find('button.double').trigger('click')
    // 4. advance past the debounce so any SURVIVING T2 would fire and reopen.
    vi.advanceTimersByTime(300)
    await flushPromises()
    // Invariant: navigation happened, the slide-over closed on navigate, and the
    // cancelled T2 did NOT reopen it over the document view.
    expect(routerPush).toHaveBeenCalledWith(
      expect.objectContaining({ name: 'document-view', params: { id: 'doc-42' } }),
    )
    expect(wrapper.find('.slide-over').exists()).toBe(false)
    vi.useRealTimers()
  })
})

// --- #28: the "Assigned to me" (workflow=me) filter round-trip. workflowMe lives
//     in component state, but the returnTo query + the URL rewrite must carry it,
//     and the documents route must hydrate it on BOTH entry paths (in-app Back via
//     push(returnTo) AND a cold URL load). The listDocuments call must then send
//     search[searchworkflow]=me — the authoritative proof the filter is live. ---
describe('DocumentList — workflow=me returnTo round-trip (#28)', () => {
  beforeEach(() => {
    routerPush.mockReset()
    routerReplace.mockReset()
    mockRoute.query = {}
    filterState.selectedTags = []
    filterState.debouncedText = ''
    filterState.filterQuery = {}
    listDocumentsMock.mockClear()
  })

  it('with workflow=me active, returnTo carries workflow=me (Back restores it)', () => {
    mockRoute.query = { workflow: 'me' }
    filterState.filterQuery = { tags: 't1' }
    const wrapper = mountView()
    // buildDocumentViewState feeds openDocumentFull's returnTo; drive the dblclick.
    return wrapper.find('button.double').trigger('click').then(() => {
      const state = routerPush.mock.calls.at(-1)![0].state as { returnTo: string }
      expect(state.returnTo).toContain('workflow=me')
      expect(state.returnTo).toContain('tags=t1')
    })
  })

  it('a cold URL load of ?workflow=me activates the filter → listDocuments sends search[searchworkflow]=me', () => {
    mockRoute.query = { workflow: 'me' }
    mountView()
    const params = listDocumentsMock.mock.calls.at(-1)![0] as Record<string, unknown>
    expect(params['search[searchworkflow]']).toBe('me')
  })

  it('without the filter, listDocuments omits search[searchworkflow]', () => {
    mockRoute.query = {}
    mountView()
    const params = listDocumentsMock.mock.calls.at(-1)![0] as Record<string, unknown>
    expect(params['search[searchworkflow]']).toBeUndefined()
  })

  it('an in-app Back (route query changes to workflow=me) re-activates the filter', async () => {
    const wrapper = mountView()
    // Cold mount with no workflow: filter off.
    let params = listDocumentsMock.mock.calls.at(-1)![0] as Record<string, unknown>
    expect(params['search[searchworkflow]']).toBeUndefined()
    // Simulate returning to the list with the workflow filter in the URL.
    mockRoute.query = { workflow: 'me' }
    await flushPromises()
    void wrapper
    params = listDocumentsMock.mock.calls.at(-1)![0] as Record<string, unknown>
    expect(params['search[searchworkflow]']).toBe('me')
  })

  it('a free-text/tag change while workflow=me is active does NOT strip it from the NEXT request', async () => {
    mockRoute.query = { workflow: 'me' }
    mountView()
    await flushPromises()
    // Mount-time request: workflow active, no search yet.
    const callsAfterMount = listDocumentsMock.mock.calls.length
    const mountParams = listDocumentsMock.mock.calls.at(-1)![0] as Record<string, unknown>
    expect(mountParams['search[searchworkflow]']).toBe('me')
    expect(mountParams.search).toBeUndefined()

    // Change the filter dimension the store owns; workflow stays in the route.
    // The reactive store state drives the component's queryKey, so this triggers
    // a REAL refetch through the mocked query (see the vue-query mock's
    // key-change watcher) — no manual queryFn poking.
    filterState.debouncedText = 'invoice'
    filterState.filterQuery = { search: 'invoice' }
    await flushPromises()

    // Non-vacuous: a NEW request happened, and it is provably the post-change one
    // (it carries the new search) — a broken component that dropped workflow on a
    // search change fails the last assertion; one that never refetched fails the
    // count assertion.
    expect(listDocumentsMock.mock.calls.length).toBeGreaterThan(callsAfterMount)
    const params = listDocumentsMock.mock.calls.at(-1)![0] as Record<string, unknown>
    expect(params.search).toBe('invoice')
    expect(params['search[searchworkflow]']).toBe('me')
  })

  // --- Canonicalization on entry: only the scalar "me" is a valid workflow value.
  //     Any OTHER present value (unknown scalar, empty, array) must be REWRITTEN
  //     out of the URL on route-entry evaluation — otherwise ?workflow=them (etc.)
  //     sits in the URL indefinitely: the hydration watcher only turns the toggle
  //     off, the toggle watcher early-returns (off == not-'me'), and the store's
  //     rewrite only fires on tag/text/mode changes. ---
  it('cold-loading an UNKNOWN workflow value canonicalizes it out of the URL', async () => {
    mockRoute.query = { workflow: 'them' }
    mountView()
    await flushPromises()
    expect(routerReplace).toHaveBeenCalled()
    const q = routerReplace.mock.calls.at(-1)![0].query as Record<string, string>
    expect(q.workflow).toBeUndefined()
    // And the filter is NOT active.
    const params = listDocumentsMock.mock.calls.at(-1)![0] as Record<string, unknown>
    expect(params['search[searchworkflow]']).toBeUndefined()
  })

  it('canonicalizing an invalid workflow does NOT drop not-yet-hydrated dimensions (cold-load race)', async () => {
    // Cold load of ?tags=t1&workflow=them: the tag store DEFERS tag-ID hydration
    // until the tags request settles, so buildFilterQuery() returns {} at the
    // moment the canonicalizing replace fires. The replace must therefore be
    // SURGICAL — the current route query minus the workflow key — never a rebuild
    // from the (still-empty) serializer, or the valid raw tags=t1 is dropped from
    // the URL before it can hydrate.
    mockRoute.query = { tags: 't1', workflow: 'them' }
    filterState.filterQuery = {} // hydration unresolved: the serializer has nothing yet
    mountView()
    await flushPromises()
    expect(routerReplace).toHaveBeenCalled()
    const q = routerReplace.mock.calls.at(-1)![0].query as Record<string, string>
    expect(q.workflow).toBeUndefined()
    expect(q.tags).toBe('t1') // raw, not-yet-hydrated param preserved verbatim
  })

  it('empty and array workflow values are canonicalized away too', async () => {
    mockRoute.query = { workflow: '' }
    mountView()
    await flushPromises()
    expect(routerReplace).toHaveBeenCalled()
    expect(
      (routerReplace.mock.calls.at(-1)![0].query as Record<string, string>).workflow,
    ).toBeUndefined()

    routerReplace.mockClear()
    mockRoute.query = { workflow: ['me', 'me'] }
    mountView()
    await flushPromises()
    expect(routerReplace).toHaveBeenCalled()
    expect(
      (routerReplace.mock.calls.at(-1)![0].query as Record<string, string>).workflow,
    ).toBeUndefined()
  })

  it('a VALID workflow=me (and an absent key) triggers NO canonicalizing replace on entry', async () => {
    mockRoute.query = { workflow: 'me' }
    mountView()
    await flushPromises()
    expect(routerReplace).not.toHaveBeenCalled()

    mockRoute.query = {}
    mountView()
    await flushPromises()
    expect(routerReplace).not.toHaveBeenCalled()
  })
})

// --- #41: the favorites=me filter round-trip. favoritesMe lives in component state
//     (mirroring workflowMe), but the returnTo query + the URL rewrite must carry it,
//     and the documents route must hydrate it on BOTH entry paths. The listDocuments
//     call must then send favorites=me — the authoritative proof the filter is live. ---
describe('DocumentList — favorites=me filter round-trip (#41)', () => {
  beforeEach(() => {
    routerPush.mockReset()
    routerReplace.mockReset()
    mockRoute.query = {}
    filterState.selectedTags = []
    filterState.debouncedText = ''
    filterState.filterQuery = {}
    listDocumentsMock.mockClear()
  })

  it('a cold URL load of ?favorites=me activates the filter → listDocuments sends favorites=me', () => {
    mockRoute.query = { favorites: 'me' }
    mountView()
    const params = listDocumentsMock.mock.calls.at(-1)![0] as Record<string, unknown>
    expect(params.favorites).toBe('me')
  })

  it('without the filter, listDocuments omits favorites', () => {
    mockRoute.query = {}
    mountView()
    const params = listDocumentsMock.mock.calls.at(-1)![0] as Record<string, unknown>
    expect(params.favorites).toBeUndefined()
  })

  it('an in-app Back (route query changes to favorites=me) re-activates the filter', async () => {
    const wrapper = mountView()
    let params = listDocumentsMock.mock.calls.at(-1)![0] as Record<string, unknown>
    expect(params.favorites).toBeUndefined()
    mockRoute.query = { favorites: 'me' }
    await flushPromises()
    void wrapper
    params = listDocumentsMock.mock.calls.at(-1)![0] as Record<string, unknown>
    expect(params.favorites).toBe('me')
  })

  it('with favorites=me active, returnTo carries favorites=me (Back restores it)', () => {
    mockRoute.query = { favorites: 'me' }
    filterState.filterQuery = { tags: 't1' }
    const wrapper = mountView()
    return wrapper.find('button.double').trigger('click').then(() => {
      const state = routerPush.mock.calls.at(-1)![0].state as { returnTo: string }
      expect(state.returnTo).toContain('favorites=me')
      expect(state.returnTo).toContain('tags=t1')
    })
  })

  it('favorites=me composes with workflow=me: both survive in the same request and returnTo', () => {
    mockRoute.query = { favorites: 'me', workflow: 'me' }
    filterState.filterQuery = { tags: 't1' }
    const wrapper = mountView()
    const params = listDocumentsMock.mock.calls.at(-1)![0] as Record<string, unknown>
    expect(params.favorites).toBe('me')
    expect(params['search[searchworkflow]']).toBe('me')
    return wrapper.find('button.double').trigger('click').then(() => {
      const state = routerPush.mock.calls.at(-1)![0].state as { returnTo: string }
      expect(state.returnTo).toContain('favorites=me')
      expect(state.returnTo).toContain('workflow=me')
    })
  })

  it('cold-loading an UNKNOWN favorites value canonicalizes it out of the URL', async () => {
    mockRoute.query = { favorites: 'them' }
    mountView()
    await flushPromises()
    expect(routerReplace).toHaveBeenCalled()
    const q = routerReplace.mock.calls.at(-1)![0].query as Record<string, string>
    expect(q.favorites).toBeUndefined()
    const params = listDocumentsMock.mock.calls.at(-1)![0] as Record<string, unknown>
    expect(params.favorites).toBeUndefined()
  })

  it('a VALID favorites=me (and an absent key) triggers NO canonicalizing replace on entry', async () => {
    mockRoute.query = { favorites: 'me' }
    mountView()
    await flushPromises()
    expect(routerReplace).not.toHaveBeenCalled()
  })
})

// --- #39: the list ⇄ gallery view mode. A pure RENDER mode over the SAME query,
//     pagination, and filter state — persisted to localStorage; gallery is
//     browse/open-only (no selection). These assert the toggle persists and
//     restores, both modes consume identical state, and switching to gallery clears
//     any live multi-selection (B2) so the bulk toolbar is unreachable there. ---
describe('DocumentList — list ⇄ gallery view mode (#39)', () => {
  beforeEach(() => {
    routerPush.mockReset()
    routerReplace.mockReset()
    mockRoute.query = {}
    filterState.selectedTags = []
    filterState.debouncedText = ''
    filterState.filterQuery = {}
    listDocumentsMock.mockClear()
    localStorage.clear()
  })

  it('defaults to list mode and renders the table, not the gallery', () => {
    const wrapper = mountView()
    expect(wrapper.find('.doc-table-stub').exists()).toBe(true)
    expect(wrapper.find('.doc-gallery-stub').exists()).toBe(false)
  })

  it('switching to gallery renders the gallery and persists the choice to localStorage', async () => {
    const wrapper = mountView()
    await wrapper.find('.to-gallery').trigger('click')
    // The gallery now renders and the table is gone — a pure mode switch.
    expect(wrapper.find('.doc-gallery-stub').exists()).toBe(true)
    expect(wrapper.find('.doc-table-stub').exists()).toBe(false)
    // Persisted under the documented key.
    expect(localStorage.getItem('teedy_document_view_mode')).toBe('gallery')
  })

  it('restores gallery mode from localStorage on a fresh mount (reload)', () => {
    localStorage.setItem('teedy_document_view_mode', 'gallery')
    const wrapper = mountView()
    // A cold mount reads the persisted mode → gallery renders immediately.
    expect(wrapper.find('.doc-gallery-stub').exists()).toBe(true)
    expect(wrapper.find('.doc-table-stub').exists()).toBe(false)
  })

  it('both modes consume the SAME list query state (identical params, one shared query)', async () => {
    // The list query runs once at mount. Switching the render mode must NOT change
    // the params — it is a view over the same TanStack query/pagination/filters, not
    // a separate fetch. Prove it by asserting the mount-time params, then that a mode
    // switch drives the same open-document paths (same `documents` reach both modes).
    mockRoute.query = { favorites: 'me' }
    filterState.debouncedText = 'invoice'
    const wrapper = mountView()
    const listParams = listDocumentsMock.mock.calls.at(-1)![0] as Record<string, unknown>
    expect(listParams.favorites).toBe('me')
    expect(listParams.search).toBe('invoice')

    // Switch to gallery: the SAME documents render (the card carries the first doc),
    // and opening a card drives the same navigation path as a table row.
    await wrapper.find('.to-gallery').trigger('click')
    await wrapper.find('.card-double').trigger('click')
    expect(routerPush).toHaveBeenCalledWith(
      expect.objectContaining({ name: 'document-view', params: { id: 'doc-42' } }),
    )
  })

  it('B2: switching to gallery clears an active list selection so the bulk toolbar is unreachable', async () => {
    const wrapper = mountView()
    // Make a real multi-selection in the table through the v-model:selection binding —
    // this is exactly what the production DocumentTable emits on a checkbox click.
    await wrapper.find('.select-one').trigger('click')
    await flushPromises()
    // The bulk toolbar renders while a selection is live in list mode (it renders
    // solely from selectedDocs.length).
    expect(wrapper.find('.bulk-bar-stub').exists()).toBe(true)

    // Switch to gallery: the selection is cleared, so the bulk toolbar detaches and
    // no bulk-mutation control is reachable in gallery mode.
    await wrapper.find('.to-gallery').trigger('click')
    await flushPromises()
    expect(wrapper.find('.bulk-bar-stub').exists()).toBe(false)
    // And the toolbar stays gone (selection empty) — switching back to list confirms
    // there is nothing to act on.
    await wrapper.find('.to-list').trigger('click')
    await flushPromises()
    expect(wrapper.find('.bulk-bar-stub').exists()).toBe(false)
  })
})
