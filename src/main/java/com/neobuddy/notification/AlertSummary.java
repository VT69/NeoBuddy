package com.neobuddy.notification;

import com.neobuddy.processing.EventType;
import com.neobuddy.subscriber.ChannelType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One entry of a student's alert history: an email that mentioned them, and how its alert fared on
 * each channel. Shown to that student only.
 *
 * @param company the company the email is about, may be null
 * @param subject the email's subject line, may be null
 * @param snippet the student's own row; null once it is older than the retention period
 * @param receivedAt when the placement inbox received the email
 * @param test true for a test alert the student asked for
 * @param deliveries one per channel the alert was queued for; empty if no channel was ready
 */
public record AlertSummary(
    UUID matchId,
    EventType eventType,
    String company,
    String subject,
    String snippet,
    Instant receivedAt,
    boolean test,
    List<Delivery> deliveries) {

  public AlertSummary {
    deliveries = List.copyOf(deliveries);
  }

  /** Omits the snippet and subject. */
  @Override
  public String toString() {
    return "AlertSummary[matchId=" + matchId + ", eventType=" + eventType + ", test=" + test + "]";
  }

  /**
   * @param channelType null if the channel was removed since
   * @param sentAt when the channel accepted it; null unless SENT
   */
  public record Delivery(
      ChannelType channelType, DeliveryStatus status, int attempts, Instant sentAt) {}
}
