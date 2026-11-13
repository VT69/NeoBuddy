package com.neobuddy.processing;

import java.util.List;
import java.util.UUID;

/**
 * Published when an email matched at least one subscriber. The notification module turns each hit
 * into deliveries.
 *
 * @param collegeId the college the email belongs to
 * @param rfcMessageId RFC 822 Message-ID of the email; the correlation id
 * @param classification type and company of the email
 * @param hits one per matched user, never empty
 */
public record MatchesFound(
    UUID collegeId, String rfcMessageId, Classification classification, List<MatchHit> hits) {

  public MatchesFound {
    hits = List.copyOf(hits);
  }
}
