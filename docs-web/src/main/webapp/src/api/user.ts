import api from './client'

export interface UserInfo {
  anonymous: boolean
  username: string
  email: string
  storage_current: number
  storage_quota: number
  groups: string[]
  is_default_password: boolean
  base_functions: string[]
  onboarding: boolean
}

export function getCurrentUser() {
  return api.get<UserInfo>('/user')
}

export function login(username: string, password: string, remember: boolean) {
  const params = new URLSearchParams()
  params.set('username', username)
  params.set('password', password)
  params.set('remember', String(remember))
  return api.post('/user/login', params)
}

export function logout() {
  return api.post('/user/logout')
}
