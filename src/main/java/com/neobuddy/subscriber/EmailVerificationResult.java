package com.neobuddy.subscriber;

/** What became of an attempt to confirm an email one-time code. */
public enum EmailVerificationResult {
  /** The code was right; the channel is verified. */
  VERIFIED,
  /** The code was wrong; the user may try again. */
  WRONG_CODE,
  /** The code was wrong too often and no longer works; a new one must be requested. */
  TOO_MANY_ATTEMPTS,
  /** The code is older than its validity; a new one must be requested. */
  EXPIRED,
  /** No code is waiting for this user. */
  NO_PENDING_CODE
}
