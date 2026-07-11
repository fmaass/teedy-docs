import { describe, it, expect, beforeEach, vi } from 'vitest'

// Mock the axios client instance (a dependency of app.ts). getAppInfo runs for
// real against the mock: we assert on the URL it hits and the payload it unwraps.
const clientMock = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
}))

vi.mock('./client', () => ({ default: clientMock }))

import {
  getAppInfo,
  getLogs,
  LOG_LEVELS,
  getSmtpConfig,
  saveSmtpConfig,
  smtpEnvManagedFields,
  getInboxConfig,
  saveInboxConfig,
  testInbox,
  cleanStorage,
  saveFooterLinks,
} from './app'

describe('app api module', () => {
  beforeEach(() => {
    clientMock.get.mockReset().mockResolvedValue({ data: { current_version: '3.0.0' } })
    clientMock.post.mockReset().mockResolvedValue({ data: {} })
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

describe('smtp config api', () => {
  beforeEach(() => {
    clientMock.get.mockReset().mockResolvedValue({ data: {} })
    clientMock.post.mockReset().mockResolvedValue({ data: {} })
  })

  it('getSmtpConfig GETs /app/config_smtp and unwraps data', async () => {
    const payload = { hostname: 'smtp.example.com', port: 587, username: 'bob', from: 'no-reply@x' }
    clientMock.get.mockResolvedValueOnce({ data: payload })
    const cfg = await getSmtpConfig()
    expect(clientMock.get).toHaveBeenCalledWith('/app/config_smtp')
    expect(cfg).toEqual(payload)
  })

  it('saveSmtpConfig POSTs filled fields as form params', async () => {
    await saveSmtpConfig({ hostname: 'h', port: 25, username: 'u', password: 'secret', from: 'f@x' })
    const [url, body] = clientMock.post.mock.calls[0]
    expect(url).toBe('/app/config_smtp')
    const params = body as URLSearchParams
    expect(params.get('hostname')).toBe('h')
    expect(params.get('port')).toBe('25')
    expect(params.get('username')).toBe('u')
    expect(params.get('password')).toBe('secret')
    expect(params.get('from')).toBe('f@x')
  })

  it('saveSmtpConfig OMITS an empty password (keep-on-empty)', async () => {
    await saveSmtpConfig({ hostname: 'h', port: 25, username: 'u', password: '', from: 'f@x' })
    const params = clientMock.post.mock.calls[0][1] as URLSearchParams
    expect(params.has('password')).toBe(false)
  })

  it('saveSmtpConfig omits null/empty optional fields', async () => {
    await saveSmtpConfig({ hostname: null, port: null, username: '', from: null })
    const params = clientMock.post.mock.calls[0][1] as URLSearchParams
    expect(params.has('hostname')).toBe(false)
    expect(params.has('port')).toBe(false)
    expect(params.has('username')).toBe(false)
    expect(params.has('from')).toBe(false)
  })

  it('saveSmtpConfig sends port=0 (a real value, not treated as empty)', async () => {
    await saveSmtpConfig({ port: 0 })
    const params = clientMock.post.mock.calls[0][1] as URLSearchParams
    expect(params.get('port')).toBe('0')
  })

  // The backend OMITS hostname/port/username from the GET response when the
  // corresponding DOCS_SMTP_* env var is set (AppResource.java:268,275,282).
  // An ABSENT key means env-managed; a PRESENT-but-null key means "not configured
  // yet, editable". getSmtpConfig must preserve that distinction (no defaulting),
  // and smtpEnvManagedFields derives the disabled-field set from it.
  it('getSmtpConfig preserves absent keys as absent (env-managed) vs null (unset)', async () => {
    clientMock.get.mockResolvedValueOnce({ data: { port: null, username: 'bob', from: null } })
    const cfg = await getSmtpConfig()
    expect('hostname' in cfg).toBe(false)
    expect(cfg.hostname).toBeUndefined()
    expect('port' in cfg).toBe(true)
    expect(cfg.port).toBeNull()
  })

  it('smtpEnvManagedFields flags exactly the keys absent from the response', () => {
    const managed = smtpEnvManagedFields({ port: null, from: 'f@x' })
    expect(managed.has('hostname')).toBe(true)
    expect(managed.has('username')).toBe(true)
    expect(managed.has('port')).toBe(false)
  })

  it('smtpEnvManagedFields treats present-but-null as editable, not env-managed', () => {
    const managed = smtpEnvManagedFields({ hostname: null, port: null, username: null, from: null })
    expect(managed.size).toBe(0)
  })

  it('smtpEnvManagedFields flags all three when everything is env-managed', () => {
    const managed = smtpEnvManagedFields({ from: null })
    expect([...managed].sort()).toEqual(['hostname', 'port', 'username'])
  })
})

describe('inbox config api', () => {
  beforeEach(() => {
    clientMock.get.mockReset().mockResolvedValue({ data: {} })
    clientMock.post.mockReset().mockResolvedValue({ data: {} })
  })

  it('getInboxConfig GETs /app/config_inbox and unwraps data', async () => {
    const payload = { enabled: true, autoTagsEnabled: false, deleteImported: true, starttls: true, hostname: 'imap.x' }
    clientMock.get.mockResolvedValueOnce({ data: payload })
    const cfg = await getInboxConfig()
    expect(clientMock.get).toHaveBeenCalledWith('/app/config_inbox')
    expect(cfg).toEqual(payload)
  })

  it('saveInboxConfig ALWAYS sends the four required booleans', async () => {
    await saveInboxConfig({ enabled: false, autoTagsEnabled: false, deleteImported: false, starttls: false })
    const [url, body] = clientMock.post.mock.calls[0]
    expect(url).toBe('/app/config_inbox')
    const params = body as URLSearchParams
    expect(params.get('enabled')).toBe('false')
    expect(params.get('autoTagsEnabled')).toBe('false')
    expect(params.get('deleteImported')).toBe('false')
    expect(params.get('starttls')).toBe('false')
  })

  it('saveInboxConfig sends optional fields when filled and omits an empty password', async () => {
    await saveInboxConfig({
      enabled: true, autoTagsEnabled: true, deleteImported: true, starttls: true,
      hostname: 'imap.x', port: 993, username: 'bob', password: '', folder: 'INBOX', tag: 'scanned',
    })
    const params = clientMock.post.mock.calls[0][1] as URLSearchParams
    expect(params.get('hostname')).toBe('imap.x')
    expect(params.get('port')).toBe('993')
    expect(params.get('username')).toBe('bob')
    expect(params.has('password')).toBe(false)
    expect(params.get('folder')).toBe('INBOX')
    expect(params.get('tag')).toBe('scanned')
  })

  it('saveInboxConfig sends a non-empty password', async () => {
    await saveInboxConfig({ enabled: true, autoTagsEnabled: false, deleteImported: false, starttls: true, password: 'pw' })
    const params = clientMock.post.mock.calls[0][1] as URLSearchParams
    expect(params.get('password')).toBe('pw')
  })

  it('testInbox POSTs /app/test_inbox with NO body and returns { count }', async () => {
    clientMock.post.mockResolvedValueOnce({ data: { count: 7 } })
    const result = await testInbox()
    expect(clientMock.post).toHaveBeenCalledWith('/app/test_inbox')
    expect(clientMock.post.mock.calls[0].length).toBe(1)
    expect(result).toEqual({ count: 7 })
  })
})

describe('clean storage api', () => {
  beforeEach(() => {
    clientMock.post.mockReset().mockResolvedValue({ data: {} })
  })

  it('cleanStorage POSTs /app/batch/clean_storage with no body', async () => {
    await cleanStorage()
    expect(clientMock.post).toHaveBeenCalledWith('/app/batch/clean_storage')
    expect(clientMock.post.mock.calls[0].length).toBe(1)
  })
})

describe('footer links api', () => {
  beforeEach(() => {
    clientMock.post.mockReset().mockResolvedValue({ data: {} })
  })

  it('saveFooterLinks POSTs /app/footer_links with the list JSON-encoded under `links`', async () => {
    await saveFooterLinks([
      { label: 'Imprint', url: 'https://example.com/imprint' },
      { label: 'Privacy', url: 'https://example.com/privacy' },
    ])
    expect(clientMock.post).toHaveBeenCalledOnce()
    const [url, body] = clientMock.post.mock.calls[0]
    expect(url).toBe('/app/footer_links')
    expect(body).toBeInstanceOf(URLSearchParams)
    expect(JSON.parse((body as URLSearchParams).get('links')!)).toEqual([
      { label: 'Imprint', url: 'https://example.com/imprint' },
      { label: 'Privacy', url: 'https://example.com/privacy' },
    ])
  })

  it('saveFooterLinks sends an empty array to clear the config', async () => {
    await saveFooterLinks([])
    const body = clientMock.post.mock.calls[0][1] as URLSearchParams
    expect(body.get('links')).toBe('[]')
  })
})
