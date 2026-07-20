import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'

// Mock the API module (a dependency of the store, NOT the unit under test).
vi.mock('../api/user', () => ({
  getCurrentUser: vi.fn(),
  login: vi.fn(),
  logout: vi.fn(),
}))

// Mock the i18n module: the store calls setLocale to seed the UI language from the server-side
// preference. setLocale is a dependency, not the unit under test, so we assert the store's seeding
// RULE (when it calls setLocale) without exercising vue-i18n's lazy locale loading.
vi.mock('../i18n', () => ({
  setLocale: vi.fn(),
}))

import { getCurrentUser, login as apiLogin, logout as apiLogout } from '../api/user'
import { setLocale } from '../i18n'
import { useAuthStore } from './auth'

const mockGetCurrentUser = vi.mocked(getCurrentUser)
const mockApiLogin = vi.mocked(apiLogin)
const mockApiLogout = vi.mocked(apiLogout)
const mockSetLocale = vi.mocked(setLocale)

// Wrap a mock body in the minimal axios-response shape each API returns. We only care
// about `.data`; the rest of the AxiosResponse envelope is irrelevant to the store, so
// cast through `unknown` to the mock's own resolved type rather than reaching for `any`.
function userResp(data: unknown) {
  return { data } as unknown as Awaited<ReturnType<typeof getCurrentUser>>
}
function loginResp(data: unknown = {}) {
  return { data } as unknown as Awaited<ReturnType<typeof apiLogin>>
}
function logoutResp(data: unknown = {}) {
  return { data } as unknown as Awaited<ReturnType<typeof apiLogout>>
}

function userInfo(overrides: Record<string, unknown> = {}) {
  return {
    anonymous: false,
    username: 'alice',
    email: 'alice@example.com',
    storage_current: 0,
    storage_quota: 100,
    groups: [],
    is_default_password: false,
    base_functions: [],
    onboarding: false,
    ...overrides,
  }
}

