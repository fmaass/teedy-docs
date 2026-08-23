import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { defineComponent, h, computed, reactive, ref } from 'vue'

// #294 — bulk duplicate from the action bar.
//
// Three properties are pinned here because nothing else can catch them:
//   1. the fan-out runs the EXISTING single-document endpoint once per selected
//      document and reports through the shared summary (no bulk endpoint exists);
//   2. it runs SERIALLY — the plan forbids parallelising the loop, and a Promise.all
//      rewrite would look identical in a call-count assertion;
//   3. an over-quota batch is stopped BEFORE the first call, with an explanation —
//      not started and then reported as N failures.

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

vi.mock('vue-i18n', () => ({
  // Echo the interpolation params alongside the key so an assertion can prove WHICH
  // numbers reached the message without depending on the English wording.
  useI18n: () => ({
    t: (k: string, params?: Record<string, unknown>) =>
      params ? `${k}:${JSON.stringify(params)}` : k,
  }),
}))

// --- The duplicate endpoint: every call parks on a deferred promise so the test can
//     observe how many calls are OUTSTANDING at once (the serial-vs-parallel signal). ---
type Deferred = { id: string; resolve: (value: { data: { id: string } }) => void; reject: (reason: unknown) => void }
const duplicateCalls = vi.hoisted(() => [] as Array<{
  id: string
  resolve: (value: { data: { id: string } }) => void
  reject: (reason: unknown) => void
}>)
vi.mock('../../api/document', () => ({
  listDocuments: vi.fn(() => Promise.resolve({ data: { documents: [], total: 2 } })),
  getDocument: vi.fn(() => new Promise(() => {})),
  updateDocument: vi.fn(),
  deleteDocument: vi.fn(),
  duplicateDocument: vi.fn((id: string) => {
    let resolve!: Deferred['resolve']
    let reject!: Deferred['reject']
    const promise = new Promise<{ data: { id: string } }>((res, rej) => {
      resolve = res
      reject = rej
    })
    duplicateCalls.push({ id, resolve, reject })
    return promise
  }),
}))

// --- /file/list drives the pre-flight storage estimate: DocumentListItem carries no
//     size field (only file_count), so the sizes come from the same endpoint the bulk
//     ZIP download already uses. ---
const fileSizes = vi.hoisted(() => ({ byDoc: {} as Record<string, number[]>, fail: false }))
vi.mock('../../api/file', () => ({
  getFileList: vi.fn((documentId: string) => {
    if (fileSizes.fail) return Promise.reject(new Error('boom'))
    const sizes = fileSizes.byDoc[documentId] ?? []
    return Promise.resolve(sizes.map((size, i) => ({ id: `${documentId}-f${i}`, size })))
  }),
  zipFilesBlob: vi.fn(),
}))

// --- The signed-in user's quota, as the auth store reports it. ---
const authUser = vi.hoisted(() => ({
  value: { storage_current: 0, storage_quota: 1_000_000 } as { storage_current: number; storage_quota: number } | null,
}))
vi.mock('../../stores/auth', () => ({
  useAuthStore: () => ({
    get user() {
      return authUser.value
    },
  }),
}))

vi.mock('../../stores/tagFilter', () => ({
  useTagFilterStore: () => ({
    combinedSearch: '',
    tagMode: 'and',
    selectedTagIds: new Set(),
    selectedTags: [],
    excludedTags: [],
    relatedTags: [],
    allTags: [],
    tagCounts: {},
    debouncedText: '',
    hasActiveFilters: false,
    searchText: '',
    clearFilters: vi.fn(),
    removeTag: vi.fn(),
    toggleTag: vi.fn(),
    buildFilterQuery: () => ({}),
  }),
}))

vi.mock('../../composables/useDocumentTags', () => ({
  useDocumentTags: () => ({ addTag: vi.fn(), removeTag: vi.fn() }),
}))
vi.mock('../../composables/useConfirmDanger', () => ({
  useConfirmDanger: () => ({ confirmDanger: vi.fn() }),
}))
vi.mock('../../composables/useClampedOffset', () => ({ useClampedOffset: vi.fn() }))

