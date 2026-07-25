import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory, type Router } from 'vue-router'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import PrimeVue from 'primevue/config'
import Tooltip from 'primevue/tooltip'
import DocumentTable from './DocumentTable.vue'
import type { DocumentListItem } from '../api/document'
import { useTagFilterStore } from '../stores/tagFilter'

// vue-i18n stub: return the key so assertions target logic, not copy.
vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (k: string) => k }) }))

// Stub the file API (thumb URLs) — no network in unit tests.
vi.mock('../api/file', () => ({ getFileUrl: (id: string, size: string) => `/file/${id}/${size}` }))

// The table now instantiates the tagFilter store (clickable tag chips, #34), which
// hits the tag API on setup. Stub it so the store's useQuery calls never touch the
// network; the store logic itself is unit-tested in stores/tagFilter.spec.ts.
vi.mock('../api/tag', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/tag')>()
  return {
    ...actual,
    listTags: vi.fn().mockResolvedValue({ data: { tags: [] } }),
    getTagStats: vi.fn().mockResolvedValue({ data: { stats: {} } }),
    getTagFacets: vi.fn().mockResolvedValue({ data: { facets: {}, total: 0 } }),
    getTagCoOccurrence: vi.fn().mockResolvedValue({ data: { pairs: [] } }),
  }
})

function makeDoc(tagCount: number): DocumentListItem {
  return {
    id: 'doc1',
    title: 'Doc',
    description: '',
    create_date: 0,
    update_date: 0,
    language: 'eng',
    file_id: null,
    file_count: 0,
    tags: Array.from({ length: tagCount }, (_, i) => ({
      id: `t${i}`,
      name: `tag-${i}`,
      color: '#123456',
    })),
    shared: false,
  }
}

// A minimal router so the tagFilter store's useRouter/useRoute resolve; the store
// scopes hydration to the `documents` route, so we name the initial route that.
let router: Router

beforeEach(() => {
  setActivePinia(createPinia())
  router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/document', name: 'documents', component: { template: '<div />' } },
      // The title cell is a <router-link> to the document view — the route must
      // resolve for the link to render a real href (#194).
      { path: '/document/view/:id', name: 'document-view', component: { template: '<div />' } },
    ],
  })
})

// Mount with the real PrimeVue DataTable/Column so scoped column body slots render
// exactly as in production. selectable=false exercises the single-select table;
// selectable=true exercises the checkbox table DocumentList actually ships.
async function mountTable(doc: DocumentListItem, selectable = false) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  router.push('/document')
  await router.isReady()
  return mount(DocumentTable, {
    props: {
      documents: [doc],
      totalRecords: 1,
      rows: 20,
      first: 0,
      selectable,
    },
    global: {
      plugins: [[PrimeVue, { theme: 'none' }], router, [VueQueryPlugin, { queryClient }]],
      directives: { tooltip: Tooltip },
      // FavoriteStar has its own spec (FavoriteStar.spec.ts) and pulls in Toast/mutation
      // services this table-focused unit test does not provide; stub it to a marker.
      stubs: { FavoriteStar: { template: '<button class="fav-star-stub" />' } },
    },
  })
}

describe('DocumentTable — tag overflow reveal (#24)', () => {
  it('renders the first three tags inline and delegates the rest to the overflow control', async () => {
    const wrapper = await mountTable(makeDoc(6))
    const html = wrapper.html()
    // The first three tags render inline as badges.
    for (let i = 0; i < 3; i++) {
      expect(html).toContain(`tag-${i}`)
    }
    // A focusable overflow trigger stands in for the remaining tags (reveal
    // semantics + hidden-name reachability are covered in TagOverflow.spec.ts,
    // since the panel now teleports to <body> to escape the table's clipping).
    const overflow = wrapper.find('.tag-overflow')
    expect(overflow.exists()).toBe(true)
    expect(overflow.attributes('tabindex')).toBe('0')
    expect(overflow.text()).toContain('+3')
  })

  it('shows no overflow control when a doc has 3 or fewer tags', async () => {
    const wrapper = await mountTable(makeDoc(3))
    expect(wrapper.find('.tag-overflow').exists()).toBe(false)
  })

  it('labels the overflow control with the exact hidden count (4 tags → +1)', async () => {
    const wrapper = await mountTable(makeDoc(4))
    const overflow = wrapper.find('.tag-overflow')
    expect(overflow.exists()).toBe(true)
    // 4 tags: 3 badges shown + a "+1" control.
    expect(overflow.text()).toContain('+1')
    // aria-label carries the count via the i18n key argument (key stubbed to itself).
    expect(overflow.attributes('aria-label')).toBe('ui.more_tags')
  })

  it('activating the +N control does NOT emit a row click (click is stopped)', async () => {
    const wrapper = await mountTable(makeDoc(6))
    const overflow = wrapper.find('.tag-overflow')
    await overflow.trigger('click')
    // The stopped handler must keep the click from bubbling to the DataTable row.
    expect(wrapper.emitted('rowClick')).toBeUndefined()
    expect(wrapper.emitted('rowDblclick')).toBeUndefined()
  })
})

