package com.neobuddy;

import static com.neobuddy.ingestion.gmail.GmailTestServer.attachmentPart;
import static com.neobuddy.ingestion.gmail.GmailTestServer.inlineAttachmentPart;
import static com.neobuddy.ingestion.gmail.GmailTestServer.multipart;
import static com.neobuddy.ingestion.gmail.GmailTestServer.textPart;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.neobuddy.ingestion.EmailIngested;
import com.neobuddy.ingestion.gmail.GmailBackedDatabaseTest;
import com.neobuddy.ingestion.gmail.GmailTestServer.Account;
import com.neobuddy.ingestion.gmail.GmailTestServer.HistoryRecord;
import com.neobuddy.ingestion.gmail.GmailTestServer.TestMessage;
import com.neobuddy.ingestion.source.SourceInbox;
import com.neobuddy.ingestion.source.SourceSync;
import com.neobuddy.shared.college.College;
import com.neobuddy.subscriber.IdentifierType;
import com.neobuddy.subscriber.OidcClaims;
import com.neobuddy.subscriber.SubscriberService;
import com.neobuddy.subscriber.SubscriberView;
import com.neobuddy.subscriber.SubscriberView.IdentifierView;
import com.neobuddy.subscriber.TestColleges;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The whole path of T6, with only Google replaced: a poll finds a new email in the inbox, records
 * it, publishes {@code EmailIngested}; the listener fetches the message and its spreadsheet, runs
 * the processing pipeline, and the matches land in {@code match_hit}. All mail is synthetic.
 */
class IngestionPipelineTests extends GmailBackedDatabaseTest {

  @Autowired private SourceSync sync;
  @Autowired private SubscriberService subscribers;
  @Autowired private ApplicationEventPublisher publisher;
  @Autowired private TransactionTemplate transactions;

  private College college;
  private Account account;
  private SourceInbox source;
  private long start;
  private SubscriberView student;
  private IdentifierView identifier;
  private String neoId;

  @BeforeEach
  void aSubscribedStudentAndAConnectedInbox() {
    college = newCollege();
    account = GOOGLE.newAccount();
    start = newHistoryId();
    source = newSource(college, account, start);
    student =
        subscribers.registerFromOidc(
            new OidcClaims(
                "sub-" + UUID.randomUUID(),
                "tara@" + domainOf(college),
                true,
                "Tara Target",
                null));
    neoId = TestColleges.neoId();
    identifier = subscribers.addIdentifier(student.id(), IdentifierType.NEO_ID, neoId);
  }

  @Test
  void anEmailWithASpreadsheetBecomesAMatchHitRow() {
    String messageId = newMessageId();
    byte[] sheet = shortlist(neoId);
    TestMessage message =
        TestMessage.withId(TestMessage.newId())
            .from(placementCell(college))
            .subject("Example Corp - Shortlist")
            .messageId(messageId)
            .payload(
                multipart(
                    "mixed",
                    List.of(
                        textPart("text/plain", "Dear students,\nthe shortlist is attached.", UTF_8),
                        attachmentPart(
                            "shortlist.xlsx",
                            "application/octet-stream",
                            "att-sheet",
                            sheet.length))));
    account.message(message);
    account.attachment(message.id(), "att-sheet", sheet);
    account.history(
        start, null, List.of(HistoryRecord.of(start + 1, message.id())), null, start + 1);

    assertThat(sync.sync(source.getId()).ingested()).isEqualTo(1);

    assertThat(awaitProcessed(messageId)).isEqualTo("PROCESSED");
    assertThat(jdbc.queryForList("select * from match_hit where email_id = ?", messageId))
        .singleElement()
        .satisfies(hit -> {
          assertThat(hit)
              .containsEntry("user_id", student.id())
              .containsEntry("identifier_id", identifier.id())
              .containsEntry("event_type", "SHORTLIST")
              // Her own row and nothing else from the sheet.
              .containsEntry("context_snippet", neoId + " | Tara Target | 10:30");
        });
    Map<String, Object> email =
        jdbc.queryForMap("select * from processed_email where rfc_message_id = ?", messageId);
    assertThat(email)
        .containsEntry("status", "PROCESSED")
        .containsEntry("event_type", "SHORTLIST")
        .containsEntry("company", "Example Corp")
        .containsEntry("error", null);
    assertThat(email.get("processed_at")).isNotNull();
  }

