package com.neobuddy.ingestion.gmail;

import static org.awaitility.Awaitility.await;

import com.neobuddy.ingestion.source.IngestionWriter;
import com.neobuddy.ingestion.source.SourceInbox;
import com.neobuddy.ingestion.source.SourceInboxRepository;
import com.neobuddy.shared.college.College;
import com.neobuddy.shared.college.CollegeRepository;
import com.neobuddy.shared.college.CollegeStatus;
import com.neobuddy.testsupport.DatabaseTest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base for tests that need the real application context and database with Google replaced by {@link
 * GmailTestServer}. Subclasses share one context.
 */
@DatabaseTest
public abstract class GmailBackedDatabaseTest {

  protected static final GmailTestServer GOOGLE = GmailTestServer.shared();

  @Autowired protected CollegeRepository colleges;
  @Autowired protected SourceInboxRepository sources;
  @Autowired protected IngestionWriter writer;
  @Autowired protected JdbcTemplate jdbc;

  @DynamicPropertySource
  static void pointGmailAtTheTestServer(DynamicPropertyRegistry registry) {
    registry.add("neobuddy.gmail.root-url", GOOGLE::rootUrl);
    registry.add("neobuddy.gmail.token-uri", GOOGLE::tokenUri);
    registry.add("neobuddy.gmail.client-id", () -> "test-client-id");
    registry.add("neobuddy.gmail.client-secret", () -> "test-client-secret");
    registry.add("neobuddy.gmail.retry.max-attempts", () -> "2");
    registry.add("neobuddy.gmail.retry.initial-backoff", () -> "5ms");
  }

  /** A college whose placement cell is one address plus a whole domain. */
  protected College newCollege() {
    String slug = "ing-" + UUID.randomUUID().toString().substring(0, 8);
    String domain = slug + ".example";
    return colleges.save(
        new College(
            UUID.randomUUID(),
            slug,
            "College " + slug,
            List.of(domain),
            List.of("placements@" + domain, "@cell." + domain),
            College.DEFAULT_NEO_ID_PATTERN,
            CollegeStatus.PILOT,
            Instant.parse("2026-01-01T00:00:00Z")));
  }

  protected static String domainOf(College college) {
    return college.getAllowedEmailDomains().get(0);
  }

  protected static String placementCell(College college) {
    return "Placement Cell <placements@" + domainOf(college) + ">";
  }

  /** A connected inbox whose history cursor is already at {@code historyId}. */
  protected SourceInbox newSource(
      College college, GmailTestServer.Account account, long historyId) {
    SourceInbox inbox = newUnpolledSource(college, account);
    writer.advanceCursor(inbox.getId(), historyId);
    return sources.findById(inbox.getId()).orElseThrow();
  }

  /** A connected inbox that has never been polled. */
  protected SourceInbox newUnpolledSource(College college, GmailTestServer.Account account) {
    return sources.save(
        new SourceInbox(
            college.getId(),
            "inbox-" + UUID.randomUUID().toString().substring(0, 8) + "@" + domainOf(college),
            account.refreshToken()));
  }

  /** A history id no other test uses. */
  protected static long newHistoryId() {
    return ThreadLocalRandom.current().nextLong(1_000_000, Long.MAX_VALUE / 4);
  }

  protected static String newMessageId() {
    return "<" + UUID.randomUUID() + "@mail.example>";
  }

  protected String statusOf(String rfcMessageId) {
    List<String> status =
        jdbc.queryForList(
            "select status from processed_email where rfc_message_id = ?",
            String.class,
            rfcMessageId);
    return status.isEmpty() ? null : status.get(0);
  }

  protected int count(String table, String column, Object value) {
    return jdbc.queryForObject(
        "select count(*) from " + table + " where " + column + " = ?", Integer.class, value);
  }

  /** Waits until the asynchronous pipeline has finished with this email, one way or the other. */
  protected String awaitProcessed(String rfcMessageId) {
    await()
        .atMost(Duration.ofSeconds(60))
        .pollInterval(Duration.ofMillis(100))
        .until(() -> {
          String status = statusOf(rfcMessageId);
          return status != null && !status.equals("RECEIVED");
        });
    return statusOf(rfcMessageId);
  }
}
