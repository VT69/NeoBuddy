package com.neobuddy;

import static com.neobuddy.ingestion.gmail.GmailTestServer.attachmentPart;
import static com.neobuddy.ingestion.gmail.GmailTestServer.multipart;
import static com.neobuddy.ingestion.gmail.GmailTestServer.textPart;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.neobuddy.ingestion.EmailIngested;
import com.neobuddy.ingestion.gmail.GmailTestServer.Account;
import com.neobuddy.ingestion.gmail.GmailTestServer.HistoryRecord;
import com.neobuddy.ingestion.gmail.GmailTestServer.TestMessage;
import com.neobuddy.ingestion.source.SourceInbox;
import com.neobuddy.ingestion.source.SourceSync;
import com.neobuddy.notification.delivery.DeliveryDatabaseTest;
import com.neobuddy.shared.college.College;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * NeoBuddy end to end, with only Google and ntfy replaced: a poll finds a shortlist email, the
 * pipeline matches the student in its spreadsheet, the match is queued for her push channel and the
 * dispatcher posts it to ntfy. Exactly once, however often the email is processed.
 */
class NotificationPipelineTests extends DeliveryDatabaseTest {

  @Autowired private SourceSync sync;

  private College college;
  private Account account;
  private SourceInbox source;
  private long start;
  private Student student;
  private String topic;

  @BeforeEach
  void aStudentWithAPushChannelAndAConnectedInbox() {
    college = newCollege();
    account = GOOGLE.newAccount();
    start = newHistoryId();
    source = newSource(college, account, start);
    student = newStudent(college);
    topic = ntfyChannel(student);
  }

  @Test
  void anIngestedShortlistBecomesExactlyOnePush() {
    String messageId = newMessageId();
    TestMessage message = shortlistEmail(messageId);

    assertThat(sync.sync(source.getId()).ingested()).isEqualTo(1);
    assertThat(awaitProcessed(messageId)).isEqualTo("PROCESSED");

    // Queued in the transaction that stored the match.
    assertThat(deliveriesFor(messageId))
        .singleElement()
        .satisfies(row -> assertThat(row).containsEntry("status", "PENDING"));
    assertThat(ntfyPosts(topic)).isEmpty();

    dispatchUntilIdle();

    assertThat(ntfyPosts(topic))
        .singleElement()
        .satisfies(request -> {
          assertThat(request.getHeader("Title")).isEqualTo("Shortlisted: Example Corp");
          assertThat(request.getHeader("Priority")).isEqualTo("default");
          assertThat(request.getHeader("Click"))
              .isEqualTo(
                  "https://mail.google.com/mail/u/0/#search/rfc822msgid:"
                      + messageId.substring(1, messageId.length() - 1).replace("@", "%40"));
          // Her own row, and nobody else's.
          assertThat(request.getBodyAsString())
              .contains(student.neoId() + " | Tara Target | 10:30")
              .contains("Received 13 Nov 2026, 4:05 PM IST")
              .doesNotContain("Asha")
              .doesNotContain("Ravi");
        });
    assertThat(deliveriesFor(messageId))
        .singleElement()
        .satisfies(
            row -> assertThat(row).containsEntry("status", "SENT").containsEntry("attempts", 1));

    // A redelivery after a restart: the same email goes through the pipeline again.
    jdbc.update(
        "update processed_email set status = 'RECEIVED' where rfc_message_id = ?", messageId);
    transactions.executeWithoutResult(tx ->
        publisher.publishEvent(
            new EmailIngested(college.getId(), source.getId(), messageId, message.id())));
    assertThat(awaitProcessed(messageId)).isEqualTo("PROCESSED");
    makeAllDue();
    dispatchUntilIdle();

    assertThat(deliveriesFor(messageId)).hasSize(1);
    assertThat(ntfyPosts(topic)).hasSize(1);
  }

  @Test
  void anEmailSeenInTwoInboxesIsPushedOnce() {
    String messageId = newMessageId();
    shortlistEmail(messageId);
    Account secondAccount = GOOGLE.newAccount();
    long secondStart = newHistoryId();
    SourceInbox second = newSource(college, secondAccount, secondStart);
    TestMessage copy =
        TestMessage.withId(TestMessage.newId())
            .from(placementCell(college))
            .subject("Example Corp - Shortlist")
            .messageId(messageId)
            .text(student.neoId() + " - Tara Target");
    secondAccount.message(copy);
    secondAccount.history(
        secondStart,
        null,
        List.of(HistoryRecord.of(secondStart + 1, copy.id())),
        null,
        secondStart + 1);

    sync.sync(source.getId());
    sync.sync(second.getId());
    awaitProcessed(messageId);
    dispatchUntilIdle();

    assertThat(ntfyPosts(topic)).hasSize(1);
  }

  @Test
  void aStudentWithoutAVerifiedChannelIsMatchedButNotAlerted() {
    Student quiet = newStudent(college);
    String messageId = newMessageId();
    TestMessage message =
        TestMessage.withId(TestMessage.newId())
            .from(placementCell(college))
            .subject("Example Corp - Offer letters")
            .messageId(messageId)
            .text(quiet.neoId() + " - Quiet Student");
    account.message(message);
    account.history(
        start, null, List.of(HistoryRecord.of(start + 1, message.id())), null, start + 1);

    sync.sync(source.getId());

    assertThat(awaitProcessed(messageId)).isEqualTo("PROCESSED");
    assertThat(count("match_hit", "email_id", messageId)).isEqualTo(1);
    assertThat(deliveriesFor(messageId)).isEmpty();
    assertThat(dispatchUntilIdle()).isZero();
    List<LoggedRequest> all =
        NTFY.findAll(
            com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor(
                com.github.tomakehurst.wiremock.client.WireMock.anyUrl()));
    assertThat(all).isEmpty();
  }

  private List<Map<String, Object>> deliveriesFor(String messageId) {
    return jdbc.queryForList(
        "select d.* from delivery d join match_hit m on m.id = d.match_id where m.email_id = ?",
        messageId);
  }

  /** Puts a shortlist email with a spreadsheet into the first inbox. */
  private TestMessage shortlistEmail(String messageId) {
    byte[] sheet = shortlist(student.neoId());
    TestMessage message =
        TestMessage.withId(TestMessage.newId())
            .from(placementCell(college))
            .subject("Example Corp - Shortlist")
            .messageId(messageId)
            .receivedAt(Instant.parse("2026-11-13T10:35:00Z"))
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
    return message;
  }

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
