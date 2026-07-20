import { describe, it, expect, beforeAll, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { ref } from 'vue'
import { createI18n } from 'vue-i18n'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import PrimeVue from 'primevue/config'
import Select from 'primevue/select'
import en from '../../locale/en.json'
import { DocumentKey } from './documentKey'
import type { DocumentDetail } from '../../api/document'

// #139: the Activity tab pages older audit rows incrementally with a "load older" button
// (keyset cursor), APPENDS each page to the accumulated set, stops when has_more is false,
// and keeps the client-side type filter operating across ALL accumulated pages. The
// /auditlog client is a dependency (mocked); the unit under test is the view's paging glue.

const getMock = vi.fn()
vi.mock('../../api/client', () => ({ default: { get: (...args: unknown[]) => getMock(...args) } }))

import DocumentViewActivity from './DocumentViewActivity.vue'

// Page 1 = the two newest rows (has_more true); page 2 = the single oldest row (has_more false).
// The oldest row is a DELETE that only ever arrives via "load older", so filtering to DELETE
// proves the filter spans the accumulated pages, not just the first one.
const PAGE_1 = [
  { id: 'r3', create_date: 300, username: 'admin', type: 'CREATE', message: 'm3' },
  { id: 'r2', create_date: 200, username: 'admin', type: 'UPDATE', message: 'm2' },
]
const PAGE_2 = [{ id: 'r1', create_date: 100, username: 'admin', type: 'DELETE', message: 'm1' }]

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

beforeEach(() => {
  getMock.mockReset()
  getMock.mockImplementation((_url: string, config?: { params?: Record<string, unknown> }) => {
    const params = config?.params ?? {}
    if (params.before_date == null) {
      return Promise.resolve({ data: { logs: PAGE_1, total: 3, has_more: true } })
    }
    return Promise.resolve({ data: { logs: PAGE_2, has_more: false } })
  })
})

function mountActivity() {
  const i18n = createI18n({ legacy: false, locale: 'en', fallbackLocale: 'en', messages: { en } })
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const doc = ref({ id: 'doc-1' } as unknown as DocumentDetail)
  return mount(DocumentViewActivity, {
    global: {
      plugins: [i18n, PrimeVue, [VueQueryPlugin, { queryClient }]],
      provide: { [DocumentKey as symbol]: doc },
    },
  })
}

const typeCells = (wrapper: ReturnType<typeof mountActivity>) => wrapper.findAll('.activity-type')

describe('DocumentViewActivity — load older (#139)', () => {
  it('first load requests a page with a limit and NO cursor', async () => {
    const wrapper = await mountActivity()
    await flushPromises()
    expect(getMock).toHaveBeenCalledTimes(1)
    expect(getMock).toHaveBeenNthCalledWith(1, '/auditlog', { params: { document: 'doc-1', limit: 20 } })
    expect(typeCells(wrapper).length).toBe(2)
    // has_more true -> the button is offered.
    expect(wrapper.find('.activity-load-older').exists()).toBe(true)
  })

  it('"load older" passes the oldest row as the cursor, appends the page, and stops on has_more=false', async () => {
    const wrapper = await mountActivity()
    await flushPromises()

    await wrapper.find('.activity-load-older').trigger('click')
    await flushPromises()

    // The cursor is the oldest currently-loaded row (r2 / create_date 200), not a fresh first page.
    expect(getMock).toHaveBeenNthCalledWith(2, '/auditlog', {
      params: { document: 'doc-1', limit: 20, before_date: 200, before_id: 'r2' },
    })
    // The older page was APPENDED (2 + 1), the earlier rows retained.
    expect(typeCells(wrapper).length).toBe(3)
    // has_more=false -> the button is gone (termination).
    expect(wrapper.find('.activity-load-older').exists()).toBe(false)
  })

  it('does not duplicate a boundary row the server returns again on the older page', async () => {
    // Overlapping keyset page: page 2 re-returns r2 (the boundary) plus the new r1.
    getMock.mockImplementation((_url: string, config?: { params?: Record<string, unknown> }) => {
      const params = config?.params ?? {}
      if (params.before_date == null) {
        return Promise.resolve({ data: { logs: PAGE_1, total: 3, has_more: true } })
      }
      return Promise.resolve({ data: { logs: [PAGE_1[1], ...PAGE_2], has_more: false } })
    })
    const wrapper = await mountActivity()
    await flushPromises()
    await wrapper.find('.activity-load-older').trigger('click')
    await flushPromises()
    // 3 distinct rows, not 4 — the duplicated r2 was dropped on append.
    expect(typeCells(wrapper).length).toBe(3)
  })

  it('keeps the client-side type filter operating across the accumulated pages', async () => {
    const wrapper = await mountActivity()
    await flushPromises()
    await wrapper.find('.activity-load-older').trigger('click')
    await flushPromises()
    expect(typeCells(wrapper).length).toBe(3)

    const select = wrapper.findComponent(Select)

    // DELETE only exists on the SECOND (older) page — filtering to it proves the filter
    // spans the accumulated set, not just the first page.
    select.vm.$emit('update:modelValue', 'DELETE')
    await flushPromises()
    expect(typeCells(wrapper).length).toBe(1)
    expect(typeCells(wrapper)[0].text()).toBe('Deleted')

    // A first-page type still filters correctly.
    select.vm.$emit('update:modelValue', 'CREATE')
    await flushPromises()
    expect(typeCells(wrapper).length).toBe(1)
    expect(typeCells(wrapper)[0].text()).toBe('Created')

    // Back to all types restores every accumulated row.
    select.vm.$emit('update:modelValue', '__all__')
    await flushPromises()
    expect(typeCells(wrapper).length).toBe(3)
  })

  it('discards a stale "load older" response after switching documents (#139)', async () => {
    // Hold doc-1's cursored "load older" open so it can resolve AFTER navigation to doc-2.
    let releaseStale: (() => void) | undefined
    const DOC2_PAGE = [{ id: 'd2r1', create_date: 900, username: 'admin', type: 'CREATE', message: 'd2' }]
    getMock.mockImplementation((_url: string, config?: { params?: Record<string, unknown> }) => {
      const params = config?.params ?? {}
      if (params.document === 'doc-1' && params.before_date != null) {
        return new Promise((resolve) => {
          releaseStale = () => resolve({ data: { logs: PAGE_2, has_more: false } })
        })
      }
      if (params.document === 'doc-2') {
        return Promise.resolve({ data: { logs: DOC2_PAGE, total: 1, has_more: false } })
      }
      return Promise.resolve({ data: { logs: PAGE_1, total: 3, has_more: true } })
    })

    const i18n = createI18n({ legacy: false, locale: 'en', fallbackLocale: 'en', messages: { en } })
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const doc = ref({ id: 'doc-1' } as unknown as DocumentDetail)
    const wrapper = mount(DocumentViewActivity, {
      global: {
        plugins: [i18n, PrimeVue, [VueQueryPlugin, { queryClient }]],
        provide: { [DocumentKey as symbol]: doc },
      },
    })
    await flushPromises()
    expect(typeCells(wrapper).length).toBe(2)

    // Start "load older" for doc-1 (its response is held open).
    await wrapper.find('.activity-load-older').trigger('click')
    await flushPromises()

    // Navigate to doc-2; its first page loads and replaces the accumulation.
    doc.value = { id: 'doc-2' } as unknown as DocumentDetail
    await flushPromises()
    expect(typeCells(wrapper).length).toBe(1)

    // The stale doc-1 response now resolves — it must NOT append to doc-2.
    releaseStale?.()
    await flushPromises()
    expect(typeCells(wrapper).length).toBe(1)
  })
})
