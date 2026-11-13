package com.neobuddy.ingestion.source;

import static org.assertj.core.api.Assertions.assertThat;

import com.neobuddy.ingestion.EmailIngested;
import com.neobuddy.ingestion.SourceAuthExpired;
import com.neobuddy.ingestion.gmail.GmailBackedDatabaseTest;
import com.neobuddy.ingestion.gmail.GmailTestServer.Account;
import com.neobuddy.ingestion.gmail.GmailTestServer.HistoryRecord;
import com.neobuddy.ingestion.gmail.GmailTestServer.TestMessage;
import com.neobuddy.ingestion.source.SourceSync.Outcome;
import com.neobuddy.ingestion.source.SourceSync.Result;
import com.neobuddy.shared.college.College;
import com.neobuddy.shared.college.CollegeStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

/** The poller against a WireMock Gmail and the real database. */
@RecordApplicationEvents
class SourceSyncTests extends GmailBackedDatabaseTest {

  @Autowired private SourceSync sync;
  @Autowired private ApplicationEvents events;

  private College college;
  private Account account;
  private long start;

  @BeforeEach
  void connectAnInbox() {
    college = newCollege();
    account = GOOGLE.newAccount();
    start = newHistoryId();
  }

  @Test
  void theFirstPollStartsFromNowAndReadsNothing() {
    SourceInbox source = newUnpolledSource(college, account);
    account.profile(start + 500);

    Result result = sync.sync(source.getId());

    assertThat(result).isEqualTo(new Result(Outcome.STARTED_FROM_NOW, 0, 0));
    SourceInbox after = sources.findById(source.getId()).orElseThrow();
    assertThat(after.getLastHistoryId()).isEqualTo(start + 500);
    assertThat(after.getLastSyncedAt()).isNotNull();
    assertThat(account.requestsTo("/history")).as("old mail is never listed").isZero();
    assertThat(ingestedFrom(source)).isEmpty();
  }

  @Test
  void ingestsEveryPageAndAdvancesTheCursorToTheEnd() {
    SourceInbox source = newSource(college, account, start);
    TestMessage first = mail("Example Corp - Shortlist");
    TestMessage second = mail("Sample Ltd - Offer letters");
    TestMessage third = mail("Example Corp - Interview schedule");
    account.history(
        start,
        null,
        List.of(HistoryRecord.of(start + 1, first.id()), HistoryRecord.of(start + 2, second.id())),
        "next",
        start + 9);
    account.history(
        start, "next", List.of(HistoryRecord.of(start + 3, third.id())), null, start + 9);

    Result result = sync.sync(source.getId());

    assertThat(result).isEqualTo(new Result(Outcome.SYNCED, 3, 3));
    assertThat(sources.findById(source.getId()).orElseThrow().getLastHistoryId())
        .isEqualTo(start + 9);
    assertThat(ingestedFrom(source))
        .extracting(EmailIngested::gmailId)
        .containsExactly(first.id(), second.id(), third.id());
    assertThat(ingestedFrom(source))
        .allSatisfy(event -> {
          assertThat(event.collegeId()).isEqualTo(college.getId());
          assertThat(event.sourceId()).isEqualTo(source.getId());
        });

    Map<String, Object> row = processedEmail(messageIdOf(first));
    assertThat(row)
        .containsEntry("college_id", college.getId())
        .containsEntry("source_id", source.getId())
        .containsEntry("gmail_id", first.id())
        .containsEntry("sender", "placements@" + domainOf(college))
        .containsEntry("subject", "Example Corp - Shortlist");
    awaitAll(first, second, third);
  }

