package com.neobuddy.discord.bot;

import com.neobuddy.ingestion.SourceAuthExpired;
import com.neobuddy.notification.DeliveryDead;
import com.neobuddy.processing.EmailProcessingFailed;
import com.neobuddy.shared.college.College;
import com.neobuddy.shared.college.CollegeRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import org.springframework.modulith.events.ApplicationModuleListener;

/**
 * Tells the admin in #ops when something needs a person:
 *
 * <ul>
 *   <li>a source inbox lost its Gmail access (every time);
 *   <li>more than 5 deliveries went DEAD within an hour (once per hour at most);
 *   <li>an email could not be processed (at most once per 10 minutes, the rest counted).
 * </ul>
 *
 * Posts carry ids, the college slug and error classes; never an address or email content. The
 * listeners run after the publishing transaction committed, off the request thread.
 */
class AdminNotifier {

  private final OpsChannel ops;
  private final CollegeRepository colleges;
  private final DiscordProperties properties;
  private final Clock clock;

  private final Deque<Instant> recentDead = new ArrayDeque<>();
  private Instant lastDeadAlert = Instant.EPOCH;
  private Instant lastFailureAlert = Instant.EPOCH;
  private int unreportedFailures;

  AdminNotifier(
      OpsChannel ops, CollegeRepository colleges, DiscordProperties properties, Clock clock) {
    this.ops = ops;
    this.colleges = colleges;
    this.properties = properties;
    this.clock = clock;
  }

  @ApplicationModuleListener
  void on(SourceAuthExpired event) {
    ops.post(
        ":warning: **Source inbox lost Gmail access** (college "
            + slugOf(event.collegeId())
            + ", source `"
            + event.sourceId()
            + "`). It is AUTH_EXPIRED and no longer polled. Run `./gradlew authorizeSource`, put"
            + " the new token in SSM `NEOBUDDY_BOOTSTRAP_SOURCE_REFRESH_TOKEN` and redeploy.");
  }

  @ApplicationModuleListener
  void on(DeliveryDead event) {
    String message = null;
    synchronized (this) {
      Instant now = clock.instant();
      recentDead.addLast(now);
      Instant windowStart = now.minus(properties.deadAlertWindow());
      while (!recentDead.isEmpty() && recentDead.peekFirst().isBefore(windowStart)) {
        recentDead.removeFirst();
      }
      if (recentDead.size() > properties.deadAlertThreshold()
          && !lastDeadAlert.isAfter(windowStart)) {
        lastDeadAlert = now;
        message =
            ":rotating_light: **"
                + recentDead.size()
                + " alerts failed for good** in the last "
                + minutes(properties.deadAlertWindow())
                + ". Latest: "
                + event.channelType()
                + ", "
                + event.reason()
                + ". Check the `delivery` table (status DEAD).";
      }
    }
    if (message != null) {
      ops.post(message);
    }
  }

  @ApplicationModuleListener
  void on(EmailProcessingFailed event) {
    String message = null;
    synchronized (this) {
      Instant now = clock.instant();
      if (lastFailureAlert.plus(properties.failureAlertGap()).isAfter(now)) {
        unreportedFailures++;
      } else {
        lastFailureAlert = now;
        message =
            ":x: **Processing failed** for email `"
                + event.rfcMessageId()
                + "` (college "
                + slugOf(event.collegeId())
                + "): "
                + event.errorClass()
                + ". It is marked FAILED."
                + (unreportedFailures > 0
                    ? " Also " + unreportedFailures + " more failure(s) since the last post."
                    : "");
        unreportedFailures = 0;
      }
    }
    if (message != null) {
      ops.post(message);
    }
  }

  private String slugOf(java.util.UUID collegeId) {
    return colleges.findById(collegeId).map(College::getSlug).orElse(String.valueOf(collegeId));
  }

  private static String minutes(Duration window) {
    return window.toMinutes() == 60 ? "hour" : window.toMinutes() + " minutes";
  }
}
