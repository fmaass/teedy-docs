import { describe, it, expect, beforeAll, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import { createRouter, createMemoryHistory } from 'vue-router'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import PrimeVue from 'primevue/config'
import ToastService from 'primevue/toastservice'
import ConfirmationService from 'primevue/confirmationservice'
import en from '../../locale/en.json'
import type { Tag } from '../../api/tag'

// #14: the tag-PARENT Select must support type-to-filter (critical at ~350 tags).

const TAGS: Tag[] = [
  { id: 'a', name: 'Alpha', color: '#111111', parent: null },
  { id: 'b', name: 'Bravo', color: '#222222', parent: null },
  { id: 'c', name: 'Charlie', color: '#333333', parent: null },
]

const tagApiMock = vi.hoisted(() => ({
  listTags: vi.fn(),
  updateTag: vi.fn(),
  deleteTag: vi.fn(),
}))
vi.mock('../../api/tag', () => tagApiMock)

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

import TagEdit from './TagEdit.vue'

const router = createRouter({
  history: createMemoryHistory(),
  routes: [
    { path: '/', name: 'home', component: { template: '<div/>' } },
    { path: '/tags', name: 'tags', component: { template: '<div/>' } },
  ],
})

async function mountEdit() {
  const i18n = createI18n({ legacy: false, locale: 'en', fallbackLocale: 'en', messages: { en } })
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  router.push('/')
  await router.isReady()
  const wrapper = mount(TagEdit, {
    props: { id: 'b' },
    global: {
      plugins: [i18n, router, PrimeVue, ToastService, ConfirmationService, [VueQueryPlugin, { queryClient }]],
    },
  })
  await flushPromises()
  return wrapper
}

describe('TagEdit — parent Select (#14 filter)', () => {
  beforeEach(() => {
    tagApiMock.listTags.mockReset().mockResolvedValue({ data: { tags: TAGS } })
  })

  it('enables type-to-filter on the parent Select', async () => {
    const wrapper = await mountEdit()
    const select = wrapper.findComponent({ name: 'Select' })
    expect(select.exists()).toBe(true)
    expect(select.props('filter')).toBe(true)
  })
})