  @Test
  void keepsOnlyAllowListedSenders() {
    SourceInbox source = newSource(college, account, start);
    TestMessage fromCell = mail("From the placement cell");
    TestMessage fromDomain =
        mail("From anyone on the allow-listed domain")
            .from("Dean <dean@cell." + domainOf(college) + ">");
    TestMessage fromStudent = mail("From a student").from("student@" + domainOf(college));
    TestMessage lookalike =
        mail("From a lookalike domain").from("placements@evil" + domainOf(college));
    TestMessage spoofedName =
        mail("Display name only")
            .from("\"placements@" + domainOf(college) + "\" <attacker@elsewhere.example>");
    TestMessage draft = mail("An unsent draft").label("DRAFT");
    for (TestMessage message : List.of(fromDomain, fromStudent, lookalike, spoofedName, draft)) {
      account.message(message);
    }
    account.history(
        start,
        null,
        List.of(
            HistoryRecord.of(
                start + 1,
                fromCell.id(),
                fromDomain.id(),
                fromStudent.id(),
                lookalike.id(),
                spoofedName.id(),
                draft.id())),
        null,
        start + 1);

    Result result = sync.sync(source.getId());

    assertThat(result).isEqualTo(new Result(Outcome.SYNCED, 6, 2));
    assertThat(ingestedFrom(source))
        .extracting(EmailIngested::gmailId)
        .containsExactly(fromCell.id(), fromDomain.id());
    assertThat(statusOf(messageIdOf(fromStudent))).isNull();
    assertThat(statusOf(messageIdOf(lookalike))).isNull();
    assertThat(statusOf(messageIdOf(spoofedName))).isNull();
    assertThat(statusOf(messageIdOf(draft))).isNull();
    awaitAll(fromCell, fromDomain);
  }

  @Test
  void theSameMessageIdArrivingViaTwoSourcesIsIngestedOnce() {
    Account otherAccount = GOOGLE.newAccount();
    long otherStart = newHistoryId();
    SourceInbox source = newSource(college, account, start);
    SourceInbox otherSource = newSource(college, otherAccount, otherStart);
    String sharedMessageId = newMessageId();
    // One email, delivered to both inboxes: same Message-ID, different Gmail ids.
    TestMessage here = mail("Example Corp - Shortlist").messageId(sharedMessageId);
    TestMessage there =
        TestMessage.withId(TestMessage.newId())
            .from(placementCell(college))
            .subject("Example Corp - Shortlist")
            .messageId(sharedMessageId);
    account.message(here);
    otherAccount.message(there);
    account.history(start, null, List.of(HistoryRecord.of(start + 1, here.id())), null, start + 1);
    otherAccount.history(
        otherStart,
        null,
        List.of(HistoryRecord.of(otherStart + 1, there.id())),
        null,
        otherStart + 1);

    Result first = sync.sync(source.getId());
    Result second = sync.sync(otherSource.getId());

    assertThat(first).isEqualTo(new Result(Outcome.SYNCED, 1, 1));
    assertThat(second).as("seen, but already known").isEqualTo(new Result(Outcome.SYNCED, 1, 0));
    assertThat(count("processed_email", "rfc_message_id", sharedMessageId)).isEqualTo(1);
    assertThat(processedEmail(sharedMessageId)).containsEntry("source_id", source.getId());
    assertThat(events.stream(EmailIngested.class))
        .filteredOn(event -> event.rfcMessageId().equals(sharedMessageId))
        .hasSize(1);
    // Both cursors moved on, so neither inbox reads the message again.
    assertThat(sources.findById(otherSource.getId()).orElseThrow().getLastHistoryId())
        .isEqualTo(otherStart + 1);
    awaitProcessed(sharedMessageId);
  }

  @Test
  void readingTheSamePageAgainIngestsNothingNew() {
    SourceInbox source = newSource(college, account, start);
    TestMessage message = mail("Example Corp - Shortlist");
    account.history(
        start, null, List.of(HistoryRecord.of(start + 1, message.id())), null, start + 1);
    // What a crash between "page committed" and "cursor advanced" looks like on the next poll.
    account.history(
        start + 1, null, List.of(HistoryRecord.of(start + 1, message.id())), null, start + 1);

    Result first = sync.sync(source.getId());
    Result replay = sync.sync(source.getId());

    assertThat(first.ingested()).isEqualTo(1);
    assertThat(replay).isEqualTo(new Result(Outcome.SYNCED, 1, 0));
    assertThat(ingestedFrom(source)).hasSize(1);
    assertThat(count("processed_email", "source_id", source.getId())).isEqualTo(1);
    awaitAll(message);
  }

