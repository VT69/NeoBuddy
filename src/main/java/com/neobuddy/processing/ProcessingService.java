package com.neobuddy.processing;

import com.neobuddy.ingestion.AttachmentRef;
import com.neobuddy.ingestion.RawEmail;
import com.neobuddy.processing.ProcessingResult.ExtractionSummary;
import com.neobuddy.processing.classify.EmailClassifier;
import com.neobuddy.processing.extract.ExtractionResult;
import com.neobuddy.processing.extract.ExtractorRegistry;
import com.neobuddy.processing.extract.TextChunk;
import com.neobuddy.processing.match.Matcher;
import com.neobuddy.shared.college.College;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * The processing pipeline for one email: extract chunks from the subject, body and attachments,
 * classify the email, match the chunks against the college's identifiers, hand the result to the
 * {@link ProcessingResultSink} and publish {@link MatchesFound}.
 *
 * <p>It knows nothing about Gmail: the email arrives as a {@link RawEmail} and attachment content
 * through an {@link AttachmentSource}. Chunks live only for the duration of the call; nothing but
 * each matched user's own snippet leaves it. Log lines carry the Message-ID and filenames only.
 */
@Service
public class ProcessingService {

  private static final Logger log = LoggerFactory.getLogger(ProcessingService.class);

  static final String SUBJECT_SOURCE = "subject";

  private final ExtractorRegistry extractors;
  private final EmailClassifier classifier;
  private final Matcher matcher;
  private final ApplicationEventPublisher events;
  private final Optional<ProcessingResultSink> sink;

  public ProcessingService(
      ExtractorRegistry extractors,
      EmailClassifier classifier,
      Matcher matcher,
      ApplicationEventPublisher events,
      Optional<ProcessingResultSink> sink) {
    this.extractors = extractors;
    this.classifier = classifier;
    this.matcher = matcher;
    this.events = events;
    this.sink = sink;
  }

  /**
   * Processes one email of one college.
   *
   * @param college the college the email was received for; matching never leaves it
   * @param email the email
   * @param attachments opens the content of the email's attachments
   * @return the classification and the matches; a bad attachment never fails the email
   */
  public ProcessingResult process(College college, RawEmail email, AttachmentSource attachments) {
    List<TextChunk> chunks = new ArrayList<>();
    if (email.subject() != null && !email.subject().isBlank()) {
      chunks.add(new TextChunk(SUBJECT_SOURCE, SUBJECT_SOURCE, email.subject().strip()));
    }
    chunks.addAll(extractors.extractBody(email.textBody(), email.htmlBody()));
    ExtractionSummary summary = extractAttachments(email, attachments, chunks);

    Classification classification = classifier.classify(college.getSlug(), email);
    List<MatchHit> hits = matcher.match(college.getId(), classification.type(), chunks);
    ProcessingResult result =
        new ProcessingResult(college.getId(), email.rfcMessageId(), classification, hits, summary);

    sink.ifPresent(s -> s.save(result));
    if (!hits.isEmpty()) {
      events.publishEvent(
          new MatchesFound(college.getId(), email.rfcMessageId(), classification, hits));
    }
    log.info(
        "Processed email {}: type {}, {} matches, attachments {} extracted, {} skipped, {} failed",
        email.rfcMessageId(),
        classification.type(),
        hits.size(),
        summary.extracted(),
        summary.skipped(),
        summary.failed());
    return result;
  }

  private ExtractionSummary extractAttachments(
      RawEmail email, AttachmentSource attachments, List<TextChunk> chunks) {
    int extracted = 0;
    int skipped = 0;
    int failed = 0;
    for (AttachmentRef attachment : email.attachments()) {
      try (InputStream content = attachments.open(attachment)) {
        ExtractionResult result = extractors.extract(attachment, content);
        switch (result.status()) {
          case EXTRACTED -> {
            extracted++;
            chunks.addAll(result.chunks());
          }
          case SKIPPED_TOO_LARGE, SKIPPED_UNSUPPORTED -> skipped++;
          case FAILED -> failed++;
        }
      } catch (IOException | RuntimeException e) {
        failed++;
        log.warn(
            "Could not fetch attachment '{}' of email {}",
            attachment.filenameForLog(),
            email.rfcMessageId());
      }
    }
    return new ExtractionSummary(email.attachments().size(), extracted, skipped, failed);
  }
}
