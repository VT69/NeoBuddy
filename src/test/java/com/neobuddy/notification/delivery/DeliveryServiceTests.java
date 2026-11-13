package com.neobuddy.notification.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import com.neobuddy.ingestion.source.SourceInbox;
import com.neobuddy.processing.EventType;
import com.neobuddy.shared.college.College;
import com.neobuddy.subscriber.ChannelAddress;
import com.neobuddy.subscriber.ChannelType;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** How matches become outbox rows. */
class DeliveryServiceTests extends DeliveryDatabaseTest {

  private College college;
  private SourceInbox source;
  private Student student;

  @BeforeEach
  void aStudent() {
    college = newCollege();
    source = newUnpolledSource(college, GOOGLE.newAccount());
    student = newStudent(college);
  }

  @Test
  void queuesOneDeliveryPerVerifiedEnabledChannel() {
    ntfyChannel(student);
    emailChannel(student, "tara@personal.example");

    UUID matchId = newMatch(college, source, student, EventType.SHORTLIST, "Example Corp", "s");

    assertThat(deliveriesOf(matchId))
        .hasSize(2)
        .allSatisfy(row ->
            assertThat(row)
                .containsEntry("status", "PENDING")
                .containsEntry("attempts", 0)
                .containsEntry("sent_at", null));
    assertThat(deliveriesOf(matchId))
        .extracting(row -> row.get("channel_id"))
        .containsExactlyInAnyOrder(
            channelId(student, ChannelType.NTFY), channelId(student, ChannelType.EMAIL));
  }

  @Test
  void anUnverifiedChannelGetsNothing() {
    subscribers.addChannel(
        student.id(), ChannelType.EMAIL, new ChannelAddress("unproven@personal.example"));

    UUID matchId = newMatch(college, source, student, EventType.SHORTLIST, "Example Corp", "s");

    assertThat(deliveriesOf(matchId)).isEmpty();
  }

  @Test
  void aSwitchedOffChannelGetsNothing() {
    ntfyChannel(student);
    UUID email = emailChannel(student, "tara@personal.example");
    jdbc.update("update channel set enabled = false where id = ?", email);

    UUID matchId = newMatch(college, source, student, EventType.SHORTLIST, "Example Corp", "s");

    assertThat(deliveriesOf(matchId))
        .singleElement()
        .satisfies(row ->
            assertThat(row).containsEntry("channel_id", channelId(student, ChannelType.NTFY)));
  }

  @Test
  void aChannelTypeNothingCanSendToYetGetsNothing() {
    UUID discord =
        subscribers
            .addChannel(student.id(), ChannelType.DISCORD, new ChannelAddress("123456789"))
            .id();
    subscribers.markVerified(student.id(), discord);

    UUID matchId = newMatch(college, source, student, EventType.SHORTLIST, "Example Corp", "s");

    assertThat(deliveriesOf(matchId)).isEmpty();
  }

  @Test
  void theSameMatchesAnnouncedAgainQueueNothingNew() {
    ntfyChannel(student);
    String messageId = newEmail(college, source, EventType.OFFER, "Example Corp", "Offers");
    UUID matchId = storeMatch(messageId, student, EventType.OFFER, "row");

    for (int i = 0; i < 3; i++) {
      transactions.executeWithoutResult(tx ->
          publisher.publishEvent(
              matchesFound(college, messageId, EventType.OFFER, "Example Corp", student)));
    }

    assertThat(deliveriesOf(matchId)).hasSize(1);
  }

  @Test
  void deliveriesShareTheMatchsTransactionSoARollbackLeavesNeither() {
    ntfyChannel(student);
    UUID[] matchId = new UUID[1];
    int[] queuedInsideTheTransaction = new int[1];

    transactions.executeWithoutResult(tx -> {
      String messageId = newEmail(college, source, EventType.SHORTLIST, "Example Corp", "s");
      matchId[0] = storeMatch(messageId, student, EventType.SHORTLIST, "row");
      publisher.publishEvent(
          matchesFound(college, messageId, EventType.SHORTLIST, "Example Corp", student));
      queuedInsideTheTransaction[0] = deliveriesOf(matchId[0]).size();
      tx.setRollbackOnly();
    });

    // Written by the listener before the publisher's transaction ended, and gone with it.
    assertThat(queuedInsideTheTransaction[0]).isEqualTo(1);
    assertThat(deliveriesOf(matchId[0])).isEmpty();
    assertThat(count("match_hit", "id", matchId[0])).isZero();
    assertThat(dispatchUntilIdle()).isZero();
  }

  @Test
  void aHitThatWasNeverStoredGetsNoDelivery() {
    ntfyChannel(student);
    Student stored = newStudent(college);
    ntfyChannel(stored);
    String messageId = newEmail(college, source, EventType.SHORTLIST, "Example Corp", "s");
    UUID storedMatch = storeMatch(messageId, stored, EventType.SHORTLIST, "row");

    // The event names both students, but only one match made it into match_hit.
    transactions.executeWithoutResult(tx ->
        publisher.publishEvent(
            matchesFound(
                college, messageId, EventType.SHORTLIST, "Example Corp", student, stored)));

    assertThat(deliveriesOf(storedMatch)).hasSize(1);
    assertThat(jdbc.queryForObject("select count(*) from delivery", Integer.class)).isEqualTo(1);
  }

  @Test
  void onlyTheUsersNamedInTheEventAreQueued() {
    ntfyChannel(student);
    Student earlier = newStudent(college);
    ntfyChannel(earlier);
    String messageId = newEmail(college, source, EventType.SHORTLIST, "Example Corp", "s");
    UUID earlierMatch = storeMatch(messageId, earlier, EventType.SHORTLIST, "row");
    UUID match = storeMatch(messageId, student, EventType.SHORTLIST, "row");

    transactions.executeWithoutResult(tx ->
        publisher.publishEvent(
            matchesFound(college, messageId, EventType.SHORTLIST, "Example Corp", student)));

    assertThat(deliveriesOf(match)).hasSize(1);
    assertThat(deliveriesOf(earlierMatch)).isEmpty();
  }
}
