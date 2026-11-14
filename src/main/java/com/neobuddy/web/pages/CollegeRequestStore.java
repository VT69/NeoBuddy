package com.neobuddy.web.pages;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** The {@code college_request} table: who asked for which college. */
@Repository
public class CollegeRequestStore {

  /** One request, as the admin console lists it. */
  public record Request(
      UUID id, String collegeName, String emailDomain, String contactEmail, Instant createdAt) {}

  private final JdbcTemplate jdbc;

  CollegeRequestStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * @param contactEmail may be null: leaving an address is optional
   */
  public void save(String collegeName, String emailDomain, String contactEmail) {
    jdbc.update(
        "insert into college_request (id, college_name, email_domain, contact_email)"
            + " values (?, ?, ?, ?)",
        UUID.randomUUID(),
        collegeName,
        emailDomain,
        contactEmail);
  }

  /** Newest first. */
  public List<Request> recent(int limit) {
    return jdbc.query(
        "select id, college_name, email_domain, contact_email, created_at from college_request"
            + " order by created_at desc limit ?",
        (rs, n) ->
            new Request(
                rs.getObject("id", UUID.class),
                rs.getString("college_name"),
                rs.getString("email_domain"),
                rs.getString("contact_email"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant()),
        limit);
  }

  public Optional<Request> find(UUID id) {
    return jdbc
        .query(
            "select id, college_name, email_domain, contact_email, created_at from college_request"
                + " where id = ?",
            (rs, n) ->
                new Request(
                    rs.getObject("id", UUID.class),
                    rs.getString("college_name"),
                    rs.getString("email_domain"),
                    rs.getString("contact_email"),
                    rs.getObject("created_at", OffsetDateTime.class).toInstant()),
            id)
        .stream()
        .findFirst();
  }

  public boolean delete(UUID id) {
    return jdbc.update("delete from college_request where id = ?", id) == 1;
  }

  /**
   * Removes the requests a new college answers.
   *
   * @return how many were removed
   */
  public int deleteForDomains(List<String> domains) {
    if (domains.isEmpty()) {
      return 0;
    }
    return jdbc.update(
        "delete from college_request where lower(email_domain) = any (?)",
        (Object) domains.stream().map(d -> d.toLowerCase(Locale.ROOT)).toArray(String[]::new));
  }
}
