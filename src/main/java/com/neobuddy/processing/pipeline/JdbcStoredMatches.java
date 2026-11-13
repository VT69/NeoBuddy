package com.neobuddy.processing.pipeline;

import com.neobuddy.processing.EventType;
import com.neobuddy.processing.StoredMatch;
import com.neobuddy.processing.StoredMatches;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Reads {@code match_hit}, the table {@link StoredProcessingResults} writes. */
@Component
class JdbcStoredMatches implements StoredMatches {

  private static final String SELECT =
      "select id, user_id, email_id, event_type, context_snippet from match_hit";

  private static final RowMapper<StoredMatch> MAPPER =
      (rs, row) ->
          new StoredMatch(
              rs.getObject("id", UUID.class),
              rs.getObject("user_id", UUID.class),
              rs.getString("email_id"),
              EventType.valueOf(rs.getString("event_type")),
              rs.getString("context_snippet"));

  private final JdbcTemplate jdbc;

  JdbcStoredMatches(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
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
}
