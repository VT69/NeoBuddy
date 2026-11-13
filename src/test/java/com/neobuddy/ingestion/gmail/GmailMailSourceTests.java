package com.neobuddy.ingestion.gmail;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static com.neobuddy.ingestion.gmail.GmailTestServer.attachmentPart;
import static com.neobuddy.ingestion.gmail.GmailTestServer.inlineAttachmentPart;
import static com.neobuddy.ingestion.gmail.GmailTestServer.multipart;
import static com.neobuddy.ingestion.gmail.GmailTestServer.textPart;
import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neobuddy.ingestion.AttachmentRef;
import com.neobuddy.ingestion.RawEmail;
import com.neobuddy.ingestion.gmail.GmailTestServer.Account;
import com.neobuddy.ingestion.gmail.GmailTestServer.HistoryRecord;
import com.neobuddy.ingestion.gmail.GmailTestServer.TestMessage;
import com.neobuddy.ingestion.source.ChangePage;
import com.neobuddy.ingestion.source.LoadedMessage;
import com.neobuddy.ingestion.source.MailHeader;
import com.neobuddy.ingestion.source.MailRef;
import com.neobuddy.ingestion.source.MailSource;
import com.neobuddy.ingestion.source.MailSourceException;
import com.neobuddy.ingestion.source.SourceAuthExpiredException;
import com.neobuddy.ingestion.source.StaleCursorException;
import com.neobuddy.ingestion.source.SyncCursor;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;

/** The Gmail adapter against a WireMock Gmail API. No Spring, no database. */
class GmailMailSourceTests {

  private static final String HISTORY = "/gmail/v1/users/me/history";

  private final GmailTestServer google = GmailTestServer.shared();
  private final Account account = google.newAccount();
  private final UUID sourceId = UUID.randomUUID();
  private final MailSource source =
      new GmailMailSourceFactory(google.properties(3)).open(sourceId, account.refreshToken());

  /** A history id no other test uses, so stubs never overlap. */
  private final long start = ThreadLocalRandom.current().nextLong(1_000_000, Long.MAX_VALUE / 4);

  // ---- history.list ------------------------------------------------------------------------

  @Test
  void readsEveryPageOfHistoryAndReportsWhereItIsSafeToResume() {
    account.history(
        start,
        null,
        List.of(HistoryRecord.of(start + 10, "m1", "m2"), HistoryRecord.of(start + 20, "m3")),
        "page-2",
        start + 99);
    account.history(
        start, "page-2", List.of(HistoryRecord.of(start + 30, "m4")), "page-3", start + 99);
    account.history(start, "page-3", List.of(), null, start + 99);

    ChangePage first = source.changesSince(SyncCursor.startingAt(start));
    ChangePage second = source.changesSince(new SyncCursor(start, first.nextPageToken()));
    ChangePage third = source.changesSince(new SyncCursor(start, second.nextPageToken()));

    assertThat(first.messages()).extracting(MailRef::gmailId).containsExactly("m1", "m2", "m3");
    // Mid-way, only what this page showed is safe: the highest record id on it.
    assertThat(first.checkpointHistoryId()).isEqualTo(start + 20);
    assertThat(first.isLast()).isFalse();
    assertThat(second.messages()).extracting(MailRef::gmailId).containsExactly("m4");
    assertThat(second.checkpointHistoryId()).isEqualTo(start + 30);
    // The last page moves the cursor to the mailbox's current history id.
    assertThat(third.messages()).isEmpty();
    assertThat(third.isLast()).isTrue();
    assertThat(third.checkpointHistoryId()).isEqualTo(start + 99);
    assertThat(account.historyRequests(start)).isEqualTo(3);
  }

  @Test
  void aMessageAddedInSeveralRecordsIsListedOnce() {
    account.history(
        start,
        null,
        List.of(HistoryRecord.of(start + 1, "m1"), HistoryRecord.of(start + 2, "m1", "m2")),
        null,
        start + 2);

    assertThat(source.changesSince(SyncCursor.startingAt(start)).messages())
        .extracting(MailRef::gmailId)
        .containsExactly("m1", "m2");
  }

  @Test
  void noNewHistoryIsAnEmptyLastPage() {
    account.history(start, null, List.of(), null, start);

    ChangePage page = source.changesSince(SyncCursor.startingAt(start));

    assertThat(page.messages()).isEmpty();
    assertThat(page.isLast()).isTrue();
    assertThat(page.checkpointHistoryId()).isEqualTo(start);
  }

