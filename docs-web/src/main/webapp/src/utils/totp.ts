// Fields the client needs to build an otpauth:// URI for a TOTP enrollment. The algorithm parameters are
// FIXED to match the server verifier's GoogleAuthenticatorConfig (HMAC-SHA1, 6 digits, 30-second period);
// declaring anything else here would make the authenticator app generate codes the server rejects.
export interface OtpAuthParams {
  secret: string
  issuer: string
  account: string
}

/**
 * Builds the otpauth://totp/ URI an authenticator app scans. The label is `issuer:account` (each part
 * percent-encoded, the colon kept as the standard delimiter); the query pins the SHA1/6-digit/30-second
 * parameters the server verifier uses.
 */
export function buildOtpAuthUri({ secret, issuer, account }: OtpAuthParams): string {
  const label = `${encodeURIComponent(issuer)}:${encodeURIComponent(account)}`
  const query = [
    `secret=${encodeURIComponent(secret)}`,
    `issuer=${encodeURIComponent(issuer)}`,
    'algorithm=SHA1',
    'digits=6',
    'period=30',
  ].join('&')
  return `otpauth://totp/${label}?${query}`
}
