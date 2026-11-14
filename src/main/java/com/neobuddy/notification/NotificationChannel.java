package com.neobuddy.notification;

import com.neobuddy.subscriber.ChannelAddress;
import com.neobuddy.subscriber.ChannelType;

/**
 * One way of getting an alert to a student. Each implementation is a bean; the dispatcher picks the
 * one whose {@link #type()} matches the channel being delivered to.
 */
public interface NotificationChannel {

  ChannelType type();

  /** Which implementation sends, for health and metrics: ntfy, ses, log, discord. */
  default String provider() {
    return type().name().toLowerCase(java.util.Locale.ROOT);
  }

  /**
   * Sends the alert. Failures are reported in the result rather than thrown, and classified:
   * retryable ones are tried again with backoff, permanent ones are given up at once.
   */
  DeliveryResult send(ChannelAddress to, Alert alert);
}
