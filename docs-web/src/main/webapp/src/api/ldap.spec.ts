import { describe, it, expect, beforeEach, vi } from 'vitest'

// Mock the axios client instance (a dependency of ldap.ts). The functions under
// test — URL construction and form-param encoding — run for real against the
// mock and we assert on what they pass to axios.
const clientMock = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
}))

vi.mock('./client', () => ({ default: clientMock }))

import { getLdapConfig, saveLdapConfig } from './ldap'

describe('ldap api module', () => {
  beforeEach(() => {
    clientMock.get.mockReset().mockResolvedValue({ data: { enabled: false } })
    clientMock.post.mockReset().mockResolvedValue({ data: {} })
  })

  it('getLdapConfig GETs /app/config_ldap and unwraps response.data', async () => {
    const payload = { enabled: true, host: 'ldap.example.com', port: 636 }
    clientMock.get.mockResolvedValueOnce({ data: payload })
    const config = await getLdapConfig()
    expect(clientMock.get).toHaveBeenCalledWith('/app/config_ldap')
    expect(config).toEqual(payload)
  })

  it('saveLdapConfig POSTs /app/config_ldap with only enabled=false when disabled', async () => {
    await saveLdapConfig({ enabled: false, host: 'ignored', port: 389 })
    expect(clientMock.post).toHaveBeenCalledTimes(1)
    const [url, body] = clientMock.post.mock.calls[0]
    expect(url).toBe('/app/config_ldap')
    expect(body).toBeInstanceOf(URLSearchParams)
    const params = body as URLSearchParams
    expect(params.get('enabled')).toBe('false')
    // When disabled the other fields are not sent.
    expect(params.has('host')).toBe(false)
    expect(params.has('usessl')).toBe(false)
  })

  it('saveLdapConfig sends every field as a form param when enabled', async () => {
    await saveLdapConfig({
      enabled: true,
      host: 'ldap.example.com',
      port: 636,
      usessl: true,
      admin_dn: 'cn=admin,dc=example,dc=com',
      admin_password: 'secret',
      base_dn: 'dc=example,dc=com',
      filter: '(&(objectClass=user)(sAMAccountName=USERNAME))',
      default_email: 'example.com',
      default_storage: 104857600,
    })
    const params = clientMock.post.mock.calls[0][1] as URLSearchParams
    expect(params.get('enabled')).toBe('true')
    expect(params.get('host')).toBe('ldap.example.com')
    expect(params.get('port')).toBe('636')
    expect(params.get('usessl')).toBe('true')
    expect(params.get('admin_dn')).toBe('cn=admin,dc=example,dc=com')
    expect(params.get('admin_password')).toBe('secret')
    expect(params.get('base_dn')).toBe('dc=example,dc=com')
    expect(params.get('filter')).toBe('(&(objectClass=user)(sAMAccountName=USERNAME))')
    expect(params.get('default_email')).toBe('example.com')
    expect(params.get('default_storage')).toBe('104857600')
  })

  it('always sends usessl when enabled even if omitted (backend NPEs on null)', async () => {
    await saveLdapConfig({ enabled: true, host: 'h', port: 389, filter: 'USERNAME' })
    const params = clientMock.post.mock.calls[0][1] as URLSearchParams
    expect(params.has('usessl')).toBe(true)
    expect(params.get('usessl')).toBe('false')
  })

  it('serializes usessl=true faithfully', async () => {
    await saveLdapConfig({ enabled: true, host: 'h', port: 389, usessl: true, filter: 'USERNAME' })
    const params = clientMock.post.mock.calls[0][1] as URLSearchParams
    expect(params.get('usessl')).toBe('true')
  })

  it('passes the filter through unchanged (USERNAME placeholder preserved)', async () => {
    const filter = '(uid=USERNAME)'
    await saveLdapConfig({ enabled: true, host: 'h', port: 389, filter })
    const params = clientMock.post.mock.calls[0][1] as URLSearchParams
    expect(params.get('filter')).toBe(filter)
  })
})
