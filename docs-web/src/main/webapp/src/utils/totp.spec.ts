import { describe, it, expect } from 'vitest'
import { buildOtpAuthUri } from './totp'

describe('buildOtpAuthUri', () => {
  it('builds an otpauth URI with the fixed SHA1/6-digit/30-second parameters the server verifier uses', () => {
    const uri = buildOtpAuthUri({ secret: 'JBSWY3DPEHPK3PXP', issuer: 'Teedy', account: 'alice' })
    expect(uri).toBe(
      'otpauth://totp/Teedy:alice?secret=JBSWY3DPEHPK3PXP&issuer=Teedy&algorithm=SHA1&digits=6&period=30',
    )
    // Explicitly assert each parameter the authenticator app must match against the server config.
    expect(uri).toContain('algorithm=SHA1')
    expect(uri).toContain('digits=6')
    expect(uri).toContain('period=30')
  })

  it('percent-encodes issuer and account so spaces and separators cannot break the URI', () => {
    const uri = buildOtpAuthUri({ secret: 'ABC DEF', issuer: 'My Docs', account: 'a@b.com' })
    expect(uri).toBe(
      'otpauth://totp/My%20Docs:a%40b.com?secret=ABC%20DEF&issuer=My%20Docs&algorithm=SHA1&digits=6&period=30',
    )
  })
})
