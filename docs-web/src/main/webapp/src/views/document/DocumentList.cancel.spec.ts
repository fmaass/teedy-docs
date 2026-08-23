import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { defineComponent, h, nextTick } from 'vue'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'

// #290: a search the user has already superseded (another keystroke, a new page, a sort
// flip) must not leave its XHR alive in the browser. TanStack Query hands the queryFn an
// AbortSignal and aborts it when the query key changes; the view forwards that signal to
// listDocuments, which hands it to axios. This spec exercises the REAL @tanstack/vue-query
// (a live QueryClient, not the module mock DocumentList.spec.ts installs) because the
// cancellation under test is TanStack's own behaviour — asserting it against a mocked
// useQuery would only assert the mock. Client-side cleanup only: nothing here interrupts
// the server-side search.

// --- Router: the view reads route.query and writes canonical URLs; neither matters here ---
const routeHolder = vi.hoisted(() => ({ route: { query: {} as Record<string, unknown> } }))
vi.mock('vue-router', async () => {
  const { reactive } = await vi.importActual<typeof import('vue')>('vue')
  routeHolder.route = reactive({ query: {} as Record<string, unknown> })
  return {
    useRouter: () => ({
      push: vi.fn(),
      replace: vi.fn(),
      resolve: (to: { query?: Record<string, string> }) => ({
        fullPath: '/documents?' + new URLSearchParams(to.query ?? {}).toString(),
      }),
    }),
    useRoute: () => routeHolder.route,
  }
})

vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (k: string) => k }) }))

// --- The list API: every call parks on a deferred promise so a request can be left
//     genuinely IN FLIGHT while the query key changes underneath it. Each call records the
//     options object it received, which is where the AbortSignal must arrive. ---
type ListOptions = { signal?: AbortSignal }
type ListCall = {
  params: Record<string, unknown>
  options?: ListOptions
  resolve: (response: { data: { documents: unknown[]; total: number } }) => void
}
const listCalls = vi.hoisted(() => [] as Array<{
  params: Record<string, unknown>
  options?: { signal?: AbortSignal }
  resolve: (response: { data: { documents: unknown[]; total: number } }) => void
}>)

vi.mock('../../api/document', () => ({
  listDocuments: (params: Record<string, unknown>, options?: ListOptions) => {
    let resolve!: ListCall['resolve']
    const promise = new Promise<{ data: { documents: unknown[]; total: number } }>((r) => {
      resolve = r
    })
    listCalls.push({ params, options, resolve })
    return promise
  },
  getDocument: vi.fn(() => new Promise(() => {})),
  updateDocument: vi.fn(),
  deleteDocument: vi.fn(),
}))

