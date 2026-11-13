package com.neobuddy.ingestion;

import com.neobuddy.ingestion.source.ProcessedEmailStore;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lets processing record what became of an ingested email. The {@code processed_email} table
 * belongs to ingestion; this is the only way another module reads or changes it.
 */
@Service
public class ProcessedEmails {

  private final ProcessedEmailStore store;

  ProcessedEmails(ProcessedEmailStore store) {
    this.store = store;
  }

  /** True if the email was recorded by ingestion. */
  @Transactional(readOnly = true)
  public boolean exists(String rfcMessageId) {
    return store.exists(rfcMessageId);
  }

  /** The email's headers and classification, for building an alert about it. */
  @Transactional(readOnly = true)
  public Optional<EmailSummary> find(String rfcMessageId) {
    return store.find(rfcMessageId);
  }

  /**
   * @param eventType SHORTLIST, OFFER or OTHER
   * @param company the company the email is about, may be null
   */
  @Transactional
  public void markProcessed(String rfcMessageId, String eventType, String company) {
    store.markProcessed(rfcMessageId, eventType, company);
  }

  /**
   * @param error the class name of what went wrong, nothing else: a message could carry content
   */
  @Transactional
  public void markFailed(String rfcMessageId, String error) {
    store.markFailed(rfcMessageId, error);
  }
}
