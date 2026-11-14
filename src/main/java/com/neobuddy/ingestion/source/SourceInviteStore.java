package com.neobuddy.ingestion.source;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** The {@code source_invite} table. Tokens are stored only as their SHA-256 hash. */
@Repository
public class SourceInviteStore {

  /** One invite as stored. */
  public record Row(
      UUID id,
      UUID collegeId,
      Instant createdAt,
      Instant expiresAt,
      Instant usedAt,
      UUID sourceId) {}

  private static final String SELECT =
      "select id, college_id, created_at, expires_at, used_at, source_id from source_invite";

  private static final RowMapper<Row> MAPPER =
      (rs, n) ->
          new Row(
              rs.getObject("id", UUID.class),
              rs.getObject("college_id", UUID.class),
              instant(rs.getObject("created_at", OffsetDateTime.class)),
              instant(rs.getObject("expires_at", OffsetDateTime.class)),
              instant(rs.getObject("used_at", OffsetDateTime.class)),
              rs.getObject("source_id", UUID.class));

  private final JdbcTemplate jdbc;

  SourceInviteStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void insert(
      UUID id, UUID collegeId, String tokenHash, UUID createdBy, Instant now, Instant expiresAt) {
    jdbc.update(
        "insert into source_invite (id, college_id, token_hash, created_by, created_at,"
            + " expires_at) values (?, ?, ?, ?, ?, ?)",
        id,
        collegeId,
        tokenHash,
        createdBy,
        timestamp(now),
        timestamp(expiresAt));
  }

  public Optional<Row> findByTokenHash(String tokenHash) {
    return jdbc.query(SELECT + " where token_hash = ?", MAPPER, tokenHash).stream().findFirst();
  }

  /**
   * Marks the invite used, if it is still unused and unexpired. The condition and the update are
   * one statement, so two callbacks racing for the same link cannot both succeed.
   *
   * @return the invite's college, if this call consumed it
   */
  public Optional<UUID> consume(String tokenHash, Instant now) {
    return jdbc
        .query(
            "update source_invite set used_at = ? where token_hash = ? and used_at is null"
                + " and expires_at > ? returning college_id",
            (rs, n) -> rs.getObject("college_id", UUID.class),
            timestamp(now),
            tokenHash,
            timestamp(now))
        .stream()
        .findFirst();
  }

  public void recordSource(String tokenHash, UUID sourceId) {
    jdbc.update("update source_invite set source_id = ? where token_hash = ?", sourceId, tokenHash);
  }

  /** The most recent invites, newest first. */
  public List<Row> recent(int limit) {
    return jdbc.query(SELECT + " order by created_at desc limit ?", MAPPER, limit);
  }

  private static OffsetDateTime timestamp(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }

  private static Instant instant(OffsetDateTime value) {
    return value == null ? null : value.toInstant();
  }
}
