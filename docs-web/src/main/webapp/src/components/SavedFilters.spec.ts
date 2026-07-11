import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { defineComponent, h } from 'vue'

// Drive the component's route-derived affordance + query round-trip. The route is a
// reactive holder (as in DocumentList.spec) so a test can flip route.query and see
// the "Save filter" affordance appear.
const routerPush = vi.hoisted(() => vi.fn())
const routeHolder = vi.hoisted(() => ({ route: { query: {} as Record<string, unknown> } }))
vi.mock('vue-router', async () => {
  const { reactive } = await vi.importActual<typeof import('vue')>('vue')
  routeHolder.route = reactive({ query: {} as Record<string, unknown> })
  return {
    useRouter: () => ({ push: routerPush }),
    useRoute: () => routeHolder.route,
  }
})
const mockRoute = {
  get query() { return routeHolder.route.query },
  set query(v: Record<string, unknown>) { routeHolder.route.query = v },
}

vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (k: string) => k }) }))
vi.mock('primevue/usetoast', () => ({ useToast: () => ({ add: vi.fn() }) }))

const confirmDangerMock = vi.hoisted(() => vi.fn())
vi.mock('../composables/useConfirmDanger', () => ({
  useConfirmDanger: () => ({ confirmDanger: confirmDangerMock }),
}))

// --- API mock: capture the query string the create call receives ---
const createMock = vi.hoisted(() =>
  vi.fn((_name: string, _query: string) => Promise.resolve({ data: { id: 'new', name: 'n', query: 'q' } })),
)
const deleteMock = vi.hoisted(() => vi.fn((_id: string) => Promise.resolve({ data: {} })))
const listMock = vi.hoisted(() => vi.fn(() => Promise.resolve({ data: { saved_filters: [] as unknown[] } })))
vi.mock('../api/savedfilter', () => ({
  listSavedFilters: () => listMock(),
  createSavedFilter: (name: string, query: string) => createMock(name, query),
  deleteSavedFilter: (id: string) => deleteMock(id),
}))

// --- vue-query mock: expose the loaded filter list + record mutation invocations ---
const savedFiltersHolder = vi.hoisted(() => ({ list: [] as unknown[] }))
vi.mock('@tanstack/vue-query', () => ({
  useQuery: () => ({ data: { get value() { return savedFiltersHolder.list } } }),
  useMutation: (opts: { mutationFn: (v?: unknown) => Promise<unknown>; onSuccess?: () => void; onError?: () => void }) => ({
    isPending: { value: false },
    mutate: (v?: unknown) => {
      opts.mutationFn(v).then(() => opts.onSuccess?.()).catch(() => opts.onError?.())
    },
  }),
  useQueryClient: () => ({ invalidateQueries: vi.fn() }),
}))

// Stub the PrimeVue overlays so the dialog/popover content renders inline.
// The Popover ref is called with .toggle()/.hide(); expose no-op methods so the
// component's applyFilter (which hides then pushes) does not throw on the stub.
const popoverStub = defineComponent({
  setup(_p, { slots, expose }) {
    expose({ toggle: () => {}, hide: () => {} })
    return () => h('div', slots.default?.())
  },
})
const footerPassthrough = defineComponent({
  setup: (_p, { slots }) => () => h('div', [slots.default?.(), slots.footer?.()]),
})

import SavedFilters from './SavedFilters.vue'

function mountView() {
  return mount(SavedFilters, {
    global: {
      stubs: {
        Popover: popoverStub,
        Dialog: footerPassthrough,
        Button: {
          props: ['label', 'icon', 'ariaLabel'],
          emits: ['click'],
          template: '<button :aria-label="ariaLabel" @click="$emit(\'click\', $event)">{{ label }}</button>',
        },
        InputText: {
          props: ['modelValue'],
          emits: ['update:modelValue'],
          template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
        },
      },
    },
  })
}

