import { describe, it, expect, beforeEach, vi } from 'vitest'

// Mock the axios client instance (a dependency of app.ts). getAppInfo runs for
// real against the mock: we assert on the URL it hits and the payload it unwraps.
const clientMock = vi.hoisted(() => ({
  get: vi.fn(),
}))

vi.mock('./client', () => ({ default: clientMock }))

import { getAppInfo } from './app'

describe('app api module', () => {
  beforeEach(() => {
    clientMock.get.mockReset().mockResolvedValue({ data: { current_version: '3.0.0' } })
  })

  it('getAppInfo GETs /app', async () => {
    await getAppInfo()
    expect(clientMock.get).toHaveBeenCalledWith('/app')
  })

  it('getAppInfo unwraps response.data', async () => {
    const payload = { current_version: '3.0.0', guest_login: false, oidc_enabled: true }
    clientMock.get.mockResolvedValueOnce({ data: payload })
    const info = await getAppInfo()
    expect(info).toEqual(payload)
    expect(info.current_version).toBe('3.0.0')
  })
})
