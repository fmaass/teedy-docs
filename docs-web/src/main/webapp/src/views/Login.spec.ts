import { describe, it, expect, beforeAll, beforeEach, vi } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { mount, flushPromises } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import PrimeVue from 'primevue/config'
import ToastService from 'primevue/toastservice'
import en from '../locale/en.json'

// The login action is the dependency under mock; the unit under test is the
// Login view's challenge -> reveal-code-field -> resubmit-with-code flow.
const authLogin = vi.hoisted(() => vi.fn())
vi.mock('../stores/auth', () => ({
  useAuthStore: () => ({ login: authLogin }),
}))

vi.mock('../api/user', () => ({ requestPasswordReset: vi.fn() }))
// getAppInfo resolves with no OIDC/guest so onMounted takes the plain local path.
// appInfoResult lets a test inject footer_links (or other fields) into that resolve.
const appInfoResult = vi.hoisted(() => ({ value: {} as Record<string, unknown> }))
vi.mock('../api/app', () => ({ getAppInfo: vi.fn(() => Promise.resolve(appInfoResult.value)) }))

const routerPush = vi.hoisted(() => vi.fn())
vi.mock('vue-router', () => ({
  useRouter: () => ({ push: routerPush, replace: vi.fn() }),
  useRoute: () => ({ query: { local: '1' } }),
}))

