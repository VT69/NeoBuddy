package com.neobuddy.subscriber;

/** A sign-in that may not become a NeoBuddy account. Carries the reason, never the email. */
public class RegistrationRejectedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public enum Reason {
    /** Google has not verified the email address. */
    EMAIL_NOT_VERIFIED,
    /** No onboarded college uses this email domain. */
    COLLEGE_NOT_SUPPORTED,
    /** The college exists but is paused. */
    COLLEGE_PAUSED,
    /** The Google Workspace domain is not one of the college's domains. */
    HOSTED_DOMAIN_MISMATCH,
    /** The email already belongs to an account tied to a different Google identity. */
    EMAIL_ALREADY_REGISTERED
  }

  private final Reason reason;

  public RegistrationRejectedException(Reason reason) {
    super("Registration rejected: " + reason);
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }
}
