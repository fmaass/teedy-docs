import { describe, it, expect, beforeAll, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import PrimeVue from 'primevue/config'
import Select from 'primevue/select'
import en from '../locale/en.json'

// #177: ActivityTable is the shared audit table behind BOTH the per-document Activity tab and the
// global /history view. In global mode its filters are SERVER-side, so a filter change starts a
// different request stream — and #139's in-flight guard, which bound a "load older" to the
// DOCUMENT it was issued for, has to be generalized to that whole identity. This spec is about
// the generalized guard; the document-scope half stays covered by DocumentViewActivity.spec.ts.

const getMock = vi.fn()
vi.mock('../api/client', () => ({ default: { get: (...args: unknown[]) => getMock(...args) } }))

import ActivityTable from './ActivityTable.vue'

const PAGE_1 = [
  { id: 'r3', create_date: 300, username: 'admin', type: 'CREATE', class: 'Document', target: 'd3', message: 'm3' },
  { id: 'r2', create_date: 200, username: 'admin', type: 'CREATE', class: 'Document', target: 'd2', message: 'm2' },
]
const OLDER = [
  { id: 'r1', create_date: 100, username: 'admin', type: 'CREATE', class: 'Document', target: 'd1', message: 'm1' },
]
// The page the DELETE filter returns — a single, clearly distinguishable row.
const DELETE_PAGE = [
  { id: 'x9', create_date: 900, username: 'bob', type: 'DELETE', class: 'Tag', target: 't9', message: 'gone' },
]

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
})

const RouterLinkStub = { props: ['to'], template: '<a><slot /></a>' }

function mountGlobal() {
  const i18n = createI18n({ legacy: false, locale: 'en', fallbackLocale: 'en', messages: { en } })
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return mount(ActivityTable, {
    // Mirrors HistoryView.vue's real prop set, so this spec cannot drift from the shipped view.
    props: {
      scope: 'global' as const,
      serverFilters: true,
      linkTargets: true,
      showEntityClass: true,
      isAdmin: true,
    },
    global: {
      plugins: [i18n, PrimeVue, [VueQueryPlugin, { queryClient }]],
      stubs: { RouterLink: RouterLinkStub },
    },
  })
}

function mountDocument(props: Record<string, unknown> = {}) {
  const i18n = createI18n({ legacy: false, locale: 'en', fallbackLocale: 'en', messages: { en } })
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return mount(ActivityTable, {
    props: { scope: 'document' as const, documentId: 'doc-1', ...props },
    global: {
      plugins: [i18n, PrimeVue, [VueQueryPlugin, { queryClient }]],
      stubs: { RouterLink: RouterLinkStub },
    },
  })
}

const typeCells = (wrapper: ReturnType<typeof mountGlobal>) => wrapper.findAll('.activity-type')
const typeFilter = (wrapper: ReturnType<typeof mountGlobal>) => wrapper.findComponent(Select)

