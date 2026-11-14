package com.neobuddy.notification.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.neobuddy.ingestion.source.SourceInbox;
import com.neobuddy.notification.ChannelHealth;
import com.neobuddy.notification.ChannelHealth.ChannelState;
import com.neobuddy.notification.DeliveryAdmin;
import com.neobuddy.notification.DeliveryAdmin.DeadDelivery;
import com.neobuddy.processing.EventType;
import com.neobuddy.shared.college.College;
import com.neobuddy.shared.observability.LogContext;
import com.neobuddy.subscriber.ChannelType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * T11 on the outbox: the delivery metrics, the DeliveryDead log event CloudWatch counts, the
 * channel health the indicators read, and the admin's retry and discard.
 */
class DeliveryObservabilityTests extends DeliveryDatabaseTest {

  @Autowired private MeterRegistry meters;
  @Autowired private DeliveryAdmin admin;
  @Autowired private ChannelHealth channelHealth;

  private College college;
  private SourceInbox source;
  private Student student;
  private ListAppender<ILoggingEvent> logs;

  @BeforeEach
  void aStudent() {
    college = newCollege();
    source = newUnpolledSource(college, GOOGLE.newAccount());
    student = newStudent(college);
    logs = new ListAppender<>();
    logs.start();
    dispatcherLogger().addAppender(logs);
  }

  @AfterEach
  void detach() {
    dispatcherLogger().detachAppender(logs);
  }

  private static Logger dispatcherLogger() {
    return (Logger) LoggerFactory.getLogger(DeliveryDispatcher.class);
  }

  private UUID shortlistMatch() {
    return newMatch(
        college, source, student, EventType.SHORTLIST, "Example Corp", "Example Corp - Shortlist");
  }

  private double dead(String channel) {
    Counter counter =
        meters
            .find("deliveries.dead")
            .tag("college", college.getSlug())
            .tag("channel", channel)
            .counter();
    return counter == null ? 0 : counter.count();
  }

  @Test
  void aSentAlertRecordsItsLatencyTaggedWithTheCollege() {
    ntfyChannel(student);
    shortlistMatch();

    dispatchUntilIdle();

    Timer latency =
        meters
            .find("delivery.latency")
            .tag("college", college.getSlug())
            .tag("channel", "ntfy")
            .timer();
    assertThat(latency).isNotNull();
    assertThat(latency.count()).isEqualTo(1);
    assertThat(latency.totalTime(java.util.concurrent.TimeUnit.SECONDS)).isPositive();
    assertThat(logs.list)
        .filteredOn(e -> e.getFormattedMessage().contains("SENT"))
        .singleElement()
        .satisfies(e -> {
          assertThat(e.getMDCPropertyMap())
              .containsEntry(LogContext.COLLEGE_SLUG, college.getSlug())
              .containsKey(LogContext.RFC_MESSAGE_ID);
        });
  }

  @Test
  void aDeadDeliveryIsCountedAndLoggedAsADeliveryDeadEvent() {
    String topic = ntfyChannel(student);
    ntfyAnswers(topic, 403);
    shortlistMatch();

    dispatchUntilIdle();

    assertThat(dead("ntfy")).isEqualTo(1);
    assertThat(logs.list)
        .filteredOn(e -> "DeliveryDead".equals(e.getMDCPropertyMap().get(LogContext.EVENT)))
        .singleElement()
        .satisfies(e -> {
          assertThat(e.getFormattedMessage()).contains("DEAD").doesNotContain(topic);
          assertThat(e.getMDCPropertyMap()).containsEntry("collegeSlug", college.getSlug());
        });
    // Only that one line carries the event marker.
    assertThat(logs.list)
        .filteredOn(e -> e.getMDCPropertyMap().containsKey(LogContext.EVENT))
        .hasSize(1);
  }

