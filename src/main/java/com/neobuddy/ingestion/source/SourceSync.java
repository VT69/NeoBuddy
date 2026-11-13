package com.neobuddy.ingestion.source;

import com.neobuddy.shared.college.College;
import com.neobuddy.shared.college.CollegeRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * One poll of one source inbox: read what is new since the cursor, keep mail from the college's
 * allow-listed senders, record each new Message-ID once, and move the cursor.
 *
 * <p>Nothing here is transactional; the writes go through {@link IngestionWriter}. Log lines carry
 * source ids and counts only.
 */
@Service
public class SourceSync {

  /** How a poll of one source ended. */
  public enum Outcome {
    /** Read the changes since the cursor. */
    SYNCED,
    /** First poll: the cursor was set to "now" and nothing was read. */
    STARTED_FROM_NOW,
    /** The cursor was too old; recent mail was listed instead and deduped. */
    RECOVERED_FROM_STALE_CURSOR,
    /** Google rejected the refresh token; the source is now AUTH_EXPIRED. */
    AUTH_EXPIRED,
    /** A request failed; the cursor did not move and the next poll retries. */
    FAILED,
    /** The source is not active, or its college is paused. */
    SKIPPED
  }

  /**
   * @param seen messages the inbox reported
   * @param ingested of those, how many were from an allow-listed sender and new
   */
  public record Result(Outcome outcome, int seen, int ingested) {}

  private static final Logger log = LoggerFactory.getLogger(SourceSync.class);

  private final SourceInboxRepository sources;
  private final CollegeRepository colleges;
  private final MailSourceFactory factory;
  private final IngestionWriter writer;
  private final IngestionProperties properties;

  SourceSync(
      SourceInboxRepository sources,
      CollegeRepository colleges,
      MailSourceFactory factory,
      IngestionWriter writer,
      IngestionProperties properties) {
    this.sources = sources;
    this.colleges = colleges;
    this.factory = factory;
    this.writer = writer;
    this.properties = properties;
  }

  /** Polls every active source. One failing source never stops the others. */
  public void syncAll() {
    for (UUID sourceId : sources.findIdsByStatus(SourceStatus.ACTIVE)) {
      sync(sourceId);
    }
  }

  /** Polls one source. Never throws: whatever goes wrong is reported in the result. */
  public Result sync(UUID sourceId) {
    try {
      return syncOrThrow(sourceId);
    } catch (RuntimeException e) {
      // For example a refresh token that does not decrypt with the configured key. Only the
      // type is logged: the message of an unexpected exception could carry anything.
      log.error("Polling source {} failed unexpectedly: {}", sourceId, e.getClass().getName());
      return new Result(Outcome.FAILED, 0, 0);
    }
  }

  private Result syncOrThrow(UUID sourceId) {
    Optional<SourceInbox> found = sources.findById(sourceId);
    if (found.isEmpty() || found.get().getStatus() != SourceStatus.ACTIVE) {
      return new Result(Outcome.SKIPPED, 0, 0);
    }
    SourceInbox source = found.get();
    Optional<College> college =
        colleges.findById(source.getCollegeId()).filter(College::acceptsSubscribers);
    if (college.isEmpty()) {
      return new Result(Outcome.SKIPPED, 0, 0);
    }
    Result result;
    try {
      result = poll(source, college.get(), factory.open(source));
    } catch (SourceAuthExpiredException e) {
      writer.markAuthExpired(sourceId);
      log.warn("Source {} lost its authorisation and was set to AUTH_EXPIRED", sourceId);
      return new Result(Outcome.AUTH_EXPIRED, 0, 0);
    } catch (MailSourceException e) {
      log.warn("Polling source {} failed, will retry next cycle: {}", sourceId, e.getMessage());
      return new Result(Outcome.FAILED, 0, 0);
    }
    if (result.seen() > 0) {
      log.info(
          "Source {}: {} new messages, {} ingested ({})",
          sourceId,
          result.seen(),
          result.ingested(),
          result.outcome());
    }
    return result;
  }

  private Result poll(SourceInbox source, College college, MailSource mail) {
    if (source.getLastHistoryId() == null) {
      // A newly connected inbox starts from now; mail that was already there is not ingested.
      writer.advanceCursor(source.getId(), mail.currentHistoryId());
      return new Result(Outcome.STARTED_FROM_NOW, 0, 0);
    }
    int seen = 0;
    int ingested = 0;
    SyncCursor cursor = SyncCursor.startingAt(source.getLastHistoryId());
    try {
      ChangePage page;
      do {
        page = mail.changesSince(cursor);
        seen += page.messages().size();
        ingested += record(source, college, mail, page.messages());
        // Only now, with the page committed, may the cursor move.
        writer.advanceCursor(source.getId(), page.checkpointHistoryId());
        cursor = new SyncCursor(cursor.historyId(), page.nextPageToken());
      } while (!page.isLast());
      return new Result(Outcome.SYNCED, seen, ingested);
    } catch (StaleCursorException e) {
      return recover(source, college, mail);
    }
  }

  /**
   * Gmail keeps history for a limited time. When the cursor has fallen out of it, list the recent
   * mail instead; whatever was already ingested is dropped by the Message-ID dedupe.
   */
  private Result recover(SourceInbox source, College college, MailSource mail) {
    // Taken before listing, so mail that arrives during the listing is read again next time.
    long resumeFrom = mail.currentHistoryId();
    List<MailRef> recent = mail.recentMessages(properties.fallbackWindowDays());
    int ingested = 0;
    int batch = properties.fallbackBatchSize();
    for (int from = 0; from < recent.size(); from += batch) {
      ingested +=
          record(
              source, college, mail, recent.subList(from, Math.min(from + batch, recent.size())));
    }
    writer.advanceCursor(source.getId(), resumeFrom);
    log.warn(
        "Source {} had a stale history cursor; listed the last {} days instead",
        source.getId(),
        properties.fallbackWindowDays());
    return new Result(Outcome.RECOVERED_FROM_STALE_CURSOR, recent.size(), ingested);
  }

  /** Fetches headers outside any transaction, then records the wanted ones in one. */
  private int record(SourceInbox source, College college, MailSource mail, List<MailRef> refs) {
    List<MailHeader> wanted = new ArrayList<>();
    for (MailRef ref : refs) {
      mail.header(ref)
          .filter(header -> !header.draft())
          .filter(
              header -> SenderAllowlist.allows(college.getSenderAllowlist(), header.fromAddress()))
          .ifPresent(wanted::add);
    }
    return wanted.isEmpty() ? 0 : writer.recordPage(source.getId(), college.getId(), wanted);
  }
}
