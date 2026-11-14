package com.neobuddy.web.pages;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** The {@code college_request} table: who asked for which college. */
@Repository
class CollegeRequestStore {

  private final JdbcTemplate jdbc;

  CollegeRequestStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * @param contactEmail may be null: leaving an address is optional
   */
  void save(String collegeName, String emailDomain, String contactEmail) {
    jdbc.update(
        "insert into college_request (id, college_name, email_domain, contact_email)"
            + " values (?, ?, ?, ?)",
        UUID.randomUUID(),
        collegeName,
        emailDomain,
        contactEmail);
  }
}
