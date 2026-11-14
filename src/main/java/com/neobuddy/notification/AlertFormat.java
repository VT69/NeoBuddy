package com.neobuddy.notification;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * How an alert is worded, the same on every channel: a one-line headline, the student's own row,
 * when the email arrived (in IST) and a link that searches the student's own mailbox for it.
 * Channels outside this module (Discord) use it too.
 */
public final class AlertFormat {

  public static final ZoneId IST = ZoneId.of("Asia/Kolkata");
  public static final String TEST_HEADLINE = "Test alert: NeoBuddy can reach you here";
  public static final String NO_SNIPPET = "Your identifier appears in this email.";

  private static final DateTimeFormatter RECEIVED =
      DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a", Locale.ENGLISH).withZone(IST);
  private static final String MAILBOX_SEARCH = "https://mail.google.com/mail/u/0/#search/";
  private static final int MAX_HEADLINE_SUBJECT = 120;

  private AlertFormat() {}

  /** "Shortlisted: Company", "Offer: Company" or "You're mentioned: subject". Always one line. */
  public static String headline(Alert alert) {
    if (alert.test()) {
      return TEST_HEADLINE;
    }
    String subject = oneLine(alert.subject());
    String company = oneLine(alert.company());
    String about = !company.isEmpty() ? company : subject;
    if (about.isEmpty()) {
      about = "a placement email";
    }
    return switch (alert.eventType()) {
      case SHORTLIST -> "Shortlisted: " + shortened(about);
      case OFFER -> "Offer: " + shortened(about);
      case OTHER -> "You're mentioned: " + shortened(subject.isEmpty() ? about : subject);
    };
  }

  /** For example "13 Nov 2026, 4:05 PM IST". */
  public static String receivedAt(Alert alert) {
    return RECEIVED.format(alert.receivedAt()) + " IST";
  }

  /**
   * Opens Gmail's search for exactly this email, in whichever mailbox the reader is signed in; null
   * for a test alert, which is about no real email.
   */
  public static String mailboxLink(Alert alert) {
    if (alert.test()) {
      return null;
    }
    String id = alert.rfcMessageId().strip();
    if (id.startsWith("<") && id.endsWith(">")) {
      id = id.substring(1, id.length() - 1);
    }
    return MAILBOX_SEARCH
        + "rfc822msgid:"
        + URLEncoder.encode(id, StandardCharsets.UTF_8).replace("+", "%20");
  }

  /** The student's row, or a sentence saying the email mentions them. */
  public static String snippet(Alert alert) {
    return alert.snippet() == null || alert.snippet().isBlank()
        ? NO_SNIPPET
        : alert.snippet().strip();
  }

  /** Collapses line breaks and control characters, so the text is safe in a header or subject. */
  public static String oneLine(String value) {
    return value == null ? "" : value.replaceAll("[\\s\\p{Cntrl}]+", " ").strip();
  }

  private static String shortened(String value) {
    return value.length() <= MAX_HEADLINE_SUBJECT
        ? value
        : value.substring(0, MAX_HEADLINE_SUBJECT - 1).stripTrailing() + "…";
  }
}
