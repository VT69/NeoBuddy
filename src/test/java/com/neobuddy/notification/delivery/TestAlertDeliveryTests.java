package com.neobuddy.notification.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.neobuddy.ingestion.source.SourceInbox;
import com.neobuddy.notification.AlertHistory;
import com.neobuddy.notification.AlertSummary;
import com.neobuddy.notification.DeliveryStatus;
import com.neobuddy.notification.TestAlertNotPossibleException;
import com.neobuddy.notification.TestAlertNotPossibleException.Reason;
import com.neobuddy.notification.TestAlerts;
import com.neobuddy.processing.EventType;
import com.neobuddy.shared.college.College;
import com.neobuddy.subscriber.ChannelType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** A test alert travels the normal pipeline: synthetic match, delivery row, dispatcher, ntfy. */
class TestAlertDeliveryTests extends DeliveryDatabaseTest {

  @Autowired private TestAlerts testAlerts;
  @Autowired private AlertHistory history;

  private College college;
  private Student student;

  @BeforeEach
  void aStudent() {
    college = newCollege();
    student = newStudent(college);
  }

  @Test
  void aTestAlertIsPushedOnceWithTheTestWordingAndNoMailboxLink() {
    String topic = ntfyChannel(student);
    emailChannel(student, "tara@personal.example");

    testAlerts.send(student.id(), channelId(student, ChannelType.NTFY));
    dispatchUntilIdle();

    assertThat(ntfyPosts(topic))
        .singleElement()
        .satisfies((LoggedRequest request) -> {
          assertThat(request.getHeader("Title"))
              .isEqualTo("Test alert: NeoBuddy can reach you here");
          assertThat(request.getHeader("Tags")).isEqualTo("white_check_mark");
          assertThat(request.containsHeader("Click")).isFalse();
          assertThat(request.getBodyAsString()).startsWith("This is a test.");
        });
    // Only the channel asked for; the email channel got nothing.
    assertThat(jdbc.queryForObject("select count(*) from delivery", Integer.class)).isEqualTo(1);
  }

  @Test
  void theHistoryShowsTestAndRealAlertsNewestFirstWithTheirDeliveries() {
    String topic = ntfyChannel(student);
    SourceInbox source = newUnpolledSource(college, GOOGLE.newAccount());
    newMatch(college, source, student, EventType.OFFER, "Example Corp", "Offer letters");
    dispatchUntilIdle();
    testAlerts.send(student.id(), channelId(student, ChannelType.NTFY));

    List<AlertSummary> recent = history.recent(student.id(), 0, 20);

    assertThat(recent).hasSize(2);
    assertThat(recent.get(0).test()).isTrue();
    assertThat(recent.get(0).deliveries())
        .singleElement()
        .satisfies(d -> {
          assertThat(d.channelType()).isEqualTo(ChannelType.NTFY);
          assertThat(d.status()).isEqualTo(DeliveryStatus.PENDING);
        });
    assertThat(recent.get(1).test()).isFalse();
    assertThat(recent.get(1).company()).isEqualTo("Example Corp");
    assertThat(recent.get(1).eventType()).isEqualTo(EventType.OFFER);
    assertThat(recent.get(1).snippet()).isEqualTo(student.neoId() + " | Tara Target");
    assertThat(recent.get(1).deliveries())
        .singleElement()
        .satisfies(d -> assertThat(d.status()).isEqualTo(DeliveryStatus.SENT));
    assertThat(history.recent(student.id(), 1, 20)).isEmpty();
    assertThat(history.recent(newStudent(college).id(), 0, 20)).isEmpty();
    assertThat(ntfyPosts(topic)).hasSize(1);
  }

  @Test
  void aTestNeedsAReadyChannelOfTheUserAndAnIdentifier() {
    UUID unverified = channelIdAfterAddingUnverifiedEmail();

    assertThatThrownBy(() -> testAlerts.send(student.id(), unverified))
        .isInstanceOfSatisfying(
            TestAlertNotPossibleException.class,
            e -> assertThat(e.reason()).isEqualTo(Reason.CHANNEL_NOT_READY));
    assertThatThrownBy(() -> testAlerts.send(student.id(), UUID.randomUUID()))
        .isInstanceOfSatisfying(
            TestAlertNotPossibleException.class,
            e -> assertThat(e.reason()).isEqualTo(Reason.CHANNEL_NOT_FOUND));

    ntfyChannel(student);
    UUID push = channelId(student, ChannelType.NTFY);
    subscribers.removeIdentifier(student.id(), student.identifier().id());
    assertThatThrownBy(() -> testAlerts.send(student.id(), push))
        .isInstanceOfSatisfying(
            TestAlertNotPossibleException.class,
            e -> assertThat(e.reason()).isEqualTo(Reason.NO_IDENTIFIER));
    assertThat(
            jdbc.queryForObject(
                "select count(*) from match_hit where user_id = ?", Integer.class, student.id()))
        .isZero();
  }

  @Test
  void testMatchesAndEmailsAreFlaggedSoTheyNeverCountAsPlacementMail() {
    ntfyChannel(student);
    testAlerts.send(student.id(), channelId(student, ChannelType.NTFY));

    assertThat(
            jdbc.queryForObject(
                "select count(*) from match_hit m join processed_email e on e.rfc_message_id = m.email_id"
                    + " where m.user_id = ? and m.is_test and e.is_test and e.source_id is null"
                    + " and e.rfc_message_id like '<test-%@neobuddy.invalid>'",
                Integer.class, student.id()))
        .isEqualTo(1);
  }

  @Test
  void aRealEmailWithoutASourceIsStillRefusedByTheDatabase() {
    assertThatThrownBy(() ->
            jdbc.update(
                "insert into processed_email (rfc_message_id, college_id, gmail_id, sender, received_at, status)"
                    + " values ('<x@y>', ?, 'g', 's', now(), 'RECEIVED')",
                college.getId()))
        .hasMessageContaining("processed_email_source_required");
  }

  private UUID channelIdAfterAddingUnverifiedEmail() {
    return subscribers
        .addChannel(
            student.id(),
            ChannelType.EMAIL,
            new com.neobuddy.subscriber.ChannelAddress("u@personal.example"))
        .id();
  }
}
