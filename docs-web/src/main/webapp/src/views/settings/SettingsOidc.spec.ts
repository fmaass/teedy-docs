import { describe, it, expect, beforeAll, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import PrimeVue from 'primevue/config'
import ToastService from 'primevue/toastservice'
import en from '../../locale/en.json'
import type { OidcConfig } from '../../api/oidc'

// Mock the oidc api module: the render + save flow under test must reflect the GET config and
// send the form as OidcConfig without ever pre-filling the write-only client secret.
const apiMock = vi.hoisted(() => ({
  getOidcConfig: vi.fn(),
  saveOidcConfig: vi.fn(),
}))
vi.mock('../../api/oidc', async (orig) => ({ ...(await orig()), ...apiMock }))

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

import SettingsOidc from './SettingsOidc.vue'

const ENABLED_CONFIG: OidcConfig = {
  enabled: true,
  issuer: 'https://iss.example',
  client_id: 'the-client',
  client_secret_set: true,
  redirect_uri: 'https://app.example/api/oidc/callback',
  scope: 'openid profile email',
  authorization_endpoint: '',
  token_endpoint: '',
  jwks_uri: '',
  userinfo_endpoint: '',
  username_claim: 'preferred_username',
  email_claim: 'email',
  sources: {
    enabled: 'db', issuer: 'property', client_id: 'db', client_secret: 'db',
    redirect_uri: 'db', scope: 'default', authorization_endpoint: 'default',
    token_endpoint: 'default', jwks_uri: 'default', userinfo_endpoint: 'default',
    username_claim: 'default', email_claim: 'default',
  },
}

function mountView() {
  const i18n = createI18n({ legacy: false, locale: 'en', fallbackLocale: 'en', messages: { en } })
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return mount(SettingsOidc, {
    global: { plugins: [i18n, PrimeVue, ToastService, [VueQueryPlugin, { queryClient }]] },
  })
}

describe('SettingsOidc', () => {
  beforeEach(() => {
    apiMock.getOidcConfig.mockReset().mockResolvedValue(ENABLED_CONFIG)
    apiMock.saveOidcConfig.mockReset().mockResolvedValue({ data: {} })
  })

  it('renders the populated non-secret fields from the GET', async () => {
    const wrapper = mountView()
    await flushPromises()
    const issuer = wrapper.find('#oidc-issuer').element as HTMLInputElement
    const clientId = wrapper.find('#oidc-client-id').element as HTMLInputElement
    expect(issuer.value).toBe('https://iss.example')
    expect(clientId.value).toBe('the-client')
    expect(wrapper.text()).toContain(en.ui.oidc.title)
  })

  it('never pre-fills the write-only client secret; shows the keep affordance when set', async () => {
    const wrapper = mountView()
    await flushPromises()
    const secret = wrapper.find('#oidc-client-secret').element as HTMLInputElement
    expect(secret.value).toBe('')
    expect(wrapper.text()).toContain(en.ui.oidc.client_secret_keep)
  })

  it('hints when a field is currently from a JVM property', async () => {
    const wrapper = mountView()
    await flushPromises()
    // issuer source is 'property' in the fixture -> the property hint renders.
    expect(wrapper.text()).toContain(en.ui.oidc.from_property)
  })

  it('saves the form as an OidcConfig without a pre-filled secret', async () => {
    const wrapper = mountView()
    await flushPromises()
    ;(wrapper.vm as unknown as { onSave: () => void }).onSave()
    await flushPromises()
    expect(apiMock.saveOidcConfig).toHaveBeenCalledTimes(1)
    const sent = apiMock.saveOidcConfig.mock.calls[0][0] as OidcConfig
    expect(sent.enabled).toBe(true)
    expect(sent.issuer).toBe('https://iss.example')
    expect(sent.client_secret).toBe('')
  })
})