  @Test
  void nothingAboutTheEmailsContentIsKeptInTheEventRegistry() {
    String messageId = newMessageId();
    TestMessage message = bodyOnly(messageId, "Shortlisted: " + neoId + " - Tara Target");
    account.history(
        start, null, List.of(HistoryRecord.of(start + 1, message.id())), null, start + 1);

    sync.sync(source.getId());
    awaitProcessed(messageId);

    List<Map<String, Object>> stored =
        jdbc.queryForList(
            "select serialized_event, completion_date from event_publication"
                + " where serialized_event like ?",
            "%" + messageId + "%");
    assertThat(stored).isNotEmpty();
    assertThat(stored)
        .allSatisfy(row ->
            assertThat((String) row.get("serialized_event"))
                .contains(message.id())
                .doesNotContain(neoId)
                .doesNotContain("Tara")
                .doesNotContain("Shortlisted"));
    // The listener finished, so the publication is marked complete.
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(() ->
            assertThat(
                    jdbc.queryForObject(
                        "select count(*) from event_publication where serialized_event like ?"
                            + " and completion_date is null",
                        Integer.class,
                        "%" + messageId + "%"))
                .isZero());
  }

  @Test
  void processingTheSameEmailAgainNeverCreatesASecondMatch() {
    String messageId = newMessageId();
    TestMessage message = bodyOnly(messageId, neoId + " - Tara Target");
    account.history(
        start, null, List.of(HistoryRecord.of(start + 1, message.id())), null, start + 1);
    sync.sync(source.getId());
    awaitProcessed(messageId);
    assertThat(count("match_hit", "email_id", messageId)).isEqualTo(1);
    UUID firstHit =
        jdbc.queryForObject("select id from match_hit where email_id = ?", UUID.class, messageId);

    // What a redelivery after a restart does: the event again, for an email already processed.
    jdbc.update(
        "update processed_email set status = 'RECEIVED' where rfc_message_id = ?", messageId);
    transactions.executeWithoutResult(tx ->
        publisher.publishEvent(
            new EmailIngested(college.getId(), source.getId(), messageId, message.id())));

    assertThat(awaitProcessed(messageId)).isEqualTo("PROCESSED");
    assertThat(
            jdbc.queryForList("select id from match_hit where email_id = ?", UUID.class, messageId))
        .containsExactly(firstHit);
  }

  @Test
  void anInlineAttachmentIsReadWithoutASecondRequest() {
    String messageId = newMessageId();
    byte[] csv = ("NeoID,Name\n" + neoId + ",Tara Target\n").getBytes(UTF_8);
    TestMessage message =
        TestMessage.withId(TestMessage.newId())
            .from(placementCell(college))
            .subject("Example Corp - Offer letters")
            .messageId(messageId)
            .payload(
                multipart(
                    "mixed",
                    List.of(
                        textPart("text/plain", "Congratulations.", UTF_8),
                        inlineAttachmentPart("offers.csv", "text/csv", csv))));
    account.message(message);
    account.history(
        start, null, List.of(HistoryRecord.of(start + 1, message.id())), null, start + 1);

    sync.sync(source.getId());

    assertThat(awaitProcessed(messageId)).isEqualTo("PROCESSED");
    assertThat(jdbc.queryForMap("select * from match_hit where email_id = ?", messageId))
        .containsEntry("event_type", "OFFER")
        .containsEntry("context_snippet", neoId + " | Tara Target");
    assertThat(account.requestsTo("/messages/" + message.id() + "/attachments/anything")).isZero();
  }

