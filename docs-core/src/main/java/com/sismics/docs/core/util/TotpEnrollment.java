package com.sismics.docs.core.util;

/**
 * The result of beginning a TOTP enrollment: the freshly generated pending secret and the account
 * label (the enrolling user's username). The edge combines these with the display-only issuer to build
 * the {@code otpauth://} URI fields.
 *
 * @param secret  The pending TOTP secret seed
 * @param account The account label (username) for the {@code otpauth://} URI
 */
public record TotpEnrollment(String secret, String account) {
}
