import api from './client'

// GET /api/app/config_oidc returns the non-secret OIDC provider/claim settings. The client
// secret is write-only (mirrors the LDAP admin password): the GET never returns it, only a
// boolean `client_secret_set` flag so the UI can show a "leave blank to keep" affordance.
// Each field also carries its effective SOURCE (db | property | default) via `sources`, so the
// UI can hint "currently from a JVM property — saving stores a DB override".
//
// ADR-0015 fence: this covers ONLY provider/claim settings. Identity binding (issuer+sub),
// username derivation, disabled-account eligibility, and fail-closed rules are non-editable
// behavior and are NOT part of this contract.
export type OidcSource = 'db' | 'property' | 'default'

export interface OidcConfig {
  enabled: boolean
  issuer?: string
  client_id?: string
  // Write-only. Present in the form (what the admin types), never returned by the GET.
  client_secret?: string
  // Read-only flag from the GET: true when a client secret is already stored server-side.
  client_secret_set?: boolean
  // When true on a POST, clears the stored client secret (write-only reset).
  client_secret_reset?: boolean
  redirect_uri?: string
  scope?: string
  authorization_endpoint?: string
  token_endpoint?: string
  jwks_uri?: string
  userinfo_endpoint?: string
  username_claim?: string
  email_claim?: string
  // Per-field effective source, keyed by the lowercase key name (enabled, issuer, ...).
  sources?: Record<string, OidcSource>
}

export function getOidcConfig() {
  return api.get<OidcConfig>('/app/config_oidc').then((r) => r.data)
}

export function saveOidcConfig(config: OidcConfig) {
  const params = new URLSearchParams()
  params.set('enabled', String(config.enabled))
  if (config.enabled) {
    params.set('issuer', config.issuer ?? '')
    params.set('client_id', config.client_id ?? '')
    // Write-only: an empty client_secret keeps the stored value; client_secret_reset clears it.
    params.set('client_secret', config.client_secret ?? '')
    if (config.client_secret_reset) params.set('client_secret_reset', 'true')
    params.set('redirect_uri', config.redirect_uri ?? '')
    params.set('scope', config.scope ?? '')
    // Optional endpoints: sent (possibly blank) so clearing a field derives it from discovery.
    params.set('authorization_endpoint', config.authorization_endpoint ?? '')
    params.set('token_endpoint', config.token_endpoint ?? '')
    params.set('jwks_uri', config.jwks_uri ?? '')
    params.set('userinfo_endpoint', config.userinfo_endpoint ?? '')
    params.set('username_claim', config.username_claim ?? '')
    params.set('email_claim', config.email_claim ?? '')
  }
  return api.post('/app/config_oidc', params)
}