  @Test
  void anEmailThatMatchesNobodyIsProcessedWithNoHits() {
    String messageId = newMessageId();
    TestMessage message = bodyOnly(messageId, "The pre-placement talk is at 5 pm.");
    account.message(message.subject("Example Corp pre-placement talk"));
    account.history(
        start, null, List.of(HistoryRecord.of(start + 1, message.id())), null, start + 1);

    sync.sync(source.getId());

    assertThat(awaitProcessed(messageId)).isEqualTo("PROCESSED");
    assertThat(count("match_hit", "email_id", messageId)).isZero();
    assertThat(
            jdbc.queryForMap(
                "select event_type from processed_email where rfc_message_id = ?", messageId))
        .containsEntry("event_type", "OTHER");
  }

  @Test
  void aFailureIsRecordedAsFailedWithTheExceptionClassOnly() {
    String messageId = newMessageId();
    TestMessage message = bodyOnly(messageId, neoId + " - Tara Target");
    account.fullMessageFails(message.id(), 500);
    account.history(
        start, null, List.of(HistoryRecord.of(start + 1, message.id())), null, start + 1);

    sync.sync(source.getId());

    assertThat(awaitProcessed(messageId)).isEqualTo("FAILED");
    Map<String, Object> email =
        jdbc.queryForMap("select * from processed_email where rfc_message_id = ?", messageId);
    assertThat(email)
        .containsEntry("error", "com.neobuddy.ingestion.source.MailSourceException")
        .containsEntry("event_type", null);
    assertThat(email.get("processed_at")).isNotNull();
    assertThat(count("match_hit", "email_id", messageId)).isZero();
  }

  @Test
  void aBadAttachmentDoesNotStopTheRestOfTheEmail() {
    String messageId = newMessageId();
    TestMessage message =
        TestMessage.withId(TestMessage.newId())
            .from(placementCell(college))
            .subject("Example Corp - Shortlist")
            .messageId(messageId)
            .payload(
                multipart(
                    "mixed",
                    List.of(
                        textPart("text/plain", neoId + " - Tara Target", UTF_8),
                        // Its content is never stubbed: fetching it answers 404.
                        attachmentPart("missing.xlsx", "application/octet-stream", "gone", 2048),
                        inlineAttachmentPart(
                            "broken.xlsx", "application/octet-stream", new byte[] {1, 2}))));
    account.message(message);
    account.history(
        start, null, List.of(HistoryRecord.of(start + 1, message.id())), null, start + 1);

    sync.sync(source.getId());

    assertThat(awaitProcessed(messageId)).isEqualTo("PROCESSED");
    assertThat(count("match_hit", "email_id", messageId)).isEqualTo(1);
  }

  private TestMessage bodyOnly(String messageId, String text) {
    TestMessage message =
        TestMessage.withId(TestMessage.newId())
            .from(placementCell(college))
            .subject("Example Corp - Shortlist")
            .messageId(messageId)
            .text(text);
    account.message(message);
    return message;
  }

  /** A small synthetic shortlist with the given NeoID in its third row. */
  private static byte[] shortlist(String neoId) {
    try (XSSFWorkbook workbook = new XSSFWorkbook()) {
      Sheet sheet = workbook.createSheet("Shortlist");
      String[][] rows = {
        {"NeoID", "Name", "Slot"},
        {"Z9X8C7V6", "Asha Example", "10:00"},
        {neoId, "Tara Target", "10:30"},
        {"Q1W2E3R4", "Ravi Sample", "11:00"},
      };
      for (int r = 0; r < rows.length; r++) {
        Row row = sheet.createRow(r);
        for (int c = 0; c < rows[r].length; c++) {
          row.createCell(c).setCellValue(rows[r][c]);
        }
      }
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      workbook.write(out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
