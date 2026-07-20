import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { setActivePinia, createPinia } from 'pinia'

// Unit under test: AppHeader's dark-mode toggle (#147). It always applies the class + writes
// localStorage, and — for an authenticated user only — additionally persists the preference
// server-side, form-encoded and best-effort (a failure is non-blocking).

// Mock the api client (the persist dependency, NOT the unit under test).
const apiPost = vi.hoisted(() => vi.fn())
vi.mock('../api/client', () => ({ default: { post: apiPost } }))

// Mock vue-router: the header pushes routes on logout, irrelevant to the toggle.
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }))

// Mock vue-i18n: return the key so button aria-labels are stable to target.
vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (k: string) => k }) }))

// Mock src/i18n so pulling in the auth store (which imports it) does not load the real i18n bundle.
vi.mock('../i18n', () => ({ setLocale: vi.fn() }))

import AppHeader from './AppHeader.vue'
import { useAuthStore } from '../stores/auth'

// Stub PrimeVue Button down to a plain button keyed by aria-label, forwarding @click.
const Button = {
  props: ['icon', 'text', 'rounded', 'size', 'ariaLabel'],
  emits: ['click'],
  template: `<button :aria-label="ariaLabel" @click="$emit('click')" />`,
}
const AboutDialog = { props: ['visible'], template: '<div />' }

function userInfo(overrides: Record<string, unknown> = {}) {
  return {
    anonymous: false,
    username: 'alice',
    email: '',
    storage_current: 0,
    storage_quota: 0,
    groups: [],
    is_default_password: false,
    base_functions: [],
    onboarding: false,
    ...overrides,
  }
}

function mountHeader() {
  return mount(AppHeader, {
    global: {
      stubs: { Button, AboutDialog },
      directives: { tooltip: {} },
    },
  })
}

function darkModeButton(wrapper: VueWrapper) {
  return wrapper.get('button[aria-label="ui.dark_mode"]')
}

describe('AppHeader dark-mode toggle (#147)', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    localStorage.clear()
    document.documentElement.classList.remove('dark-mode')
  })

  it('persists the toggle as a form-encoded dark_mode body (not JSON)', async () => {
    apiPost.mockResolvedValue({})
    const auth = useAuthStore()
    auth.user = userInfo() as never
    const wrapper = mountHeader()

    await darkModeButton(wrapper).trigger('click')
    await flushPromises()

    expect(apiPost).toHaveBeenCalledTimes(1)
    const [url, body] = apiPost.mock.calls[0]
    expect(url).toBe('/user')
    // Form-encoded: a URLSearchParams body, not a plain JSON object.
    expect(body).toBeInstanceOf(URLSearchParams)
    expect((body as URLSearchParams).toString()).toBe('dark_mode=true')
  })

  it('keeps the local choice and does not throw when the server persist fails', async () => {
    vi.spyOn(console, 'warn').mockImplementation(() => {})
    apiPost.mockRejectedValue(new Error('network down'))
    const auth = useAuthStore()
    auth.user = userInfo() as never
    const wrapper = mountHeader()

    await darkModeButton(wrapper).trigger('click')
    await flushPromises()

    // The failure is swallowed (non-blocking) and the local choice is preserved.
    expect(apiPost).toHaveBeenCalledTimes(1)
    expect(localStorage.getItem('teedy-dark-mode')).toBe('true')
    expect(document.documentElement.classList.contains('dark-mode')).toBe(true)
  })
})