  @Test
  void aHistoryIdThatGmailNoLongerHasIsAStaleCursor() {
    account.historyGone(start);

    assertThatThrownBy(() -> source.changesSince(SyncCursor.startingAt(start)))
        .isInstanceOf(StaleCursorException.class);
    assertThat(account.historyRequests(start)).as("a 404 is not retried").isEqualTo(1);
  }

  // ---- Authorisation -----------------------------------------------------------------------

  @Test
  void aRevokedRefreshTokenIsAuthExpired() {
    account.revoke();
    account.history(start, null, List.of(), null, start);

    assertThatThrownBy(() -> source.changesSince(SyncCursor.startingAt(start)))
        .isInstanceOf(SourceAuthExpiredException.class)
        .hasMessageNotContaining(account.refreshToken());
    assertThat(account.historyRequests(start)).as("Gmail itself is never reached").isZero();
  }

  @Test
  void requestsCarryTheAccessTokenNeverTheRefreshToken() {
    account.profile(start);

    source.currentHistoryId();

    assertThat(
            google
                .wireMock()
                .findAll(getRequestedFor(urlPathEqualTo("/gmail/v1/users/me/profile"))))
        .anySatisfy(request -> {
          assertThat(request.getHeader("Authorization")).startsWith("Bearer access-");
          assertThat(request.getAbsoluteUrl()).doesNotContain(account.refreshToken());
        });
  }

  // ---- Retry -------------------------------------------------------------------------------

  @Test
  void aRateLimitAndAServerErrorAreRetriedUntilTheCallSucceeds() {
    String scenario = "retry-" + start;
    stubHistory(scenario, STARTED, "second", 429);
    stubHistory(scenario, "second", "third", 503);
    google
        .wireMock()
        .stubFor(
            get(urlPathEqualTo(HISTORY))
                .withQueryParam("startHistoryId", equalTo("" + start))
                .inScenario(scenario)
                .whenScenarioStateIs("third")
                .willReturn(
                    aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"historyId\":\"" + (start + 5) + "\"}")));

    ChangePage page = source.changesSince(SyncCursor.startingAt(start));