  @Test
  void aStaleCursorFallsBackToRecentMailAndReliesOnDedupe() {
    SourceInbox source = newSource(college, account, start);
    TestMessage alreadyIngested = mail("Example Corp - Shortlist");
    TestMessage missedWhileAway = mail("Sample Ltd - Shortlist");
    TestMessage notFromTheCell = mail("Newsletter").from("news@elsewhere.example");
    account.message(notFromTheCell);
    writer.recordPage(
        source.getId(),
        college.getId(),
        List.of(
            new MailHeader(
                new MailRef(alreadyIngested.id()),
                messageIdOf(alreadyIngested),
                "placements@" + domainOf(college),
                "Example Corp - Shortlist",
                Instant.parse("2026-11-10T10:00:00Z"),
                false)));
    account.historyGone(start);
    account.profile(start + 1000);
    account.recentMessages(alreadyIngested.id(), missedWhileAway.id(), notFromTheCell.id());

    Result result = sync.sync(source.getId());

    assertThat(result).isEqualTo(new Result(Outcome.RECOVERED_FROM_STALE_CURSOR, 3, 1));
    assertThat(statusOf(messageIdOf(missedWhileAway))).isNotNull();
    assertThat(statusOf(messageIdOf(notFromTheCell))).isNull();
    assertThat(count("processed_email", "source_id", source.getId())).isEqualTo(2);
    assertThat(sources.findById(source.getId()).orElseThrow().getLastHistoryId())
        .isEqualTo(start + 1000);
    awaitAll(alreadyIngested, missedWhileAway);
  }

  @Test
  void aRevokedTokenMarksTheSourceAuthExpiredAndStopsPollingIt() {
    SourceInbox source = newSource(college, account, start);
    account.history(start, null, List.of(), null, start);
    account.revoke();

    Result result = sync.sync(source.getId());

    assertThat(result.outcome()).isEqualTo(Outcome.AUTH_EXPIRED);
    SourceInbox after = sources.findById(source.getId()).orElseThrow();
    assertThat(after.getStatus()).isEqualTo(SourceStatus.AUTH_EXPIRED);
    assertThat(after.getLastHistoryId()).as("the cursor stays put").isEqualTo(start);
    assertThat(events.stream(SourceAuthExpired.class))
        .containsExactly(new SourceAuthExpired(source.getId(), college.getId()));

    assertThat(sync.sync(source.getId()).outcome()).isEqualTo(Outcome.SKIPPED);
    assertThat(events.stream(SourceAuthExpired.class)).as("announced once").hasSize(1);
  }

  @Test
  void aFailingGmailLeavesTheCursorAloneSoTheNextPollRetries() {
    SourceInbox source = newSource(college, account, start);
    account.historyFails(start, 503);

    Result failed = sync.sync(source.getId());

    assertThat(failed.outcome()).isEqualTo(Outcome.FAILED);
    assertThat(account.historyRequests(start)).as("retried once, as configured").isEqualTo(2);
    SourceInbox after = sources.findById(source.getId()).orElseThrow();
    assertThat(after.getLastHistoryId()).isEqualTo(start);
    assertThat(after.getStatus()).isEqualTo(SourceStatus.ACTIVE);

    TestMessage message = mail("Example Corp - Shortlist");
    account.history(
        start, null, List.of(HistoryRecord.of(start + 1, message.id())), null, start + 1);

    assertThat(sync.sync(source.getId())).isEqualTo(new Result(Outcome.SYNCED, 1, 1));
    awaitAll(message);
  }

