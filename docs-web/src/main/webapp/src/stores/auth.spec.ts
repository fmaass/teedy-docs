import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'

// Mock the API module (a dependency of the store, NOT the unit under test).
vi.mock('../api/user', () => ({
  getCurrentUser: vi.fn(),
  login: vi.fn(),
  logout: vi.fn(),
}))

import { getCurrentUser, login as apiLogin, logout as apiLogout } from '../api/user'
import { useAuthStore } from './auth'

const mockGetCurrentUser = vi.mocked(getCurrentUser)
const mockApiLogin = vi.mocked(apiLogin)
const mockApiLogout = vi.mocked(apiLogout)

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
      mockGetCurrentUser.mockResolvedValue({ data: userInfo({ username: 'bob' }) } as any)
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

  describe('isAnonymous getter', () => {
    it('is true when the fetched user is flagged anonymous', async () => {
      mockGetCurrentUser.mockResolvedValue({ data: userInfo({ anonymous: true }) } as any)
      const store = useAuthStore()

      await store.fetchCurrentUser()

      expect(store.isAnonymous).toBe(true)
    })

    it('is false for a real, non-anonymous user', async () => {
      mockGetCurrentUser.mockResolvedValue({ data: userInfo({ anonymous: false }) } as any)
      const store = useAuthStore()

      await store.fetchCurrentUser()

      expect(store.isAnonymous).toBe(false)
    })
  })

  describe('isAdmin getter', () => {
    it('is true only when base_functions includes ADMIN', async () => {
      mockGetCurrentUser.mockResolvedValue({ data: userInfo({ base_functions: ['ADMIN', 'WRITE'] }) } as any)
      const store = useAuthStore()

      await store.fetchCurrentUser()

      expect(store.isAdmin).toBe(true)
    })

    it('is false when base_functions lacks ADMIN', async () => {
      mockGetCurrentUser.mockResolvedValue({ data: userInfo({ base_functions: ['WRITE'] }) } as any)
      const store = useAuthStore()

      await store.fetchCurrentUser()

      expect(store.isAdmin).toBe(false)
    })
  })

  describe('login', () => {
    it('calls the login API then re-fetches the current user', async () => {
      mockApiLogin.mockResolvedValue({} as any)
      mockGetCurrentUser.mockResolvedValue({ data: userInfo({ username: 'carol' }) } as any)
      const store = useAuthStore()

      await store.login('carol', 'pw', true)

      expect(mockApiLogin).toHaveBeenCalledWith('carol', 'pw', true)
      expect(mockGetCurrentUser).toHaveBeenCalledTimes(1)
      expect(store.user?.username).toBe('carol')
      expect(store.initialized).toBe(true)
    })
  })

  describe('logout', () => {
    it('calls the logout API and resets user + initialized', async () => {
      mockApiLogin.mockResolvedValue({} as any)
      mockGetCurrentUser.mockResolvedValue({ data: userInfo({ username: 'dave' }) } as any)
      mockApiLogout.mockResolvedValue({} as any)
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
      mockApiLogout.mockResolvedValue({ data: { logout_url: 'https://idp.example.com/logout' } } as any)
      const store = useAuthStore()

      const result = await store.logout()

      expect(result).toBe('https://idp.example.com/logout')
      expect(store.user).toBeNull()
    })

    it('returns null when the backend supplies no logout_url', async () => {
      mockApiLogout.mockResolvedValue({ data: {} } as any)
      const store = useAuthStore()

      const result = await store.logout()

      expect(result).toBeNull()
    })
  })
})
