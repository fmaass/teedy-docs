import api from './client'

// GET /api/app/config_ldap returns `enabled` plus every non-secret field that exists in
// T_CONFIG — INDEPENDENT of `enabled` (#83): a disabled-but-previously-configured setup still
// returns its host/port/etc so the admin UI repopulates. The admin bind password is write-only:
// the GET never returns it, only a boolean `admin_password_set` flag so the UI can show a
// "leave blank to keep" affordance. POST writes the config back as form params; an absent/empty
// admin_password keeps the stored value. When enabled=false the POST persists only `enabled`,
// leaving the previously-stored connection settings intact.
export interface LdapConfig {
  enabled: boolean
  host?: string
  port?: number
  usessl?: boolean
  admin_dn?: string
  // Write-only. Present in the form (what the admin types), never returned by the GET.
  admin_password?: string
  // Read-only flag from the GET: true when a bind password is already stored server-side.
  admin_password_set?: boolean
  base_dn?: string
  filter?: string
  default_email?: string
  // Per-user default storage quota in bytes (backend `long`).
  default_storage?: number
}

export function getLdapConfig() {
  return api.get<LdapConfig>('/app/config_ldap').then((r) => r.data)
}

export function saveLdapConfig(config: LdapConfig) {
  const params = new URLSearchParams()
  params.set('enabled', String(config.enabled))
  if (config.enabled) {
    params.set('host', config.host ?? '')
    params.set('port', String(config.port ?? ''))
    // Always send usessl when enabled — the backend NPEs on a null value.
    params.set('usessl', String(config.usessl ?? false))
    params.set('admin_dn', config.admin_dn ?? '')
    params.set('admin_password', config.admin_password ?? '')
    params.set('base_dn', config.base_dn ?? '')
    params.set('filter', config.filter ?? '')
    params.set('default_email', config.default_email ?? '')
    params.set('default_storage', String(config.default_storage ?? ''))
  }
  return api.post('/app/config_ldap', params)
}
