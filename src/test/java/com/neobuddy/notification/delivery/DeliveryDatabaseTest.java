package com.neobuddy.notification.delivery;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.neobuddy.ingestion.gmail.GmailBackedDatabaseTest;
import com.neobuddy.ingestion.source.SourceInbox;
import com.neobuddy.processing.Classification;
import com.neobuddy.processing.EventType;
import com.neobuddy.processing.MatchHit;
import com.neobuddy.processing.MatchesFound;
import com.neobuddy.shared.college.College;
import com.neobuddy.subscriber.ChannelAddress;
import com.neobuddy.subscriber.ChannelType;
import com.neobuddy.subscriber.ChannelVerificationService;
import com.neobuddy.subscriber.IdentifierType;
import com.neobuddy.subscriber.OidcClaims;
import com.neobuddy.subscriber.SubscriberService;
import com.neobuddy.subscriber.SubscriberView;
import com.neobuddy.subscriber.SubscriberView.IdentifierView;
import com.neobuddy.subscriber.TestColleges;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.sesv2.SesV2Client;

/**
 * Base for tests of the outbox with the outside world replaced: ntfy is a WireMock server, Amazon
 * SES a mocked client, Google the simulated Gmail of {@link GmailBackedDatabaseTest}. The scheduled
 * dispatcher is off; tests dispatch by hand. Subclasses share one context.
 */
public abstract class DeliveryDatabaseTest extends GmailBackedDatabaseTest {

  protected static final String SES_FROM = "NeoBuddy <alerts@neobuddy.example>";
  protected static final Instant RECEIVED_AT = Instant.parse("2026-11-13T10:35:00Z");

  protected static final WireMockServer NTFY = startNtfy();

  @MockitoBean protected SesV2Client ses;

  @Autowired private DeliveryDispatcher dispatcher;
  @Autowired protected SubscriberService subscribers;
  @Autowired protected ChannelVerificationService verification;
  @Autowired protected ApplicationEventPublisher publisher;
  @Autowired protected TransactionTemplate transactions;

  private static WireMockServer startNtfy() {
    WireMockServer server = new WireMockServer(wireMockConfig().dynamicPort());
    server.start();
    return server;
  }

  @DynamicPropertySource
  static void replaceNtfyAndSes(DynamicPropertyRegistry registry) {
    registry.add("neobuddy.channels.ntfy.base-url", NTFY::baseUrl);
    registry.add("neobuddy.channels.ntfy.read-timeout", () -> "2s");
    registry.add("neobuddy.channels.ntfy.rate-limit.limit-for-period", () -> "100000");
    registry.add("neobuddy.channels.email.provider", () -> "ses");
    registry.add("neobuddy.channels.ses.from", () -> SES_FROM);
    registry.add("neobuddy.channels.ses.rate-limit.limit-for-period", () -> "100000");
  }

  /** Tests run one after another against one database, so each starts with an empty outbox. */
  @BeforeEach
  void emptyOutboxAndNtfy() {
    jdbc.update("delete from delivery");
    NTFY.resetAll();
  }

  /** A student with a NeoID; no channels yet. */
  protected record Student(SubscriberView view, IdentifierView identifier, String neoId) {

    public UUID id() {
      return view.id();
    }
  }

  protected Student newStudent(College college) {
    SubscriberView view =
        subscribers.registerFromOidc(
            new OidcClaims(
                "sub-" + UUID.randomUUID(),
                TestColleges.studentEmail(college),
                true,
                "Tara Target",
                null));
    String neoId = TestColleges.neoId();
    return new Student(
        view, subscribers.addIdentifier(view.id(), IdentifierType.NEO_ID, neoId), neoId);
  }

  /** Gives the student a verified push channel and makes ntfy accept posts to it. */
  protected String ntfyChannel(Student student) {
    String topic = verification.createNtfyChannel(student.id()).topic();
    ntfyAnswers(topic, 200);
    return topic;
  }

