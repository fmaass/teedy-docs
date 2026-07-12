import { describe, it, expect, beforeAll, beforeEach, afterEach, vi } from 'vitest'
import { mount, flushPromises, enableAutoUnmount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import { createRouter, createMemoryHistory, type Router } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import PrimeVue from 'primevue/config'
import ToastService from 'primevue/toastservice'
import ConfirmationService from 'primevue/confirmationservice'
import en from '../../locale/en.json'
import type { Tag } from '../../api/tag'
import { useTagFilterStore } from '../../stores/tagFilter'

// Unmount every mounted wrapper after each test so no pending async work (queries,
// watchers, image/pdf loads) fires after the jsdom environment is torn down — that
// leak surfaced as an intermittent "HTMLElement is not defined" CI failure.
enableAutoUnmount(afterEach)

// The document header's clickable tag chips (#34) are the unit under test: a chip
// click must apply a POSITIVE tag filter via the store's selectTag and land on the
// filtered documents list. The chip must never route through toggleTag — its
// 3-state cycle would EXCLUDE an already-selected tag, so the already-selected
// case is pinned explicitly below.

const DOC_TAG: Tag = { id: 'tag-inv', name: 'Invoice', color: '#d32f2f', parent: null }

const DOC = {
  id: 'doc1',
  title: 'Quarterly invoice',
  description: '',
  create_date: 1700000000000,
  update_date: 1700000000000,
  language: 'eng',
  creator: 'admin',
  file_id: null,
  file_count: 0,
  tags: [DOC_TAG],
  shared: false,
  writable: true,
}

vi.mock('../../api/document', () => ({
  getDocument: vi.fn(() => Promise.resolve({ data: DOC })),
  deleteDocument: vi.fn(),
}))

vi.mock('../../api/file', () => ({
  getFileUrl: (id: string) => `/api/file/${id}/data`,
}))

// The tagFilter store hits the tag API on setup; feed it the doc's tag so the
// store logic runs against real data without the network.
vi.mock('../../api/tag', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../api/tag')>()
  return {
    ...actual,
    listTags: vi.fn(() => Promise.resolve({ data: { tags: [DOC_TAG] } })),
    getTagStats: vi.fn(() => Promise.resolve({ data: { stats: {} } })),
    getTagFacets: vi.fn(() => Promise.resolve({ data: { facets: {}, total: 0 } })),
    getTagCoOccurrence: vi.fn(() => Promise.resolve({ data: { pairs: [] } })),
  }
})

// PrimeVue overlays probe window.matchMedia, and Tabs uses ResizeObserver —
// neither is provided by jsdom. Stub both for this environment.
beforeAll(() => {
  if (typeof globalThis.ResizeObserver !== 'function') {
    globalThis.ResizeObserver = class {
      observe() {}
      unobserve() {}
      disconnect() {}
    } as unknown as typeof ResizeObserver
  }
  if (typeof window.matchMedia !== 'function') {
    Object.defineProperty(window, 'matchMedia', {
      configurable: true,
      value: (query: string) => ({
        matches: false,
        media: query,
        onchange: null,
        addEventListener: () => {},
        removeEventListener: () => {},
        addListener: () => {},
        removeListener: () => {},
        dispatchEvent: () => false,
      }),
    })
  }
})

import DocumentView from './DocumentView.vue'

let router: Router

beforeEach(() => {
  setActivePinia(createPinia())
  router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/document', name: 'documents', component: { template: '<div />' } },
      {
        path: '/document/view/:id',
        name: 'document-view-content',
        component: { template: '<div />' },
      },
    ],
  })
})

async function mountView() {
  const i18n = createI18n({ legacy: false, locale: 'en', fallbackLocale: 'en', messages: { en } })
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  router.push('/document/view/doc1')
  await router.isReady()
  const wrapper = mount(DocumentView, {
    props: { id: 'doc1' },
    global: {
      plugins: [i18n, router, PrimeVue, ToastService, ConfirmationService, [VueQueryPlugin, { queryClient }]],
      stubs: { RouterView: true },
    },
  })
  await flushPromises()
  return wrapper
}

describe('DocumentView — clickable header tag chips (#34)', () => {
  it('clicking an unselected tag chip selects the tag and navigates to the filtered list', async () => {
    const wrapper = await mountView()
    const store = useTagFilterStore()
    expect(store.selectedTagIds.has(DOC_TAG.id)).toBe(false)

    // The header chip renders as a filter button with the tag's accessible name.
    const chip = wrapper.find('.doc-header-tags button.tag-clickable')
    expect(chip.exists()).toBe(true)
    expect(chip.attributes('aria-label')).toBe('Filter by tag Invoice')

    await chip.trigger('click')
    await flushPromises()

    expect(store.selectedTagIds.has(DOC_TAG.id)).toBe(true)
    expect(store.excludedTagIds.size).toBe(0)
    // selectTag itself navigates to the documents list carrying the filter.
    expect(router.currentRoute.value.name).toBe('documents')
    expect(router.currentRoute.value.query.tags).toBe(DOC_TAG.id)
  })

  it('clicking an ALREADY-SELECTED tag chip navigates WITHOUT excluding the tag', async () => {
    const wrapper = await mountView()
    const store = useTagFilterStore()
    // The tag is already part of the active filter (e.g. the user arrived from
    // the filtered list).
    store.selectedTagIds = new Set([DOC_TAG.id])

    await wrapper.find('.doc-header-tags button.tag-clickable').trigger('click')
    await flushPromises()

    // Idempotent: still selected, and NEVER moved into the excluded set (the
    // toggleTag cycle would do exactly that).
    expect(store.selectedTagIds.has(DOC_TAG.id)).toBe(true)
    expect(store.excludedTagIds.size).toBe(0)
    // Navigation still lands on the filtered documents list.
    expect(router.currentRoute.value.name).toBe('documents')
    expect(router.currentRoute.value.query.tags).toBe(DOC_TAG.id)
  })
})
