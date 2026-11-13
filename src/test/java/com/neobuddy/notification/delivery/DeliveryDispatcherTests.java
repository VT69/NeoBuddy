package com.neobuddy.notification.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.neobuddy.ingestion.source.SourceInbox;
import com.neobuddy.notification.DeliveryDead;
import com.neobuddy.processing.EventType;
import com.neobuddy.shared.college.College;
import com.neobuddy.subscriber.ChannelType;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.sesv2.model.MessageRejectedException;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;
import software.amazon.awssdk.services.sesv2.model.SendEmailResponse;
import software.amazon.awssdk.services.sesv2.model.TooManyRequestsException;

/** The dispatcher's state machine: PENDING to SENT, RETRY with backoff, and DEAD. */
@RecordApplicationEvents
class DeliveryDispatcherTests extends DeliveryDatabaseTest {

  @Autowired private DeliveryStore store;
  @Autowired private DeliveryProperties properties;
  @Autowired private ApplicationEvents events;

  private College college;
  private SourceInbox source;
  private Student student;

  @BeforeEach
  void aStudent() {
    college = newCollege();
    source = newUnpolledSource(college, GOOGLE.newAccount());
    student = newStudent(college);
  }

  private UUID shortlistMatch() {
    return newMatch(
        college, source, student, EventType.SHORTLIST, "Example Corp", "Example Corp - Shortlist");
  }

  // ---- Push ----------------------------------------------------------------------------------

  @Test
  void aPushThatNtfyAcceptsIsSent() {
    String topic = ntfyChannel(student);
    UUID matchId = shortlistMatch();

    assertThat(dispatchUntilIdle()).isEqualTo(1);

    Map<String, Object> row = delivery(matchId, channelId(student, ChannelType.NTFY));
    assertThat(row)
        .containsEntry("status", "SENT")
        .containsEntry("attempts", 1)
        .containsEntry("last_error", null);
    assertThat(instant(row.get("sent_at"))).isCloseTo(Instant.now(), within(1, ChronoUnit.MINUTES));
    assertThat(ntfyPosts(topic))
        .singleElement()
        .satisfies(request -> {
          assertThat(request.getHeader("Title")).isEqualTo("Shortlisted: Example Corp");
          assertThat(request.getHeader("Priority")).isEqualTo("default");
          assertThat(request.getHeader("Tags")).isEqualTo("clipboard");
          assertThat(request.getHeader("Click"))
              .startsWith("https://mail.google.com/mail/u/0/#search/rfc822msgid:")
              .endsWith("%40mail.example");
          assertThat(request.getBodyAsString())
              .contains(student.neoId() + " | Tara Target")
              .contains("Received 13 Nov 2026, 4:05 PM IST");
        });
    assertThat(events.stream(DeliveryDead.class)).isEmpty();
  }

  @Test
  void anOfferIsPushedWithHighPriority() {
    String topic = ntfyChannel(student);
    newMatch(college, source, student, EventType.OFFER, "Example Corp", "Offer letters");

    dispatchUntilIdle();

    LoggedRequest request = ntfyPosts(topic).get(0);
    assertThat(request.getHeader("Title")).isEqualTo("Offer: Example Corp");
    assertThat(request.getHeader("Priority")).isEqualTo("high");
  }

  @Test
  void aSentDeliveryIsNeverSentAgain() {
    String topic = ntfyChannel(student);
    shortlistMatch();
    dispatchUntilIdle();

    jdbc.update("update delivery set next_attempt_at = now() - interval '1 hour'");

    assertThat(dispatchUntilIdle()).isZero();
    assertThat(ntfyPosts(topic)).hasSize(1);
  }

  @Test
  void aServerErrorIsRetriedAfterOneMinute() {
    String topic = ntfyChannel(student);
    ntfyAnswers(topic, 503);
    UUID matchId = shortlistMatch();

    dispatchUntilIdle();

    Map<String, Object> row = delivery(matchId, channelId(student, ChannelType.NTFY));
    assertThat(row)
        .containsEntry("status", "RETRY")
        .containsEntry("attempts", 1)
        .containsEntry("last_error", "HTTP 503")
        .containsEntry("sent_at", null);
    assertThat(instant(row.get("next_attempt_at")))
        .isCloseTo(Instant.now().plus(Duration.ofMinutes(1)), within(5, ChronoUnit.SECONDS));
    // Not due yet, so another run leaves it alone.
    assertThat(dispatchUntilIdle()).isZero();
    assertThat(ntfyPosts(topic)).hasSize(1);
  }

