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

import { listSessions, deleteOtherSessions, type UserSession } from './user'

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

describe('deleteOtherSessions', () => {
  beforeEach(() => mock.delete.mockClear())

  it('DELETEs /user/session', async () => {
    mock.delete.mockResolvedValueOnce({ data: { status: 'ok' } })
    await deleteOtherSessions()
    expect(mock.delete).toHaveBeenCalledTimes(1)
    expect(mock.delete.mock.calls[0][0]).toBe('/user/session')
  })
})