describe('DocumentTable — clickable tag chips (#34)', () => {
  it('clicking an inline tag chip filters by that tag and does NOT open the row', async () => {
    const wrapper = await mountTable(makeDoc(2))
    const store = useTagFilterStore()
    const selectSpy = vi.spyOn(store, 'selectTag').mockImplementation(() => {})

    // The inline chips now render as interactive filter buttons.
    const chip = wrapper.find('.doc-tags .tag-clickable')
    expect(chip.exists()).toBe(true)

    await chip.trigger('click')
    // Selects exactly the clicked tag (t0).
    expect(selectSpy).toHaveBeenCalledWith('t0')
    // BOTH click and dblclick propagation are stopped, so the row's open handlers
    // never fire from a chip interaction.
    await chip.trigger('dblclick')
    expect(wrapper.emitted('rowClick')).toBeUndefined()
    expect(wrapper.emitted('rowDblclick')).toBeUndefined()
  })
})

describe('DocumentTable — native open-in-new-tab affordances (#194)', () => {
  // Dispatch a REAL MouseEvent so (a) `defaultPrevented` is observable and (b) the
  // event bubbles into PrimeVue's own row handler exactly as it does in a browser —
  // which is the whole point: DataTable.onRowClick bails out via isClickable() when
  // the target is an <a>, so the anchor's handler is the ONLY source of rowClick.
  function clickTitle(wrapper: ReturnType<typeof mount>, init: MouseEventInit = {}): MouseEvent {
    const link = wrapper.find('a.doc-title')
    expect(link.exists()).toBe(true)
    const event = new MouseEvent('click', { button: 0, bubbles: true, cancelable: true, ...init })
    link.element.dispatchEvent(event)
    return event
  }

  it.each([[false], [true]])(
    'renders the title as a real link to the document view (selectable=%s)',
    async (selectable) => {
      const wrapper = await mountTable(makeDoc(0), selectable)
      const link = wrapper.find('a.doc-title')
      expect(link.exists()).toBe(true)
      expect(link.attributes('href')).toContain('/document/view/doc1')
      expect(link.text()).toBe('Doc')
    },
  )

  it.each([[false], [true]])(
    'a plain left click is intercepted and emits EXACTLY ONE rowClick (selectable=%s)',
    async (selectable) => {
      const wrapper = await mountTable(makeDoc(0), selectable)
      const event = clickTitle(wrapper)
      await wrapper.vm.$nextTick()
      // The list contract is unchanged: plain click opens the slide-over, so the
      // navigation is suppressed and re-emitted once — never zero, never twice.
      expect(event.defaultPrevented).toBe(true)
      const emitted = wrapper.emitted('rowClick')
      expect(emitted).toHaveLength(1)
      expect((emitted as unknown[][])[0][0]).toMatchObject({ id: 'doc1' })
    },
  )

  it.each([
    ['ctrl', { ctrlKey: true }],
    ['meta', { metaKey: true }],
    ['middle button', { button: 1 }],
  ])(
    'a %s click falls through to the native href — no interception, no rowClick',
    async (_label, init) => {
      // jsdom logs "Not implemented: navigation (except hash changes)" for these three
      // — that stderr line IS the proof the click reached a real href unimpeded.
      const wrapper = await mountTable(makeDoc(0))
      const event = clickTitle(wrapper, init as MouseEventInit)
      await wrapper.vm.$nextTick()
      expect(event.defaultPrevented).toBe(false)
      expect(wrapper.emitted('rowClick')).toBeUndefined()
    },
  )

  it('double-clicking the title still opens the full view (rowDblclick survives the anchor)', async () => {
    const wrapper = await mountTable(makeDoc(0))
    await wrapper.find('a.doc-title').trigger('dblclick')
    const emitted = wrapper.emitted('rowDblclick')
    expect(emitted).toHaveLength(1)
    expect((emitted as unknown[][])[0][0]).toMatchObject({ id: 'doc1' })
  })
})

describe('DocumentTable — double-click to open full view (#25)', () => {
  it('emits rowDblclick with the document on a native row double-click', async () => {
    const doc = makeDoc(1)
    const wrapper = await mountTable(doc)
    const row = wrapper.find('tbody tr')
    expect(row.exists()).toBe(true)
    await row.trigger('dblclick')
    const emitted = wrapper.emitted('rowDblclick')
    expect(emitted).toBeTruthy()
    expect((emitted as unknown[][])[0][0]).toMatchObject({ id: 'doc1' })
  })
})
