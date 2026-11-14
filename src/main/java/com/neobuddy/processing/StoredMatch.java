package com.neobuddy.processing;

import java.time.Instant;
import java.util.UUID;

/**
 * A match as stored in {@code match_hit}.
 *
 * @param id the match's id
 * @param userId the matched subscriber
 * @param rfcMessageId RFC 822 Message-ID of the email
 * @param eventType the type of the email
 * @param contextSnippet the user's own row or line, may be null; never anyone else's. Cleared after
 *     the retention period.
 * @param createdAt when the match was stored
 * @param test true for the synthetic match of a test alert; it never counts in statistics
 */
public record StoredMatch(
    UUID id,
    UUID userId,
    String rfcMessageId,
    EventType eventType,
    String contextSnippet,
    Instant createdAt,
    boolean test) {

  /** Omits the snippet: it is a student's personal row. */
  @Override
  public String toString() {
    return "StoredMatch[id="
        + id
        + ", userId="
        + userId
        + ", eventType="
        + eventType
        + ", test="
        + test
        + "]";
  }
}