describe('ActivityTable — global mode (#177)', () => {
  it('sends no document param and no filter params on an unfiltered first load', async () => {
    getMock.mockResolvedValue({ data: { logs: PAGE_1, total: 2, has_more: false } })
    const wrapper = mountGlobal()
    await flushPromises()
    // A global request must NOT carry `document` — that is what makes it the cross-user feed.
    expect(getMock).toHaveBeenCalledWith('/auditlog', { params: { limit: 20 } })
    expect(typeCells(wrapper).length).toBe(2)
  })

  it('sends an active filter as a query param and refetches the first page', async () => {
    getMock.mockImplementation((_url: string, config?: { params?: Record<string, unknown> }) => {
      const params = config?.params ?? {}
      if (params.type === 'DELETE') {
        return Promise.resolve({ data: { logs: DELETE_PAGE, total: 1, has_more: false } })
      }
      return Promise.resolve({ data: { logs: PAGE_1, total: 2, has_more: false } })
    })
    const wrapper = mountGlobal()
    await flushPromises()
    expect(typeCells(wrapper).length).toBe(2)

    typeFilter(wrapper).vm.$emit('update:modelValue', 'DELETE')
    await flushPromises()

    // The filter is a SERVER param (not a client-side narrowing of the loaded rows).
    expect(getMock).toHaveBeenLastCalledWith('/auditlog', { params: { limit: 20, type: 'DELETE' } })
    expect(typeCells(wrapper).length).toBe(1)
    expect(typeCells(wrapper)[0].text()).toBe('Deleted')
  })

  it('discards a stale "load older" append after the FILTER SET changes mid-flight', async () => {
    // Hold the unfiltered "load older" open so it can resolve AFTER the filter changes.
    let releaseStale: (() => void) | undefined
    getMock.mockImplementation((_url: string, config?: { params?: Record<string, unknown> }) => {
      const params = config?.params ?? {}
      if (params.type === 'DELETE') {
        return Promise.resolve({ data: { logs: DELETE_PAGE, total: 1, has_more: false } })
      }
      if (params.before_date != null) {
        // The stale, in-flight older page of the UNFILTERED stream. has_more:true so an
        // erroneous append would also flip the button back on — a second observable symptom.
        return new Promise((resolve) => {
          releaseStale = () => resolve({ data: { logs: OLDER, has_more: true } })
        })
      }
      return Promise.resolve({ data: { logs: PAGE_1, total: 3, has_more: true } })
    })

    const wrapper = mountGlobal()
    await flushPromises()
    expect(typeCells(wrapper).length).toBe(2)
    expect(wrapper.find('.activity-load-older').exists()).toBe(true)

    // Start "load older" on the UNFILTERED stream; its response is held open.
    await wrapper.find('.activity-load-older').trigger('click')
    await flushPromises()

    // Change the filter — a DIFFERENT request stream. Its first page replaces the rows.
    typeFilter(wrapper).vm.$emit('update:modelValue', 'DELETE')
    await flushPromises()
    expect(typeCells(wrapper).length).toBe(1)
    expect(typeCells(wrapper)[0].text()).toBe('Deleted')
    // has_more=false on the filtered page: the button is gone.
    expect(wrapper.find('.activity-load-older').exists()).toBe(false)

    // The stale unfiltered response now resolves. It must NOT append (still 1 row) and must NOT
    // overwrite has_more (the button must stay gone) — otherwise the user sees rows that do not
    // match the filter they are looking at, and a "load older" button that pages the wrong stream.
    releaseStale?.()
    await flushPromises()
    expect(typeCells(wrapper).length).toBe(1)
    expect(typeCells(wrapper)[0].text()).toBe('Deleted')
    expect(wrapper.find('.activity-load-older').exists()).toBe(false)
  })

  it('appends the older page when the filter set is UNCHANGED (the guard is not over-eager)', async () => {
    getMock.mockImplementation((_url: string, config?: { params?: Record<string, unknown> }) => {
      const params = config?.params ?? {}
      if (params.before_date != null) {
        return Promise.resolve({ data: { logs: OLDER, has_more: false } })
      }
      return Promise.resolve({ data: { logs: PAGE_1, total: 3, has_more: true } })
    })
    const wrapper = mountGlobal()
    await flushPromises()

    await wrapper.find('.activity-load-older').trigger('click')
    await flushPromises()

    expect(getMock).toHaveBeenLastCalledWith('/auditlog', {
      params: { limit: 20, before_date: 200, before_id: 'r2' },
    })
    expect(typeCells(wrapper).length).toBe(3)
    expect(wrapper.find('.activity-load-older').exists()).toBe(false)
  })

  it('carries the ACTIVE filters on the "load older" request too', async () => {
    getMock.mockImplementation((_url: string, config?: { params?: Record<string, unknown> }) => {
      const params = config?.params ?? {}
      if (params.before_date != null) {
        return Promise.resolve({ data: { logs: OLDER, has_more: false } })
      }
      return Promise.resolve({ data: { logs: PAGE_1, total: 3, has_more: true } })
    })
    const wrapper = mountGlobal()
    await flushPromises()
    typeFilter(wrapper).vm.$emit('update:modelValue', 'CREATE')
    await flushPromises()

    await wrapper.find('.activity-load-older').trigger('click')
    await flushPromises()

    // A cursored page of a FILTERED stream must repeat the filter, or it would page the
    // unfiltered stream and mix non-matching rows into the view.
    expect(getMock).toHaveBeenLastCalledWith('/auditlog', {
      params: { limit: 20, type: 'CREATE', before_date: 200, before_id: 'r2' },
    })
  })

  it('commits the username filter on change, not on every keystroke', async () => {
    getMock.mockResolvedValue({ data: { logs: PAGE_1, total: 2, has_more: false } })
    const wrapper = mountGlobal()
    await flushPromises()
    expect(getMock).toHaveBeenCalledTimes(1)

    const input = wrapper.get('input.history-filter-user')
    // A KEYSTROKE is an `input` event; `change` only fires on blur/Enter. Driven at that level
    // rather than via setValue(), which synthesizes both and so cannot tell them apart.
    ;(input.element as HTMLInputElement).value = 'bob'
    await input.trigger('input')
    await flushPromises()
    // Typing alone must NOT refetch — otherwise every character issues a request that the next
    // one immediately supersedes.
    expect(getMock).toHaveBeenCalledTimes(1)

    await input.trigger('change')
    await flushPromises()
    expect(getMock).toHaveBeenLastCalledWith('/auditlog', { params: { limit: 20, user: 'bob' } })
    expect(getMock).toHaveBeenCalledTimes(2)
  })

  it('renders a resolvable target as a link and an unresolvable one as plain text', async () => {
    getMock.mockResolvedValue({
      data: {
        logs: [
          // Document -> linkable via its own target id.
          { id: 'a', create_date: 300, username: 'admin', type: 'CREATE', class: 'Document', target: 'doc-9', message: 'Report' },
          // User -> never linkable (no per-user route; entityId is an internal id).
          { id: 'b', create_date: 200, username: 'admin', type: 'AUTHENTICATION', class: 'User', target: 'use-1', message: 'alice' },
        ],
        total: 2,
        has_more: false,
      },
    })
    const wrapper = mountGlobal()
    await flushPromises()

    const links = wrapper.findAll('.activity-target-link')
    expect(links.length).toBe(1)
    expect(links[0].text()).toBe('Report')
    // The User row is present but rendered as text.
    expect(wrapper.text()).toContain('alice')
  })
})

