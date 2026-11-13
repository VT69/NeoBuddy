package com.neobuddy.notification.channel;

import com.neobuddy.notification.Alert;
import com.neobuddy.notification.DeliveryResult;
import com.neobuddy.notification.NotificationChannel;
import com.neobuddy.subscriber.ChannelAddress;
import com.neobuddy.subscriber.ChannelType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The email channel for development without SES: sends nothing and logs that it would have. The log
 * line names the email by its Message-ID only; never the recipient, the text or a code.
 */
class LoggingEmailChannel implements NotificationChannel, EmailTransport {

  private static final Logger log = LoggerFactory.getLogger(LoggingEmailChannel.class);

  @Override
  public ChannelType type() {
    return ChannelType.EMAIL;
  }

  @Override
  public DeliveryResult send(ChannelAddress to, Alert alert) {
    log.info(
        "Email alert for {} ({}) not sent: neobuddy.channels.email.provider is 'log'",
        alert.rfcMessageId(),
        alert.eventType());
    return DeliveryResult.success();
  }

  @Override
  public DeliveryResult send(ChannelAddress to, EmailContent content) {
    log.info("An email was not sent: neobuddy.channels.email.provider is 'log'");
    return DeliveryResult.success();
  }
}
