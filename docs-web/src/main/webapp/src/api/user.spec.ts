import { describe, it, expect, beforeEach, vi } from 'vitest'

// Mock the shared axios client (a dependency of user.ts). The unit under test is
// user.ts's own path building and response unwrapping, exercised for real.
const mock = vi.hoisted(() => ({
  get: vi.fn((..._args: unknown[]) => Promise.resolve({ data: {} })),
  put: vi.fn((..._args: unknown[]) => Promise.resolve({ data: {} })),
  post: vi.fn((..._args: unknown[]) => Promise.resolve({ data: {} })),
  delete: vi.fn((..._args: unknown[]) => Promise.resolve({ data: {} })),
}))

vi.mock('./client', () => ({ default: mock }))

import { listSessions, deleteOtherSessions, login, updateUser, type UserSession } from './user'

describe('login', () => {
  beforeEach(() => mock.post.mockClear())

  function postedParams(): URLSearchParams {
    return mock.post.mock.calls[0][1] as URLSearchParams
  }

  it('POSTs /user/login with username, password, remember and NO code when code is omitted', async () => {
    await login('alice', 'secret', true)
    expect(mock.post).toHaveBeenCalledTimes(1)
    expect(mock.post.mock.calls[0][0]).toBe('/user/login')
    const params = postedParams()
    expect(params.get('username')).toBe('alice')
    expect(params.get('password')).toBe('secret')
    expect(params.get('remember')).toBe('true')
    // Non-TOTP login must send no `code` param (the backend contract for it).
    expect(params.has('code')).toBe(false)
  })

  it('appends the code param only when a non-empty code is provided', async () => {
    await login('alice', 'secret', false, '123456')
    const params = postedParams()
    expect(params.get('code')).toBe('123456')
  })

  it('omits the code param when the code is an empty string', async () => {
    await login('alice', 'secret', false, '')
    expect(postedParams().has('code')).toBe(false)
  })
})

describe('listSessions', () => {
  beforeEach(() => mock.get.mockClear())

  it('GETs /user/session', async () => {
    mock.get.mockResolvedValueOnce({ data: { sessions: [] } })
    await listSessions()
    expect(mock.get).toHaveBeenCalledTimes(1)
    expect(mock.get.mock.calls[0][0]).toBe('/user/session')
  })

  it('returns the raw axios response (sessions under data)', async () => {
    const sessions: UserSession[] = [
      { create_date: 100, ip: '1.2.3.4', user_agent: 'ua', last_connection_date: 200, current: true },
      { create_date: 50, ip: null, user_agent: null, current: false },
    ]
    mock.get.mockResolvedValueOnce({ data: { sessions } })
    const res = await listSessions()
    expect(res.data.sessions).toEqual(sessions)
    expect(res.data.sessions[0].current).toBe(true)
  })
})

describe('updateUser', () => {
  beforeEach(() => mock.post.mockClear())

  function postedParams(): URLSearchParams {
    return mock.post.mock.calls[0][1] as URLSearchParams
  }

  it('POSTs /user/{username} and sends disabled=true when disabling', async () => {
    await updateUser('bob', { disabled: true })
    expect(mock.post).toHaveBeenCalledTimes(1)
    expect(mock.post.mock.calls[0][0]).toBe('/user/bob')
    expect(postedParams().get('disabled')).toBe('true')
  })

  it('sends disabled=false when re-enabling', async () => {
    await updateUser('bob', { disabled: false })
    expect(postedParams().get('disabled')).toBe('false')
  })

  it('omits disabled when not provided (e.g. an email-only edit)', async () => {
    await updateUser('bob', { email: 'bob@example.com' })
    const params = postedParams()
    expect(params.get('email')).toBe('bob@example.com')
    expect(params.has('disabled')).toBe(false)
  })
})

describe('deleteOtherSessions', () => {
  beforeEach(() => mock.delete.mockClear())

  it('DELETEs /user/session', async () => {
    mock.delete.mockResolvedValueOnce({ data: { status: 'ok' } })
    await deleteOtherSessions()
    expect(mock.delete).toHaveBeenCalledTimes(1)
    expect(mock.delete.mock.calls[0][0]).toBe('/user/session')
  })
})
