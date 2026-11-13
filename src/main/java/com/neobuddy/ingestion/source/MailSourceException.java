package com.neobuddy.ingestion.source;

/**
 * A call to a source inbox failed. The message names the operation and the HTTP status, never any
 * mail content, address or token.
 */
public class MailSourceException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public MailSourceException(String message) {
    super(message);
  }

  public MailSourceException(String message, Throwable cause) {
    super(message, cause);
  }
}
