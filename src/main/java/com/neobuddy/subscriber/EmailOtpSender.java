package com.neobuddy.subscriber;

import java.time.Duration;

/**
 * Port for emailing a one-time code to an address being verified. The notification module
 * implements it, since it owns outbound email.
 */
public interface EmailOtpSender {

  /**
   * @param to the address being verified
   * @param code the code as the user must type it; never log or store it
   * @param validFor how long the code works, to tell the user
   * @throws VerificationNotSentException if the email could not be handed over
   */
  void send(ChannelAddress to, String code, Duration validFor);
}
