package com.neobuddy.notification.channel;

import com.neobuddy.notification.Alert;
import com.neobuddy.processing.EventType;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

/**
 * Words an alert for each channel. Every channel gets the same four things: a headline, the
 * student's own row, when the email arrived (in IST) and a link that searches the student's own
 * mailbox for the email.
 */
@Component
class AlertRenderer {

  static final ZoneId IST = ZoneId.of("Asia/Kolkata");

  private static final DateTimeFormatter RECEIVED =
      DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a", Locale.ENGLISH).withZone(IST);
  private static final String MAILBOX_SEARCH = "https://mail.google.com/mail/u/0/#search/";
  private static final int MAX_HEADLINE_SUBJECT = 120;
  static final String TEST_HEADLINE = "Test alert: NeoBuddy can reach you here";
  private static final String NO_SNIPPET = "Your identifier appears in this email.";
  private static final String WHY =
      "You get this because one of your identifiers appears in a placement email.";

  /** "Shortlisted: Company", "Offer: Company" or "You're mentioned: subject". Always one line. */
  String headline(Alert alert) {
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
  String receivedAt(Alert alert) {
    return RECEIVED.format(alert.receivedAt()) + " IST";
  }

  /** Opens Gmail's search for exactly this email, in whichever mailbox the reader is signed in. */
  String mailboxLink(Alert alert) {
    if (alert.test()) {
      // A test is about no real email, so there is nothing to find.
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

  NtfyMessage ntfy(Alert alert) {
    String body = snippet(alert) + "\nReceived " + receivedAt(alert);
    return new NtfyMessage(
        headline(alert),
        body,
        alert.test()
            ? "white_check_mark"
            : switch (alert.eventType()) {
              case OFFER -> "tada";
              case SHORTLIST -> "clipboard";
              case OTHER -> "bell";
            },
        alert.eventType() == EventType.OFFER ? "high" : "default",
        mailboxLink(alert));
  }

  EmailContent email(Alert alert) {
    String headline = headline(alert);
    String snippet = snippet(alert);
    String received = "Received " + receivedAt(alert);
    String link = mailboxLink(alert);
    String text =
        String.join(
            "\n",
            headline,
            "",
            snippet,
            "",
            received,
            link == null ? "" : "Find the email in your mailbox: " + link,
            "",
            WHY,
            "NeoBuddy");
    String html =
        "<div style=\"font-family:Arial,Helvetica,sans-serif;font-size:15px;color:#222\">"
            + "<h2 style=\"font-size:18px;margin:0 0 12px\">"
            + escape(headline)
            + "</h2>"
            + "<p style=\"background:#f4f4f4;padding:10px;border-radius:4px;"
            + "font-family:Consolas,monospace;white-space:pre-wrap\">"
            + escape(snippet)
            + "</p>"
            + "<p>"
            + escape(received)
            + "</p>"
            + (link == null
                ? ""
                : "<p><a href=\"" + escape(link) + "\">Find the email in your mailbox</a></p>")
            + "<p style=\"font-size:12px;color:#777\">"
            + escape(WHY)
            + "<br>NeoBuddy</p>"
            + "</div>";
    return new EmailContent(headline, text, html);
  }

  /** The email that carries a one-time code to an address being verified. */
  EmailContent otpEmail(String code, Duration validFor) {
    String validity = "It works for " + Math.max(1, validFor.toMinutes()) + " minutes.";
    String ignore = "If you did not ask for this, ignore this email.";
    String text =
        String.join(
            "\n",
            "Your NeoBuddy verification code is " + code + ".",
            validity,
            "",
            ignore,
            "NeoBuddy");
    String html =
        "<div style=\"font-family:Arial,Helvetica,sans-serif;font-size:15px;color:#222\">"
            + "<p>Your NeoBuddy verification code is</p>"
            + "<p style=\"font-size:26px;letter-spacing:4px;font-weight:bold\">"
            + escape(code)
            + "</p>"
            + "<p>"
            + escape(validity)
            + "</p>"
            + "<p style=\"font-size:12px;color:#777\">"
            + escape(ignore)
            + "<br>NeoBuddy</p>"
            + "</div>";
    return new EmailContent("Your NeoBuddy verification code", text, html);
  }

  private static String snippet(Alert alert) {
    return alert.snippet() == null || alert.snippet().isBlank()
        ? NO_SNIPPET
        : alert.snippet().strip();
  }

  /** Collapses line breaks and control characters, so the text is safe in a header or subject. */
  private static String oneLine(String value) {
    return value == null ? "" : value.replaceAll("[\\s\\p{Cntrl}]+", " ").strip();
  }

  private static String shortened(String value) {
    return value.length() <= MAX_HEADLINE_SUBJECT
        ? value
        : value.substring(0, MAX_HEADLINE_SUBJECT - 1).stripTrailing() + "…";
  }

  private static String escape(String value) {
    return HtmlUtils.htmlEscape(value, "UTF-8");
  }
}
