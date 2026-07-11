import { describe, it, expect, beforeAll, vi } from 'vitest'
import { ref } from 'vue'
import { mount, flushPromises } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import PrimeVue from 'primevue/config'
import en from '../locale/en.json'
import type { AppInfo, FooterLink } from '../api/app'

// Unit under test: AppLayout renders the configurable footer/imprint links (issue #43)
// in the desktop panel-footer AND the mobile drawer footer, each anchor carrying
// target="_blank" rel="noopener noreferrer" — and renders NOTHING when the config is
// empty/absent (today's chrome). useAppInfo is the dependency under mock so we drive
// footer_links directly without the network.

const appInfo = vi.hoisted(() => ({ value: undefined as AppInfo | undefined }))
vi.mock('../composables/useAppInfo', () => ({
  useAppInfo: () => ({ data: ref(appInfo.value) }),
}))

// Authenticated, admin — AppLayout only renders its shell when !auth.isAnonymous.
vi.mock('../stores/auth', () => ({
  useAuthStore: () => ({ isAnonymous: false, isAdmin: true }),
}))
vi.mock('../stores/tagFilter', () => ({
  useTagFilterStore: () => ({
    tagMode: 'and',
    activeTreeNodes: [],
    activeExpandedKeys: {},
    selectedTagIds: [],
    excludedTagIds: [],
    tagCounts: {},
    viewMode: 'list',
    toggleTag: vi.fn(),
    navigateToDocuments: vi.fn(),
  }),
}))
vi.mock('../composables/useResizablePanel', () => ({
  useResizablePanel: () => ({
    width: ref(280),
    startDrag: vi.fn(),
    onKeydown: vi.fn(),
    reset: vi.fn(),
  }),
}))
vi.mock('vue-router', () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
  useRoute: () => ({ path: '/document', name: 'documents' }),
  RouterLink: { name: 'RouterLink', template: '<a><slot /></a>', props: ['to'] },
}))

// Drives isMobile: false => desktop panel-footer renders; true => mobile drawer footer.
const mobile = { matches: false }
beforeAll(() => {
  Object.defineProperty(window, 'matchMedia', {
    configurable: true,
    value: (query: string) => ({
      matches: mobile.matches,
      media: query,
      onchange: null,
      addEventListener: () => {},
      removeEventListener: () => {},
      addListener: () => {},
      removeListener: () => {},
      dispatchEvent: () => false,
    }),
  })
})

import AppLayout from './AppLayout.vue'

function mountLayout() {
  const i18n = createI18n({ legacy: false, locale: 'en', fallbackLocale: 'en', messages: { en } })
  return mount(AppLayout, {
    global: {
      plugins: [i18n, PrimeVue],
      stubs: {
        AppHeader: true,
        DefaultPasswordBanner: true,
        AdminNavPanel: true,
        TagTreePanel: true,
        // Render the Drawer's default slot so the mobile footer is present in the DOM.
        Drawer: { template: '<div class="drawer-stub"><slot /></div>' },
        Button: true,
        'router-view': true,
      },
    },
  })
}

const LINKS: FooterLink[] = [
  { label: 'Imprint', url: 'https://example.com/imprint' },
  { label: 'Privacy', url: 'https://example.com/privacy' },
]

function assertSafeAnchors(anchors: ReturnType<ReturnType<typeof mountLayout>['findAll']>) {
  expect(anchors.length).toBe(2)
  expect(anchors[0].text()).toBe('Imprint')
  expect(anchors[0].attributes('href')).toBe('https://example.com/imprint')
  expect(anchors[1].text()).toBe('Privacy')
  expect(anchors[1].attributes('href')).toBe('https://example.com/privacy')
  for (const a of anchors) {
    expect(a.attributes('target')).toBe('_blank')
    expect(a.attributes('rel')).toBe('noopener noreferrer')
  }
}

describe('AppLayout — configurable footer links', () => {
  it('renders the links with safe rel/target in the desktop panel-footer', async () => {
    mobile.matches = false
    appInfo.value = { current_version: '3.4.0', footer_links: LINKS }
    const wrapper = mountLayout()
    await flushPromises()
    assertSafeAnchors(wrapper.findAll('a.footer-external-link'))
  })

  it('renders the links with safe rel/target in the mobile drawer footer', async () => {
    mobile.matches = true
    appInfo.value = { current_version: '3.4.0', footer_links: LINKS }
    const wrapper = mountLayout()
    await flushPromises()
    assertSafeAnchors(wrapper.findAll('a.footer-external-link'))
  })

  it('renders NOTHING when footer_links is empty (desktop)', async () => {
    mobile.matches = false
    appInfo.value = { current_version: '3.4.0', footer_links: [] }
    const wrapper = mountLayout()
    await flushPromises()
    expect(wrapper.findAll('a.footer-external-link').length).toBe(0)
    expect(wrapper.find('.footer-external-links').exists()).toBe(false)
  })

  it('renders NOTHING when footer_links is absent (mobile)', async () => {
    mobile.matches = true
    appInfo.value = { current_version: '3.4.0' }
    const wrapper = mountLayout()
    await flushPromises()
    expect(wrapper.findAll('a.footer-external-link').length).toBe(0)
    expect(wrapper.find('.footer-external-links').exists()).toBe(false)
  })
})