describe('SavedFilters — save affordance derives from route.query (#42)', () => {
  beforeEach(() => {
    routerPush.mockReset()
    createMock.mockClear()
    deleteMock.mockClear()
    confirmDangerMock.mockClear()
    mockRoute.query = {}
    savedFiltersHolder.list = []
  })

  it('hides the Save affordance when the route carries no filter dimension', () => {
    const wrapper = mountView()
    expect(wrapper.text()).not.toContain('ui.saved_filters.save_current')
  })

  it('shows the Save affordance for a workflow-ONLY filter (not just tags/search)', async () => {
    mockRoute.query = { workflow: 'me' }
    const wrapper = mountView()
    await flushPromises()
    expect(wrapper.text()).toContain('ui.saved_filters.save_current')
  })

  it('serializes ALL FIVE filter dimensions of route.query VERBATIM (non-filter keys dropped)', async () => {
    // All five filter keys present with exact values; `foo` is not a filter
    // dimension and is the ONLY thing dropped — no other normalization.
    mockRoute.query = {
      tags: 't1,t2',
      exclude: 't3',
      mode: 'or',
      search: 'acme',
      workflow: 'me',
      foo: 'bar',
    }
    const wrapper = mountView()
    await flushPromises()

    // Open the save dialog, name it, save.
    await wrapper.get('button:nth-of-type(2)').trigger('click') // the "Save filter" button
    const input = wrapper.get('input')
    await input.setValue('My filter')
    // Click the dialog footer Save button (label 'save').
    const saveBtn = wrapper.findAll('button').find((b) => b.text() === 'save')
    expect(saveBtn).toBeTruthy()
    await saveBtn!.trigger('click')

    expect(createMock).toHaveBeenCalledTimes(1)
    const [name, query] = createMock.mock.calls[0]
    expect(name).toBe('My filter')
    // Verbatim values in stable FILTER_KEYS order; foo excluded.
    expect(query).toBe('tags=t1%2Ct2&exclude=t3&mode=or&search=acme&workflow=me')
  })

  it('preserves empty values and repeated keys verbatim (no normalization)', async () => {
    // A carried-but-empty `mode=` stays; a repeated key (vue-router array) is
    // appended verbatim — the BACKEND contract rejects it, the frontend must not
    // silently repair the URL by dropping entries.
    mockRoute.query = { mode: '', search: ['a', 'b'] }
    const wrapper = mountView()
    await flushPromises()

    await wrapper.get('button:nth-of-type(2)').trigger('click')
    await wrapper.get('input').setValue('Verbatim')
    const saveBtn = wrapper.findAll('button').find((b) => b.text() === 'save')
    await saveBtn!.trigger('click')

    expect(createMock).toHaveBeenCalledTimes(1)
    const [, query] = createMock.mock.calls[0]
    expect(query).toBe('mode=&search=a&search=b')
  })

  it('applies a saved filter by pushing the parsed query through the router', async () => {
    savedFiltersHolder.list = [{ id: 'f1', name: 'Invoices', query: 'tags=a&search=x&workflow=me', create_date: 1 }]
    const wrapper = mountView()
    await flushPromises()

    // Click the filter's apply button (label = filter name).
    const applyBtn = wrapper.findAll('button').find((b) => b.text() === 'Invoices')
    expect(applyBtn).toBeTruthy()
    await applyBtn!.trigger('click')

    expect(routerPush).toHaveBeenCalledTimes(1)
    expect(routerPush).toHaveBeenCalledWith({
      name: 'documents',
      query: { tags: 'a', search: 'x', workflow: 'me' },
    })
  })

  it('blocks a save whose name duplicates an existing filter (case-insensitive)', async () => {
    savedFiltersHolder.list = [{ id: 'f1', name: 'Invoices', query: 'search=x', create_date: 1 }]
    mockRoute.query = { search: 'y' }
    const wrapper = mountView()
    await flushPromises()

    await wrapper.get('button:nth-of-type(2)').trigger('click')
    await wrapper.get('input').setValue('invoices')
    const saveBtn = wrapper.findAll('button').find((b) => b.text() === 'save')
    await saveBtn!.trigger('click')

    // The precheck rejects it — no API call is made.
    expect(createMock).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('ui.saved_filters.name_exists')
  })

  it('routes delete through the danger confirm', async () => {
    savedFiltersHolder.list = [{ id: 'f1', name: 'Invoices', query: 'search=x', create_date: 1 }]
    const wrapper = mountView()
    await flushPromises()

    const deleteBtn = wrapper.findAll('button').find((b) => b.attributes('aria-label') === 'ui.saved_filters.delete_button')
    expect(deleteBtn).toBeTruthy()
    await deleteBtn!.trigger('click')

    expect(confirmDangerMock).toHaveBeenCalledTimes(1)
    // Invoking the confirm's accept callback fires the delete mutation.
    const opts = confirmDangerMock.mock.calls[0][0]
    opts.accept()
    await flushPromises()
    expect(deleteMock).toHaveBeenCalledWith('f1')
  })
})
