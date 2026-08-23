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
const updateMock = vi.hoisted(() =>
  vi.fn((_id: string, name: string, query: string) => Promise.resolve({ data: { id: 'f1', name, query } })),
)
const deleteMock = vi.hoisted(() => vi.fn((_id: string) => Promise.resolve({ data: {} })))
const listMock = vi.hoisted(() => vi.fn(() => Promise.resolve({ data: { saved_filters: [] as unknown[] } })))
vi.mock('../api/savedfilter', () => ({
  listSavedFilters: () => listMock(),
  createSavedFilter: (name: string, query: string) => createMock(name, query),
  updateSavedFilter: (id: string, name: string, query: string) => updateMock(id, name, query),
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
          // `size` is a PrimeVue prop; declaring it here stops it falling through to
          // the bare <input>, where "small" is not a valid HTML size attribute.
          props: ['modelValue', 'size'],
          emits: ['update:modelValue'],
          template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
        },
      },
    },
  })
}

type Wrapper = ReturnType<typeof mountView>

function buttonByText(wrapper: Wrapper, text: string) {
  const button = wrapper.findAll('button').find((b) => b.text() === text)
  expect(button, `no button labelled "${text}"`).toBeTruthy()
  return button!
}

function buttonByAria(wrapper: Wrapper, label: string) {
  const button = wrapper.findAll('button').find((b) => b.attributes('aria-label') === label)
  expect(button, `no button with aria-label "${label}"`).toBeTruthy()
  return button!
}

async function openSaveDialog(wrapper: Wrapper, name: string) {
  await buttonByText(wrapper, 'ui.saved_filters.save_current').trigger('click')
  await wrapper.get('#saved-filter-name').setValue(name)
  await buttonByText(wrapper, 'save').trigger('click')
}

/** The names rendered by the list, in DOM order. */
function renderedNames(wrapper: Wrapper) {
  return wrapper.findAll('.saved-filters-apply').map((b) => b.text())
}

