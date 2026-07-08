import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { getCurrentUser, login as apiLogin, logout as apiLogout, type UserInfo } from '../api/user'

export const useAuthStore = defineStore('auth', () => {
  const user = ref<UserInfo | null>(null)
  const initialized = ref(false)

  const isAnonymous = computed(() => !user.value || user.value.anonymous)
  const isAdmin = computed(() => user.value?.base_functions?.includes('ADMIN') ?? false)
  const username = computed(() => user.value?.username ?? '')

  async function fetchCurrentUser() {
    try {
      const { data } = await getCurrentUser()
      user.value = data
    } catch {
      user.value = null
    }
    initialized.value = true
  }

  async function login(username: string, password: string, remember: boolean) {
    await apiLogin(username, password, remember)
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
    }
    return logoutUrl
  }

  return { user, initialized, isAnonymous, isAdmin, username, fetchCurrentUser, login, logout }
})
