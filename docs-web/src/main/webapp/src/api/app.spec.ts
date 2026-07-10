import { describe, it, expect, beforeEach, vi } from 'vitest'

// Mock the axios client instance (a dependency of app.ts). getAppInfo runs for
// real against the mock: we assert on the URL it hits and the payload it unwraps.
const clientMock = vi.hoisted(() => ({
  get: vi.fn(),
}))

vi.mock('./client', () => ({ default: clientMock }))

import { getAppInfo, getLogs, LOG_LEVELS } from './app'

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

describe('getLogs', () => {
  beforeEach(() => {
    clientMock.get.mockReset().mockResolvedValue({ data: { total: 0, logs: [] } })
  })

  it('exposes the five backend-accepted levels in severity order', () => {
    expect(LOG_LEVELS).toEqual(['FATAL', 'ERROR', 'WARN', 'INFO', 'DEBUG'])
  })

  it('GETs /app/log and passes only the supplied params', async () => {
    await getLogs({ level: 'ERROR', tag: 'com.sismics', message: 'boom', limit: 25, offset: 50 })
    expect(clientMock.get).toHaveBeenCalledTimes(1)
    const [url, config] = clientMock.get.mock.calls[0]
    expect(url).toBe('/app/log')
    expect(config).toEqual({
      params: { level: 'ERROR', tag: 'com.sismics', message: 'boom', limit: 25, offset: 50 },
    })
  })

  it('omits empty filters but keeps a zero offset', async () => {
    await getLogs({ limit: 25, offset: 0 })
    const config = clientMock.get.mock.calls[0][1] as { params: Record<string, unknown> }
    expect(config.params).toEqual({ limit: 25, offset: 0 })
    expect('level' in config.params).toBe(false)
    expect('tag' in config.params).toBe(false)
    expect('message' in config.params).toBe(false)
  })

  it('sends no params when the query is empty', async () => {
    await getLogs()
    expect(clientMock.get.mock.calls[0][1]).toEqual({ params: {} })
  })

  it('unwraps { total, logs } from response.data', async () => {
    const payload = { total: 2, logs: [{ date: 1, level: 'INFO', tag: 't', message: 'm' }] }
    clientMock.get.mockResolvedValueOnce({ data: payload })
    const page = await getLogs()
    expect(page).toEqual(payload)
    expect(page.total).toBe(2)
  })
})
