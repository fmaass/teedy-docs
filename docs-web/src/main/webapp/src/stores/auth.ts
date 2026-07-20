import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { getCurrentUser, login as apiLogin, logout as apiLogout, type UserInfo } from '../api/user'
import { setLocale } from '../i18n'

export const useAuthStore = defineStore('auth', () => {
  const user = ref<UserInfo | null>(null)
  const initialized = ref(false)

  const isAnonymous = computed(() => !user.value || user.value.anonymous)
  const isAdmin = computed(() => user.value?.base_functions?.includes('ADMIN') ?? false)
  const username = computed(() => user.value?.username ?? '')
  // R-042: GET /api/user sets is_default_password to true only when the current
  // user is an admin AND the admin account still holds the built-in default
  // password. Backend already gates it on ADMIN, so this getter mirrors the raw
  // signal; the banner uses it to warn until the password is changed.
  const hasDefaultPassword = computed(
    () => !isAnonymous.value && (user.value?.is_default_password ?? false),
  )

  async function fetchCurrentUser() {
    let fetched: UserInfo | null = null
    try {
      const { data } = await getCurrentUser()
      user.value = data
      fetched = data
    } catch {
      user.value = null
    }
    initialized.value = true

    // #82: seed the UI locale from the server-side preference ONLY when there is no explicit
    // on-device choice. An explicit local choice (localStorage 'teedy-locale', written when the
    // user picks a language) always wins and is never overwritten; the server value only seeds a
    // fresh device / new login. Runs at initial routing and after login (both call this).
    //
    // DELIBERATELY outside the auth try/catch above: setLocale lazy-loads a translation chunk and
    // can reject (network hiccup, missing chunk). That failure must NEVER clear the already-loaded
    // user or flip the session to anonymous — an i18n load error is not an auth error. Its own
    // try/catch swallows-and-logs and touches no auth state.
    if (fetched?.locale && !localStorage.getItem('teedy-locale')) {
      try {
        await setLocale(fetched.locale)
      } catch (e) {
        console.warn('Failed to seed UI locale from the server preference; keeping the current locale', e)
      }
    }

    // #147: seed the dark-mode preference from the server-side value ONLY when there is no explicit
    // on-device choice, and apply the `.dark-mode` class LIVE so a fresh device themes with no reload.
    // Local-choice-always-wins: the header toggle persists 'true' OR 'false' to localStorage, and BOTH
    // count as an explicit choice — so the guard is `=== null` (not a truthiness check), otherwise a
    // device that explicitly chose light mode ('false') would be re-seeded to the server's dark value.
    // Like the locale seed, the server value is NOT written to localStorage: localStorage stays the
    // record of an explicit on-device choice only, and the server re-seeds the live class each login.
    if (typeof fetched?.dark_mode === 'boolean' && localStorage.getItem('teedy-dark-mode') === null) {
      document.documentElement.classList.toggle('dark-mode', fetched.dark_mode)
    }
  }

  // `code` is the optional TOTP 2FA validation code, forwarded to the API when the
  // backend has challenged the login with "ValidationCodeRequired". A rejection here
  // propagates to the caller so the login view can reveal the code field / show a
  // wrong-code error.
  async function login(username: string, password: string, remember: boolean, code?: string) {
    await apiLogin(username, password, remember, code)
    await fetchCurrentUser()
  }

  // RP-initiated logout: the backend returns the provider end_session_endpoint as
  // logout_url when the session was OIDC-backed. Returned to the caller so the UI
  // can navigate the browser there to end the IdP session too.
  async function logout(): Promise<string | null> {
    let logoutUrl: string | null = null
    try {
      const { data } = await apiLogout()
      logoutUrl = (data as { logout_url?: string })?.logout_url ?? null
    } finally {
      user.value = null
      initialized.value = false
      // #147 follow-up: fetchCurrentUser applies the SERVER's dark-mode preference as a live class on
      // <html> without writing it to localStorage. Logout is an SPA route change with no page reload,
      // so that class would otherwise survive the session and the next account to log in on a shared
      // browser would inherit the previous user's theme. Reset to this device's own explicit choice,
      // using exactly the boot rule in main.ts (dark only when the stored value is the string 'true'),
      // so a device that never chose keeps the light default.
      document.documentElement.classList.toggle(
        'dark-mode',
        localStorage.getItem('teedy-dark-mode') === 'true',
      )
    }
    return logoutUrl
  }

  return { user, initialized, isAnonymous, isAdmin, username, hasDefaultPassword, fetchCurrentUser, login, logout }
})