  @Test
  void tooManyRequestsIsRetried() {
    String topic = ntfyChannel(student);
    ntfyAnswers(topic, 429);
    UUID matchId = shortlistMatch();

    dispatchUntilIdle();

    assertThat(delivery(matchId, channelId(student, ChannelType.NTFY)))
        .containsEntry("status", "RETRY")
        .containsEntry("last_error", "HTTP 429");
  }

  @Test
  void aRetryThatSucceedsEndsSent() {
    String topic = ntfyChannel(student);
    NTFY.stubFor(
        WireMock.post(WireMock.urlPathEqualTo("/" + topic))
            .inScenario(topic)
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(WireMock.aResponse().withStatus(500))
            .willSetStateTo("recovered"));
    NTFY.stubFor(
        WireMock.post(WireMock.urlPathEqualTo("/" + topic))
            .inScenario(topic)
            .whenScenarioStateIs("recovered")
            .willReturn(WireMock.aResponse().withStatus(200)));
    UUID matchId = shortlistMatch();

    dispatchUntilIdle();
    makeAllDue();
    dispatchUntilIdle();

    assertThat(delivery(matchId, channelId(student, ChannelType.NTFY)))
        .containsEntry("status", "SENT")
        .containsEntry("attempts", 2)
        .containsEntry("last_error", null);
    assertThat(ntfyPosts(topic)).hasSize(2);
  }

  @Test
  void backsOffOneTwoFourEightSixteenMinutesAndDiesAfterTheSixthAttempt() {
    String topic = ntfyChannel(student);
    ntfyAnswers(topic, 500);
    UUID matchId = shortlistMatch();
    UUID channelId = channelId(student, ChannelType.NTFY);
    assertThat(properties.maxAttempts()).isEqualTo(6);

    List<Long> waitsInMinutes = new ArrayList<>();
    for (int attempt = 1; attempt <= 5; attempt++) {
      makeAllDue();
      dispatchUntilIdle();
      Map<String, Object> row = delivery(matchId, channelId);
      assertThat(row).containsEntry("status", "RETRY").containsEntry("attempts", attempt);
      waitsInMinutes.add(
          Math.round(
              Duration.between(Instant.now(), instant(row.get("next_attempt_at"))).toSeconds()
                  / 60.0));
    }
    assertThat(waitsInMinutes).containsExactly(1L, 2L, 4L, 8L, 16L);
    assertThat(events.stream(DeliveryDead.class)).isEmpty();

    makeAllDue();
    dispatchUntilIdle();

    assertThat(delivery(matchId, channelId))
        .containsEntry("status", "DEAD")
        .containsEntry("attempts", 6)
        .containsEntry("last_error", "HTTP 500")
        .containsEntry("sent_at", null);
    assertThat(events.stream(DeliveryDead.class))
        .singleElement()
        .satisfies(dead -> {
          assertThat(dead.matchId()).isEqualTo(matchId);
          assertThat(dead.channelId()).isEqualTo(channelId);
          assertThat(dead.channelType()).isEqualTo(ChannelType.NTFY);
          assertThat(dead.attempts()).isEqualTo(6);
          assertThat(dead.reason()).isEqualTo("HTTP 500");
          assertThat(dead.rfcMessageId()).endsWith("@mail.example>");
        });

    // Dead is final: no seventh attempt, however long we wait.
    jdbc.update("update delivery set next_attempt_at = now() - interval '1 hour'");
    assertThat(dispatchUntilIdle()).isZero();
    assertThat(ntfyPosts(topic)).hasSize(6);
  }

  @Test
  void aPermanentFailureIsDeadAtOnce() {
    String topic = ntfyChannel(student);
    ntfyAnswers(topic, 403);
    UUID matchId = shortlistMatch();

    dispatchUntilIdle();
    makeAllDue();
    dispatchUntilIdle();

    assertThat(delivery(matchId, channelId(student, ChannelType.NTFY)))
        .containsEntry("status", "DEAD")
        .containsEntry("attempts", 1)
        .containsEntry("last_error", "HTTP 403");
    assertThat(ntfyPosts(topic)).hasSize(1);
    assertThat(events.stream(DeliveryDead.class))
        .singleElement()
        .satisfies(dead -> assertThat(dead.reason()).isEqualTo("HTTP 403"));
  }