// #195: the document Activity tab rendered a File row's RAW message, which is the 36-char parent
// document id CONCATENATED with the file name ("645c4756-…-07431a1a7fb4Sachspende.xml"). The
// helpers to split it already existed but were gated behind `linkTargets`, which the document view
// did not pass — so the id leaked into the cell. The LABEL must therefore be unconditional; only
// the LINK is a mode choice.
const FILE_DOC_UUID = '645c4756-1111-4222-8333-07431a1a7fb4'

describe('ActivityTable — document mode target labels (#195)', () => {
  it('strips the parent-document id from a File row label, with no links enabled', async () => {
    getMock.mockResolvedValue({
      data: {
        logs: [
          {
            id: 'f1',
            create_date: 300,
            username: 'admin',
            type: 'CREATE',
            class: 'File',
            target: 'file-1',
            message: `${FILE_DOC_UUID}Sachspende.xml`,
          },
        ],
        total: 1,
        has_more: false,
      },
    })
    const wrapper = mountDocument()
    await flushPromises()

    const cell = wrapper.get('.activity-message')
    expect(cell.text()).toBe('Sachspende.xml')
    // The defect, asserted directly: the raw id must not appear anywhere in the rendered table.
    expect(wrapper.text()).not.toContain(FILE_DOC_UUID)
  })

  it('shows the Open label for a Comment row instead of the bare document id', async () => {
    getMock.mockResolvedValue({
      data: {
        logs: [
          {
            id: 'c1',
            create_date: 300,
            username: 'admin',
            type: 'CREATE',
            class: 'Comment',
            target: 'comment-1',
            message: FILE_DOC_UUID,
          },
        ],
        total: 1,
        has_more: false,
      },
    })
    const wrapper = mountDocument()
    await flushPromises()

    expect(wrapper.get('.activity-message').text()).toBe('Open')
    expect(wrapper.text()).not.toContain(FILE_DOC_UUID)
  })

  it('still renders a plain Document row message verbatim (no behaviour change)', async () => {
    getMock.mockResolvedValue({
      data: {
        logs: [
          { id: 'd1', create_date: 300, username: 'admin', type: 'CREATE', class: 'Document', target: 'doc-1', message: 'My report' },
        ],
        total: 1,
        has_more: false,
      },
    })
    const wrapper = mountDocument()
    await flushPromises()
    expect(wrapper.get('.activity-message').text()).toBe('My report')
  })

  it('links a File row to its parent document when linkTargets is on, and adds NO class column', async () => {
    getMock.mockResolvedValue({
      data: {
        logs: [
          {
            id: 'f1',
            create_date: 300,
            username: 'admin',
            type: 'CREATE',
            class: 'File',
            target: 'file-1',
            message: `${FILE_DOC_UUID}Sachspende.xml`,
          },
        ],
        total: 1,
        has_more: false,
      },
    })
    const wrapper = mountDocument({ linkTargets: true })
    await flushPromises()

    const link = wrapper.findComponent(RouterLinkStub)
    expect(link.text()).toBe('Sachspende.xml')
    expect(link.props('to')).toEqual({ name: 'document-view-content', params: { id: FILE_DOC_UUID } })
    // linkTargets must NOT drag the entity-class column into the document tab — that column is
    // gated separately (showEntityClass), which the document view does not set.
    expect(wrapper.find('.activity-class').exists()).toBe(false)
  })
})

// The class filter must offer EVERY value the backend accepts — including the ones that are not
// Loggable entities. `Export` rows are written directly by DocumentResource; listing them in the
// feed while omitting them from the filter is the gap this pins shut on the SPA side.
describe('ActivityTable — class filter vocabulary', () => {
  it('offers Export in the class filter and labels an Export row', async () => {
    getMock.mockResolvedValue({
      data: {
        logs: [
          { id: 'e1', create_date: 300, username: 'admin', type: 'CREATE', class: 'Export', target: 'use-1', message: 'Exported 3 document(s)' },
        ],
        total: 1,
        has_more: false,
      },
    })
    const wrapper = mountGlobal()
    await flushPromises()

    // The row renders with a localized entity label rather than the raw class name.
    expect(wrapper.get('.activity-class').text()).toBe('Export')
    // Export rows have no route target, so the message stands as plain text.
    expect(wrapper.get('.activity-message').text()).toBe('Exported 3 document(s)')

    // And Export is selectable in the class filter.
    const classSelect = wrapper.findAllComponents(Select)[1]
    const values = (classSelect.props('options') as { value: string }[]).map((o) => o.value)
    expect(values).toContain('Export')
  })
})
