package com.neobuddy.ingestion.source;

import com.neobuddy.ingestion.EmailSummary;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The {@code processed_email} table. Plain SQL, because the one operation that matters here is
 * "insert unless this Message-ID is already known", which is the cross-inbox dedupe.
 */
@Repository
public class ProcessedEmailStore {

  private static final int MAX_ERROR_LENGTH = 200;

  private final JdbcTemplate jdbc;

  ProcessedEmailStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Records the email unless its Message-ID is already there.
   *
   * @return true if this call recorded it; false if another inbox, or an earlier poll, already had
   */
  public boolean insertIfAbsent(
      String rfcMessageId,
      UUID collegeId,
      UUID sourceId,
      String gmailId,
      String sender,
      String subject,
      Instant receivedAt) {
    int inserted =
        jdbc.update(
            "insert into processed_email"
                + " (rfc_message_id, college_id, source_id, gmail_id, sender, subject, received_at,"
                + " status) values (?, ?, ?, ?, ?, ?, ?, 'RECEIVED')"
                + " on conflict (rfc_message_id) do nothing",
            rfcMessageId,
            collegeId,
            sourceId,
            gmailId,
            sender,
            subject,
            OffsetDateTime.ofInstant(receivedAt, ZoneOffset.UTC));
    return inserted == 1;
  }

  /**
   * Records a synthetic email for a test alert: processed, of type OTHER, from no source inbox.
   *
   * @return its Message-ID, under the reserved {@code neobuddy.invalid} domain
   */
  public String insertTest(UUID collegeId, String subject, Instant receivedAt) {
    String rfcMessageId = "<test-" + UUID.randomUUID() + "@neobuddy.invalid>";
    jdbc.update(
        "insert into processed_email"
            + " (rfc_message_id, college_id, source_id, gmail_id, sender, subject, received_at,"
            + " event_type, status, processed_at, is_test)"
            + " values (?, ?, null, 'test', 'NeoBuddy', ?, ?, 'OTHER', 'PROCESSED', now(), true)",
        rfcMessageId,
        collegeId,
        subject,
        OffsetDateTime.ofInstant(receivedAt, ZoneOffset.UTC));
    return rfcMessageId;
  }

  public boolean exists(String rfcMessageId) {
    Integer count =
        jdbc.queryForObject(
            "select count(*) from processed_email where rfc_message_id = ?",
            Integer.class,
            rfcMessageId);
    return count != null && count > 0;
  }

  public Optional<EmailSummary> find(String rfcMessageId) {
    List<EmailSummary> found =
        jdbc.query(
            "select rfc_message_id, college_id, subject, received_at, event_type, company"
                + " from processed_email where rfc_message_id = ?",
            (rs, row) ->
                new EmailSummary(
                    rs.getString("rfc_message_id"),
                    rs.getObject("college_id", UUID.class),
                    rs.getString("subject"),
                    rs.getObject("received_at", OffsetDateTime.class).toInstant(),
                    rs.getString("event_type"),
                    rs.getString("company")),
            rfcMessageId);
    return found.stream().findFirst();
  }

  public void markProcessed(String rfcMessageId, String eventType, String company) {
    jdbc.update(
        "update processed_email set status = 'PROCESSED', event_type = ?, company = ?,"
            + " error = null, processed_at = now() where rfc_message_id = ?",
        eventType,
        company,
        rfcMessageId);
  }

  public void markFailed(String rfcMessageId, String error) {
    String bounded =
        error == null || error.length() <= MAX_ERROR_LENGTH
            ? error
            : error.substring(0, MAX_ERROR_LENGTH);
    jdbc.update(
        "update processed_email set status = 'FAILED', error = ?, processed_at = now()"
            + " where rfc_message_id = ?",
        bounded,
        rfcMessageId);
  }

  /** One email as the admin console lists it. Holds no body. */
  public record AdminRow(
      String rfcMessageId,
      UUID collegeId,
      UUID sourceId,
      String gmailId,
      String sender,
      String subject,
      Instant receivedAt,
      String eventType,
      String company,
      String status,
      String error,
      Instant processedAt,
      boolean test) {}

  private static final String ADMIN_SELECT =
      "select rfc_message_id, college_id, source_id, gmail_id, sender, subject, received_at,"
          + " event_type, company, status, error, processed_at, is_test from processed_email";

  private static AdminRow adminRow(java.sql.ResultSet rs) throws java.sql.SQLException {
    OffsetDateTime processed = rs.getObject("processed_at", OffsetDateTime.class);
    return new AdminRow(
        rs.getString("rfc_message_id"),
        rs.getObject("college_id", UUID.class),
        rs.getObject("source_id", UUID.class),
        rs.getString("gmail_id"),
        rs.getString("sender"),
        rs.getString("subject"),
        rs.getObject("received_at", OffsetDateTime.class).toInstant(),
        rs.getString("event_type"),
        rs.getString("company"),
        rs.getString("status"),
        rs.getString("error"),
        processed == null ? null : processed.toInstant(),
        rs.getBoolean("is_test"));
  }

  /**
   * Real (not test) emails, newest first.
   *
   * @param collegeId null for every college
   * @param status null for every status
   */
  public List<AdminRow> recent(UUID collegeId, String status, int offset, int limit) {
    StringBuilder sql = new StringBuilder(ADMIN_SELECT).append(" where not is_test");
    List<Object> args = new java.util.ArrayList<>();
    if (collegeId != null) {
      sql.append(" and college_id = ?");
      args.add(collegeId);
    }
    if (status != null) {
      sql.append(" and status = ?");
      args.add(status);
    }
    sql.append(" order by received_at desc, rfc_message_id limit ? offset ?");
    args.add(limit);
    args.add(offset);
    return jdbc.query(sql.toString(), (rs, n) -> adminRow(rs), args.toArray());
  }

  public Optional<AdminRow> findForAdmin(String rfcMessageId) {
    return jdbc
        .query(ADMIN_SELECT + " where rfc_message_id = ?", (rs, n) -> adminRow(rs), rfcMessageId)
        .stream()
        .findFirst();
  }

  /** Back to RECEIVED, so the result of the next processing run is what shows. */
  public void markReceived(String rfcMessageId) {
    jdbc.update(
        "update processed_email set status = 'RECEIVED', error = null, processed_at = null"
            + " where rfc_message_id = ?",
        rfcMessageId);
  }
}
