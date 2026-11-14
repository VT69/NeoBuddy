package com.neobuddy.web.support;

import com.neobuddy.subscriber.EmailVerificationResult;

/** The email one-time code the user typed was not accepted. */
public class EmailCodeRejectedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final EmailVerificationResult result;

  public EmailCodeRejectedException(EmailVerificationResult result) {
    super("Email code rejected: " + result);
    this.result = result;
  }

  public EmailVerificationResult result() {
    return result;
  }
}
