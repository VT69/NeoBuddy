package com.neobuddy.notification.channel;

import static org.assertj.core.api.Assertions.assertThat;

import com.neobuddy.notification.Alert;
import com.neobuddy.processing.EventType;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class AlertRendererTests {

  private static final Instant RECEIVED = Instant.parse("2026-11-13T19:45:00Z");

  private final AlertRenderer renderer = new AlertRenderer();

  private static Alert alert(EventType type, String company, String subject) {
    return new Alert(
        type, company, subject, "N1B7J5V6 | Tara Target | 10:30", RECEIVED, "<abc+1@mail.example>");
  }

  @Test
  void headlineNamesTheCompanyForShortlistsAndOffers() {
    assertThat(renderer.headline(alert(EventType.SHORTLIST, "Example Corp", "Shortlist - round 2")))
        .isEqualTo("Shortlisted: Example Corp");
    assertThat(renderer.headline(alert(EventType.OFFER, "Example Corp", "Offer letters")))
        .isEqualTo("Offer: Example Corp");
  }

  @Test
  void headlineQuotesTheSubjectForOtherMail() {
    assertThat(renderer.headline(alert(EventType.OTHER, "Example Corp", "Venue change for Friday")))
        .isEqualTo("You're mentioned: Venue change for Friday");
  }

  @Test
  void headlineFallsBackWhenCompanyOrSubjectIsMissing() {
    assertThat(renderer.headline(alert(EventType.SHORTLIST, null, "Round 2 list")))
        .isEqualTo("Shortlisted: Round 2 list");
    assertThat(renderer.headline(alert(EventType.OFFER, " ", null)))
        .isEqualTo("Offer: a placement email");
    assertThat(renderer.headline(alert(EventType.OTHER, null, null)))
        .isEqualTo("You're mentioned: a placement email");
  }

  @Test
  void headlineIsAlwaysOneShortLine() {
    String headline =
        renderer.headline(
            alert(EventType.OTHER, null, "Line one\r\nX-Injected: yes\t" + "x".repeat(300)));

    assertThat(headline).doesNotContain("\r").doesNotContain("\n").doesNotContain("\t");
    assertThat(headline).startsWith("You're mentioned: Line one X-Injected: yes x");
    assertThat(headline.length()).isLessThanOrEqualTo("You're mentioned: ".length() + 120);
    assertThat(headline).endsWith("…");
  }

  @Test
  void receivedTimeIsShownInIndianStandardTime() {
    // 19:45 UTC is 01:15 the next day in India.
    assertThat(renderer.receivedAt(alert(EventType.OTHER, null, "s")))
        .isEqualTo("14 Nov 2026, 1:15 AM IST");
  }

  @Test
  void mailboxLinkSearchesByMessageIdWithoutTheAngleBrackets() {
    assertThat(renderer.mailboxLink(alert(EventType.OTHER, null, "s")))
        .isEqualTo("https://mail.google.com/mail/u/0/#search/rfc822msgid:abc%2B1%40mail.example");
  }

  @Test
  void pushCarriesHeadlineRowTimeAndLink() {
    NtfyMessage offer = renderer.ntfy(alert(EventType.OFFER, "Example Corp", "Offers"));

    assertThat(offer.title()).isEqualTo("Offer: Example Corp");
    assertThat(offer.body())
        .isEqualTo("N1B7J5V6 | Tara Target | 10:30\nReceived 14 Nov 2026, 1:15 AM IST");
    assertThat(offer.priority()).isEqualTo("high");
    assertThat(offer.tags()).isEqualTo("tada");
    assertThat(offer.click()).startsWith("https://mail.google.com/mail/u/0/#search/rfc822msgid:");

    assertThat(renderer.ntfy(alert(EventType.SHORTLIST, "Example Corp", "s")).priority())
        .isEqualTo("default");
    assertThat(renderer.ntfy(alert(EventType.OTHER, null, "s")).priority()).isEqualTo("default");
  }

  @Test
  void aMissingRowIsSaidSoRatherThanLeftBlank() {
    Alert alert = new Alert(EventType.SHORTLIST, "Example Corp", "s", null, RECEIVED, "<a@b>");

    assertThat(renderer.ntfy(alert).body()).startsWith("Your identifier appears in this email.");
  }

  @Test
  void emailHasTextAndHtmlPartsWithTheSameContent() {
    EmailContent email = renderer.email(alert(EventType.SHORTLIST, "Example Corp", "Shortlist"));

    assertThat(email.subject()).isEqualTo("Shortlisted: Example Corp");
    assertThat(email.text())
        .contains("Shortlisted: Example Corp")
        .contains("N1B7J5V6 | Tara Target | 10:30")
        .contains("Received 14 Nov 2026, 1:15 AM IST")
        .contains("https://mail.google.com/mail/u/0/#search/rfc822msgid:abc%2B1%40mail.example");
    assertThat(email.html())
        .contains("Shortlisted: Example Corp")
        .contains("N1B7J5V6 | Tara Target | 10:30")
        .contains("Received 14 Nov 2026, 1:15 AM IST")
        .contains(
            "href=\"https://mail.google.com/mail/u/0/#search/rfc822msgid:abc%2B1%40mail.example\"");
  }

  @Test
  void emailHtmlEscapesWhatCameFromTheSheet() {
    Alert alert =
        new Alert(
            EventType.SHORTLIST,
            "R&D <Labs>",
            "s",
            "<script>alert(1)</script> | Tara",
            RECEIVED,
            "<a@b>");

    String html = renderer.email(alert).html();

    assertThat(html).doesNotContain("<script>").contains("&lt;script&gt;alert(1)&lt;/script&gt;");
    assertThat(html).contains("R&amp;D &lt;Labs&gt;");
  }

  @Test
  void otpEmailShowsTheCodeAndItsValidity() {
    EmailContent email = renderer.otpEmail("042917", Duration.ofMinutes(10));

    assertThat(email.subject()).doesNotContain("042917");
    assertThat(email.text()).contains("042917").contains("10 minutes");
    assertThat(email.html()).contains("042917").contains("10 minutes");
  }

  @Test
  void contentNeverShowsUpInToString() {
    Alert alert = alert(EventType.SHORTLIST, "Example Corp", "s");

    assertThat(alert.toString()).doesNotContain("Tara");
    assertThat(renderer.email(alert).toString()).doesNotContain("Tara");
    assertThat(renderer.ntfy(alert).toString()).doesNotContain("Tara");
  }
}
