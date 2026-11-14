package com.neobuddy.web.admin;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.springframework.stereotype.Component;

/** Times on the admin pages, in IST: {@code ${@adminTime.format(instant)}}. */
@Component("adminTime")
public class AdminTime {

  private static final DateTimeFormatter FORMAT =
      DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH)
          .withZone(ZoneId.of("Asia/Kolkata"));

  private final Clock clock;

  AdminTime(Clock clock) {
    this.clock = clock;
  }

  public String format(Instant instant) {
    return instant == null ? "never" : FORMAT.format(instant) + " IST";
  }

  public boolean past(Instant instant) {
    return instant != null && !instant.isAfter(clock.instant());
  }

  /** "5 min ago", "3 h ago", "2 d ago". */
  public String ago(Instant instant) {
    if (instant == null) {
      return "never";
    }
    Duration age = Duration.between(instant, clock.instant());
    if (age.isNegative() || age.toMinutes() < 1) {
      return "just now";
    }
    if (age.toHours() < 1) {
      return age.toMinutes() + " min ago";
    }
    if (age.toDays() < 1) {
      return age.toHours() + " h ago";
    }
    return age.toDays() + " d ago";
  }
}
