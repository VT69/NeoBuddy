package com.neobuddy.ingestion;

import com.neobuddy.ingestion.EmailNotReprocessableException.Reason;
import com.neobuddy.ingestion.source.ProcessedEmailStore;
import com.neobuddy.ingestion.source.ProcessedEmailStore.AdminRow;
import com.neobuddy.ingestion.source.SourceInboxRepository;
import com.neobuddy.ingestion.source.SourceStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ingested emails for the admin console: headers and outcome only, never a body, and running the
 * pipeline again for one email.
 */
@Service
public class EmailAdmin {

  /**
   * One ingested email.
   *
   * @param status RECEIVED, PROCESSED or FAILED
   * @param error the class name of what failed, for FAILED
   */
  public record EmailRow(
      String rfcMessageId,
      UUID collegeId,
      String sender,
      String subject,
      Instant receivedAt,
      String eventType,
      String company,
      String status,
      String error,
      Instant processedAt) {}

  public static final int MAX_PAGE_SIZE = 200;

  private static final Logger log = LoggerFactory.getLogger(EmailAdmin.class);

  private final ProcessedEmailStore store;
  private final SourceInboxRepository sources;
  private final ApplicationEventPublisher events;

  EmailAdmin(
      ProcessedEmailStore store, SourceInboxRepository sources, ApplicationEventPublisher events) {
    this.store = store;
    this.sources = sources;
    this.events = events;
  }

  /**
   * Real emails, newest first; test-alert emails are left out.
   *
   * @param collegeId null for every college
   * @param status RECEIVED, PROCESSED, FAILED or null for all
   */
  @Transactional(readOnly = true)
  public List<EmailRow> recent(UUID collegeId, String status, int page, int size) {
    int limit = Math.clamp(size, 1, MAX_PAGE_SIZE);
    return store.recent(collegeId, status, Math.max(page, 0) * limit, limit).stream()
        .map(EmailAdmin::row)
        .toList();
  }

  /**
   * Runs the pipeline for the email again: it is fetched from its source inbox, classified and
   * matched as if it had just arrived. Matches and alerts already stored are kept and never
   * duplicated; a student who matches now but did not before (an identifier added since, a fixed
   * classifier rule) gets alerted, and so does a channel verified since the first run.
   *
   * @throws EmailNotReprocessableException if it cannot be fetched again
   */
  @Transactional
  public void reprocess(String rfcMessageId) {
    AdminRow email =
        store
            .findForAdmin(rfcMessageId)
            .orElseThrow(() -> new EmailNotReprocessableException(Reason.NOT_FOUND));
    if (email.test() || email.sourceId() == null) {
      throw new EmailNotReprocessableException(Reason.TEST_EMAIL);
    }
    boolean readable =
        sources
            .findById(email.sourceId())
            .filter(source -> source.getStatus() == SourceStatus.ACTIVE)
            .isPresent();
    if (!readable) {
      throw new EmailNotReprocessableException(Reason.SOURCE_UNAVAILABLE);
    }
    store.markReceived(rfcMessageId);
    // The same event ingestion publishes, stored by the event registry with this transaction.
    events.publishEvent(
        new EmailIngested(email.collegeId(), email.sourceId(), rfcMessageId, email.gmailId()));
    log.info("Email {} queued for processing again by an admin", rfcMessageId);
  }

  private static EmailRow row(AdminRow r) {
    return new EmailRow(
        r.rfcMessageId(),
        r.collegeId(),
        r.sender(),
        r.subject(),
        r.receivedAt(),
        r.eventType(),
        r.company(),
        r.status(),
        r.error(),
        r.processedAt());
  }
}
