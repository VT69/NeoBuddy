package com.neobuddy.ingestion.source;

import com.neobuddy.ingestion.EmailIngested;
import com.neobuddy.ingestion.SourceAuthExpired;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The database writes of a poll, each in its own transaction.
 *
 * <p>Recording a page and advancing the cursor are separate transactions on purpose: the cursor
 * moves only after the page is safely committed. If the application stops in between, the next poll
 * reads the same page again and the Message-ID dedupe makes that harmless.
 */
@Service
public class IngestionWriter {

  private final ProcessedEmailStore emails;
  private final SourceInboxRepository sources;
  private final ApplicationEventPublisher events;
  private final Clock clock;

  IngestionWriter(
      ProcessedEmailStore emails,
      SourceInboxRepository sources,
      ApplicationEventPublisher events,
      Clock clock) {
    this.emails = emails;
    this.sources = sources;
    this.events = events;
    this.clock = clock;
  }

  /**
   * Records each email whose Message-ID is new and publishes {@link EmailIngested} for it, all in
   * one transaction: an email is either recorded with its event, or not at all.
   *
   * @return how many were new
   */
  @Transactional
  public int recordPage(UUID sourceId, UUID collegeId, List<MailHeader> page) {
    int recorded = 0;
    for (MailHeader mail : page) {
      boolean isNew =
          emails.insertIfAbsent(
              mail.rfcMessageId(),
              collegeId,
              sourceId,
              mail.ref().gmailId(),
              mail.fromAddress(),
              mail.subject(),
              mail.receivedAt());
      if (isNew) {
        recorded++;
        events.publishEvent(
            new EmailIngested(collegeId, sourceId, mail.rfcMessageId(), mail.ref().gmailId()));
      }
    }
    return recorded;
  }

  @Transactional
  public void advanceCursor(UUID sourceId, long historyId) {
    sources.findById(sourceId).ifPresent(source -> source.advanceTo(historyId, clock.instant()));
  }

  @Transactional
  public void markAuthExpired(UUID sourceId) {
    sources
        .findById(sourceId)
        .filter(source -> source.getStatus() == SourceStatus.ACTIVE)
        .ifPresent(source -> {
          source.markAuthExpired();
          events.publishEvent(new SourceAuthExpired(source.getId(), source.getCollegeId()));
        });
  }
}