  /** Gives the student a verified email channel. */
  protected UUID emailChannel(Student student, String address) {
    UUID id =
        subscribers.addChannel(student.id(), ChannelType.EMAIL, new ChannelAddress(address)).id();
    subscribers.markVerified(student.id(), id);
    return id;
  }

  protected UUID channelId(Student student, ChannelType type) {
    return subscribers.get(student.id()).channels().stream()
        .filter(channel -> channel.type() == type)
        .findFirst()
        .orElseThrow()
        .id();
  }

  protected static void ntfyAnswers(String topic, int status) {
    NTFY.stubFor(post(urlPathEqualTo("/" + topic)).willReturn(aResponse().withStatus(status)));
  }

  protected static List<LoggedRequest> ntfyPosts(String topic) {
    return NTFY.findAll(postRequestedFor(urlPathEqualTo("/" + topic)));
  }

  /** Records an email as ingestion would, without running the pipeline. */
  protected String newEmail(
      College college, SourceInbox source, EventType type, String company, String subject) {
    String messageId = newMessageId();
    jdbc.update(
        "insert into processed_email (rfc_message_id, college_id, source_id, gmail_id, sender,"
            + " subject, received_at, event_type, company, status)"
            + " values (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PROCESSED')",
        messageId,
        college.getId(),
        source.getId(),
        "gmail-" + UUID.randomUUID(),
        "placements@" + domainOf(college),
        subject,
        Timestamp.from(RECEIVED_AT),
        type.name(),
        company);
    return messageId;
  }

  /** Stores a match as processing would. */
  protected UUID storeMatch(String messageId, Student student, EventType type, String snippet) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into match_hit (id, user_id, email_id, identifier_id, event_type, context_snippet)"
            + " values (?, ?, ?, ?, ?, ?)",
        id,
        student.id(),
        messageId,
        student.identifier().id(),
        type.name(),
        snippet);
    return id;
  }

  protected MatchesFound matchesFound(
      College college, String messageId, EventType type, String company, Student... students) {
    return new MatchesFound(
        college.getId(),
        messageId,
        new Classification(type, Optional.ofNullable(company)),
        List.of(students).stream()
            .map(student -> new MatchHit(student.id(), student.identifier().id(), type, "row"))
            .toList());
  }

  /**
   * An email that matched the student, stored and announced in one transaction the way the pipeline
   * does it, so the student's deliveries are queued.
   *
   * @return the match's id
   */
  protected UUID newMatch(
      College college,
      SourceInbox source,
      Student student,
      EventType type,
      String company,
      String subject) {
    return transactions.execute(tx -> {
      String messageId = newEmail(college, source, type, company, subject);
      UUID matchId = storeMatch(messageId, student, type, student.neoId() + " | Tara Target");
      publisher.publishEvent(matchesFound(college, messageId, type, company, student));
      return matchId;
    });
  }

  protected int dispatchOnce() {
    return dispatcher.dispatchDue();
  }

  /** Dispatches until nothing is due. */
  protected int dispatchUntilIdle() {
    int total = 0;
    int claimed;
    while ((claimed = dispatcher.dispatchDue()) > 0) {
      total += claimed;
    }
    return total;
  }

  protected Map<String, Object> delivery(UUID matchId, UUID channelId) {
    return jdbc.queryForMap(
        "select * from delivery where match_id = ? and channel_id = ?", matchId, channelId);
  }

  protected List<Map<String, Object>> deliveriesOf(UUID matchId) {
    return jdbc.queryForList("select * from delivery where match_id = ?", matchId);
  }

  /** What time passing does: every unfinished delivery becomes due now. */
  protected void makeAllDue() {
    jdbc.update(
        "update delivery set next_attempt_at = now() - interval '1 second'"
            + " where status in ('PENDING', 'RETRY')");
  }

  protected static Instant instant(Object timestamp) {
    return ((Timestamp) timestamp).toInstant();
  }
}
