package com.neobuddy.ingestion;

import java.util.UUID;

/**
 * Published once for every new allow-listed email, in the same transaction that records it in
 * {@code processed_email}. Spring Modulith stores the event until its listener has finished, so an
 * email that was recorded is processed even if the application stops in between.
 *
 * <p>It carries references only. The email itself is fetched with {@link MailReader} when it is
 * processed, because a stored event must never hold a body or an attachment.
 *
 * @param collegeId the college the source inbox belongs to
 * @param sourceId the source inbox the email was first seen in
 * @param rfcMessageId RFC 822 Message-ID; the cross-inbox dedupe key and correlation id
 * @param gmailId id of the message in that source inbox
 */
public record EmailIngested(UUID collegeId, UUID sourceId, String rfcMessageId, String gmailId) {}
