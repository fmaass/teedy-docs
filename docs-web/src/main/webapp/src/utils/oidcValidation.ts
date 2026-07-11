import type { OidcConfig } from '../api/oidc'

export type OidcFieldError =
  | 'issuer_required'
  | 'issuer_url'
  | 'client_id_required'
  | 'client_secret_required'
  | 'redirect_uri_required'
  | 'redirect_uri_url'
  | 'scope_required'
  | 'authorization_endpoint_url'
  | 'token_endpoint_url'
  | 'jwks_uri_url'
  | 'userinfo_endpoint_url'
  | 'username_claim_required'
  | 'email_claim_required'

// Mirrors AppResource.configOidc's required-when-enabled validation (the `if (enabled)` branch).
// When OIDC is disabled the backend ignores every field but `enabled`, so nothing else is
// validated. issuer/redirect_uri must be http(s); the optional endpoints must be http(s) only
// when non-blank (blank clears them so OidcResource derives them from discovery). The client
// secret is write-only: required only on first setup (no stored secret) unless a reset is set.
function isBlank(value: string | undefined): boolean {
  return (value ?? '').trim().length < 1
}

function isHttpUrl(value: string): boolean {
  return /^https?:\/\/.+/.test(value.trim())
}

export function validateOidcConfig(config: OidcConfig): OidcFieldError[] {
  if (!config.enabled) return []
  const errors: OidcFieldError[] = []

  if (isBlank(config.issuer)) errors.push('issuer_required')
  else if (!isHttpUrl(config.issuer!)) errors.push('issuer_url')

  if (isBlank(config.client_id)) errors.push('client_id_required')

  // Secret: required only when none stored yet and not resetting; blank otherwise keeps it.
  if (isBlank(config.client_secret) && !config.client_secret_reset && !config.client_secret_set) {
    errors.push('client_secret_required')
  }

  if (isBlank(config.redirect_uri)) errors.push('redirect_uri_required')
  else if (!isHttpUrl(config.redirect_uri!)) errors.push('redirect_uri_url')

  if (isBlank(config.scope)) errors.push('scope_required')

  // Optional endpoints: an http(s) check only when a value is present.
  if (!isBlank(config.authorization_endpoint) && !isHttpUrl(config.authorization_endpoint!)) {
    errors.push('authorization_endpoint_url')
  }
  if (!isBlank(config.token_endpoint) && !isHttpUrl(config.token_endpoint!)) {
    errors.push('token_endpoint_url')
  }
  if (!isBlank(config.jwks_uri) && !isHttpUrl(config.jwks_uri!)) {
    errors.push('jwks_uri_url')
  }
  if (!isBlank(config.userinfo_endpoint) && !isHttpUrl(config.userinfo_endpoint!)) {
    errors.push('userinfo_endpoint_url')
  }

  if (isBlank(config.username_claim)) errors.push('username_claim_required')
  if (isBlank(config.email_claim)) errors.push('email_claim_required')

  return errors
}
