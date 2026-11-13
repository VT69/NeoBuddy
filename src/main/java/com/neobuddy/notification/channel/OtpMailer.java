package com.neobuddy.notification.channel;

import com.neobuddy.notification.DeliveryResult;
import com.neobuddy.subscriber.ChannelAddress;
import com.neobuddy.subscriber.EmailOtpSender;
import com.neobuddy.subscriber.VerificationNotSentException;
import java.time.Duration;
import org.springframework.stereotype.Component;

/** Emails one-time codes for the subscriber module, through the active email channel. */
@Component
class OtpMailer implements EmailOtpSender {

  private final EmailTransport transport;
  private final AlertRenderer renderer;

  OtpMailer(EmailTransport transport, AlertRenderer renderer) {
    this.transport = transport;
    this.renderer = renderer;
  }

  @Override
  public void send(ChannelAddress to, String code, Duration validFor) {
    DeliveryResult result = transport.send(to, renderer.otpEmail(code, validFor));
    if (result.outcome() != DeliveryResult.Outcome.SUCCESS) {
      throw new VerificationNotSentException(result.reason());
    }
  }
}