  @Test
  void aChannelSwitchedOffAfterQueueingIsNotSentTo() {
    String topic = ntfyChannel(student);
    UUID matchId = shortlistMatch();
    UUID channelId = channelId(student, ChannelType.NTFY);
    jdbc.update("update channel set enabled = false where id = ?", channelId);

    dispatchUntilIdle();

    assertThat(delivery(matchId, channelId))
        .containsEntry("status", "DEAD")
        .containsEntry("last_error", DeliveryDispatcher.CHANNEL_UNAVAILABLE);
    assertThat(ntfyPosts(topic)).isEmpty();
  }

  @Test
  void oneFailingDeliveryDoesNotHoldUpTheOthers() {
    String failing = ntfyChannel(student);
    ntfyAnswers(failing, 500);
    Student other = newStudent(college);
    String working = ntfyChannel(other);
    UUID failingMatch = shortlistMatch();
    UUID workingMatch =
        newMatch(college, source, other, EventType.SHORTLIST, "Example Corp", "Shortlist");

    dispatchUntilIdle();

    assertThat(delivery(failingMatch, channelId(student, ChannelType.NTFY)))
        .containsEntry("status", "RETRY");
    assertThat(delivery(workingMatch, channelId(other, ChannelType.NTFY)))
        .containsEntry("status", "SENT");
    assertThat(ntfyPosts(working)).hasSize(1);
  }

  // ---- Email ---------------------------------------------------------------------------------

  @Test
  void anEmailThatSesAcceptsIsSent() {
    UUID channelId = emailChannel(student, "tara@personal.example");
    when(ses.sendEmail(any(SendEmailRequest.class)))
        .thenReturn(SendEmailResponse.builder().messageId("ses-1").build());
    UUID matchId = shortlistMatch();

    dispatchUntilIdle();

    assertThat(delivery(matchId, channelId))
        .containsEntry("status", "SENT")
        .containsEntry("attempts", 1);
    ArgumentCaptor<SendEmailRequest> sent = ArgumentCaptor.forClass(SendEmailRequest.class);
    verify(ses, times(1)).sendEmail(sent.capture());
    SendEmailRequest request = sent.getValue();
    assertThat(request.fromEmailAddress()).isEqualTo(SES_FROM);
    assertThat(request.destination().toAddresses()).containsExactly("tara@personal.example");
    assertThat(request.content().simple().subject().data()).isEqualTo("Shortlisted: Example Corp");
    assertThat(request.content().simple().body().text().data())
        .contains(student.neoId() + " | Tara Target")
        .contains("13 Nov 2026, 4:05 PM IST")
        .contains("https://mail.google.com/mail/u/0/#search/rfc822msgid:");
    assertThat(request.content().simple().body().html().data())
        .contains(student.neoId())
        .contains("<a href=\"https://mail.google.com/mail/u/0/#search/rfc822msgid:");
  }

  @Test
  void sesThrottlingIsRetried() {
    UUID channelId = emailChannel(student, "tara@personal.example");
    when(ses.sendEmail(any(SendEmailRequest.class)))
        .thenThrow(TooManyRequestsException.builder().statusCode(429).message("slow").build());
    UUID matchId = shortlistMatch();

    dispatchUntilIdle();

    assertThat(delivery(matchId, channelId))
        .containsEntry("status", "RETRY")
        .containsEntry("attempts", 1)
        .containsEntry("last_error", "SES TooManyRequestsException HTTP 429");
  }

  @Test
  void anEmailThatNeverReachedSesIsRetried() {
    UUID channelId = emailChannel(student, "tara@personal.example");
    when(ses.sendEmail(any(SendEmailRequest.class)))
        .thenThrow(SdkClientException.create("connection reset"));
    UUID matchId = shortlistMatch();

    dispatchUntilIdle();

    assertThat(delivery(matchId, channelId))
        .containsEntry("status", "RETRY")
        .containsEntry("last_error", "SES SdkClientException");
  }

