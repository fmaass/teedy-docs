import { describe, it, expect, beforeAll, vi } from 'vitest'
import { computed, ref, type Ref } from 'vue'
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

// The brand row reads the shared theme query. Mocked here for the same reason useAppInfo is: the
// component is the unit under test, the query is a dependency — and mounting it unmocked would
// need a real VueQueryPlugin client.
const brand = vi.hoisted(() => ({
  name: 'Teedy',
  logoUrl: null as string | null,
}))
// The live ref the mocked useBrand hands the component for the logo URL, re-seeded from
// `brand.logoUrl` on every mount and exposed here so a test can change the brand URL AFTER
// mount — the reactive path the AppLayout.vue:44 logo-retry watcher depends on (#255). The
// pre-mount tests below set `brand.logoUrl` before mountLayout(), so seeding the ref at call
// time keeps their behaviour identical.
const brandRefs = vi.hoisted(() => ({ logoUrl: null as unknown as Ref<string | null> }))
vi.mock('../composables/useThemeBranding', () => ({
  useBrand: () => {
    const logoUrl = ref(brand.logoUrl)
    brandRefs.logoUrl = logoUrl
    return {
      brandName: computed(() => brand.name),
      brandLogoUrl: logoUrl,
    }
  },
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
        // Render the Drawer's header AND default slots so both the mobile brand (header) and the
        // mobile footer (body) are present in the DOM.
        Drawer: { template: '<div class="drawer-stub"><slot name="header" /><slot /></div>' },
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

// --- The brand row (#57 display half) ----------------------------------------
// The panel/drawer brand was a hardcoded "teedy" literal and the uploaded logo had no consumer,
// so a renamed instance still showed stock Teedy in the most visible place on screen. These pin
// the fallback rules at the component level; useBrand's own derivation is tested in
// useThemeBranding.spec.ts.
describe('AppLayout — the instance brand', () => {
  it('renders the configured name in the desktop panel', async () => {
    mobile.matches = false
    brand.name = 'Contoso Archive'
    brand.logoUrl = null
    appInfo.value = { current_version: '3.8.2', footer_links: [] }
    const wrapper = mountLayout()
    await flushPromises()
    expect(wrapper.find('.panel-brand-name').text()).toBe('Contoso Archive')
    // The name is the brand link's own text, so it is that link's accessible name. (That it
    // resolves to a real anchor pointing at /document is asserted end-to-end, where the router is
    // real — here `router-link` is a stub.)
    expect(wrapper.find('.panel-brand').text()).toContain('Contoso Archive')
  })

  it('renders the configured name in the mobile drawer', async () => {
    mobile.matches = true
    brand.name = 'Contoso Archive'
    brand.logoUrl = null
    appInfo.value = { current_version: '3.8.2', footer_links: [] }
    const wrapper = mountLayout()
    await flushPromises()
    expect(wrapper.find('.panel-brand-name').text()).toBe('Contoso Archive')
  })

  it('renders NO image for an instance that never uploaded a logo', async () => {
    mobile.matches = false
    brand.name = 'Teedy'
    brand.logoUrl = null
    appInfo.value = { current_version: '3.8.2', footer_links: [] }
    const wrapper = mountLayout()
    await flushPromises()
    // The bundled default is a Teedy asset; an unbranded instance must not grow a logo it never
    // chose, so the brand stays text-only.
    expect(wrapper.find('.panel-brand-logo').exists()).toBe(false)
    expect(wrapper.find('.panel-brand-name').text()).toBe('Teedy')
  })

  it('renders the uploaded logo beside the name, at its cache-busted URL', async () => {
    mobile.matches = false
    brand.name = 'Contoso Archive'
    brand.logoUrl = '/api/theme/image/logo?v=42'
    appInfo.value = { current_version: '3.8.2', footer_links: [] }
    const wrapper = mountLayout()
    await flushPromises()
    const logo = wrapper.find('img.panel-brand-logo')
    expect(logo.exists()).toBe(true)
    expect(logo.attributes('src')).toBe('/api/theme/image/logo?v=42')
    // Decorative: the name beside it is what names the link, so a screen reader hears the
    // instance name once rather than twice.
    expect(logo.attributes('alt')).toBe('')
    expect(wrapper.find('.panel-brand-name').text()).toBe('Contoso Archive')
  })

  it('falls back to the text brand when the uploaded logo fails to load', async () => {
    mobile.matches = false
    brand.name = 'Contoso Archive'
    brand.logoUrl = '/api/theme/image/logo?v=42'
    appInfo.value = { current_version: '3.8.2', footer_links: [] }
    const wrapper = mountLayout()
    await flushPromises()

    await wrapper.find('img.panel-brand-logo').trigger('error')
    // A broken-image glyph in the top-left corner is worse than no logo at all.
    expect(wrapper.find('.panel-brand-logo').exists()).toBe(false)
    expect(wrapper.find('.panel-brand-name').text()).toBe('Contoso Archive')
  })

  // Pins the logo-retry watcher at AppLayout.vue:44 (#255, advisory 1): after a logo load
  // failure degrades to the text brand, REPLACING the logo (a fresh upload changes the
  // cache-busted URL) must clear the broken flag so the new image gets a fresh chance to
  // render. This is the reactive-replacement path the fallback test above does not cover.
  // Removing `watch(brandLogoUrl, () => { logoBroken.value = false })` leaves the flag set,
  // the replacement never renders, and `logo.exists()` below is false — the assertion goes
  // red, which is what makes this a real test of the watcher rather than of the fallback.
  it('retries the logo when the brand URL changes after a prior load failure (AppLayout.vue:44 watcher)', async () => {
    mobile.matches = false
    brand.name = 'Contoso Archive'
    brand.logoUrl = '/api/theme/image/logo?v=42'
    appInfo.value = { current_version: '3.8.2', footer_links: [] }
    const wrapper = mountLayout()
    await flushPromises()

    // First logo fails to decode -> component falls back to the text brand.
    await wrapper.find('img.panel-brand-logo').trigger('error')
    expect(wrapper.find('.panel-brand-logo').exists()).toBe(false)

    // A replacement logo is uploaded: brandLogoUrl changes on the live instance.
    brandRefs.logoUrl.value = '/api/theme/image/logo?v=43'
    await flushPromises()

    const logo = wrapper.find('img.panel-brand-logo')
    expect(logo.exists()).toBe(true)
    expect(logo.attributes('src')).toBe('/api/theme/image/logo?v=43')
  })
})
