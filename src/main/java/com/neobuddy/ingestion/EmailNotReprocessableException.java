package com.neobuddy.ingestion;

/** An email cannot be processed again. Carries no content. */
public class EmailNotReprocessableException extends RuntimeException {

  public enum Reason {
    /** No email with that Message-ID was ingested. */
    NOT_FOUND,
    /** A synthetic test-alert email; it has no source to fetch it from. */
    TEST_EMAIL,
    /** Its source inbox is disconnected or lost its authorisation. */
    SOURCE_UNAVAILABLE
  }

  private final Reason reason;

  public EmailNotReprocessableException(Reason reason) {
    super(reason.name());
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }
}
