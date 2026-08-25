import { describe, it, expect, beforeAll, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import PrimeVue from 'primevue/config'
import en from '../../locale/en.json'
import type { AccessStats } from '../../api/access'

// The access-counter half of the admin statistics screen (#300). The app-stats half has its own
// spec; here it is stubbed with a minimal payload so this file only ever fails for access reasons.
const appApiMock = vi.hoisted(() => ({
  getAppStats: vi.fn(() =>
    Promise.resolve({
      window: 7,
      totals: { documents: 0, files: 0, users: 0, tags: 0, favorites: 0 },
      storage: { global: 0, per_user: [] },
      series: { documents_created: [], activity: [] },
    }),
  ),
}))
vi.mock('../../api/app', async (orig) => ({ ...(await orig()), ...appApiMock }))

const accessApiMock = vi.hoisted(() => ({
  getAccessStats: vi.fn(),
}))
vi.mock('../../api/access', () => accessApiMock)

vi.mock('primevue/chart', () => ({
  default: { name: 'Chart', props: ['type', 'data', 'options'], template: '<div class="chart-stub" />' },
}))

beforeAll(() => {
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

import SettingsStats from './SettingsStats.vue'

const STATS: AccessStats = {
  total_document_accesses: 137,
  total_file_accesses: 52,
  documents: [
    {
      id: 'doc-popular',
      title: 'Quarterly report',
      total: 12,
      users: [
        { username: 'mario', count: 8 },
        { username: 'admin', count: 4 },
      ],
    },
    { id: 'doc-quiet', title: 'Old memo', total: 1, users: [{ username: 'admin', count: 1 }] },
  ],
}

function mountView() {
  const i18n = createI18n({ legacy: false, locale: 'en', fallbackLocale: 'en', messages: { en } })
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return mount(SettingsStats, {
    global: { plugins: [i18n, PrimeVue, [VueQueryPlugin, { queryClient }]] },
  })
}

describe('SettingsStats access counters (#300)', () => {
  beforeEach(() => {
    accessApiMock.getAccessStats.mockReset()
  })

  it('asks for a bounded ranking rather than the whole table', async () => {
    accessApiMock.getAccessStats.mockResolvedValue({ data: STATS })
    mountView()
    await flushPromises()
    expect(accessApiMock.getAccessStats).toHaveBeenCalledWith(10)
  })

  it('renders the global totals and the most-used documents with their per-user breakdown', async () => {
    accessApiMock.getAccessStats.mockResolvedValue({ data: STATS })
    const wrapper = mountView()
    await flushPromises()

    const text = wrapper.text()
    expect(text).toContain(en.ui.access.admin_title)
    expect(text).toContain('137')
    expect(text).toContain('52')
    expect(text).toContain('Quarterly report')
    expect(text).toContain('Old memo')

    // The per-user breakdown is the administrator-only part: names WITH their counts.
    const users = wrapper.findAll('.access-user').map((node) => node.text().replace(/\s+/g, ' ').trim())
    expect(users).toContain('mario · 8')
    expect(users).toContain('admin · 4')
  })

  it('keeps the ranking order the server sent — most used first', async () => {
    accessApiMock.getAccessStats.mockResolvedValue({ data: STATS })
    const wrapper = mountView()
    await flushPromises()
    const text = wrapper.text()
    expect(text.indexOf('Quarterly report')).toBeLessThan(text.indexOf('Old memo'))
  })

  it('shows the empty state when nothing has been accessed yet', async () => {
    accessApiMock.getAccessStats.mockResolvedValue({
      data: { total_document_accesses: 0, total_file_accesses: 0, documents: [] },
    })
    const wrapper = mountView()
    await flushPromises()
    expect(wrapper.text()).toContain(en.ui.access.admin_empty)
    expect(wrapper.findAll('.access-user')).toHaveLength(0)
  })

  it('a failing access query degrades to a retryable error and leaves the rest of the dashboard alone', async () => {
    accessApiMock.getAccessStats.mockRejectedValue(new Error('403'))
    const wrapper = mountView()
    await flushPromises()
    // The access section reports its own failure...
    expect(wrapper.text()).not.toContain(en.ui.access.admin_empty)
    // ...while the app-stats half of the screen still rendered.
    expect(wrapper.text()).toContain(en.ui.stats.title)
  })
})