  @Test
  void retryingADeadDeliveryQueuesItAgainWithFreshAttempts() {
    String topic = ntfyChannel(student);
    ntfyAnswers(topic, 403);
    UUID matchId = shortlistMatch();
    dispatchUntilIdle();
    UUID channelId = channelId(student, ChannelType.NTFY);
    UUID deliveryId = (UUID) delivery(matchId, channelId).get("id");

    List<DeadDelivery> listed = admin.dead(0, 200);
    assertThat(listed)
        .filteredOn(d -> d.id().equals(deliveryId))
        .singleElement()
        .satisfies(d -> {
          assertThat(d.collegeId()).isEqualTo(college.getId());
          assertThat(d.channelType()).isEqualTo(ChannelType.NTFY);
          assertThat(d.reason()).isEqualTo("HTTP 403");
          assertThat(d.subject()).isEqualTo("Example Corp - Shortlist");
          assertThat(d.attempts()).isEqualTo(1);
        });

    ntfyAnswers(topic, 200);
    assertThat(admin.retry(deliveryId)).isTrue();
    assertThat(delivery(matchId, channelId))
        .containsEntry("status", "PENDING")
        .containsEntry("attempts", 0)
        .containsEntry("last_error", null);
    assertThat(admin.retry(deliveryId)).as("only a DEAD delivery can be retried").isFalse();

    dispatchUntilIdle();
    assertThat(delivery(matchId, channelId)).containsEntry("status", "SENT");
    assertThat(ntfyPosts(topic)).hasSize(2);
  }

  @Test
  void aDiscardedDeliveryIsNeverSentAndNotQueuedAgain() {
    String topic = ntfyChannel(student);
    ntfyAnswers(topic, 403);
    UUID matchId = shortlistMatch();
    dispatchUntilIdle();
    UUID channelId = channelId(student, ChannelType.NTFY);
    UUID deliveryId = (UUID) delivery(matchId, channelId).get("id");

    assertThat(admin.discard(deliveryId)).isTrue();
    assertThat(admin.discard(deliveryId)).isFalse();
    assertThat(admin.retry(deliveryId)).isFalse();
    assertThat(delivery(matchId, channelId)).containsEntry("status", "DISCARDED");
    assertThat(admin.dead(0, 200)).extracting(DeadDelivery::id).doesNotContain(deliveryId);

    // Announcing the same matches again, as a reprocess does, queues nothing new.
    String messageId =
        jdbc.queryForObject("select email_id from match_hit where id = ?", String.class, matchId);
    transactions.executeWithoutResult(tx ->
        publisher.publishEvent(
            matchesFound(college, messageId, EventType.SHORTLIST, "Example Corp", student)));
    makeAllDue();
    assertThat(dispatchUntilIdle()).isZero();
    assertThat(ntfyPosts(topic)).hasSize(1);
  }

  @Test
  void aChannelIsDegradedAfterThreeRetryableFailuresInARowAndRecoversOnSuccess() {
    String topic = ntfyChannel(student);
    ntfyAnswers(topic, 503);
    shortlistMatch();

    for (int attempt = 1; attempt <= ChannelHealth.DEGRADED_AFTER; attempt++) {
      makeAllDue();
      dispatchUntilIdle();
    }
    assertThat(ntfyState().state()).isEqualTo(ChannelHealth.State.DEGRADED);
    assertThat(ntfyState().lastFailureReason()).isEqualTo("HTTP 503");

    ntfyAnswers(topic, 200);
    makeAllDue();
    dispatchUntilIdle();
    assertThat(ntfyState().state()).isEqualTo(ChannelHealth.State.OK);
    assertThat(ntfyState().consecutiveFailures()).isZero();
  }

  @Test
  void aPermanentFailureIsAboutTheStudentNotTheProvider() {
    String topic = ntfyChannel(student);
    ntfyAnswers(topic, 403);
    int before = ntfyState().consecutiveFailures();

    shortlistMatch();
    dispatchUntilIdle();

    assertThat(ntfyState().consecutiveFailures()).isEqualTo(before);
  }

  private ChannelState ntfyState() {
    return channelHealth.states().stream()
        .filter(s -> s.type() == ChannelType.NTFY)
        .findFirst()
        .orElseThrow();
  }
}
