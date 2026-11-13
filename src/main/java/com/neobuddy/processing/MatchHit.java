package com.neobuddy.processing;

import java.util.UUID;

/**
 * One subscriber found in one email. There is at most one hit per user per email.
 *
 * @param userId the matched subscriber
 * @param identifierId the identifier of theirs that matched
 * @param eventType the type of the email
 * @param contextSnippet the text of the single chunk that matched (the user's own row or line), at
 *     most {@link #MAX_SNIPPET_LENGTH} characters; never the surrounding rows
 */
public record MatchHit(UUID userId, UUID identifierId, EventType eventType, String contextSnippet) {

  public static final int MAX_SNIPPET_LENGTH = 300;

  /** Omits the snippet: it is a student's personal row. */
  @Override
  public String toString() {
    return "MatchHit[userId="
        + userId
        + ", identifierId="
        + identifierId
        + ", eventType="
        + eventType
        + "]";
  }
}
