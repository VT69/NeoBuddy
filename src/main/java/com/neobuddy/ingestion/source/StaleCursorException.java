package com.neobuddy.ingestion.source;

/** The provider no longer keeps history as far back as the saved cursor. */
public class StaleCursorException extends MailSourceException {

  private static final long serialVersionUID = 1L;

  public StaleCursorException() {
    super("The saved history cursor is too old");
  }
}
