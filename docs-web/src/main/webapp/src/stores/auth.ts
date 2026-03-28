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

  async function logout() {
    await apiLogout()
    user.value = null
    initialized.value = false
  }

  return { user, initialized, isAnonymous, isAdmin, username, fetchCurrentUser, login, logout }
})
