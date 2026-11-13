package com.neobuddy.subscriber;

/** The verification email could not be sent. The message never contains the address or code. */
public class VerificationNotSentException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public VerificationNotSentException(String reason) {
    super("Verification email not sent: " + reason);
  }
}