describe('SavedFilters — save affordance derives from route.query (#42)', () => {
  beforeEach(() => {
    routerPush.mockReset()
    createMock.mockClear()
    updateMock.mockClear()
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
    await openSaveDialog(wrapper, 'My filter')

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

    await openSaveDialog(wrapper, 'Verbatim')

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

  it('offers a confirmed OVERWRITE when the save name duplicates an existing filter (case-insensitive)', async () => {
    // #193: the duplicate guard no longer dead-ends the save — the same
    // case-insensitive match now routes into a confirm-replace, which UPDATES the
    // matched filter with the CURRENT query instead of creating a second one.
    savedFiltersHolder.list = [{ id: 'f1', name: 'Invoices', query: 'search=x', create_date: 1 }]
    mockRoute.query = { search: 'y' }
    const wrapper = mountView()
    await flushPromises()

    await openSaveDialog(wrapper, 'invoices')

    // Nothing is created, and nothing is replaced before the user confirms.
    expect(createMock).not.toHaveBeenCalled()
    expect(updateMock).not.toHaveBeenCalled()
    expect(confirmDangerMock).toHaveBeenCalledTimes(1)
    expect(confirmDangerMock.mock.calls[0][0].header).toBe('ui.saved_filters.replace_title')

    confirmDangerMock.mock.calls[0][0].accept()
    await flushPromises()

    // The matched filter is overwritten with the CURRENT route query.
    expect(createMock).not.toHaveBeenCalled()
    expect(updateMock).toHaveBeenCalledTimes(1)
    expect(updateMock).toHaveBeenCalledWith('f1', 'invoices', 'search=y')
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

describe('SavedFilters — list sort, search and rename (#193)', () => {
  // Mixed case on purpose: the server orders under a BINARY collation ("Zebra"
  // before "apple"), so the list arrives in that order and the component must
  // re-sort it with localeCompare.
  const serverOrder = [
    { id: 'f1', name: 'Zebra', query: 'search=z', create_date: 3 },
    { id: 'f2', name: 'apple', query: 'search=a', create_date: 1 },
    { id: 'f3', name: 'Mango', query: 'search=m', create_date: 2 },
  ]

  beforeEach(() => {
    routerPush.mockReset()
    createMock.mockClear()
    updateMock.mockClear()
    deleteMock.mockClear()
    confirmDangerMock.mockClear()
    mockRoute.query = {}
    savedFiltersHolder.list = serverOrder.map((f) => ({ ...f }))
  })

  it('sorts with localeCompare and toggles the direction', async () => {
    const wrapper = mountView()
    await flushPromises()

    // localeCompare — NOT the server's binary order, which would put "Zebra" first.
    expect(renderedNames(wrapper)).toEqual(['apple', 'Mango', 'Zebra'])

    await buttonByAria(wrapper, 'ui.saved_filters.sort_descending').trigger('click')
    expect(renderedNames(wrapper)).toEqual(['Zebra', 'Mango', 'apple'])

    // Toggling back restores ascending, and the control advertises the next action.
    await buttonByAria(wrapper, 'ui.saved_filters.sort_ascending').trigger('click')
    expect(renderedNames(wrapper)).toEqual(['apple', 'Mango', 'Zebra'])
  })

  it('never reorders the query-cache array in place', async () => {
    // The list handed to the component IS the vue-query cache entry; sorting it
    // directly would reorder data every other consumer shares.
    const cached = savedFiltersHolder.list
    const wrapper = mountView()
    await flushPromises()
    await buttonByAria(wrapper, 'ui.saved_filters.sort_descending').trigger('click')

    expect((cached as { name: string }[]).map((f) => f.name)).toEqual(['Zebra', 'apple', 'Mango'])
  })

  it('filters the list by name, case-insensitively', async () => {
    const wrapper = mountView()
    await flushPromises()

    await wrapper.get('#saved-filter-search').setValue('AN')
    expect(renderedNames(wrapper)).toEqual(['Mango'])

    // A term matching nothing shows the no-matches message, not the empty state.
    await wrapper.get('#saved-filter-search').setValue('zzz')
    expect(renderedNames(wrapper)).toEqual([])
    expect(wrapper.text()).toContain('ui.saved_filters.no_matches')
    expect(wrapper.text()).not.toContain('ui.saved_filters.empty')

    // Clearing restores the full sorted list.
    await wrapper.get('#saved-filter-search').setValue('')
    expect(renderedNames(wrapper)).toEqual(['apple', 'Mango', 'Zebra'])
  })

  it('renames a filter, carrying its STORED query over verbatim', async () => {
    mockRoute.query = { search: 'unrelated' }
    const wrapper = mountView()
    await flushPromises()

    await buttonByAria(wrapper, 'ui.saved_filters.rename_button').trigger('click')
    // The dialog is seeded with the current name.
    expect((wrapper.get('#saved-filter-rename-name').element as HTMLInputElement).value).toBe('apple')

    await wrapper.get('#saved-filter-rename-name').setValue('Apricot')
    await buttonByText(wrapper, 'rename').trigger('click')
    await flushPromises()

    // The stored query travels unchanged — a rename from an unrelated view must
    // NOT re-capture route.query (which is search=unrelated here).
    expect(updateMock).toHaveBeenCalledTimes(1)
    expect(updateMock).toHaveBeenCalledWith('f2', 'Apricot', 'search=a')
  })

  it('blocks a rename onto another filter name but allows a case-only self-rename', async () => {
    const wrapper = mountView()
    await flushPromises()

    // The first rename control belongs to the first rendered row ("apple").
    await buttonByAria(wrapper, 'ui.saved_filters.rename_button').trigger('click')
    await wrapper.get('#saved-filter-rename-name').setValue('mango')
    await buttonByText(wrapper, 'rename').trigger('click')

    expect(updateMock).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('ui.saved_filters.name_exists')

    // Its OWN name in a different case is not a duplicate (the index is exact-case).
    await wrapper.get('#saved-filter-rename-name').setValue('APPLE')
    await buttonByText(wrapper, 'rename').trigger('click')
    await flushPromises()

    expect(updateMock).toHaveBeenCalledTimes(1)
    expect(updateMock).toHaveBeenCalledWith('f2', 'APPLE', 'search=a')
  })

  it('rejects an empty rename without calling the API', async () => {
    const wrapper = mountView()
    await flushPromises()

    await buttonByAria(wrapper, 'ui.saved_filters.rename_button').trigger('click')
    await wrapper.get('#saved-filter-rename-name').setValue('   ')
    await buttonByText(wrapper, 'rename').trigger('click')

    expect(updateMock).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('ui.saved_filters.name_required')
  })
})

describe('SavedFilters — the ACTIVE saved filter is highlighted (#297)', () => {
  // The stored query deliberately carries the keys in a DIFFERENT order from the one
  // serialize() emits (FILTER_KEYS order: tags before search) — the reporter saves from
  // whatever URL he is on, and tagFilter.buildFilterQuery rebuilds the canonical URL in
  // its own insertion order. Naive string equality would miss this match.
  const invoices = { id: 'f1', name: 'Invoices', query: 'search=x&tags=a', create_date: 1 }
  const receipts = { id: 'f2', name: 'Receipts', query: 'search=z', create_date: 2 }

  beforeEach(() => {
    routerPush.mockReset()
    createMock.mockClear()
    updateMock.mockClear()
    deleteMock.mockClear()
    confirmDangerMock.mockClear()
    mockRoute.query = {}
    savedFiltersHolder.list = []
  })

  /** The saved-filters toolbar button: the group's first control. */
  function toggleButton(wrapper: Wrapper) {
    return wrapper.findAll('button')[0]
  }

  function expectDefaultToolbarButton(wrapper: Wrapper) {
    const toggle = toggleButton(wrapper)
    expect(toggle.text()).toBe('ui.saved_filters.saved_label')
    expect(toggle.classes()).not.toContain('saved-filters-active')
    expect(toggle.attributes('aria-current')).toBeUndefined()
    expect(toggle.attributes('aria-label')).toBe('ui.saved_filters.saved_label')
    expect(wrapper.findAll('.saved-filters-item.active')).toHaveLength(0)
  }

  it('swaps the toolbar label to the active filter, matching across KEY ORDER', async () => {
    savedFiltersHolder.list = [{ ...receipts }, { ...invoices }]
    // serialize() emits `tags=a&search=x`; the stored string is `search=x&tags=a`.
    mockRoute.query = { search: 'x', tags: 'a' }
    const wrapper = mountView()
    await flushPromises()

    const toggle = toggleButton(wrapper)
    expect(toggle.text()).toBe('Invoices')
    expect(toggle.classes()).toContain('saved-filters-active')
    expect(toggle.attributes('aria-current')).toBe('true')
    // The accessible name keeps the control's PURPOSE and names the active filter —
    // the e2e suite opens this dropdown by the "Saved filters" substring, and an
    // accessible name of just the filter name would also collide (strict mode) with
    // the popover row that applies it.
    expect(toggle.attributes('aria-label')).toBe('ui.saved_filters.saved_label: Invoices')

    // Exactly one popover row is marked, and it is the matching one.
    const activeRows = wrapper.findAll('.saved-filters-item.active')
    expect(activeRows).toHaveLength(1)
    expect(activeRows[0].text()).toContain('Invoices')
    const marked = wrapper
      .findAll('.saved-filters-apply')
      .filter((b) => b.attributes('aria-current') === 'true')
      .map((b) => b.text())
    expect(marked).toEqual(['Invoices'])
  })

  it('renders the DEFAULT toolbar button when no stored filter matches', async () => {
    savedFiltersHolder.list = [{ ...invoices }]
    mockRoute.query = { tags: 'b' }
    const wrapper = mountView()
    await flushPromises()

    expectDefaultToolbarButton(wrapper)
  })

  it('falls back to the default as soon as ONE dimension is edited', async () => {
    savedFiltersHolder.list = [{ ...invoices }]
    mockRoute.query = { search: 'x', tags: 'a' }
    const wrapper = mountView()
    await flushPromises()
    expect(toggleButton(wrapper).text()).toBe('Invoices')

    // Editing the filter is NOT a "modified" state in this phase (that is a separate
    // ticket): the filter simply stops being the active one.
    mockRoute.query = { search: 'y', tags: 'a' }
    await flushPromises()
    expectDefaultToolbarButton(wrapper)
  })

  it('ignores favorites when deciding which filter is active', async () => {
    // The reporter's decision (#297, 2026-08-23): favourites are an informal
    // collection (#209), not a filter dimension — a favourites toggle must not
    // un-highlight the saved filter that is applied.
    savedFiltersHolder.list = [{ ...invoices }]
    mockRoute.query = { search: 'x', tags: 'a', favorites: 'me' }
    const wrapper = mountView()
    await flushPromises()

    expect(toggleButton(wrapper).text()).toBe('Invoices')
  })

  it('never marks a filter active on an UNFILTERED route', async () => {
    // A stored filter with an empty query would otherwise match the bare document list
    // and leave the toolbar permanently "active".
    savedFiltersHolder.list = [{ id: 'f3', name: 'Empty', query: '', create_date: 3 }]
    mockRoute.query = {}
    const wrapper = mountView()
    await flushPromises()

    expectDefaultToolbarButton(wrapper)
  })
})
