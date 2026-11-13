package com.neobuddy.ingestion;

import java.time.Instant;
import java.util.UUID;

/**
 * What is kept about an ingested email: headers and the classification, never a body.
 *
 * @param rfcMessageId RFC 822 Message-ID; the correlation id
 * @param collegeId the college the email belongs to
 * @param subject the subject line, may be null
 * @param receivedAt when the source inbox received it
 * @param eventType SHORTLIST, OFFER or OTHER; null until the email has been processed
 * @param company the company the email is about, may be null
 */
public record EmailSummary(
    String rfcMessageId,
    UUID collegeId,
    String subject,
    Instant receivedAt,
    String eventType,
    String company) {}
