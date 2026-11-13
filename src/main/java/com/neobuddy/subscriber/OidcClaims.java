package com.neobuddy.subscriber;

/**
 * What Google asserts about a signed-in user, taken from a verified ID token.
 *
 * @param subject the {@code sub} claim: Google's stable id for the account
 * @param email the {@code email} claim
 * @param emailVerified the {@code email_verified} claim
 * @param name the {@code name} claim, may be null
 * @param hostedDomain the {@code hd} claim: the Google Workspace domain, null for personal accounts
 */
public record OidcClaims(
    String subject, String email, boolean emailVerified, String name, String hostedDomain) {

  /** Only says whether the claims are complete; personal data stays out of logs. */
  @Override
  public String toString() {
    return "OidcClaims[emailVerified=" + emailVerified + "]";
  }
}
