package com.neobuddy.processing.pipeline;

import com.neobuddy.ingestion.EmailIngested;
import com.neobuddy.ingestion.LoadedMail;
import com.neobuddy.ingestion.MailReader;
import com.neobuddy.ingestion.ProcessedEmails;
import com.neobuddy.processing.ProcessingService;
import com.neobuddy.shared.college.College;
import com.neobuddy.shared.college.CollegeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs the processing pipeline for every ingested email.
 *
 * <p>It runs after the ingesting transaction has committed, on its own thread. Spring Modulith
 * keeps the event until this method returns, so an email whose processing was cut short by a
 * restart is processed again; that is safe because every write downstream is idempotent.
 *
 * <p>The work happens in an inner transaction: the matches, the update of {@code processed_email}
 * and the {@code MatchesFound} event either all commit or all roll back. If it fails, the email is
 * marked FAILED with the exception's class name and nothing else, since a message could carry
 * content.
 */
@Component
class EmailIngestedListener {

  private static final Logger log = LoggerFactory.getLogger(EmailIngestedListener.class);

  private final MailReader mailReader;
  private final ProcessingService processing;
  private final ProcessedEmails processedEmails;
  private final CollegeRepository colleges;
  private final TransactionTemplate inNewTransaction;

  EmailIngestedListener(
      MailReader mailReader,
      ProcessingService processing,
      ProcessedEmails processedEmails,
      CollegeRepository colleges,
      PlatformTransactionManager transactionManager) {
    this.mailReader = mailReader;
    this.processing = processing;
    this.processedEmails = processedEmails;
    this.colleges = colleges;
    this.inNewTransaction = new TransactionTemplate(transactionManager);
    this.inNewTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  @ApplicationModuleListener
  void on(EmailIngested event) {
    try {
      inNewTransaction.executeWithoutResult(status -> process(event));
    } catch (RuntimeException | Error e) {
      log.warn(
          "Processing email {} failed: {}", event.rfcMessageId(), e.getClass().getSimpleName());
      processedEmails.markFailed(event.rfcMessageId(), e.getClass().getName());
    }
  }

  private void process(EmailIngested event) {
    College college =
        colleges
            .findById(event.collegeId())
            .orElseThrow(() -> new IllegalStateException("College no longer exists"));
    LoadedMail mail = mailReader.load(event);
    processing.process(college, mail.email(), mail::openAttachment);
  }
}
