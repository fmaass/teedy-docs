import { describe, it, expect, beforeAll, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import PrimeVue from 'primevue/config'
import en from '../../locale/en.json'
import type { AppStats } from '../../api/app'

// Mock the app api: the render + window-switch flow under test must reflect the mocked payload
// and refetch when the window changes, without ever hitting a network or chart.js.
const apiMock = vi.hoisted(() => ({
  getAppStats: vi.fn(),
}))
vi.mock('../../api/app', async (orig) => ({ ...(await orig()), ...apiMock }))

// PrimeVue's Chart wrapper dynamically imports chart.js/auto (a canvas library that does not
// run under jsdom). Stub it with a marker component that records the received type so the test
// asserts a chart was rendered for each series WITHOUT booting a real canvas.
vi.mock('primevue/chart', () => ({
  default: {
    name: 'Chart',
    props: ['type', 'data', 'options'],
    template: '<div class="chart-stub" :data-type="type" :data-points="data?.datasets?.[0]?.data?.length ?? 0" />',
  },
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

function makeStats(window: number): AppStats {
  const days = Array.from({ length: window }, (_, i) => ({
    date: `2026-07-${String(i + 1).padStart(2, '0')}`,
    count: i,
  }))
  return {
    window,
    totals: { documents: 42, files: 108, users: 7, tags: 15, favorites: 3 },
    storage: {
      global: 1024 * 1024 * 500,
      per_user: [
        { username: 'alice', storage_current: 1024 * 1024 * 300, storage_quota: 1024 * 1024 * 1024 },
        { username: 'bob', storage_current: 1024 * 1024 * 100, storage_quota: 0 },
      ],
    },
    series: { documents_created: days, activity: days },
  }
}

function mountView() {
  const i18n = createI18n({ legacy: false, locale: 'en', fallbackLocale: 'en', messages: { en } })
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return mount(SettingsStats, {
    global: { plugins: [i18n, PrimeVue, [VueQueryPlugin, { queryClient }]] },
  })
}

describe('SettingsStats', () => {
  beforeEach(() => {
    apiMock.getAppStats.mockReset()
  })

  it('renders totals, both charts, and the per-user storage table from the payload', async () => {
    apiMock.getAppStats.mockResolvedValue(makeStats(7))
    const wrapper = mountView()
    await flushPromises()

    // Every total value is rendered.
    const text = wrapper.text()
    expect(text).toContain('42') // documents
    expect(text).toContain('108') // files
    expect(text).toContain('15') // tags
    expect(text).toContain('alice')
    expect(text).toContain('bob')

    // Two charts (line + bar), each fed the 7-point series.
    const charts = wrapper.findAll('.chart-stub')
    expect(charts).toHaveLength(2)
    expect(charts[0].attributes('data-type')).toBe('line')
    expect(charts[1].attributes('data-type')).toBe('bar')
    expect(charts[0].attributes('data-points')).toBe('7')
  })

  it('requests window=7 on mount', async () => {
    apiMock.getAppStats.mockResolvedValue(makeStats(7))
    mountView()
    await flushPromises()
    expect(apiMock.getAppStats).toHaveBeenCalledWith(7)
  })

  it('refetches with the new window when the selector changes', async () => {
    apiMock.getAppStats.mockImplementation((w: number) => Promise.resolve(makeStats(w)))
    const wrapper = mountView()
    await flushPromises()
    expect(apiMock.getAppStats).toHaveBeenLastCalledWith(7)

    // Click the "30 days" option in the real SelectButton (its options render as buttons
    // labelled from ui.stats.window_days). This is the user action, not a direct ref poke.
    const thirtyLabel = en.ui.stats.window_days.replace('{days}', '30')
    const option = wrapper
      .findAll('[role="button"], button')
      .find((node) => node.text().trim() === thirtyLabel)
    expect(option, `the "${thirtyLabel}" window option must be present`).toBeTruthy()
    await option!.trigger('click')
    await flushPromises()

    expect(apiMock.getAppStats).toHaveBeenLastCalledWith(30)
    // The new window's series length (30) reaches the charts.
    expect(wrapper.findAll('.chart-stub')[0].attributes('data-points')).toBe('30')
  })

  it('shows an error state (with retry) when the query fails and there is no data', async () => {
    apiMock.getAppStats.mockRejectedValue(new Error('boom'))
    const wrapper = mountView()
    await flushPromises()
    // ErrorState renders its retry button; no totals grid.
    expect(wrapper.find('.totals-grid').exists()).toBe(false)
    expect(wrapper.text()).toContain(en.ui.retry)
  })
})
