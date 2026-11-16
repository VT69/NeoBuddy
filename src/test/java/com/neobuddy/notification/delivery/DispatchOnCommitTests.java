package com.neobuddy.notification.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.neobuddy.ingestion.source.SourceInbox;
import com.neobuddy.notification.TestAlerts;
import com.neobuddy.processing.EventType;
import com.neobuddy.shared.college.College;
import com.neobuddy.subscriber.ChannelType;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * The Render profile's delivery mode: alerts go out right after their transaction commits, with the
 * scheduled dispatcher still off (as in every test), so nothing here calls the dispatcher.
 */
@TestPropertySource(properties = "neobuddy.delivery.dispatch-on-commit=true")
class DispatchOnCommitTests extends DeliveryDatabaseTest {

  private static final Duration WAIT = Duration.ofSeconds(10);

  @Autowired private TestAlerts testAlerts;

  private College college;
  private SourceInbox source;
  private Student student;

  @BeforeEach
  void aStudent() {
    college = newCollege();
    source = newUnpolledSource(college, GOOGLE.newAccount());
    student = newStudent(college);
  }

  private String statusOf(UUID matchId, UUID channelId) {
    return (String) delivery(matchId, channelId).get("status");
  }

  @Test
  void aMatchIsPushedRightAfterItCommits() {
    String topic = ntfyChannel(student);

    UUID matchId =
        newMatch(college, source, student, EventType.SHORTLIST, "Example Corp", "Shortlist");

    UUID channelId = channelId(student, ChannelType.NTFY);
    await().atMost(WAIT).until(() -> "SENT".equals(statusOf(matchId, channelId)));
    assertThat(ntfyPosts(topic)).hasSize(1);
  }

  @Test
  void aTestAlertIsPushedRightAway() {
    String topic = ntfyChannel(student);

    testAlerts.send(student.id(), channelId(student, ChannelType.NTFY));

    await().atMost(WAIT).until(() -> ntfyPosts(topic).size() == 1);
  }

  @Test
  void aFailedSendIsLeftForTheSweepToRetry() {
    String topic = ntfyChannel(student);
    ntfyAnswers(topic, 503);

    UUID matchId =
        newMatch(college, source, student, EventType.OFFER, "Example Corp", "Offer letters");

    UUID channelId = channelId(student, ChannelType.NTFY);
    await().atMost(WAIT).until(() -> "RETRY".equals(statusOf(matchId, channelId)));
    ntfyAnswers(topic, 200);
    makeAllDue();
    dispatchUntilIdle();
    assertThat(statusOf(matchId, channelId)).isEqualTo("SENT");
  }
}
