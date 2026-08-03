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

// #258: the login background is read from the shared PUBLIC theme query. themeResult lets a test
// choose whether an admin has actually uploaded a background (background_version > 0) or not (0 —
// the server then serves its BUNDLED default, which must never reach the page).
const themeResult = vi.hoisted(() => ({ value: {} as Record<string, unknown> }))
vi.mock('../api/theme', () => ({ getTheme: vi.fn(() => Promise.resolve(themeResult.value)) }))

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

// --- #258: the configured branding background -------------------------------------------------
// An admin could upload a background on the Branding screen, it previewed there, and NOTHING in
// the app ever rendered it. It is the login page background — which is also the riskiest place to
// put an arbitrary photo, so the two properties that matter are: it shows when configured, and
// the page is untouched when it is not.
describe('Login — configured branding background (#258)', () => {
  beforeEach(() => {
    authLogin.mockReset()
    routerPush.mockReset()
    appInfoResult.value = {}
    themeResult.value = {}
  })

  it('renders the uploaded background on the login page, cache-busted by its version', async () => {
    themeResult.value = { name: 'Acme', background_version: 1754212345678 }
    const wrapper = mountView()
    await flushPromises()

    const page = wrapper.find('.login-page')
    expect(page.exists()).toBe(true)
    expect(page.classes()).toContain('has-login-background')
    // The view supplies only the URL; the scrim that keeps the form legible is composed in CSS.
    expect(page.attributes('style')).toContain(
      'url("/api/theme/image/background?v=1754212345678")',
    )
  })

  it('leaves the page EXACTLY as before when no background has been uploaded', async () => {
    // background_version 0 means no uploaded file — GET /theme/image/background would still answer
    // with the BUNDLED default, so rendering on "the endpoint returns bytes" would give every
    // existing install a login background it never chose. Nothing may change on this path.
    themeResult.value = { name: 'Acme', background_version: 0 }
    const wrapper = mountView()
    await flushPromises()

    const page = wrapper.find('.login-page')
    expect(page.classes()).not.toContain('has-login-background')
    // No style attribute at all, not merely an empty one.
    expect(page.attributes('style')).toBeUndefined()
    expect(wrapper.html()).not.toContain('/api/theme/image/background')
  })

  it('leaves the FOOTER exactly as before when no background has been uploaded', async () => {
    // The footer plate that keeps the imprint links legible over a photo must never reach an
    // install with no background: the links there sit on the plain page surface and are styled
    // by the global theme alone. Nothing on the element may change on the default path.
    appInfoResult.value = {
      footer_links: [
        { label: 'Imprint', url: 'https://example.com/imprint' },
        { label: 'Privacy policy', url: 'https://example.com/privacy' },
      ],
    }
    themeResult.value = { name: 'Acme', background_version: 0 }
    const wrapper = mountView()
    await flushPromises()

    const footer = wrapper.find('.teedy-login-footer')
    expect(footer.exists()).toBe(true)
    expect(footer.findAll('a').length).toBe(2)
    // Exactly the one class it always had, and no inline style.
    expect(footer.classes()).toEqual(['teedy-login-footer'])
    expect(footer.attributes('style')).toBeUndefined()
    // The gating class the plate hangs off is absent, so the CSS cannot match.
    expect(wrapper.find('.login-page').classes()).not.toContain('has-login-background')
  })

  it('renders no background on a server too old to report background_version', async () => {
    themeResult.value = { name: 'Acme' }
    const wrapper = mountView()
    await flushPromises()

    const page = wrapper.find('.login-page')
    expect(page.classes()).not.toContain('has-login-background')
    expect(page.attributes('style')).toBeUndefined()
  })

  // Legibility is the hard requirement: an arbitrary uploaded photo must not make the form
  // unreadable. jsdom applies no component CSS, so assert the rules at their source — the same
  // technique the .teedy-login layout guard above uses.
  describe('legibility over an arbitrary photo', () => {
    const sfc = readFileSync(resolve(process.cwd(), 'src/views/Login.vue'), 'utf8')
    const style = sfc.slice(sfc.indexOf('<style'))

    function ruleBody(selector: string): string {
      const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
      const match = style.match(new RegExp(`${escaped}\\s*\\{([^}]*)\\}`))
      expect(match, `no rule for ${selector}`).not.toBeNull()
      return match![1]
    }

    it('lays a scrim OVER the image so the field behind un-carded text is predictable', () => {
      const body = ruleBody('.login-page.has-login-background')
      // Two layers, scrim first: CSS paints background-image layers front-to-back, so the
      // gradient rides on top of the photo. No overlay element, so no layout/stacking change.
      expect(body).toMatch(
        /background-image:\s*linear-gradient\(\s*var\(--login-scrim\),\s*var\(--login-scrim\)\s*\),\s*var\(--login-background-image\)/,
      )
      expect(body).toMatch(/background-size:\s*cover/)
      expect(body).toMatch(/background-position:\s*center/)
      expect(body).toMatch(/background-repeat:\s*no-repeat/)
      // A DARK scrim in BOTH themes: that is what makes a bright/busy image and a dark image
      // land on the same predictable field, instead of light-on-light for one of them.
      expect(body).toMatch(/--login-scrim:\s*rgba\(0,\s*0,\s*0,\s*0?\.\d+\)/)
    })

    it('deepens the scrim in dark mode', () => {
      const body = ruleBody('.dark-mode .login-page.has-login-background')
      expect(body).toMatch(/--login-scrim:\s*rgba\(0,\s*0,\s*0,\s*0?\.\d+\)/)
    })

    it('lifts the footer links — the only text NOT inside the opaque card — off the scrim', () => {
      const body = ruleBody('.login-page.has-login-background .teedy-login-footer a')
      expect(body).toMatch(/color:\s*#fff/i)
      expect(body).toMatch(/text-shadow:/)
    })

    // Measured on a real instance: white 12px links over the page scrim alone reach 5.70:1 on a
    // bright photograph but only 4.35:1 over a near-white image, with an analytic floor of 3.95:1
    // for a pure-white pixel — under the 4.5:1 AA bar. A bright sky or a white-backed product
    // shot gets there, so the links carry their OWN surface rather than relying on the image.
    it('gives the footer links their own surface, so a near-white image cannot sink them', () => {
      const body = ruleBody('.login-page.has-login-background .teedy-login-footer')
      // A dark, mostly-opaque plate: over the 0.5 page scrim this pins the worst case near
      // 9:1 whatever the image does, instead of tracking the photo's brightest pixel.
      const alpha = body.match(/background:\s*rgba\(0,\s*0,\s*0,\s*(0?\.\d+)\)/)
      expect(alpha, 'footer surface must be a black rgba() plate').not.toBeNull()
      expect(Number(alpha![1])).toBeGreaterThanOrEqual(0.4)
      expect(body).toMatch(/padding:/)
      expect(body).toMatch(/border-radius:/)
      // Shrink-to-fit so the plate hugs the links instead of drawing a full card-width bar.
      expect(body).toMatch(/width:\s*auto/)
    })

    // The release-safety property for this rule specifically: it must be impossible for the
    // footer plate to reach an install that never configured a background.
    it('gates EVERY footer background rule behind .has-login-background', () => {
      const rules = style.match(/[^{}]*\.teedy-login-footer[^{}]*\{[^}]*\}/g) ?? []
      const withBackground = rules.filter((r) => /\{[^}]*background:/.test(r))
      expect(withBackground.length).toBeGreaterThan(0)
      for (const rule of withBackground) {
        const selector = rule.slice(0, rule.indexOf('{'))
        expect(selector, `ungated footer background rule: ${selector.trim()}`).toContain(
          'has-login-background',
        )
      }
    })

    it('separates the card edge from a busy image', () => {
      const body = ruleBody('.login-page.has-login-background .teedy-login-card')
      expect(body).toMatch(/box-shadow:/)
    })

    it('never makes the card itself translucent — the opaque surface IS the legibility guarantee', () => {
      // The fields, labels, button and error message all sit inside .teedy-login-card, whose
      // background is the opaque --p-content-background token (teedy-theme.css). A rule that
      // introduced transparency there would put the photo directly behind the form.
      expect(style).not.toMatch(/\.teedy-login-card[^{]*\{[^}]*opacity\s*:/)
      expect(style).not.toMatch(/\.teedy-login-card[^{]*\{[^}]*background[^;]*(transparent|rgba)/)
    })
  })
})
