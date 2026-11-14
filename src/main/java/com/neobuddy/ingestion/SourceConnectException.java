package com.neobuddy.ingestion;

/** Connecting a source inbox through an invite link did not work. Carries no token or address. */
public class SourceConnectException extends RuntimeException {

  public enum Reason {
    /** Unknown, already used or expired invite link. */
    INVITE_UNUSABLE,
    /** The web OAuth client is not configured on this server. */
    NOT_CONFIGURED,
    /** The person declined at Google, or the answer did not belong to this browser's request. */
    DENIED,
    /** Google refused to exchange the code. */
    GOOGLE_REFUSED,
    /** Google could not be reached. */
    GOOGLE_UNREACHABLE,
    /** Read access to Gmail was not ticked on Google's consent screen. */
    SCOPE_NOT_GRANTED,
    /** Google returned no refresh token. */
    NO_REFRESH_TOKEN,
    /** The inbox is already a source of another college. */
    BELONGS_TO_ANOTHER_COLLEGE
  }

  private final Reason reason;

  public SourceConnectException(Reason reason) {
    super(reason.name());
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }
}
