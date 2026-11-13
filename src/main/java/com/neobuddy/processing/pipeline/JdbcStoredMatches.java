package com.neobuddy.processing.pipeline;

import com.neobuddy.ingestion.ProcessedEmails;
import com.neobuddy.processing.EventType;
import com.neobuddy.processing.StoredMatch;
import com.neobuddy.processing.StoredMatches;
import com.neobuddy.processing.TestMatches;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads {@code match_hit}, the table {@link StoredProcessingResults} writes, and adds test matches.
 */
@Component
class JdbcStoredMatches implements StoredMatches, TestMatches {

  private static final String SELECT =
      "select id, user_id, email_id, event_type, context_snippet, created_at, is_test"
          + " from match_hit";

  private static final RowMapper<StoredMatch> MAPPER =
      (rs, row) ->
          new StoredMatch(
              rs.getObject("id", UUID.class),
              rs.getObject("user_id", UUID.class),
              rs.getString("email_id"),
              EventType.valueOf(rs.getString("event_type")),
              rs.getString("context_snippet"),
              rs.getTimestamp("created_at").toInstant(),
              rs.getBoolean("is_test"));

  private final JdbcTemplate jdbc;
  private final ProcessedEmails emails;
  private final Clock clock;

  JdbcStoredMatches(JdbcTemplate jdbc, ProcessedEmails emails, Clock clock) {
    this.jdbc = jdbc;
    this.emails = emails;
    this.clock = clock;
  }

  @Override
  @Transactional(readOnly = true)
  public List<StoredMatch> ofEmail(String rfcMessageId) {
    return jdbc.query(SELECT + " where email_id = ?", MAPPER, rfcMessageId);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<StoredMatch> find(UUID matchId) {
    return jdbc.query(SELECT + " where id = ?", MAPPER, matchId).stream().findFirst();
  }

  @Override
  @Transactional(readOnly = true)
  public List<StoredMatch> ofUser(UUID userId, int offset, int limit) {
    return jdbc.query(
        SELECT + " where user_id = ? order by created_at desc, id limit ? offset ?",
        MAPPER,
        userId,
        Math.max(limit, 0),
        Math.max(offset, 0));
  }

  @Override
  @Transactional
  public StoredMatch createTestMatch(UUID collegeId, UUID userId, UUID identifierId) {
    Instant now = clock.instant();
    String messageId = emails.recordTestEmail(collegeId, SUBJECT, now);
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into match_hit (id, user_id, email_id, identifier_id, event_type,"
            + " context_snippet, created_at, is_test) values (?, ?, ?, ?, 'OTHER', ?, ?, true)",
        id,
        userId,
        messageId,
        identifierId,
        SNIPPET,
        Timestamp.from(now));
    return new StoredMatch(id, userId, messageId, EventType.OTHER, SNIPPET, now, true);
  }
}
