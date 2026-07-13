import { describe, it, expect, beforeEach, vi } from 'vitest'

// Mock the axios client (a dependency of oidc.ts). The functions under test — URL construction
// and form-param encoding — run for real; we assert on what they pass to axios.
const clientMock = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
}))
vi.mock('./client', () => ({ default: clientMock }))

import { getOidcConfig, saveOidcConfig } from './oidc'

describe('oidc api module', () => {
  beforeEach(() => {
    clientMock.get.mockReset().mockResolvedValue({ data: { enabled: false } })
    clientMock.post.mockReset().mockResolvedValue({ data: {} })
  })

  it('getOidcConfig GETs /app/config_oidc and unwraps response.data', async () => {
    const payload = { enabled: true, issuer: 'https://iss', client_secret_set: true }
    clientMock.get.mockResolvedValueOnce({ data: payload })
    const config = await getOidcConfig()
    expect(clientMock.get).toHaveBeenCalledWith('/app/config_oidc')
    expect(config).toEqual(payload)
  })

  it('saveOidcConfig POSTs only enabled=false when disabled', async () => {
    await saveOidcConfig({ enabled: false, issuer: 'ignored' })
    const [url, body] = clientMock.post.mock.calls[0]
    expect(url).toBe('/app/config_oidc')
    const params = body as URLSearchParams
    expect(params.get('enabled')).toBe('false')
    expect(params.has('issuer')).toBe(false)
  })

  it('saveOidcConfig sends every field when enabled', async () => {
    await saveOidcConfig({
      enabled: true,
      issuer: 'https://iss.example',
      client_id: 'client',
      client_secret: 'secret',
      redirect_uri: 'https://app/api/oidc/callback',
      scope: 'openid profile email',
      authorization_endpoint: 'https://iss.example/authorize',
      token_endpoint: 'https://iss.example/token',
      jwks_uri: 'https://iss.example/jwks',
      userinfo_endpoint: 'https://iss.example/userinfo',
      username_claim: 'preferred_username',
      email_claim: 'email',
      username_verbatim: true,
    })
    const params = clientMock.post.mock.calls[0][1] as URLSearchParams
    expect(params.get('enabled')).toBe('true')
    expect(params.get('issuer')).toBe('https://iss.example')
    expect(params.get('client_id')).toBe('client')
    expect(params.get('client_secret')).toBe('secret')
    expect(params.get('scope')).toBe('openid profile email')
    expect(params.get('username_claim')).toBe('preferred_username')
    expect(params.get('email_claim')).toBe('email')
    expect(params.get('username_verbatim')).toBe('true')
  })

  it('username_verbatim defaults to false when omitted', async () => {
    await saveOidcConfig({ enabled: true, issuer: 'https://i', client_id: 'c', redirect_uri: 'https://r', scope: 's', username_claim: 'u', email_claim: 'e' })
    const params = clientMock.post.mock.calls[0][1] as URLSearchParams
    expect(params.get('username_verbatim')).toBe('false')
  })

  it('write-only secret: an empty client_secret is sent blank (backend keeps the stored value)', async () => {
    await saveOidcConfig({ enabled: true, issuer: 'https://i', client_id: 'c', redirect_uri: 'https://r', scope: 's', username_claim: 'u', email_claim: 'e' })
    const params = clientMock.post.mock.calls[0][1] as URLSearchParams
    expect(params.get('client_secret')).toBe('')
    expect(params.has('client_secret_reset')).toBe(false)
  })

  it('client_secret_reset=true is sent when the admin clears the stored secret', async () => {
    await saveOidcConfig({ enabled: true, issuer: 'https://i', client_id: 'c', client_secret: '', client_secret_reset: true, redirect_uri: 'https://r', scope: 's', username_claim: 'u', email_claim: 'e' })
    const params = clientMock.post.mock.calls[0][1] as URLSearchParams
    expect(params.get('client_secret_reset')).toBe('true')
    expect(params.get('client_secret')).toBe('')
  })
})
