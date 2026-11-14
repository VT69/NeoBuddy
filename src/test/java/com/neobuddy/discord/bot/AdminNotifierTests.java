package com.neobuddy.discord.bot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.neobuddy.ingestion.SourceAuthExpired;
import com.neobuddy.notification.DeliveryDead;
import com.neobuddy.processing.EmailProcessingFailed;
import com.neobuddy.shared.college.College;
import com.neobuddy.shared.college.CollegeRepository;
import com.neobuddy.subscriber.ChannelType;
import com.neobuddy.subscriber.TestColleges;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AdminNotifierTests {

  /** A clock tests move by hand. */
  static final class MovableClock extends Clock {
    Instant now = Instant.parse("2026-11-14T10:00:00Z");

    void advance(Duration by) {
      now = now.plus(by);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  private final OpsChannel ops = mock(OpsChannel.class);
  private final CollegeRepository colleges = mock(CollegeRepository.class);
  private final MovableClock clock = new MovableClock();
  private final DiscordProperties properties =
      new DiscordProperties(
          "token", "1", "2", null, 5, Duration.ofHours(1), Duration.ofMinutes(10));
  private final AdminNotifier notifier = new AdminNotifier(ops, colleges, properties, clock);
  private College college;

  @BeforeEach
  void aCollege() {
    college = TestColleges.pilot();
    when(colleges.findById(college.getId())).thenReturn(Optional.of(college));
  }

  private String lastPost() {
    ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
    verify(ops, org.mockito.Mockito.atLeastOnce()).post(text.capture());
    return text.getValue();
  }

  private DeliveryDead dead() {
    return new DeliveryDead(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        ChannelType.NTFY,
        "<m@x>",
        6,
        "HTTP 500");
  }

  @Test
  void aSourceThatLostAccessIsAlwaysPosted() {
    UUID source = UUID.randomUUID();

    notifier.on(new SourceAuthExpired(source, college.getId()));
    notifier.on(new SourceAuthExpired(source, college.getId()));

    verify(ops, times(2)).post(any());
    assertThat(lastPost())
        .contains("Source inbox lost Gmail access")
        .contains(college.getSlug())
        .contains(source.toString())
        .contains("authorizeSource");
  }

  @Test
  void fiveDeadDeliveriesInAnHourAreQuietAndTheSixthPostsOnce() {
    for (int i = 0; i < 5; i++) {
      notifier.on(dead());
    }
    verify(ops, never()).post(any());

    notifier.on(dead());
    assertThat(lastPost())
        .contains("6 alerts failed for good")
        .contains("NTFY")
        .contains("HTTP 500");

    // More in the same hour: no second post.
    for (int i = 0; i < 10; i++) {
      clock.advance(Duration.ofMinutes(1));
      notifier.on(dead());
    }
    verify(ops, times(1)).post(any());
  }

  @Test
  void deadDeliveriesSpreadOverMoreThanAnHourNeverPost() {
    for (int i = 0; i < 20; i++) {
      notifier.on(dead());
      clock.advance(Duration.ofMinutes(15));
    }

    verify(ops, never()).post(any());
  }

  @Test
  void anotherBurstAnHourLaterPostsAgain() {
    for (int i = 0; i < 6; i++) {
      notifier.on(dead());
    }
    clock.advance(Duration.ofMinutes(61));
    for (int i = 0; i < 6; i++) {
      notifier.on(dead());
    }

    verify(ops, times(2)).post(any());
  }

  @Test
  void processingFailuresArePostedAtMostEveryTenMinutesWithACountOfTheRest() {
    notifier.on(new EmailProcessingFailed(college.getId(), "<a@x>", "java.io.IOException"));
    assertThat(lastPost())
        .contains("Processing failed")
        .contains("<a@x>")
        .contains("java.io.IOException")
        .contains(college.getSlug());

    clock.advance(Duration.ofMinutes(1));
    notifier.on(new EmailProcessingFailed(college.getId(), "<b@x>", "java.io.IOException"));
    notifier.on(new EmailProcessingFailed(college.getId(), "<c@x>", "java.io.IOException"));
    verify(ops, times(1)).post(any());

    clock.advance(Duration.ofMinutes(10));
    notifier.on(
        new EmailProcessingFailed(college.getId(), "<d@x>", "java.lang.IllegalStateException"));
    verify(ops, times(2)).post(any());
    assertThat(lastPost()).contains("<d@x>").contains("Also 2 more failure(s)");
  }
}