const toastAddSpy = vi.hoisted(() => vi.fn())
vi.mock('primevue/usetoast', () => ({ useToast: () => ({ add: toastAddSpy }) }))

// The blocking dialog. Captured rather than rendered: the real ConfirmDialog lives in
// App.vue and would need the whole PrimeVue service stack for an assertion that is
// really about WHAT the view decided, not how PrimeVue paints it.
const confirmRequireSpy = vi.hoisted(() => vi.fn())
vi.mock('primevue/useconfirm', () => ({ useConfirm: () => ({ require: confirmRequireSpy }) }))

const invalidateQueriesSpy = vi.hoisted(() => vi.fn())
vi.mock('@tanstack/vue-query', () => ({
  useQuery: (opts: { enabled?: { value: boolean } }) => {
    if (opts.enabled) {
      // A real ref: the view WATCHES this error, and an inert `{ value: null }` literal is
      // not a valid watch source (Vue warns on every mount).
      return {
        data: computed(() => null),
        isLoading: { value: false },
        isError: { value: false },
        error: ref<unknown>(null),
        refetch: vi.fn(),
      }
    }
    return {
      data: computed(() => ({ documents: listResult.docs, total: 2 })),
      isLoading: { value: false },
      isError: { value: false },
      error: { value: null },
      refetch: vi.fn(),
    }
  },
  useQueryClient: () => ({
    invalidateQueries: invalidateQueriesSpy,
    cancelQueries: vi.fn(() => Promise.resolve()),
    removeQueries: vi.fn(),
  }),
  keepPreviousData: undefined,
}))

import DocumentList from './DocumentList.vue'
import { duplicateDocument } from '../../api/document'
import { getFileList } from '../../api/file'

function makeDoc(id: string) {
  return {
    id,
    title: `Doc ${id}`,
    description: '',
    create_date: 0,
    update_date: 0,
    language: 'eng',
    file_id: null,
    file_count: 1,
    tags: [],
    shared: false,
  }
}
const docA = makeDoc('doc-a')
const docB = makeDoc('doc-b')
const listResult = reactive({ docs: [docA, docB] as Array<ReturnType<typeof makeDoc>> })

// Selects BOTH rows through the real v-model:selection binding — exactly what
// DocumentTable emits when the header checkbox is ticked.
const DocumentTableStub = defineComponent({
  props: ['documents', 'selection'],
  emits: ['update:selection'],
  setup(props, { emit }) {
    return () =>
      h('div', { class: 'doc-table-stub' }, [
        h('button', { class: 'select-both', onClick: () => emit('update:selection', [...(props.documents as unknown[])]) }, 'select'),
      ])
  },
})
// Re-emits the bar's duplicate contract, so a rename of the emit fails HERE.
const BulkActionBarStub = defineComponent({
  emits: ['duplicate'],
  setup(_props, { emit }) {
    return () =>
      h('div', { class: 'bulk-bar-stub' }, [
        h('button', { class: 'bulk-duplicate', onClick: () => emit('duplicate') }, 'dup'),
      ])
  },
})
const passthrough = defineComponent({ setup: () => () => h('div') })
const slotWrapper = defineComponent({ setup: (_p, { slots }) => () => h('div', slots.default?.()) })
const attrSink = defineComponent({ inheritAttrs: false, setup: () => () => h('div') })

function mountView() {
  return mount(DocumentList, {
    global: {
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
        BulkActionBar: BulkActionBarStub,
        EmptyState: passthrough,
        ErrorState: passthrough,
        TagQuickMenu: passthrough,
        ToggleButton: attrSink,
      },
      directives: { tooltip: {} },
    },
  })
}

async function selectBothAndDuplicate() {
  const wrapper = mountView()
  await wrapper.find('.select-both').trigger('click')
  await flushPromises()
  expect(wrapper.find('.bulk-bar-stub').exists()).toBe(true)
  await wrapper.find('.bulk-duplicate').trigger('click')
  await flushPromises()
  return wrapper
}

