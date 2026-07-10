import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { defineComponent, h, computed } from 'vue'

// --- Router under mock: assert the dblclick navigation target + history state ---
const routerPush = vi.hoisted(() => vi.fn())
vi.mock('vue-router', () => ({
  // resolve echoes the query into fullPath so a test can assert returnTo carries
  // the active filter (buildFilterQuery output), not just a bare route.
  useRouter: () => ({
    push: routerPush,
    resolve: (to: { query?: Record<string, string> }) => ({
      fullPath: '/documents?' + new URLSearchParams(to.query ?? {}).toString(),
    }),
  }),
}))

// Mutable filter state so a test can switch between "no filters" and "active
// filter" without a second global mock. Read by the tagFilter store mock below.
const filterState = vi.hoisted(() => ({
  selectedTags: [] as { id: string; name: string }[],
  debouncedText: '',
  filterQuery: {} as Record<string, string>,
}))

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
vi.mock('../../stores/tagFilter', () => ({
  useTagFilterStore: () => ({
    combinedSearch: '',
    tagMode: 'and',
    selectedTagIds: new Set(),
    get selectedTags() { return filterState.selectedTags },
    excludedTags: [],
    relatedTags: [],
    allTags: [],
    get debouncedText() { return filterState.debouncedText },
    hasActiveFilters: false,
    searchText: '',
    clearFilters: vi.fn(),
    removeTag: vi.fn(),
    toggleTag: vi.fn(),
    buildFilterQuery: () => filterState.filterQuery,
  }),
}))

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
    // The list query resolves to our documents.
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
  props: ['documents'],
  emits: ['rowClick', 'rowDblclick', 'rowContextMenu', 'page', 'sort'],
  setup(props, { emit }) {
    return () =>
      h('div', { class: 'doc-table-stub' }, [
        h('button', { class: 'single', onClick: () => emit('rowClick', props.documents[0]) }, 'single'),
        h('button', { class: 'single-b', onClick: () => emit('rowClick', props.documents[1]) }, 'single-b'),
        h('button', { class: 'double', onClick: () => emit('rowDblclick', props.documents[0]) }, 'double'),
      ])
  },
})
const passthrough = defineComponent({ setup: () => () => h('div') })

import DocumentList from './DocumentList.vue'

function mountView() {
  return mount(DocumentList, {
    global: {
      stubs: {
        DocumentTable: DocumentTableStub,
        DocumentSlideOver: {
          props: ['visible', 'document'],
          template: '<div class="slide-over" v-if="visible">{{ document?.id }}</div>',
        },
        DocumentSearchBar: passthrough,
        TagFilterChips: passthrough,
        BulkActionBar: passthrough,
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
