package com.neobuddy.notification.channel;

import com.neobuddy.notification.Alert;
import com.neobuddy.notification.AlertFormat;
import com.neobuddy.processing.EventType;
import java.time.Duration;
import java.time.ZoneId;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

/**
 * Words an alert for each channel. Every channel gets the same four things: a headline, the
 * student's own row, when the email arrived (in IST) and a link that searches the student's own
 * mailbox for the email.
 */
@Component
class AlertRenderer {

  static final ZoneId IST = AlertFormat.IST;
  static final String TEST_HEADLINE = AlertFormat.TEST_HEADLINE;

  private static final String WHY =
      "You get this because one of your identifiers appears in a placement email.";

  String headline(Alert alert) {
    return AlertFormat.headline(alert);
  }

  String receivedAt(Alert alert) {
    return AlertFormat.receivedAt(alert);
  }

  String mailboxLink(Alert alert) {
    return AlertFormat.mailboxLink(alert);
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
    return AlertFormat.snippet(alert);
  }

  private static String escape(String value) {
    return HtmlUtils.htmlEscape(value, "UTF-8");
  }
}
