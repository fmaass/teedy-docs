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
  // True if the user has the ADMIN base function. The backend refuses to disable an
  // admin (or the guest) user, so the UI hides the disable/enable toggle for them.
  admin: boolean
  disabled: boolean
}

export function getCurrentUser() {
  return api.get<UserInfo>('/user')
}

// `code` is the TOTP 2FA validation code. It is appended ONLY when non-empty:
// a non-TOTP login sends nothing new, and the backend rejects a login for a
// TOTP-enabled user that omits it with ClientException type "ValidationCodeRequired".
export function login(username: string, password: string, remember: boolean, code?: string) {
  const params = new URLSearchParams()
  params.set('username', username)
  params.set('password', password)
  params.set('remember', String(remember))
  if (code) params.set('code', code)
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

export function updateUser(
  username: string,
  data: { email?: string; password?: string; storage_quota?: number; disabled?: boolean },
) {
  const params = new URLSearchParams()
  if (data.email !== undefined) params.set('email', data.email)
  if (data.password !== undefined) params.set('password', data.password)
  if (data.storage_quota !== undefined) params.set('storage_quota', String(data.storage_quota))
  // A disabled account is rejected per-request (soft): re-enabling resurrects old
  // sessions/API keys rather than hard-revoking them. Only sent when explicitly set.
  if (data.disabled !== undefined) params.set('disabled', String(data.disabled))
  return api.post(`/user/${username}`, params)
}

export function deleteUser(username: string) {
  return api.delete(`/user/${username}`)
}

// Admin recovery for a TOTP-locked user: clears the user's TOTP key so they can
// log in again (Authelia owns 2FA; Teedy only provides admin recovery).
export function disableUserTotp(username: string) {
  return api.post(`/user/${username}/disable_totp`)
}

/**
 * One active authentication session for the current user, as returned by
 * GET /api/user/session. `current` marks the session used for THIS request (the
 * one "sign out other sessions" preserves). `last_connection_date` is absent for a
 * session that has never been used since creation, so it is optional.
 */
export interface UserSession {
  create_date: number
  ip: string | null
  user_agent: string | null
  last_connection_date?: number
  current: boolean
}

// List the current user's active sessions. Self-service (any authenticated
// non-guest user); the backend returns an empty list for the guest user.
export function listSessions() {
  return api.get<{ sessions: UserSession[] }>('/user/session')
}

// Revoke every session of the current user EXCEPT the one making this request.
// Self-service; the caller stays logged in.
export function deleteOtherSessions() {
  return api.delete<{ status: string }>('/user/session')
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
