package com.neobuddy.notification;

import com.neobuddy.processing.EventType;
import java.time.Instant;

/**
 * What a student is told about one email that mentions them. It holds their own row and nothing
 * else from the email.
 *
 * @param eventType shortlist, offer or other
 * @param company the company the email is about, may be null
 * @param subject the email's subject line, may be null
 * @param snippet the student's own row or line, may be null
 * @param receivedAt when the placement inbox received the email
 * @param rfcMessageId RFC 822 Message-ID of the email; the correlation id
 * @param test true for a test alert the student asked for; it is about no real email
 */
public record Alert(
    EventType eventType,
    String company,
    String subject,
    String snippet,
    Instant receivedAt,
    String rfcMessageId,
    boolean test) {

  /** An alert about a real placement email. */
  public Alert(
      EventType eventType,
      String company,
      String subject,
      String snippet,
      Instant receivedAt,
      String rfcMessageId) {
    this(eventType, company, subject, snippet, receivedAt, rfcMessageId, false);
  }

  /** Omits the snippet: it is a student's personal row. */
  @Override
  public String toString() {
    return "Alert[eventType="
        + eventType
        + ", rfcMessageId="
        + rfcMessageId
        + ", test="
        + test
        + "]";
  }
}