// --- Tag filter store: only `combinedSearch` is driven here. It is a live getter over a
//     reactive holder, so writing debouncedText changes the view's queryKey exactly as a
//     debounced keystroke does. ---
const filterHolder = vi.hoisted(() => ({ state: { debouncedText: '' } }))
const filterState = {
  get debouncedText() {
    return filterHolder.state.debouncedText
  },
  set debouncedText(v: string) {
    filterHolder.state.debouncedText = v
  },
}
vi.mock('../../stores/tagFilter', async () => {
  const { reactive } = await vi.importActual<typeof import('vue')>('vue')
  filterHolder.state = reactive(filterHolder.state)
  return {
    useTagFilterStore: () => ({
      get combinedSearch() {
        return filterHolder.state.debouncedText
      },
      tagMode: 'and',
      selectedTagIds: new Set(),
      selectedTags: [],
      excludedTags: [],
      relatedTags: [],
      allTags: [],
      tagCounts: {},
      get debouncedText() {
        return filterHolder.state.debouncedText
      },
      hasActiveFilters: false,
      searchText: '',
      clearFilters: vi.fn(),
      removeTag: vi.fn(),
      toggleTag: vi.fn(),
      buildFilterQuery: () => ({}),
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
// #294: the view now reads the signed-in user's quota (auth store) and can raise a plain
// dialog when a bulk duplicate would exceed it. Neither is exercised by this spec; the mocks
// keep the mount free of a live Pinia and PrimeVue's ConfirmationService.
vi.mock('primevue/useconfirm', () => ({ useConfirm: () => ({ require: vi.fn() }) }))
vi.mock('../../stores/auth', () => ({
  useAuthStore: () => ({ user: { storage_current: 0, storage_quota: 1_000_000_000 } }),
}))

import DocumentList from './DocumentList.vue'

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
const staleDoc = makeDoc('doc-stale')
const freshDoc = makeDoc('doc-fresh')

// Surfaces the id of every row handed to the table, so a test can assert WHICH result set
// reached the view.
const DocumentTableStub = defineComponent({
  props: ['documents', 'selection'],
  setup(props) {
    return () =>
      h(
        'div',
        { class: 'doc-table-stub' },
        (props.documents as Array<{ id: string }>).map((d) =>
          h('span', { class: 'row-title', 'data-id': d.id }, d.id),
        ),
      )
  },
})
const passthrough = defineComponent({ setup: () => () => h('div') })
const slotWrapper = defineComponent({ setup: (_p, { slots }) => () => h('div', slots.default?.()) })
// ToggleButton takes onLabel/onIcon PROPS; an inert stub would let Vue fall them through to the
// root element as `on*` event handlers and warn on every mount. Swallowing the attrs keeps the
// run's stderr clean without stubbing behaviour this spec does not exercise.
const attrSink = defineComponent({ inheritAttrs: false, setup: () => () => h('div') })

function mountView() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return mount(DocumentList, {
    global: {
      plugins: [[VueQueryPlugin, { queryClient }]],
      stubs: {
        DocumentTable: DocumentTableStub,
        DocumentGallery: passthrough,
        SelectButton: passthrough,
        Select: passthrough,
        InputText: passthrough,
        IconField: slotWrapper,
        InputIcon: passthrough,
        Paginator: passthrough,
        DocumentSlideOver: passthrough,
        DocumentSearchBar: passthrough,
        SavedFilters: passthrough,
        TagFilterChips: passthrough,
        BulkActionBar: passthrough,
        EmptyState: passthrough,
        ErrorState: passthrough,
        TagQuickMenu: passthrough,
        ToggleButton: attrSink,
      },
      directives: { tooltip: {} },
    },
  })
}

describe('DocumentList — superseded searches are cancelled in the browser (#290)', () => {
  beforeEach(() => {
    listCalls.length = 0
    filterState.debouncedText = ''
    routeHolder.route.query = {}
  })

  it('aborts the in-flight request when the query key changes, and renders only the newer result', async () => {
    const wrapper = mountView()
    await flushPromises()

    // Request 1 is in flight and carries a live (un-aborted) signal.
    expect(listCalls.length).toBe(1)
    const superseded = listCalls[0]
    expect(superseded.options?.signal).toBeInstanceOf(AbortSignal)
    expect(superseded.options!.signal!.aborted).toBe(false)

    // The user types on: combinedSearch changes -> new query key -> request 2.
    filterState.debouncedText = 'invoice'
    await nextTick()
    await flushPromises()

    expect(listCalls.length).toBe(2)
    const current = listCalls[1]
    expect(current.params.search).toBe('invoice')

    // The superseded request is aborted in the browser; the live one is untouched.
    expect(superseded.options!.signal!.aborted).toBe(true)
    expect(current.options!.signal!.aborted).toBe(false)

    // The newer result renders, and the superseded response — which arrives AFTER it —
    // cannot overtake it.
    current.resolve({ data: { documents: [freshDoc], total: 1 } })
    await flushPromises()
    superseded.resolve({ data: { documents: [staleDoc], total: 1 } })
    await flushPromises()

    const rendered = wrapper.findAll('.row-title').map((n) => n.attributes('data-id'))
    expect(rendered).toEqual(['doc-fresh'])
  })
})