describe('auth store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    localStorage.clear()
    // The dark-mode seed toggles a class on <html> (global mutable state); reset it between tests.
    document.documentElement.classList.remove('dark-mode')
  })

  describe('initial state and getters', () => {
    it('starts uninitialized with no user; isAnonymous is true, isAdmin false, username empty', () => {
      const store = useAuthStore()
      expect(store.user).toBeNull()
      expect(store.initialized).toBe(false)
      expect(store.isAnonymous).toBe(true)
      expect(store.isAdmin).toBe(false)
      expect(store.username).toBe('')
    })
  })

  describe('fetchCurrentUser', () => {
    it('stores the user and sets initialized on success', async () => {
      mockGetCurrentUser.mockResolvedValue(userResp(userInfo({ username: 'bob' })))
      const store = useAuthStore()

      await store.fetchCurrentUser()

      expect(store.user?.username).toBe('bob')
      expect(store.initialized).toBe(true)
      expect(store.username).toBe('bob')
    })

    it('clears the user but still sets initialized on failure', async () => {
      mockGetCurrentUser.mockRejectedValue(new Error('401'))
      const store = useAuthStore()

      await store.fetchCurrentUser()

      expect(store.user).toBeNull()
      expect(store.initialized).toBe(true)
      expect(store.isAnonymous).toBe(true)
    })
  })

  // #82: the store seeds the UI locale from the server-side preference, but ONLY when there is no
  // explicit on-device choice. An explicit local choice (localStorage 'teedy-locale') always wins.
  describe('fetchCurrentUser locale seeding', () => {
    it('applies the server locale when no local choice exists', async () => {
      mockGetCurrentUser.mockResolvedValue(userResp(userInfo({ locale: 'de' })))
      const store = useAuthStore()

      await store.fetchCurrentUser()

      expect(mockSetLocale).toHaveBeenCalledWith('de')
    })

    it('does NOT overwrite an explicit local choice', async () => {
      localStorage.setItem('teedy-locale', 'fr')
      mockGetCurrentUser.mockResolvedValue(userResp(userInfo({ locale: 'de' })))
      const store = useAuthStore()

      await store.fetchCurrentUser()

      expect(mockSetLocale).not.toHaveBeenCalled()
    })

    it('does nothing when the server carries no locale preference', async () => {
      mockGetCurrentUser.mockResolvedValue(userResp(userInfo()))
      const store = useAuthStore()

      await store.fetchCurrentUser()

      expect(mockSetLocale).not.toHaveBeenCalled()
    })

    // BLOCKING-1 regression: a failing locale lazy-load (setLocale rejects) must NOT clear the
    // already-authenticated user or flip the session to anonymous — an i18n failure is not an auth
    // failure. The user stays loaded and initialized stays true.
    it('keeps the authenticated user intact when setLocale rejects', async () => {
      mockGetCurrentUser.mockResolvedValue(userResp(userInfo({ username: 'erin', locale: 'de' })))
      mockSetLocale.mockRejectedValue(new Error('chunk load failed'))
      const store = useAuthStore()

      await store.fetchCurrentUser()

      expect(mockSetLocale).toHaveBeenCalledWith('de')
      expect(store.user?.username).toBe('erin') // still authenticated — NOT cleared
      expect(store.isAnonymous).toBe(false)
      expect(store.initialized).toBe(true)
    })
  })

  // #147: the store seeds dark mode from the server-side preference, but ONLY when there is no explicit
  // on-device choice, and applies the `.dark-mode` class LIVE (no reload). A local choice always wins.
  describe('fetchCurrentUser dark-mode seeding', () => {
    it('applies dark mode live on a fresh device when the server pref is true and no local choice exists', async () => {
      mockGetCurrentUser.mockResolvedValue(userResp(userInfo({ dark_mode: true })))
      const store = useAuthStore()

      await store.fetchCurrentUser()

      // Applied WITHOUT a reload — the class is toggled during response hydration.
      expect(document.documentElement.classList.contains('dark-mode')).toBe(true)
    })

    it('does NOT overwrite an explicit local choice (local light beats server dark)', async () => {
      localStorage.setItem('teedy-dark-mode', 'false')
      mockGetCurrentUser.mockResolvedValue(userResp(userInfo({ dark_mode: true })))
      const store = useAuthStore()

      await store.fetchCurrentUser()

      // The explicit local 'false' wins: the server's dark pref is not applied.
      expect(document.documentElement.classList.contains('dark-mode')).toBe(false)
    })

    it('applies an explicit server false live, distinct from an absent preference', async () => {
      // Simulate a device booted dark (e.g. main.ts read a prior class) with no local override.
      document.documentElement.classList.add('dark-mode')
      mockGetCurrentUser.mockResolvedValue(userResp(userInfo({ dark_mode: false })))
      const store = useAuthStore()

      await store.fetchCurrentUser()

      // A server-side false is a real preference and is applied live (the class is removed).
      expect(document.documentElement.classList.contains('dark-mode')).toBe(false)
    })

    it('does nothing when the server carries no dark-mode preference', async () => {
      document.documentElement.classList.add('dark-mode')
      mockGetCurrentUser.mockResolvedValue(userResp(userInfo()))
      const store = useAuthStore()

      await store.fetchCurrentUser()

      // No server pref (undefined) leaves whatever the device already had untouched.
      expect(document.documentElement.classList.contains('dark-mode')).toBe(true)
    })
  })

  describe('isAnonymous getter', () => {
    it('is true when the fetched user is flagged anonymous', async () => {
      mockGetCurrentUser.mockResolvedValue(userResp(userInfo({ anonymous: true })))
      const store = useAuthStore()

      await store.fetchCurrentUser()

      expect(store.isAnonymous).toBe(true)
    })

    it('is false for a real, non-anonymous user', async () => {
      mockGetCurrentUser.mockResolvedValue(userResp(userInfo({ anonymous: false })))
      const store = useAuthStore()

      await store.fetchCurrentUser()

      expect(store.isAnonymous).toBe(false)
    })
  })

  describe('isAdmin getter', () => {
    it('is true only when base_functions includes ADMIN', async () => {
      mockGetCurrentUser.mockResolvedValue(userResp(userInfo({ base_functions: ['ADMIN', 'WRITE'] })))
      const store = useAuthStore()

      await store.fetchCurrentUser()

      expect(store.isAdmin).toBe(true)
    })

    it('is false when base_functions lacks ADMIN', async () => {
      mockGetCurrentUser.mockResolvedValue(userResp(userInfo({ base_functions: ['WRITE'] })))
      const store = useAuthStore()

      await store.fetchCurrentUser()

      expect(store.isAdmin).toBe(false)
    })
  })

  // R-042: the first-run default-password banner reads this getter to decide
  // whether to warn the admin. Backend only sets is_default_password for admins,
  // and the getter must also stay hidden for anonymous sessions.
  describe('hasDefaultPassword getter', () => {
    it('starts false before any user is loaded', () => {
      const store = useAuthStore()
      expect(store.hasDefaultPassword).toBe(false)
    })

    it('is true when a signed-in user carries is_default_password', async () => {
      mockGetCurrentUser.mockResolvedValue(userResp(userInfo({ is_default_password: true, base_functions: ['ADMIN'] })))
      const store = useAuthStore()

      await store.fetchCurrentUser()

      expect(store.hasDefaultPassword).toBe(true)
    })

    it('is false when is_default_password is not set', async () => {
      mockGetCurrentUser.mockResolvedValue(userResp(userInfo({ is_default_password: false, base_functions: ['ADMIN'] })))
      const store = useAuthStore()

      await store.fetchCurrentUser()

      expect(store.hasDefaultPassword).toBe(false)
    })

    it('is false for an anonymous session even if the flag were set', async () => {
      mockGetCurrentUser.mockResolvedValue(userResp(userInfo({ anonymous: true, is_default_password: true })))
      const store = useAuthStore()

      await store.fetchCurrentUser()

      expect(store.hasDefaultPassword).toBe(false)
    })
  })

  describe('login', () => {
    it('calls the login API then re-fetches the current user', async () => {
      mockApiLogin.mockResolvedValue(loginResp())
      mockGetCurrentUser.mockResolvedValue(userResp(userInfo({ username: 'carol' })))
      const store = useAuthStore()

      await store.login('carol', 'pw', true)

      expect(mockApiLogin).toHaveBeenCalledWith('carol', 'pw', true, undefined)
      expect(mockGetCurrentUser).toHaveBeenCalledTimes(1)
      expect(store.user?.username).toBe('carol')
      expect(store.initialized).toBe(true)
    })

    // TOTP 2FA: the store must forward the optional validation code to the API so a
    // challenged login (ValidationCodeRequired) can be re-submitted with the code.
    it('forwards the optional TOTP code to the login API', async () => {
      mockApiLogin.mockResolvedValue(loginResp())
      mockGetCurrentUser.mockResolvedValue(userResp(userInfo({ username: 'carol' })))
      const store = useAuthStore()

      await store.login('carol', 'pw', false, '654321')

      expect(mockApiLogin).toHaveBeenCalledWith('carol', 'pw', false, '654321')
    })

    // A rejected login (wrong code / bad password) must propagate so the view can
    // reveal the code field or surface a wrong-code error.
    it('propagates a rejected login instead of swallowing it', async () => {
      mockApiLogin.mockRejectedValue({ response: { status: 400, data: { type: 'ValidationCodeRequired' } } })
      const store = useAuthStore()

      await expect(store.login('carol', 'pw', false)).rejects.toMatchObject({
        response: { data: { type: 'ValidationCodeRequired' } },
      })
      expect(mockGetCurrentUser).not.toHaveBeenCalled()
    })
  })

  describe('logout', () => {
    it('calls the logout API and resets user + initialized', async () => {
      mockApiLogin.mockResolvedValue(loginResp())
      mockGetCurrentUser.mockResolvedValue(userResp(userInfo({ username: 'dave' })))
      mockApiLogout.mockResolvedValue(logoutResp())
      const store = useAuthStore()
      await store.login('dave', 'pw', false)
      expect(store.user).not.toBeNull()

      const result = await store.logout()

      expect(mockApiLogout).toHaveBeenCalledTimes(1)
      expect(store.user).toBeNull()
      expect(store.initialized).toBe(false)
      expect(result).toBeNull()
    })

    // R-020: RP-initiated logout — the store surfaces the provider logout_url so
    // the UI can hand the browser off to end the IdP session.
    it('returns the logout_url when the backend supplies one', async () => {
      mockApiLogout.mockResolvedValue(logoutResp({ logout_url: 'https://idp.example.com/logout' }))
      const store = useAuthStore()

      const result = await store.logout()

      expect(result).toBe('https://idp.example.com/logout')
      expect(store.user).toBeNull()
    })

    it('returns null when the backend supplies no logout_url', async () => {
      mockApiLogout.mockResolvedValue(logoutResp({}))
      const store = useAuthStore()

      const result = await store.logout()

      expect(result).toBeNull()
    })

    // #147 follow-up: the server's dark-mode preference is applied as a live class on <html> and is
    // deliberately NOT written to localStorage. Logout is an SPA route change with no page reload, so
    // unless logout resets the class it survives into the next session — on a shared browser the next
    // account inherits the previous user's theme. The reset must use the same rule main.ts boots with
    // (dark only when the stored value is the string 'true').
    describe('dark-mode class on logout', () => {
      it('clears a server-seeded dark mode so it cannot leak to the next account', async () => {
        mockGetCurrentUser.mockResolvedValue(userResp(userInfo({ dark_mode: true })))
        mockApiLogout.mockResolvedValue(logoutResp())
        const store = useAuthStore()
        await store.fetchCurrentUser()
        // Seeded live from the server, with no on-device choice recorded.
        expect(document.documentElement.classList.contains('dark-mode')).toBe(true)
        expect(localStorage.getItem('teedy-dark-mode')).toBeNull()

        await store.logout()

        expect(document.documentElement.classList.contains('dark-mode')).toBe(false)
      })

      it("preserves this device's explicit dark choice across logout", async () => {
        localStorage.setItem('teedy-dark-mode', 'true')
        document.documentElement.classList.add('dark-mode')
        mockApiLogout.mockResolvedValue(logoutResp())
        const store = useAuthStore()

        await store.logout()

        // An explicit on-device choice is the device's own preference, not the departing user's.
        expect(document.documentElement.classList.contains('dark-mode')).toBe(true)
      })

      it("restores this device's explicit light choice after a dark-preferring session", async () => {
        localStorage.setItem('teedy-dark-mode', 'false')
        document.documentElement.classList.add('dark-mode')
        mockApiLogout.mockResolvedValue(logoutResp())
        const store = useAuthStore()

        await store.logout()

        expect(document.documentElement.classList.contains('dark-mode')).toBe(false)
      })
    })
  })
})
