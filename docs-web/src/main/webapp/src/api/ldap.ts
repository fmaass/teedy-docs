import api from './client'

// GET /api/app/config_ldap returns { enabled: false } when LDAP is off, or the
// full config (including admin_password) when on. POST writes it back as form
// params; when enabled=false the backend ignores every field but `enabled`.
export interface LdapConfig {
  enabled: boolean
  host?: string
  port?: number
  usessl?: boolean
  admin_dn?: string
  admin_password?: string
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