  @Test
  void aMessageSesRejectsIsDeadAtOnce() {
    UUID channelId = emailChannel(student, "tara@personal.example");
    when(ses.sendEmail(any(SendEmailRequest.class)))
        .thenThrow(
            MessageRejectedException.builder()
                .statusCode(400)
                .message("Email address is not verified: tara@personal.example")
                .build());
    UUID matchId = shortlistMatch();

    dispatchUntilIdle();
    makeAllDue();
    dispatchUntilIdle();

    Map<String, Object> row = delivery(matchId, channelId);
    assertThat(row).containsEntry("status", "DEAD").containsEntry("attempts", 1);
    // The class only: SES's message names the recipient.
    assertThat(row.get("last_error")).isEqualTo("SES MessageRejectedException");
    verify(ses, times(1)).sendEmail(any(SendEmailRequest.class));
    assertThat(events.stream(DeliveryDead.class))
        .singleElement()
        .satisfies(dead -> {
          assertThat(dead.channelType()).isEqualTo(ChannelType.EMAIL);
          assertThat(dead.toString()).doesNotContain("personal.example");
        });
  }

  @Test
  void eachChannelOfAMatchIsDeliveredOnItsOwn() {
    String topic = ntfyChannel(student);
    ntfyAnswers(topic, 500);
    UUID email = emailChannel(student, "tara@personal.example");
    when(ses.sendEmail(any(SendEmailRequest.class)))
        .thenReturn(SendEmailResponse.builder().build());
    UUID matchId = shortlistMatch();

    dispatchUntilIdle();

    assertThat(delivery(matchId, email)).containsEntry("status", "SENT");
    assertThat(delivery(matchId, channelId(student, ChannelType.NTFY)))
        .containsEntry("status", "RETRY");
  }

  // ---- Claiming ------------------------------------------------------------------------------

  @Test
  void aClaimedDeliveryIsInvisibleToOtherDispatchersUntilTheClaimTimesOut() {
    String topic = ntfyChannel(student);
    shortlistMatch();

    // Another instance claims the row and then dies before sending.
    assertThat(store.claimDue(Instant.now(), 50, Duration.ofMinutes(5))).hasSize(1);

    assertThat(dispatchUntilIdle()).isZero();
    verify(ses, never()).sendEmail(any(SendEmailRequest.class));
    assertThat(ntfyPosts(topic)).isEmpty();

    // Five minutes later the claim has run out and the alert still goes out, once.
    makeAllDue();
    assertThat(dispatchUntilIdle()).isEqualTo(1);
    assertThat(ntfyPosts(topic)).hasSize(1);
  }

  @Test
  void aRunClaimsAtMostOneBatch() {
    ntfyChannel(student);
    for (int i = 0; i < properties.batchSize() + 5; i++) {
      shortlistMatch();
    }

    assertThat(dispatchOnce()).isEqualTo(properties.batchSize());
    assertThat(dispatchOnce()).isEqualTo(5);
    assertThat(dispatchOnce()).isZero();
  }

  @Test
  void concurrentDispatchersNeverSendTheSameDeliveryTwice() throws Exception {
    int deliveries = 150;
    int dispatchers = 4;
    String topic = ntfyChannel(student);
    // Slow enough that the dispatchers overlap while sending.
    NTFY.stubFor(
        WireMock.post(WireMock.urlPathEqualTo("/" + topic))
            .willReturn(WireMock.aResponse().withStatus(200).withFixedDelay(5)));
    for (int i = 0; i < deliveries; i++) {
      shortlistMatch();
    }

    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(dispatchers);
    try {
      List<Future<Integer>> claimed = new ArrayList<>();
      for (int i = 0; i < dispatchers; i++) {
        claimed.add(
            pool.submit(() -> {
              start.await();
              int total = 0;
              while (unfinished() > 0) {
                total += dispatchOnce();
              }
              return total;
            }));
      }
      start.countDown();
      int total = 0;
      for (Future<Integer> future : claimed) {
        total += future.get(2, TimeUnit.MINUTES);
      }
      // Every delivery was claimed by exactly one dispatcher...
      assertThat(total).isEqualTo(deliveries);
    } finally {
      pool.shutdownNow();
    }

    // ...and posted exactly once.
    assertThat(ntfyPosts(topic)).hasSize(deliveries);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from delivery where status = 'SENT' and attempts = 1",
                Integer.class))
        .isEqualTo(deliveries);
  }

  private int unfinished() {
    return jdbc.queryForObject(
        "select count(*) from delivery where status in ('PENDING', 'RETRY')", Integer.class);
  }
}