// PrimeVue overlays probe window.matchMedia, absent under jsdom.
beforeAll(() => {
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

import Login from './Login.vue'

function validationCodeRequiredError() {
  return { response: { status: 400, data: { type: 'ValidationCodeRequired', message: 'code required' } } }
}

function forbiddenError() {
  return { response: { status: 403, data: { type: 'ForbiddenError', message: 'denied' } } }
}

function mountView() {
  const i18n = createI18n({ legacy: false, locale: 'en', fallbackLocale: 'en', messages: { en } })
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return mount(Login, {
    global: {
      plugins: [i18n, PrimeVue, ToastService, [VueQueryPlugin, { queryClient }]],
    },
  })
}

interface LoginVm {
  username: string
  password: string
  remember: boolean
  validationCode: string
  totpRequired: boolean
  error: string
  handleLogin: () => Promise<void>
}

describe('Login — TOTP challenge/reveal/resubmit flow', () => {
  beforeEach(() => {
    authLogin.mockReset()
    routerPush.mockReset()
    appInfoResult.value = {}
  })

  it('hides the code field for a normal login and never sends a code', async () => {
    authLogin.mockResolvedValue(undefined)
    const wrapper = mountView()
    await flushPromises()

    const vm = wrapper.vm as unknown as LoginVm
    vm.username = 'alice'
    vm.password = 'secret'
    vm.remember = true

    // Field is hidden before any challenge.
    expect(wrapper.find('#login-code').exists()).toBe(false)

    await vm.handleLogin()
    await flushPromises()

    // A successful non-TOTP login sends undefined for the code and routes onward.
    expect(authLogin).toHaveBeenCalledWith('alice', 'secret', true, undefined)
    expect(routerPush).toHaveBeenCalledWith({ name: 'documents' })
    expect(vm.totpRequired).toBe(false)
  })

  it('reveals the code field on a ValidationCodeRequired rejection and re-submits with the code', async () => {
    const wrapper = mountView()
    await flushPromises()
    const vm = wrapper.vm as unknown as LoginVm
    vm.username = 'alice'
    vm.password = 'secret'
    vm.remember = false

    // First attempt: backend challenges the TOTP-enabled account.
    authLogin.mockRejectedValueOnce(validationCodeRequiredError())
    await vm.handleLogin()
    await flushPromises()

    // Code field is now revealed; login was NOT completed.
    expect(vm.totpRequired).toBe(true)
    expect(wrapper.find('#login-code').exists()).toBe(true)
    expect(routerPush).not.toHaveBeenCalled()
    // The first call carried no code.
    expect(authLogin).toHaveBeenNthCalledWith(1, 'alice', 'secret', false, undefined)

    // User types the code and re-submits: it succeeds.
    authLogin.mockResolvedValueOnce(undefined)
    vm.validationCode = '123456'
    await vm.handleLogin()
    await flushPromises()

    // Second call forwards the entered code.
    expect(authLogin).toHaveBeenNthCalledWith(2, 'alice', 'secret', false, '123456')
    expect(routerPush).toHaveBeenCalledWith({ name: 'documents' })
  })

  it('keeps the code field visible and clears the entry when the code is wrong (403)', async () => {
    const wrapper = mountView()
    await flushPromises()
    const vm = wrapper.vm as unknown as LoginVm
    vm.username = 'alice'
    vm.password = 'secret'
    vm.remember = false

    authLogin.mockRejectedValueOnce(validationCodeRequiredError())
    await vm.handleLogin()
    await flushPromises()
    expect(vm.totpRequired).toBe(true)

    // Wrong code -> backend 403. Field stays visible for a retry, entry cleared.
    authLogin.mockRejectedValueOnce(forbiddenError())
    vm.validationCode = '000000'
    await vm.handleLogin()
    await flushPromises()

    expect(vm.totpRequired).toBe(true)
    expect(wrapper.find('#login-code').exists()).toBe(true)
    expect(vm.validationCode).toBe('')
    // Wrong-code message is the dedicated invalid-code string, not the "required" prompt.
    expect(vm.error).toBe(en.login.validation_code_invalid)
    expect(routerPush).not.toHaveBeenCalled()
  })

  // Advisory (a): editing credentials after a challenge must retract the code prompt
  // so a code entered for one account can't be submitted against a different one.
  it('retracts the code prompt when the username changes after a challenge', async () => {
    const wrapper = mountView()
    await flushPromises()
    const vm = wrapper.vm as unknown as LoginVm
    vm.username = 'alice'
    vm.password = 'secret'

    authLogin.mockRejectedValueOnce(validationCodeRequiredError())
    await vm.handleLogin()
    await flushPromises()
    expect(vm.totpRequired).toBe(true)
    vm.validationCode = '123456'

    // Switching to a different account hides the field and drops the entered code.
    vm.username = 'bob'
    await flushPromises()
    expect(vm.totpRequired).toBe(false)
    expect(vm.validationCode).toBe('')
    expect(wrapper.find('#login-code').exists()).toBe(false)

    // A subsequent login for bob therefore sends NO code (not alice's code).
    authLogin.mockResolvedValueOnce(undefined)
    await vm.handleLogin()
    await flushPromises()
    expect(authLogin).toHaveBeenLastCalledWith('bob', 'secret', false, undefined)
  })

  // Advisory (b): a non-403 failure after a challenge (network error / 429 rate-limit)
  // must NOT be mislabeled as a wrong code — it uses normal error handling and the
  // field stays as-is for the user to see the real error.
  it('does not mislabel a rate-limit (429) after a challenge as a wrong code', async () => {
    const wrapper = mountView()
    await flushPromises()
    const vm = wrapper.vm as unknown as LoginVm
    vm.username = 'alice'
    vm.password = 'secret'

    authLogin.mockRejectedValueOnce(validationCodeRequiredError())
    await vm.handleLogin()
    await flushPromises()
    expect(vm.totpRequired).toBe(true)

    // 429 rate-limit: not a wrong-code, so it surfaces the backend message and does
    // not clear the entered code.
    authLogin.mockRejectedValueOnce({
      response: { status: 429, data: { type: 'RateLimited', message: 'Too many login attempts. Try again later.' } },
    })
    vm.validationCode = '123456'
    await vm.handleLogin()
    await flushPromises()

    expect(vm.error).toBe('Too many login attempts. Try again later.')
    expect(vm.error).not.toBe(en.login.validation_code_invalid)
    // The code was NOT cleared (this wasn't a wrong-code event).
    expect(vm.validationCode).toBe('123456')
    expect(routerPush).not.toHaveBeenCalled()
  })
})

// Configurable footer/imprint links (issue #43) must be reachable BEFORE login on
// the logged-out login screen (GET /app is anonymous). Empty/absent config renders
// nothing.
describe('Login — configurable footer links', () => {
  beforeEach(() => {
    authLogin.mockReset()
    routerPush.mockReset()
    appInfoResult.value = {}
  })

  it('renders the configured links with safe rel/target beneath the login card', async () => {
    appInfoResult.value = {
      footer_links: [
        { label: 'Imprint', url: 'https://example.com/imprint' },
        { label: 'Privacy', url: 'https://example.com/privacy' },
      ],
    }
    const wrapper = mountView()
    await flushPromises()

    const anchors = wrapper.findAll('.teedy-login-footer a')
    expect(anchors.length).toBe(2)
    expect(anchors[0].text()).toBe('Imprint')
    expect(anchors[0].attributes('href')).toBe('https://example.com/imprint')
    expect(anchors[0].attributes('target')).toBe('_blank')
    expect(anchors[0].attributes('rel')).toBe('noopener noreferrer')
    expect(anchors[1].text()).toBe('Privacy')
    expect(anchors[1].attributes('rel')).toBe('noopener noreferrer')

    // The footer is a direct child of the centered .teedy-login container appearing
    // AFTER the card (a stacked sibling, not nested inside it). Guards the #48 DOM
    // structure the column layout relies on.
    const container = wrapper.find('.teedy-login')
    const children = Array.from(container.element.children)
    const cardIndex = children.findIndex((el) => el.classList.contains('teedy-login-card'))
    const footerIndex = children.findIndex((el) => el.classList.contains('teedy-login-footer'))
    expect(cardIndex).toBeGreaterThanOrEqual(0)
    expect(footerIndex).toBeGreaterThan(cardIndex)
  })

  it('renders NOTHING when footer_links is absent', async () => {
    appInfoResult.value = {}
    const wrapper = mountView()
    await flushPromises()
    expect(wrapper.find('.teedy-login-footer').exists()).toBe(false)
    expect(wrapper.findAll('.teedy-login-footer a').length).toBe(0)
  })

  it('renders NOTHING when footer_links is an empty array', async () => {
    appInfoResult.value = { footer_links: [] }
    const wrapper = mountView()
    await flushPromises()
    expect(wrapper.find('.teedy-login-footer').exists()).toBe(false)
  })

  // #48 regression guard: the container MUST lay its children out as a centered
  // column. The bug was the default row direction, which floated the footer beside
  // the card instead of stacking it below. jsdom applies no external CSS, so assert
  // the rule at its source in the global theme.
  it('.teedy-login lays out as a centered column (guards the row-flex regression)', () => {
    // Vitest runs from the webapp project root (process.cwd()).
    const css = readFileSync(resolve(process.cwd(), 'src/assets/teedy-theme.css'), 'utf8')
    const rule = css.match(/\.teedy-login\s*\{([^}]*)\}/)
    expect(rule).not.toBeNull()
    const body = rule![1]
    expect(body).toMatch(/flex-direction:\s*column/)
    expect(body).toMatch(/align-items:\s*center/)
  })
})
