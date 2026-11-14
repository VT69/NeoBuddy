package com.neobuddy.notification.delivery;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The {@code delivery} table: the outbox. Plain SQL, because its two important operations are an
 * insert that ignores duplicates and a claim with {@code FOR UPDATE SKIP LOCKED}.
 */
@Repository
class DeliveryStore {

  private static final int MAX_ERROR_LENGTH = 200;
  private static final String UNFINISHED = "status in ('PENDING', 'RETRY')";

  private final JdbcTemplate jdbc;

  DeliveryStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Queues an alert for one channel unless it is queued already.
   *
   * @return true if this call created the delivery
   */
  boolean insertIfAbsent(UUID matchId, UUID channelId, Instant dueAt) {
    return jdbc.update(
            "insert into delivery (id, match_id, channel_id, status, attempts, next_attempt_at)"
                + " values (?, ?, ?, 'PENDING', 0, ?)"
                + " on conflict (match_id, channel_id) do nothing",
            UUID.randomUUID(),
            matchId,
            channelId,
            timestamp(dueAt))
        == 1;
  }

  /**
   * Claims up to {@code limit} due deliveries, oldest first, for the caller alone.
   *
   * <p>{@code SKIP LOCKED} makes a dispatcher that runs at the same moment pass over the rows this
   * one is taking instead of waiting for them. The claim then pushes {@code next_attempt_at} into
   * the future, so the rows stay invisible to everyone else after this short transaction commits
   * and while the alerts are being sent, without holding a transaction open during the sending. A
   * dispatcher that selected a row just before that commit re-checks the due time when it gets the
   * lock (PostgreSQL re-evaluates the condition) and drops it.
   */
  @Transactional
  List<ClaimedDelivery> claimDue(Instant now, int limit, Duration claimTimeout) {
    List<ClaimedDelivery> claimed =
        jdbc.query(
            "select id, match_id, channel_id, attempts from delivery"
                + " where "
                + UNFINISHED
                + " and next_attempt_at <= ?"
                + " order by next_attempt_at"
                + " limit ?"
                + " for update skip locked",
            (rs, row) ->
                new ClaimedDelivery(
                    rs.getObject("id", UUID.class),
                    rs.getObject("match_id", UUID.class),
                    rs.getObject("channel_id", UUID.class),
                    rs.getInt("attempts")),
            timestamp(now),
            limit);
    if (!claimed.isEmpty()) {
      jdbc.update(
          "update delivery set next_attempt_at = ? where id = any (?)",
          timestamp(now.plus(claimTimeout)),
          claimed.stream().map(ClaimedDelivery::id).toArray(UUID[]::new));
    }
    return claimed;
  }

  /** Makes claimed deliveries due again at once, for a dispatcher that ran out of time. */
  void release(List<UUID> ids, Instant now) {
    if (!ids.isEmpty()) {
      jdbc.update(
          "update delivery set next_attempt_at = ? where id = any (?) and " + UNFINISHED,
          timestamp(now),
          ids.toArray(UUID[]::new));
    }
  }

  void markSent(UUID id, int attempts, Instant now) {
    jdbc.update(
        "update delivery set status = 'SENT', attempts = ?, sent_at = ?, last_error = null"
            + " where id = ? and "
            + UNFINISHED,
        attempts,
        timestamp(now),
        id);
  }

  void markRetry(UUID id, int attempts, Instant nextAttemptAt, String error) {
    jdbc.update(
        "update delivery set status = 'RETRY', attempts = ?, next_attempt_at = ?, last_error = ?"
            + " where id = ? and "
            + UNFINISHED,
        attempts,
        timestamp(nextAttemptAt),
        bounded(error),
        id);
  }

  /**
   * @return false if the delivery was already finished or is gone, so nothing changed
   */
  boolean markDead(UUID id, int attempts, String error) {
    return jdbc.update(
            "update delivery set status = 'DEAD', attempts = ?, last_error = ?"
                + " where id = ? and "
                + UNFINISHED,
            attempts,
            bounded(error),
            id)
        == 1;
  }

  /** One delivery as the alert history shows it. */
  record Row(UUID id, UUID matchId, UUID channelId, String status, int attempts, Instant sentAt) {}

  /** Every delivery of the given matches. */
  List<Row> ofMatches(List<UUID> matchIds) {
    if (matchIds.isEmpty()) {
      return List.of();
    }
    return jdbc.query(
        "select id, match_id, channel_id, status, attempts, sent_at from delivery"
            + " where match_id = any (?)",
        (rs, row) ->
            new Row(
                rs.getObject("id", UUID.class),
                rs.getObject("match_id", UUID.class),
                rs.getObject("channel_id", UUID.class),
                rs.getString("status"),
                rs.getInt("attempts"),
                rs.getTimestamp("sent_at") == null ? null : rs.getTimestamp("sent_at").toInstant()),
        (Object) matchIds.toArray(UUID[]::new));
  }

  /** The id of the delivery of this match to this channel, if there is one. */
  Optional<UUID> idOf(UUID matchId, UUID channelId) {
    return jdbc
        .query(
            "select id from delivery where match_id = ? and channel_id = ?",
            (rs, row) -> rs.getObject("id", UUID.class),
            matchId,
            channelId)
        .stream()
        .findFirst();
  }

  private static OffsetDateTime timestamp(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }

  private static String bounded(String error) {
    return error == null || error.length() <= MAX_ERROR_LENGTH
        ? error
        : error.substring(0, MAX_ERROR_LENGTH);
  }
}
