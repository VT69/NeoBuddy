package com.neobuddy.ingestion.source;

/** The inbox's refresh token was revoked or has expired; the inbox must be authorised again. */
public class SourceAuthExpiredException extends MailSourceException {

  private static final long serialVersionUID = 1L;

  public SourceAuthExpiredException() {
    super("The source inbox's authorisation is no longer valid");
  }
}
