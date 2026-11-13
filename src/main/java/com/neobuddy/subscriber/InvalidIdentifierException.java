package com.neobuddy.subscriber;

/** An identifier breaks a rule. Carries the reason, never the value. */
public class InvalidIdentifierException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public enum Reason {
    /** Nothing was entered. */
    BLANK,
    /** Not the college's NeoID format, or not something that can be found in an email. */
    WRONG_FORMAT,
    /** A college email that is not on one of the college's domains. */
    NOT_A_COLLEGE_EMAIL,
    /** The user already has an identifier of this type; remove it first. */
    ALREADY_HAS_ONE_OF_THIS_TYPE
  }

  private final Reason reason;

  public InvalidIdentifierException(Reason reason) {
    super("Invalid identifier: " + reason);
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }
}
