import type { LdapConfig } from '../api/ldap'

export type LdapFieldError =
  | 'host_required'
  | 'port_required'
  | 'admin_dn_required'
  | 'admin_password_required'
  | 'base_dn_required'
  | 'filter_required'
  | 'filter_username'
  | 'default_email_required'
  | 'default_storage_required'

// Mirrors AppResource.configLdap's required-when-enabled validation exactly
// (docs-web AppResource.java: `if (enabled)` branch). When LDAP is disabled the
// backend ignores every field but `enabled`, so nothing else is validated.
//
// Backend rules mirrored here:
//   host, admin_dn, base_dn, filter, default_email
//     → validateLength(_, 1, 250, nullable=false): whitespace-stripped, non-empty, ≤250 chars.
//   port          → validateInteger: must parse as an integer.
//   filter        → additionally must contain the literal substring USERNAME.
//   default_storage → validateLong: must parse as a number (empty/blank rejected).
//   admin_password → write-only (BL-028): required only on FIRST setup (no password stored
//     yet). When a password is already stored (config.admin_password_set), a blank field
//     keeps the existing value, so it is NOT required — mirrors AppResource.configLdap's
//     keep-on-empty branch. A non-empty value must still be ≤250 chars.
const MAX_LEN = 250

function blankOrTooLong(value: string | undefined): boolean {
  const s = (value ?? '').trim()
  return s.length < 1 || s.length > MAX_LEN
}

function tooLong(value: string | undefined): boolean {
  return (value ?? '').trim().length > MAX_LEN
}

export function validateLdapConfig(config: LdapConfig): LdapFieldError[] {
  if (!config.enabled) return []
  const errors: LdapFieldError[] = []

  if (blankOrTooLong(config.host)) errors.push('host_required')
  if (config.port == null || Number.isNaN(config.port)) errors.push('port_required')
  if (blankOrTooLong(config.admin_dn)) errors.push('admin_dn_required')
  // Password: required only when none is stored yet; otherwise blank keeps the existing.
  const passwordBlank = (config.admin_password ?? '').trim().length < 1
  if (passwordBlank) {
    if (!config.admin_password_set) errors.push('admin_password_required')
  } else if (tooLong(config.admin_password)) {
    errors.push('admin_password_required')
  }
  if (blankOrTooLong(config.base_dn)) errors.push('base_dn_required')

  if (blankOrTooLong(config.filter)) {
    errors.push('filter_required')
  } else if (!config.filter!.includes('USERNAME')) {
    errors.push('filter_username')
  }

  if (blankOrTooLong(config.default_email)) errors.push('default_email_required')
  if (config.default_storage == null || Number.isNaN(config.default_storage)) {
    errors.push('default_storage_required')
  }

  return errors
}
