import { describe, it, expect, beforeAll, beforeEach, vi } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { setActivePinia, createPinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import PrimeVue from 'primevue/config'
import ToastService from 'primevue/toastservice'
import en from '../../locale/en.json'
import type { UserInfo } from '../../api/user'

// Mock the user api (both this view AND the auth store import from it, so one mock covers both).
const apiUser = vi.hoisted(() => ({
  enableTotp: vi.fn(),
  activateTotp: vi.fn(),
  disableTotp: vi.fn(),
  listSessions: vi.fn(),
  deleteOtherSessions: vi.fn(),
  getCurrentUser: vi.fn(),
  login: vi.fn(),
  logout: vi.fn(),
}))
vi.mock('../../api/user', () => apiUser)

// Peripheral dependencies the view pulls in but that are not under test here.
vi.mock('../../i18n', () => ({ setLocale: vi.fn() }))
vi.mock('../../api/document', () => ({ exportAccountBlob: vi.fn() }))
vi.mock('../../api/client', () => ({
  default: { post: vi.fn().mockResolvedValue({ data: {} }), get: vi.fn().mockResolvedValue({ data: {} }) },
}))
vi.mock('../../composables/useThemeSwitch', () => ({
  useThemeSwitch: () => ({ switchTheme: vi.fn() }),
  themeNames: ['light', 'dark'],
  getStoredTheme: () => 'light',
}))
vi.mock('../../composables/useConfirmDanger', () => ({ useConfirmDanger: () => ({ confirmDanger: vi.fn() }) }))

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

import SettingsAccount from './SettingsAccount.vue'
import { useAuthStore } from '../../stores/auth'

function baseUser(overrides: Partial<UserInfo> = {}): UserInfo {
  return {
    anonymous: false, username: 'alice', email: 'alice@x.com',
    storage_current: 0, storage_quota: 1000, groups: [],
    is_default_password: false, base_functions: [], onboarding: false,
    totp_enabled: false, external_origin: false, ...overrides,
  }
}

async function mountView(user: UserInfo): Promise<VueWrapper> {
  setActivePinia(createPinia())
  const auth = useAuthStore()
  auth.user = user
  const i18n = createI18n({ legacy: false, locale: 'en', fallbackLocale: 'en', messages: { en } })
  const wrapper = mount(SettingsAccount, { global: { plugins: [i18n, PrimeVue, ToastService] } })
  await flushPromises()
  return wrapper
}

describe('SettingsAccount — two-factor authentication', () => {
  beforeEach(() => {
    Object.values(apiUser).forEach((fn) => fn.mockReset())
    apiUser.listSessions.mockResolvedValue({ data: { sessions: [] } })
  })

  it('hides the two-factor section for external-origin (OIDC/LDAP) accounts', async () => {
    const wrapper = await mountView(baseUser({ external_origin: true }))
    expect(wrapper.find('[data-test="two-factor-card"]').exists()).toBe(false)
  })

  it('shows the disabled state with an enable button for an internal account', async () => {
    const wrapper = await mountView(baseUser())
    expect(wrapper.find('[data-test="two-factor-card"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="totp-enable"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="totp-qr"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="totp-disable"]').exists()).toBe(false)
  })

  it('enable -> pending renders the QR (encoding the otpauth URI) and the manual secret', async () => {
    apiUser.enableTotp.mockResolvedValue({ data: { secret: 'JBSWY3DPEHPK3PXP', issuer: 'Teedy', account: 'alice' } })
    const wrapper = await mountView(baseUser())

    await wrapper.get('[data-test="totp-enable"]').trigger('click')
    await flushPromises()

    expect(apiUser.enableTotp).toHaveBeenCalledOnce()
    const qr = wrapper.get('[data-test="totp-qr"]')
    expect(qr.attributes('data-otpauth-uri')).toBe(
      'otpauth://totp/Teedy:alice?secret=JBSWY3DPEHPK3PXP&issuer=Teedy&algorithm=SHA1&digits=6&period=30',
    )
    expect(wrapper.get('[data-test="totp-secret"]').text()).toBe('JBSWY3DPEHPK3PXP')
    expect(wrapper.find('[data-test="totp-code"]').exists()).toBe(true)
  })

  it('activate posts the code and refreshes the user into the active state', async () => {
    apiUser.enableTotp.mockResolvedValue({ data: { secret: 'JBSWY3DPEHPK3PXP', issuer: 'Teedy', account: 'alice' } })
    apiUser.activateTotp.mockResolvedValue({ data: { status: 'ok' } })
    // After activation the refreshed current-user reports TOTP enabled.
    apiUser.getCurrentUser.mockResolvedValue({ data: baseUser({ totp_enabled: true }) })

    const wrapper = await mountView(baseUser())
    await wrapper.get('[data-test="totp-enable"]').trigger('click')
    await flushPromises()

    await wrapper.get('[data-test="totp-code"]').setValue('123456')
    await wrapper.get('[data-test="totp-activate"]').trigger('click')
    await flushPromises()

    expect(apiUser.activateTotp).toHaveBeenCalledWith('123456')
    expect(apiUser.getCurrentUser).toHaveBeenCalled()
    // The section re-renders into its active state (disable control present, QR gone).
    expect(wrapper.find('[data-test="totp-disable"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="totp-qr"]').exists()).toBe(false)
  })

  it('does not post an activation when the code field is empty', async () => {
    apiUser.enableTotp.mockResolvedValue({ data: { secret: 'JBSWY3DPEHPK3PXP', issuer: 'Teedy', account: 'alice' } })
    const wrapper = await mountView(baseUser())
    await wrapper.get('[data-test="totp-enable"]').trigger('click')
    await flushPromises()

    await wrapper.get('[data-test="totp-activate"]').trigger('click')
    await flushPromises()
    expect(apiUser.activateTotp).not.toHaveBeenCalled()
  })

  it('active state disables with a password-confirmed request', async () => {
    apiUser.disableTotp.mockResolvedValue({ data: { status: 'ok' } })
    apiUser.getCurrentUser.mockResolvedValue({ data: baseUser({ totp_enabled: false }) })
    const wrapper = await mountView(baseUser({ totp_enabled: true }))

    expect(wrapper.find('[data-test="totp-disable"]').exists()).toBe(true)
    await wrapper.get('#totp-disable-pass').setValue('Test1234')
    await wrapper.get('[data-test="totp-disable"]').trigger('click')
    await flushPromises()

    expect(apiUser.disableTotp).toHaveBeenCalledWith('Test1234')
  })
})
