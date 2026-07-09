import { describe, it, expect } from 'vitest'
import { validateLdapConfig } from './ldapValidation'
import type { LdapConfig } from '../api/ldap'

// A fully-valid enabled config — mirrors the backend's required-when-enabled set
// (AppResource.configLdap). Individual tests blank one field to assert its error.
function validEnabled(): LdapConfig {
  return {
    enabled: true,
    host: 'ldap.example.com',
    port: 389,
    usessl: false,
    admin_dn: 'cn=admin,dc=example,dc=com',
    admin_password: 'secret',
    base_dn: 'dc=example,dc=com',
    filter: '(&(objectClass=user)(sAMAccountName=USERNAME))',
    default_email: 'example.com',
    default_storage: 104857600,
  }
}

describe('validateLdapConfig', () => {
  it('returns no errors when LDAP is disabled, regardless of other fields', () => {
    expect(validateLdapConfig({ enabled: false })).toEqual([])
    expect(validateLdapConfig({ enabled: false, host: '', port: undefined, filter: 'nope' })).toEqual([])
  })

  it('accepts a fully-valid enabled config', () => {
    expect(validateLdapConfig(validEnabled())).toEqual([])
  })

  it('requires host when enabled', () => {
    expect(validateLdapConfig({ ...validEnabled(), host: undefined })).toContain('host_required')
  })

  it('treats a whitespace-only host as missing', () => {
    expect(validateLdapConfig({ ...validEnabled(), host: '   ' })).toContain('host_required')
  })

  it('requires port when enabled', () => {
    expect(validateLdapConfig({ ...validEnabled(), port: undefined })).toContain('port_required')
  })

  it('treats NaN port as missing', () => {
    expect(validateLdapConfig({ ...validEnabled(), port: NaN })).toContain('port_required')
  })

  it('accepts port 0 as present (only null/NaN are missing)', () => {
    expect(validateLdapConfig({ ...validEnabled(), port: 0 })).not.toContain('port_required')
  })

  it('requires admin_dn when enabled', () => {
    expect(validateLdapConfig({ ...validEnabled(), admin_dn: '' })).toContain('admin_dn_required')
  })

  it('requires admin_password when enabled', () => {
    expect(validateLdapConfig({ ...validEnabled(), admin_password: '' })).toContain('admin_password_required')
  })

  it('requires base_dn when enabled', () => {
    expect(validateLdapConfig({ ...validEnabled(), base_dn: '  ' })).toContain('base_dn_required')
  })

  it('requires default_email when enabled', () => {
    expect(validateLdapConfig({ ...validEnabled(), default_email: '' })).toContain('default_email_required')
  })

  it('requires default_storage when enabled', () => {
    expect(validateLdapConfig({ ...validEnabled(), default_storage: undefined })).toContain('default_storage_required')
  })

  it('treats NaN default_storage as missing', () => {
    expect(validateLdapConfig({ ...validEnabled(), default_storage: NaN })).toContain('default_storage_required')
  })

  it('flags a blank filter as filter_required (not filter_username)', () => {
    const errors = validateLdapConfig({ ...validEnabled(), filter: '' })
    expect(errors).toContain('filter_required')
    expect(errors).not.toContain('filter_username')
  })

  it('flags a present filter that lacks the USERNAME placeholder', () => {
    const errors = validateLdapConfig({ ...validEnabled(), filter: '(uid=foo)' })
    expect(errors).toContain('filter_username')
    expect(errors).not.toContain('filter_required')
  })

  it('rejects a string field longer than 250 characters', () => {
    expect(validateLdapConfig({ ...validEnabled(), admin_dn: 'x'.repeat(251) })).toContain('admin_dn_required')
  })

  it('reports every missing field at once for a bare enabled config', () => {
    const errors = validateLdapConfig({ enabled: true })
    expect(errors).toEqual(
      expect.arrayContaining([
        'host_required',
        'port_required',
        'admin_dn_required',
        'admin_password_required',
        'base_dn_required',
        'filter_required',
        'default_email_required',
        'default_storage_required',
      ]),
    )
  })
})
