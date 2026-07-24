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
  // #82 server-side preferred UI locale. Present only when the user has set one; the SPA seeds a
  // fresh device's locale from this at login/boot when no explicit on-device choice exists.
  locale?: string
  // #147 server-side dark-mode preference. Present only when the user has set one, so an absent value
  // (no preference) stays distinguishable from an explicit false. The SPA seeds a fresh device's dark
  // mode from this at login when no explicit on-device choice exists (a local choice always wins).
  dark_mode?: boolean
  // #169 True once the current user has an ACTIVE TOTP second factor (a pending, un-activated enrollment
  // does not set this). Absent for the anonymous response.
  totp_enabled?: boolean
  // #169 True if the account is provisioned by an external identity provider (OIDC or LDAP). Such accounts
  // delegate MFA to their IdP, so the self-service two-factor section is hidden for them.
  external_origin?: boolean
}

// #169 Response of POST /user/enable_totp: the pending secret plus the labels the client needs to build the
// otpauth:// URI. The secret becomes the active login factor only after POST /user/totp/activate confirms it.
export interface TotpEnrollment {
  secret: string
  issuer: string
  account: string
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
  // #180 True when deleting this user requires a reassignment target because they still own active
  // documents or tags. Sent to admin callers only (they are the only ones who may delete a user), so
  // an ABSENT value must be read as "a target is required" — the fail-safe direction.
  requires_reassign?: boolean
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

// Deleting a user reassigns all their documents and tags to `reassignToUsername` (a distinct,
// active user) before the departing account is removed — the documents' content is preserved and
// stays decryptable. The target is passed as a query parameter because a DELETE request carries no
// body. #180: it is omitted for a user that owns nothing; the backend then answers 400 with type
// "ReassignRequired" if the account acquired content in the meantime.
export function deleteUser(username: string, reassignToUsername?: string) {
  return api.delete(`/user/${username}`, {
    params: reassignToUsername ? { reassign_to_username: reassignToUsername } : undefined,
  })
}

// Admin recovery for a TOTP-locked user: clears the user's TOTP key so they can
// log in again (Authelia owns 2FA; Teedy only provides admin recovery).
export function disableUserTotp(username: string) {
  return api.post(`/user/${username}/disable_totp`)
}

// #169 Self-service TOTP enrollment. enableTotp generates a PENDING secret (returned with the otpauth
// issuer/account labels); it becomes the active login factor only once activateTotp confirms a code
// generated from it. disableTotp turns it off again (the current password re-authenticates the change).
export function enableTotp() {
  return api.post<TotpEnrollment>('/user/enable_totp')
}

export function activateTotp(code: string) {
  const params = new URLSearchParams()
  params.set('code', code)
  return api.post('/user/totp/activate', params)
}

export function disableTotp(password: string) {
  const params = new URLSearchParams()
  params.set('password', password)
  return api.post('/user/disable_totp', params)
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
