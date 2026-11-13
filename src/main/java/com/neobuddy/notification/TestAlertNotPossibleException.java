package com.neobuddy.notification;

/** A test alert cannot be sent. Carries the reason only. */
public class TestAlertNotPossibleException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public enum Reason {
    /** The user has no channel with this id. */
    CHANNEL_NOT_FOUND,
    /** The channel is unverified, switched off, or of a kind nothing sends to yet. */
    CHANNEL_NOT_READY,
    /** A match needs one of the user's identifiers, and they have none. */
    NO_IDENTIFIER
  }

  private final Reason reason;

  public TestAlertNotPossibleException(Reason reason) {
    super("Test alert not possible: " + reason);
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }
}
