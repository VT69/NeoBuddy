package com.neobuddy.processing;

import java.util.List;
import java.util.UUID;

/**
 * Everything processing learned from one email. Holds no email or attachment content apart from
 * each matched user's own snippet.
 *
 * @param collegeId the college the email belongs to
 * @param rfcMessageId RFC 822 Message-ID of the email
 * @param classification type and company
 * @param hits one per matched user, possibly empty
 * @param extraction how the attachments fared
 */
public record ProcessingResult(
    UUID collegeId,
    String rfcMessageId,
    Classification classification,
    List<MatchHit> hits,
    ExtractionSummary extraction) {

  public ProcessingResult {
    hits = List.copyOf(hits);
  }

  /**
   * Counts of attachment outcomes, so a caller can tell "no match" from "could not read the file".
   *
   * @param attachments attachments on the email
   * @param extracted read successfully
   * @param skipped too large or of an unsupported type
   * @param failed corrupt, encrypted, too slow, or could not be fetched
   */
  public record ExtractionSummary(int attachments, int extracted, int skipped, int failed) {}
}
