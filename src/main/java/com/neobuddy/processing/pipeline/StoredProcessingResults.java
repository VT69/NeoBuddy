package com.neobuddy.processing.pipeline;

import com.neobuddy.ingestion.ProcessedEmails;
import com.neobuddy.processing.EventType;
import com.neobuddy.processing.MatchHit;
import com.neobuddy.processing.ProcessingResult;
import com.neobuddy.processing.ProcessingResultSink;
import com.neobuddy.shared.observability.AfterCommit;
import com.neobuddy.shared.observability.CollegeTags;
import com.neobuddy.shared.observability.Metrics;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stores the outcome of processing an email: one {@code match_hit} row per matched user, and the
 * classification on the email's {@code processed_email} row.
 *
 * <p>Saving the same result twice changes nothing: {@code unique (user_id, email_id)} decides, so a
 * replayed email can never produce a second match for a user. A user or identifier that was deleted
 * while the email was being processed is skipped rather than failing everyone else's matches.
 */
@Component
class StoredProcessingResults implements ProcessingResultSink {

  private static final Logger log = LoggerFactory.getLogger(StoredProcessingResults.class);

  private final JdbcTemplate jdbc;
  private final ProcessedEmails processedEmails;

  private final MeterRegistry meters;
  private final CollegeTags collegeTags;

  StoredProcessingResults(
      JdbcTemplate jdbc,
      ProcessedEmails processedEmails,
      MeterRegistry meters,
      CollegeTags collegeTags) {
    this.jdbc = jdbc;
    this.processedEmails = processedEmails;
    this.meters = meters;
    this.collegeTags = collegeTags;
  }

  @Override
  @Transactional
  public void save(ProcessingResult result) {
    String messageId = result.rfcMessageId();
    if (!processedEmails.exists(messageId)) {
      // Processing was called for an email ingestion never recorded (a dry run, a test).
      log.warn("Email {} was not ingested; its matches are not stored", messageId);
      return;
    }
    Map<EventType, Integer> stored = new EnumMap<>(EventType.class);
    for (MatchHit hit : result.hits()) {
      int inserted =
          jdbc.update(
              "insert into match_hit (id, user_id, email_id, identifier_id, event_type,"
                  + " context_snippet)"
                  + " select ?, ?, ?, ?, ?, ?"
                  + " where exists (select 1 from app_user where id = ?)"
                  + " and exists (select 1 from identifier where id = ?)"
                  + " on conflict (user_id, email_id) do nothing",
              UUID.randomUUID(),
              hit.userId(),
              messageId,
              hit.identifierId(),
              hit.eventType().name(),
              hit.contextSnippet(),
              hit.userId(),
              hit.identifierId());
      if (inserted == 1) {
        stored.merge(hit.eventType(), 1, Integer::sum);
      }
    }
    // A replayed or reprocessed email adds only the matches that are new.
    if (!stored.isEmpty()) {
      String college = collegeTags.slugOf(result.collegeId());
      AfterCommit.run(() ->
          stored.forEach((event, count) ->
              meters
                  .counter(Metrics.MATCHES, Metrics.COLLEGE, college, "event", event.name())
                  .increment(count)));
    }
    processedEmails.markProcessed(
        messageId,
        result.classification().type().name(),
        result.classification().company().orElse(null));
  }
}
