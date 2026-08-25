import { describe, it, expect, beforeAll, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import { createRouter, createMemoryHistory, type Router } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import PrimeVue from 'primevue/config'
import ToastService from 'primevue/toastservice'
import ConfirmationService from 'primevue/confirmationservice'
import en from '../../locale/en.json'

// The document header's own access count (#300). The load-bearing behaviour is the ORDERING:
// serving the document IS the access being counted, so the counts request must not be issued
// until the document query has resolved — otherwise the header renders the count from before the
// open the user just made.

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
  tags: [],
  shared: false,
  writable: true,
}

// Resolved by hand so the test controls exactly WHEN the document request completes.
let resolveDocument: (value: unknown) => void
const documentApiMock = vi.hoisted(() => ({ getDocument: vi.fn(), deleteDocument: vi.fn(), duplicateDocument: vi.fn() }))
vi.mock('../../api/document', () => documentApiMock)

const accessApiMock = vi.hoisted(() => ({ getDocumentAccessCounts: vi.fn() }))
vi.mock('../../api/access', () => accessApiMock)

vi.mock('../../api/file', () => ({
  getFileUrl: (id: string) => `api/file/${id}/data`,
  getDocumentZipUrl: (id: string) => `api/file/zip?id=${id}`,
}))

vi.mock('../../api/tag', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../api/tag')>()
  return {
    ...actual,
    listTags: vi.fn(() => Promise.resolve({ data: { tags: [] } })),
    getTagStats: vi.fn(() => Promise.resolve({ data: { stats: {} } })),
    getTagFacets: vi.fn(() => Promise.resolve({ data: { facets: {}, total: 0 } })),
    getTagCoOccurrence: vi.fn(() => Promise.resolve({ data: { pairs: [] } })),
  }
})

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
        matches: false, media: query, onchange: null,
        addEventListener: () => {}, removeEventListener: () => {},
        addListener: () => {}, removeListener: () => {}, dispatchEvent: () => false,
      }),
    })
  }
})

import DocumentView from './DocumentView.vue'

let router: Router

beforeEach(() => {
  setActivePinia(createPinia())
  documentApiMock.getDocument.mockReset()
  accessApiMock.getDocumentAccessCounts.mockReset()
  accessApiMock.getDocumentAccessCounts.mockResolvedValue({ data: { count: 4, files: [] } })
  router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/document', name: 'documents', component: { template: '<div />' } },
      { path: '/document/view/:id', name: 'document-view-content', component: { template: '<div />' } },
      { path: '/document/copy/:id', name: 'document-view', component: { template: '<div />' } },
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

describe('DocumentView access count (#300)', () => {
  it('renders the caller\'s own open count in the header meta line', async () => {
    documentApiMock.getDocument.mockResolvedValue({ data: DOC })
    const wrapper = await mountView()

    const badge = wrapper.find('.doc-header-meta .access-count')
    expect(badge.exists()).toBe(true)
    expect(badge.find('.access-count-value').text()).toBe('4')
    expect(badge.attributes('aria-label')).toBe('Opened 4 times by you')
  })

  it('asks the server only for THIS document, and never names a user', async () => {
    documentApiMock.getDocument.mockResolvedValue({ data: DOC })
    await mountView()
    expect(accessApiMock.getDocumentAccessCounts).toHaveBeenCalledWith('doc1')
    expect(accessApiMock.getDocumentAccessCounts.mock.calls[0]).toHaveLength(1)
  })

  it('does not read the counts until the document itself has been served', async () => {
    // Serving the document is what records the access. A counts request issued in parallel would
    // observe the state from BEFORE this open and render a number one too low.
    documentApiMock.getDocument.mockImplementation(
      () => new Promise((resolve) => { resolveDocument = resolve }),
    )
    const wrapper = await mountView()

    expect(accessApiMock.getDocumentAccessCounts).not.toHaveBeenCalled()
    expect(wrapper.find('.access-count').exists()).toBe(false)

    resolveDocument({ data: DOC })
    await flushPromises()
    await flushPromises()

    expect(accessApiMock.getDocumentAccessCounts).toHaveBeenCalledTimes(1)
    expect(wrapper.find('.doc-header-meta .access-count-value').text()).toBe('4')
  })

  it('renders no badge at all while the counts are still in flight', async () => {
    documentApiMock.getDocument.mockResolvedValue({ data: DOC })
    accessApiMock.getDocumentAccessCounts.mockImplementation(() => new Promise(() => {}))
    const wrapper = await mountView()
    // Control: the header itself did render, so the missing badge is the badge's own guard.
    expect(wrapper.find('.doc-header-meta').exists()).toBe(true)
    expect(wrapper.find('.access-count').exists()).toBe(false)
  })
})
