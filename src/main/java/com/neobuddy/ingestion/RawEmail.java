package com.neobuddy.ingestion;

import java.time.Instant;
import java.util.List;

/**
 * One email as fetched from a source inbox, before any processing. Part of the ingestion module's
 * public API: ingestion produces it and processing consumes it.
 *
 * @param rfcMessageId RFC 822 Message-ID header; the cross-inbox dedupe key and correlation id
 * @param gmailId id of the message in the source inbox it was fetched from
 * @param from sender address
 * @param subject subject line, may be null
 * @param receivedAt when the source inbox received it
 * @param textBody plain-text part, may be null
 * @param htmlBody HTML part, may be null
 * @param attachments what the sender declared about each attachment; content is fetched lazily
 */
public record RawEmail(
    String rfcMessageId,
    String gmailId,
    String from,
    String subject,
    Instant receivedAt,
    String textBody,
    String htmlBody,
    List<AttachmentRef> attachments) {

  public RawEmail {
    attachments = attachments == null ? List.of() : List.copyOf(attachments);
  }

  /** Only the Message-ID: subject and bodies must never reach a log. */
  @Override
  public String toString() {
    return "RawEmail[rfcMessageId=" + rfcMessageId + ", attachments=" + attachments.size() + "]";
  }
}