    assertThat(page.checkpointHistoryId()).isEqualTo(start + 5);
    assertThat(historyCalls()).isEqualTo(3);
  }

  @Test
  void retriesStopAtTheConfiguredNumberOfAttempts() {
    account.historyFails(start, 503);

    assertThatThrownBy(() -> source.changesSince(SyncCursor.startingAt(start)))
        .isInstanceOf(MailSourceException.class)
        .isNotInstanceOf(StaleCursorException.class)
        .hasMessage("Gmail history.list failed with HTTP 503");
    assertThat(account.historyRequests(start)).isEqualTo(3);
  }

  @Test
  void otherClientErrorsAreNotRetried() {
    account.historyFails(start, 403);

    assertThatThrownBy(() -> source.changesSince(SyncCursor.startingAt(start)))
        .isInstanceOf(MailSourceException.class)
        .hasMessage("Gmail history.list failed with HTTP 403");
    assertThat(account.historyRequests(start)).isEqualTo(1);
  }

  // ---- Profile and recent messages -----------------------------------------------------------

  @Test
  void readsTheCurrentHistoryIdAndListsRecentMessages() {
    account.profile(start + 7);
    account.recentMessages("r1", "r2", "r3");

    assertThat(source.currentHistoryId()).isEqualTo(start + 7);
    assertThat(source.recentMessages(7))
        .extracting(MailRef::gmailId)
        .containsExactly("r1", "r2", "r3");
    assertThat(source.id()).isEqualTo(sourceId);
  }

  // ---- Headers -----------------------------------------------------------------------------

  @Test
  void readsTheHeadersThePollerNeeds() {
    String id = TestMessage.newId();
    Instant received = Instant.parse("2026-11-12T08:30:00Z");
    account.message(
        TestMessage.withId(id)
            .from("\"Placement Cell\" <Placements@College.Example>")
            .subject("Example Corp - Shortlist")
            .header("Message-Id", "  <abc.123@mail.college.example> ")
            .receivedAt(received));

    Optional<MailHeader> header = source.header(new MailRef(id));

    assertThat(header)
        .contains(
            new MailHeader(
                new MailRef(id),
                "<abc.123@mail.college.example>",
                "placements@college.example",
                "Example Corp - Shortlist",
                received,
                false));
  }

  @Test
  void aMessageWithoutAMessageIdGetsAStableStandIn() {
    String id = TestMessage.newId();
    account.message(TestMessage.withId(id).from("placements@college.example"));

    MailHeader first = source.header(new MailRef(id)).orElseThrow();
    MailHeader second = source.header(new MailRef(id)).orElseThrow();

    assertThat(first.rfcMessageId()).isEqualTo("<gmail-" + id + "@neobuddy.invalid>");
    assertThat(second.rfcMessageId()).isEqualTo(first.rfcMessageId());
    assertThat(first.fromAddress()).isEqualTo("placements@college.example");
  }

  @Test
  void draftsAreFlaggedAndDeletedMessagesAreEmpty() {
    String draft = TestMessage.newId();
    String deleted = TestMessage.newId();
    account.message(TestMessage.withId(draft).from("me@college.example").label("DRAFT"));
    account.messageMissing(deleted);

    assertThat(source.header(new MailRef(draft)))
        .hasValueSatisfying(h -> assertThat(h.draft()).isTrue());
    assertThat(source.header(new MailRef(deleted))).isEmpty();
  }

  @Test
  void headersNeverPrintSenderOrSubject() {
    MailHeader header =
        new MailHeader(
            new MailRef("m"),
            "<id@x>",
            "placements@college.example",
            "Secret",
            Instant.EPOCH,
            false);

    assertThat(header.toString())
        .contains("<id@x>")
        .doesNotContain("placements")
        .doesNotContain("Secret");
  }

  // ---- Full messages -------------------------------------------------------------------------

  @Test
  void walksNestedMultipartsForBodiesAndAttachments() throws Exception {
    String id = TestMessage.newId();
    byte[] logo = {1, 2, 3, 4, 5};
    account.message(
        TestMessage.withId(id)
            .from("Placement Cell <placements@college.example>")
            .subject("Example Corp - Shortlist")
            .messageId("<nested@mail.college.example>")
            .payload(
                multipart(
                    "mixed",
                    List.of(
                        multipart(
                            "related",
                            List.of(
                                multipart(
                                    "alternative",
                                    List.of(
                                        textPart("text/plain", "Plain body, Zoë.", UTF_8),
                                        textPart(
                                            "text/html", "<p>HTML body, Zoë.</p>", ISO_8859_1))),
                                // An inline image: it has a filename, so it is an attachment.
                                inlineAttachmentPart("logo.png", "image/png", logo))),
                        attachmentPart("shortlist.xlsx", "application/octet-stream", "att-1", 4096),
                        multipart(
                            "mixed",
                            List.of(
                                textPart("text/plain", "Forwarded note.", UTF_8),
                                attachmentPart(
                                    "round2.pdf", "application/pdf", "att-2", 2048)))))));

    LoadedMessage loaded = source.load(new MailRef(id));
    RawEmail email = loaded.email();

    assertThat(email.rfcMessageId()).isEqualTo("<nested@mail.college.example>");
    assertThat(email.gmailId()).isEqualTo(id);
    assertThat(email.from()).isEqualTo("placements@college.example");
    assertThat(email.subject()).isEqualTo("Example Corp - Shortlist");
    assertThat(email.textBody()).isEqualTo("Plain body, Zoë.\nForwarded note.");
    assertThat(email.htmlBody()).isEqualTo("<p>HTML body, Zoë.</p>");
    assertThat(email.attachments())
        .containsExactly(
            new AttachmentRef("logo.png", "image/png", 5),
            new AttachmentRef("shortlist.xlsx", "application/octet-stream", 4096),
            new AttachmentRef("round2.pdf", "application/pdf", 2048));
    assertThat(loaded.contentOf(email.attachments().get(0)).inline()).isEqualTo(logo);
    assertThat(loaded.contentOf(email.attachments().get(1)).attachmentId()).isEqualTo("att-1");
    assertThat(loaded.contentOf(email.attachments().get(2)).attachmentId()).isEqualTo("att-2");
    // Looked up by instance: an equal but different reference is not this message's attachment.
    assertThat(loaded.contentOf(new AttachmentRef("logo.png", "image/png", 5))).isNull();
  }

  @Test
  void aBareTextMessageHasOnlyATextBody() {
    String id = TestMessage.newId();
    account.message(TestMessage.withId(id).from("placements@college.example").text("Just text."));

    RawEmail email = source.load(new MailRef(id)).email();

    assertThat(email.textBody()).isEqualTo("Just text.");
    assertThat(email.htmlBody()).isNull();
    assertThat(email.attachments()).isEmpty();
  }

  @Test
  void twoIdenticalAttachmentsAreKeptApart() {
    String id = TestMessage.newId();
    account.message(
        TestMessage.withId(id)
            .payload(
                multipart(
                    "mixed",
                    List.of(
                        attachmentPart("list.xlsx", "application/octet-stream", "first", 100),
                        attachmentPart("list.xlsx", "application/octet-stream", "second", 100)))));

    LoadedMessage loaded = source.load(new MailRef(id));

    assertThat(loaded.email().attachments()).hasSize(2);
    assertThat(loaded.contentOf(loaded.email().attachments().get(0)).attachmentId())
        .isEqualTo("first");
    assertThat(loaded.contentOf(loaded.email().attachments().get(1)).attachmentId())
        .isEqualTo("second");
  }

  // ---- Attachments ---------------------------------------------------------------------------

  @Test
  void streamsAnAttachmentBackToItsExactBytes() throws Exception {
    String id = TestMessage.newId();
    byte[] content = new byte[300_001];
    new Random(7).nextBytes(content);
    account.attachment(id, "big", content);

    try (InputStream in = source.openAttachment(new MailRef(id), "big")) {
      assertThat(in.readAllBytes()).isEqualTo(content);
    }
  }

  @Test
  void aMissingAttachmentFailsWithoutLeakingIds() {
    String id = TestMessage.newId();

    assertThatThrownBy(() -> source.openAttachment(new MailRef(id), "nope"))
        .isInstanceOf(MailSourceException.class)
        .hasMessage("Gmail attachments.get failed with HTTP 404")
        .hasMessageNotContaining(id);
  }

  @Test
  void theJsonFieldStreamFindsTheValueWhateverSurroundsIt() throws Exception {
    assertThat(field("{\"size\": 5, \"data\": \"SGVsbG8\"}")).isEqualTo("SGVsbG8");
    assertThat(field("{\"data\":\"QQ==\",\"size\":1}")).isEqualTo("QQ==");
    assertThat(field("{\n  \"attachmentId\" : \"data\",\n  \"data\"\n :\n \"Zm9v\"\n}"))
        .isEqualTo("Zm9v");
    // Escaped padding, as an HTML-safe JSON writer produces it.
    assertThat(field("{\"data\": \"QQ\\u003d\\u003d\"}")).isEqualTo("QQ==");
    assertThat(field("{\"data\": \"a\\/b\"}")).isEqualTo("a/b");
    assertThat(field("{\"data\": \"\"}")).isEmpty();
    assertThat(field("{\"size\": 0}")).isEmpty();
    assertThat(field("")).isEmpty();
    assertThat(field("{\"data\": 12, \"other\": \"x\"}")).isEmpty();
  }

  // ---- Configuration -------------------------------------------------------------------------

  @Test
  void withoutAnOAuthClientNothingIsAttempted() {
    GmailProperties unconfigured =
        new GmailProperties(
            " ",
            null,
            google.rootUrl(),
            java.net.URI.create(google.tokenUri()),
            new GmailProperties.Retry(1, java.time.Duration.ofMillis(1)),
            java.time.Duration.ofSeconds(1),
            java.time.Duration.ofSeconds(1));

    assertThatThrownBy(() -> new GmailMailSourceFactory(unconfigured).open(sourceId, "token"))
        .isInstanceOf(MailSourceException.class)
        .hasMessageContaining("NEOBUDDY_GMAIL_CLIENT_ID");
    assertThat(google.properties(3).toString()).doesNotContain("test-client-secret");
  }

  private void stubHistory(String scenario, String state, String next, int status) {
    google
        .wireMock()
        .stubFor(
            get(urlPathEqualTo(HISTORY))
                .withQueryParam("startHistoryId", equalTo("" + start))
                .inScenario(scenario)
                .whenScenarioStateIs(state)
                .willSetStateTo(next)
                .willReturn(
                    aResponse()
                        .withStatus(status)
                        .withHeader("Content-Type", "application/json")
                        .withBody(
                            "{\"error\":{\"code\":" + status + ",\"message\":\"Simulated\"}}")));
  }

  private int historyCalls() {
    return google
        .wireMock()
        .findAll(
            getRequestedFor(urlPathEqualTo(HISTORY))
                .withQueryParam("startHistoryId", equalTo("" + start)))
        .size();
  }

  private static String field(String json) throws Exception {
    try (InputStream in =
        new JsonStringFieldInputStream(new ByteArrayInputStream(json.getBytes(UTF_8)), "data")) {
      return new String(in.readAllBytes(), UTF_8);
    }
  }
}
