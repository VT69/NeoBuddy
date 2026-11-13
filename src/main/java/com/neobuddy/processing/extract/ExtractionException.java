package com.neobuddy.processing.extract;

/**
 * A file could not be turned into chunks: corrupt, encrypted, unreadable, or too slow. Messages
 * must never contain file content.
 */
public class ExtractionException extends Exception {

  private static final long serialVersionUID = 1L;

  public ExtractionException(String message) {
    super(message);
  }

  public ExtractionException(String message, Throwable cause) {
    super(message, cause);
  }
}
