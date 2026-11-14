package com.neobuddy;

import static com.neobuddy.ingestion.gmail.GmailTestServer.textPart;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.neobuddy.ingestion.EmailAdmin;
import com.neobuddy.ingestion.EmailAdmin.EmailRow;
import com.neobuddy.ingestion.EmailNotReprocessableException;
import com.neobuddy.ingestion.EmailNotReprocessableException.Reason;
import com.neobuddy.ingestion.gmail.GmailBackedDatabaseTest;
import com.neobuddy.ingestion.gmail.GmailTestServer.Account;
import com.neobuddy.ingestion.gmail.GmailTestServer.HistoryRecord;
import com.neobuddy.ingestion.gmail.GmailTestServer.TestMessage;
import com.neobuddy.ingestion.source.SourceInbox;
import com.neobuddy.ingestion.source.SourceSync;
import com.neobuddy.processing.StoredMatches;
import com.neobuddy.shared.college.College;
import com.neobuddy.subscriber.IdentifierType;
import com.neobuddy.subscriber.OidcClaims;
import com.neobuddy.subscriber.SubscriberService;
import com.neobuddy.subscriber.SubscriberView;
import com.neobuddy.subscriber.TestColleges;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Reprocessing an email by Message-ID from the admin console: the email is fetched and processed
 * again; matches already stored stay single, new ones are added and counted once.
 */
class EmailReprocessTests extends GmailBackedDatabaseTest {

  @Autowired private SourceSync sync;
  @Autowired private EmailAdmin emails;
  @Autowired private StoredMatches matches;
  @Autowired private SubscriberService subscribers;
  @Autowired private MeterRegistry meters;

  private College college;
  private Account account;
  private SourceInbox source;
  private String firstNeoId;
  private String laterNeoId;
  private String messageId;

  @BeforeEach
  void anEmailListingTwoNeoIdsOfWhichOneIsSubscribed() {
    college = newCollege();
    account = GOOGLE.newAccount();
    long start = newHistoryId();
    source = newSource(college, account, start);
    firstNeoId = TestColleges.neoId();
    laterNeoId = TestColleges.neoId();
    subscribe(firstNeoId);

    messageId = newMessageId();
    TestMessage message =
        TestMessage.withId(TestMessage.newId())
            .from(placementCell(college))
            .subject("Example Corp - Shortlist")
            .messageId(messageId)
            .payload(
                textPart(
                    "text/plain",
                    "Shortlisted:\n" + firstNeoId + " Slot 1\n" + laterNeoId + " Slot 2\n",
                    UTF_8));
    account.message(message);
    account.history(
        start, null, List.of(HistoryRecord.of(start + 1, message.id())), null, start + 1);
    assertThat(sync.sync(source.getId()).ingested()).isEqualTo(1);
    awaitMatches(1);
  }

  private void subscribe(String neoId) {
    SubscriberView view =
        subscribers.registerFromOidc(
            new OidcClaims(
                "sub-" + UUID.randomUUID(),
                TestColleges.studentEmail(college),
                true,
                "Tara Target",
                null));
    subscribers.addIdentifier(view.id(), IdentifierType.NEO_ID, neoId);
  }

  private void awaitMatches(int expected) {
    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(() -> {
          assertThat(status()).isEqualTo("PROCESSED");
          assertThat(matches.countsByEmail(List.of(messageId))).containsEntry(messageId, expected);
        });
  }

  private String status() {
    return jdbc.queryForObject(
        "select status from processed_email where rfc_message_id = ?", String.class, messageId);
  }

  private double matchesCounted() {
    Counter counter =
        meters
            .find("matches")
            .tag("college", college.getSlug())
            .tag("event", "SHORTLIST")
            .counter();
    return counter == null ? 0 : counter.count();
  }

  @Test
  void theRecentEmailListShowsHeadersAndOutcomeOnly() {
    List<EmailRow> rows = emails.recent(college.getId(), null, 0, 50);

    assertThat(rows)
        .singleElement()
        .satisfies(row -> {
          assertThat(row.rfcMessageId()).isEqualTo(messageId);
          assertThat(row.subject()).isEqualTo("Example Corp - Shortlist");
          assertThat(row.eventType()).isEqualTo("SHORTLIST");
          assertThat(row.status()).isEqualTo("PROCESSED");
          assertThat(row.toString()).doesNotContain(firstNeoId);
        });
    assertThat(emails.recent(college.getId(), "FAILED", 0, 50)).isEmpty();
  }

  @Test
  void reprocessingAddsOnlyTheMatchesThatAreNew() {
    assertThat(matchesCounted()).isEqualTo(1);
    // The second student signs up after the email arrived.
    subscribe(laterNeoId);

    emails.reprocess(messageId);

    awaitMatches(2);
    assertThat(matchesCounted()).as("only the new match is counted").isEqualTo(2);

    // Again: nothing new, nothing doubled.
    emails.reprocess(messageId);
    awaitMatches(2);
    assertThat(matchesCounted()).isEqualTo(2);
  }

  @Test
  void anUnknownEmailCannotBeReprocessed() {
    assertThatThrownBy(() -> emails.reprocess("<unknown-" + UUID.randomUUID() + "@mail.example>"))
        .isInstanceOfSatisfying(
            EmailNotReprocessableException.class,
            e -> assertThat(e.reason()).isEqualTo(Reason.NOT_FOUND));
  }

  @Test
  void anEmailOfADisconnectedSourceCannotBeReprocessed() {
    jdbc.update("update mail_source set status = 'DISABLED' where id = ?", source.getId());

    assertThatThrownBy(() -> emails.reprocess(messageId))
        .isInstanceOfSatisfying(
            EmailNotReprocessableException.class,
            e -> assertThat(e.reason()).isEqualTo(Reason.SOURCE_UNAVAILABLE));
    assertThat(status()).as("left as it was").isEqualTo("PROCESSED");
  }

  @Test
  void ingestedEmailsAreCountedPerCollege() {
    Counter ingested = meters.find("emails.ingested").tag("college", college.getSlug()).counter();

    assertThat(ingested).isNotNull();
    assertThat(ingested.count()).isEqualTo(1);
  }
}
