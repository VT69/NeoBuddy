package com.neobuddy.ingestion.source;

import java.time.Instant;

/**
 * What the poller needs to know about a message before deciding to ingest it.
 *
 * @param ref the message
 * @param rfcMessageId the Message-ID header, or a stand-in derived from the Gmail id if absent
 * @param fromAddress the bare sender address, lower-case, without the display name
 * @param subject the subject line, may be null
 * @param receivedAt when the inbox received it
 * @param draft true for an unsent draft, which is never ingested
 */
public record MailHeader(
    MailRef ref,
    String rfcMessageId,
    String fromAddress,
    String subject,
    Instant receivedAt,
    boolean draft) {

  /** Only the Message-ID: sender and subject stay out of logs. */
  @Override
  public String toString() {
    return "MailHeader[rfcMessageId=" + rfcMessageId + "]";
  }
}