  @Test
  void aMessageDeletedBeforeItCouldBeReadIsSkipped() {
    SourceInbox source = newSource(college, account, start);
    TestMessage kept = mail("Example Corp - Shortlist");
    String deleted = TestMessage.newId();
    account.messageMissing(deleted);
    account.history(
        start, null, List.of(HistoryRecord.of(start + 1, deleted, kept.id())), null, start + 1);

    assertThat(sync.sync(source.getId())).isEqualTo(new Result(Outcome.SYNCED, 2, 1));
    awaitAll(kept);
  }

  @Test
  void inactiveSourcesAndPausedCollegesAreNotPolled() {
    College paused =
        colleges.save(
            new College(
                UUID.randomUUID(),
                "paused-" + UUID.randomUUID().toString().substring(0, 8),
                "Paused",
                List.of("paused.example"),
                List.of("placements@paused.example"),
                College.DEFAULT_NEO_ID_PATTERN,
                CollegeStatus.PAUSED,
                Instant.parse("2026-01-01T00:00:00Z")));
    SourceInbox ofPaused =
        sources.save(
            new SourceInbox(
                paused.getId(),
                "inbox-" + UUID.randomUUID() + "@paused.example",
                account.refreshToken()));

    assertThat(sync.sync(ofPaused.getId()).outcome()).isEqualTo(Outcome.SKIPPED);
    assertThat(sync.sync(UUID.randomUUID()).outcome()).isEqualTo(Outcome.SKIPPED);
    assertThat(account.requestsTo("/profile")).isZero();
  }

  @Test
  void aSourceWhoseTokenCannotBeDecryptedFailsAloneAndTheOthersAreStillPolled() {
    SourceInbox healthy = newSource(college, account, start);
    SourceInbox broken = newSource(college, GOOGLE.newAccount(), newHistoryId());
    // As if it had been encrypted with a different key.
    jdbc.update(
        "update mail_source set refresh_token_enc = ? where id = ?",
        new byte[] {
          1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25,
          26, 27, 28, 29, 30
        },
        broken.getId());
    TestMessage message = mail("Example Corp - Shortlist");
    account.history(
        start, null, List.of(HistoryRecord.of(start + 1, message.id())), null, start + 1);

    assertThat(sync.sync(broken.getId())).isEqualTo(new Result(Outcome.FAILED, 0, 0));
    sync.syncAll();

    assertThat(sources.findById(healthy.getId()).orElseThrow().getLastHistoryId())
        .as("the healthy source was polled despite the broken one")
        .isEqualTo(start + 1);
    awaitAll(message);
    // Leave no active source behind that every later poll would trip over.
    jdbc.update("update mail_source set status = 'DISABLED' where id = ?", broken.getId());
  }

  @Test
  void theCursorNeverMovesBackwards() {
    SourceInbox source = newSource(college, account, start);

    writer.advanceCursor(source.getId(), start - 100);

    assertThat(sources.findById(source.getId()).orElseThrow().getLastHistoryId()).isEqualTo(start);
  }

  /** A message from the placement cell with its own Message-ID, stubbed on the account. */
  private TestMessage mail(String subject) {
    TestMessage message =
        TestMessage.withId(TestMessage.newId())
            .from(placementCell(college))
            .subject(subject)
            .messageId(newMessageId())
            .text("See the attached list.");
    account.message(message);
    return message;
  }

  private List<EmailIngested> ingestedFrom(SourceInbox source) {
    return events.stream(EmailIngested.class)
        .filter(event -> event.sourceId().equals(source.getId()))
        .toList();
  }

  private Map<String, Object> processedEmail(String rfcMessageId) {
    return jdbc.queryForMap("select * from processed_email where rfc_message_id = ?", rfcMessageId);
  }

  private static String messageIdOf(TestMessage message) {
    return message.messageIdHeader();
  }

  /** Lets the background pipeline finish, so one test's emails do not trail into the next. */
  private void awaitAll(TestMessage... messages) {
    for (TestMessage message : messages) {
      awaitProcessed(messageIdOf(message));
    }
  }
}