describe('DocumentList — bulk duplicate (#294)', () => {
  beforeEach(() => {
    duplicateCalls.length = 0
    vi.mocked(duplicateDocument).mockClear()
    vi.mocked(getFileList).mockClear()
    toastAddSpy.mockClear()
    confirmRequireSpy.mockClear()
    fileSizes.byDoc = { 'doc-a': [10], 'doc-b': [20] }
    fileSizes.fail = false
    authUser.value = { storage_current: 0, storage_quota: 1_000_000 }
  })

  it('duplicates every selected document once, SERIALLY, and reports the shared summary', async () => {
    await selectBothAndDuplicate()

    // Serial: the second copy has not been requested while the first is still in flight.
    expect(duplicateCalls.map((c) => c.id)).toEqual(['doc-a'])

    duplicateCalls[0].resolve({ data: { id: 'copy-a' } })
    await flushPromises()
    expect(duplicateCalls.map((c) => c.id)).toEqual(['doc-a', 'doc-b'])

    duplicateCalls[1].resolve({ data: { id: 'copy-b' } })
    await flushPromises()

    expect(vi.mocked(duplicateDocument).mock.calls.map((c) => c[0])).toEqual(['doc-a', 'doc-b'])
    // summariseBulk's success branch — the same reporting every other bulk op uses.
    expect(toastAddSpy).toHaveBeenCalledWith(
      expect.objectContaining({
        severity: 'success',
        detail: 'ui.bulk.summary_ok:{"count":2}',
      }),
    )
    expect(confirmRequireSpy).not.toHaveBeenCalled()
  })

  it('reports a per-document failure through the same summary instead of aborting the batch', async () => {
    await selectBothAndDuplicate()
    duplicateCalls[0].reject({ response: { status: 400, data: { type: 'QuotaReached' } } })
    await flushPromises()
    // The rejection did not abort the fan-out: the second document was still attempted.
    expect(duplicateCalls.map((c) => c.id)).toEqual(['doc-a', 'doc-b'])
    duplicateCalls[1].resolve({ data: { id: 'copy-b' } })
    await flushPromises()

    expect(toastAddSpy).toHaveBeenCalledWith(
      expect.objectContaining({
        severity: 'warn',
        detail: 'ui.bulk.summary_partial:{"ok":1,"failed":1}',
      }),
    )
  })

  it('stops BEFORE the first copy when the remaining quota cannot cover the selection', async () => {
    // 30 bytes of files against 10 bytes of headroom.
    fileSizes.byDoc = { 'doc-a': [10], 'doc-b': [20] }
    authUser.value = { storage_current: 990, storage_quota: 1000 }

    await selectBothAndDuplicate()

    expect(confirmRequireSpy).toHaveBeenCalledTimes(1)
    const options = confirmRequireSpy.mock.calls[0][0] as { message: string }
    expect(options.message).toBe('ui.bulk.duplicate_quota:{"required":"30 B","available":"10 B"}')
    // Nothing was started: no partial batch, no summary toast.
    expect(duplicateDocument).not.toHaveBeenCalled()
    expect(toastAddSpy).not.toHaveBeenCalled()
  })

  it('proceeds when the estimate exactly fits the remaining quota', async () => {
    fileSizes.byDoc = { 'doc-a': [10], 'doc-b': [20] }
    authUser.value = { storage_current: 970, storage_quota: 1000 }

    await selectBothAndDuplicate()

    expect(confirmRequireSpy).not.toHaveBeenCalled()
    expect(duplicateCalls.map((c) => c.id)).toEqual(['doc-a'])
  })

  it('proceeds when the estimate cannot be taken — the backend stays the quota authority', async () => {
    // A failed /file/list must not block a duplicate the quota would have allowed: the
    // client check is a courtesy, and the server rejects a genuine over-quota copy anyway.
    fileSizes.fail = true
    authUser.value = { storage_current: 990, storage_quota: 1000 }

    await selectBothAndDuplicate()

    expect(confirmRequireSpy).not.toHaveBeenCalled()
    expect(duplicateCalls.map((c) => c.id)).toEqual(['doc-a'])
  })
})
