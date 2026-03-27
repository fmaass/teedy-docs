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

export interface UserListItem {
  id: string
  username: string
  email: string
  totp_enabled: boolean
  storage_quota: number
  storage_current: number
  create_date: number
  disabled: boolean
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

export function listUsers() {
  return api.get<{ users: UserListItem[] }>('/user/list', { params: { sort_column: 1, asc: true } })
}

export function createUser(username: string, password: string, email: string, storageQuota: number) {
  const params = new URLSearchParams()
  params.set('username', username)
  params.set('password', password)
  params.set('email', email)
  params.set('storage_quota', String(storageQuota))
  return api.put('/user', params)
}

export function updateUser(username: string, data: { email?: string; password?: string; storage_quota?: number }) {
  const params = new URLSearchParams()
  if (data.email !== undefined) params.set('email', data.email)
  if (data.password !== undefined) params.set('password', data.password)
  if (data.storage_quota !== undefined) params.set('storage_quota', String(data.storage_quota))
  return api.post(`/user/${username}`, params)
}

export function deleteUser(username: string) {
  return api.delete(`/user/${username}`)
}

export function requestPasswordReset(username: string) {
  const params = new URLSearchParams()
  params.set('username', username)
  return api.post('/user/password_lost', params)
}

export function resetPassword(key: string, password: string) {
  const params = new URLSearchParams()
  params.set('key', key)
  params.set('password', password)
  return api.post('/user/password_reset', params)
}
