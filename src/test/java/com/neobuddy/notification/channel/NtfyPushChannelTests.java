package com.neobuddy.notification.channel;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.neobuddy.notification.Alert;
import com.neobuddy.notification.DeliveryResult;
import com.neobuddy.notification.DeliveryResult.Outcome;
import com.neobuddy.processing.EventType;
import com.neobuddy.subscriber.ChannelAddress;
import com.neobuddy.subscriber.ChannelType;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class NtfyPushChannelTests {

  private static final String TOPIC = "nb-abcdefghijklmnopqrstuvwxyz";
  private static final ChannelAddress TO = new ChannelAddress(TOPIC);

  private static WireMockServer ntfy;

  @BeforeAll
  static void startNtfy() {
    ntfy = new WireMockServer(wireMockConfig().dynamicPort());
    ntfy.start();
  }

  @AfterAll
  static void stopNtfy() {
    ntfy.stop();
  }

  @BeforeEach
  void reset() {
    ntfy.resetAll();
  }

  private static NtfyPushChannel channel(ChannelProperties.RateLimit rateLimit) {
    return new NtfyPushChannel(
        new ChannelProperties(
            new ChannelProperties.Ntfy(
                ntfy.baseUrl() + "/", Duration.ofSeconds(2), Duration.ofMillis(500), rateLimit),
            new ChannelProperties.Email("log"),
            new ChannelProperties.Ses(null, "ap-south-1", rateLimit)),
        new AlertRenderer());
  }

  private static NtfyPushChannel channel() {
    return channel(
        new ChannelProperties.RateLimit(1000, Duration.ofSeconds(1), Duration.ofSeconds(1)));
  }

  private static Alert alert(EventType type, String company) {
    return new Alert(
        type,
        company,
        "Subject",
        "N1B7J5V6 | Tara Target | 10:30",
        Instant.parse("2026-11-13T10:35:00Z"),
        "<abc@mail.example>");
  }

  @Test
  void isTheNtfyChannel() {
    assertThat(channel().type()).isEqualTo(ChannelType.NTFY);
  }

  @Test
  void postsTheAlertToTheTopicWithTitleTagsPriorityAndLink() {
    ntfy.stubFor(post(anyUrl()).willReturn(aResponse().withStatus(200)));

    DeliveryResult result = channel().send(TO, alert(EventType.OFFER, "Example Corp"));

    assertThat(result).isEqualTo(DeliveryResult.success());
    assertThat(ntfy.findAll(postRequestedFor(anyUrl())))
        .singleElement()
        .satisfies(request -> {
          assertThat(request.getUrl()).isEqualTo("/" + TOPIC);
          assertThat(request.getHeader("Title")).isEqualTo("Offer: Example Corp");
          assertThat(request.getHeader("Tags")).isEqualTo("tada");
          assertThat(request.getHeader("Priority")).isEqualTo("high");
          assertThat(request.getHeader("Click"))
              .isEqualTo("https://mail.google.com/mail/u/0/#search/rfc822msgid:abc%40mail.example");
          assertThat(request.getHeader("Content-Type")).startsWith("text/plain");
          assertThat(request.getBodyAsString())
              .isEqualTo("N1B7J5V6 | Tara Target | 10:30\nReceived 13 Nov 2026, 4:05 PM IST");
        });
  }

  @Test
  void aShortlistHasDefaultPriority() {
    ntfy.stubFor(post(anyUrl()).willReturn(aResponse().withStatus(200)));

    channel().send(TO, alert(EventType.SHORTLIST, "Example Corp"));

    LoggedRequest request = ntfy.findAll(postRequestedFor(anyUrl())).get(0);
    assertThat(request.getHeader("Priority")).isEqualTo("default");
    assertThat(request.getHeader("Title")).isEqualTo("Shortlisted: Example Corp");
  }

  @Test
  void aTitleBeyondAsciiIsSentInEncodedForm() {
    ntfy.stubFor(post(anyUrl()).willReturn(aResponse().withStatus(200)));

    DeliveryResult result = channel().send(TO, alert(EventType.SHORTLIST, "Señor & Søn टेक"));

    assertThat(result.outcome()).isEqualTo(Outcome.SUCCESS);
    String title = ntfy.findAll(postRequestedFor(anyUrl())).get(0).getHeader("Title");
    assertThat(title).startsWith("=?UTF-8?B?").endsWith("?=");
    String decoded =
        new String(
            Base64.getDecoder().decode(title.substring(10, title.length() - 2)),
            StandardCharsets.UTF_8);
    assertThat(decoded).isEqualTo("Shortlisted: Señor & Søn टेक");
  }

  @ParameterizedTest
  @ValueSource(ints = {429, 500, 502, 503, 504})
  void throttlingAndServerErrorsAreRetryable(int status) {
    ntfy.stubFor(post(anyUrl()).willReturn(aResponse().withStatus(status)));

    assertThat(channel().send(TO, alert(EventType.SHORTLIST, "Example Corp")))
        .isEqualTo(DeliveryResult.retryable("HTTP " + status));
  }

  @ParameterizedTest
  @ValueSource(ints = {400, 401, 403, 404, 413})
  void otherClientErrorsArePermanent(int status) {
    ntfy.stubFor(post(anyUrl()).willReturn(aResponse().withStatus(status)));

    assertThat(channel().send(TO, alert(EventType.SHORTLIST, "Example Corp")))
        .isEqualTo(DeliveryResult.permanent("HTTP " + status));
  }

  @Test
  void aTimeoutIsRetryableAndItsReasonHidesTheTopic() {
    ntfy.stubFor(post(anyUrl()).willReturn(aResponse().withStatus(200).withFixedDelay(3000)));

    DeliveryResult result = channel().send(TO, alert(EventType.SHORTLIST, "Example Corp"));

    assertThat(result.outcome()).isEqualTo(Outcome.RETRYABLE);
    assertThat(result.reason()).startsWith("IO ").doesNotContain(TOPIC).doesNotContain("http");
  }

  @Test
  void aBrokenConnectionIsRetryable() {
    ntfy.stubFor(post(anyUrl()).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

    assertThat(channel().send(TO, alert(EventType.SHORTLIST, "Example Corp")).outcome())
        .isEqualTo(Outcome.RETRYABLE);
  }

  @ParameterizedTest
  @ValueSource(strings = {"../admin", "topic/with/slash", "topic?x=1", "has space", "ünïcode"})
  void anAddressThatIsNotATopicIsNeverPutIntoAUrl(String address) {
    DeliveryResult result =
        channel().send(new ChannelAddress(address), alert(EventType.SHORTLIST, "Example Corp"));

    assertThat(result).isEqualTo(DeliveryResult.permanent("INVALID_TOPIC"));
    assertThat(ntfy.getAllServeEvents()).isEmpty();
  }

  @Test
  void sendsWaitForTheRateLimitInsteadOfFailing() {
    ntfy.stubFor(post(anyUrl()).willReturn(aResponse().withStatus(200)));
    NtfyPushChannel limited =
        channel(new ChannelProperties.RateLimit(2, Duration.ofMillis(300), Duration.ofSeconds(5)));

    long started = System.nanoTime();
    for (int i = 0; i < 5; i++) {
      assertThat(limited.send(TO, alert(EventType.SHORTLIST, "Example Corp")).outcome())
          .isEqualTo(Outcome.SUCCESS);
    }
    Duration took = Duration.ofNanos(System.nanoTime() - started);

    // Two per 300 ms: the fifth send has to wait for the third period.
    assertThat(took).isGreaterThanOrEqualTo(Duration.ofMillis(400));
    assertThat(ntfy.findAll(postRequestedFor(urlPathEqualTo("/" + TOPIC)))).hasSize(5);
  }

  @Test
  void aSendThatCannotGetATurnInTimeIsRetryableWithoutCallingNtfy() {
    ntfy.stubFor(post(anyUrl()).willReturn(aResponse().withStatus(200)));
    NtfyPushChannel limited =
        channel(new ChannelProperties.RateLimit(1, Duration.ofMinutes(1), Duration.ofMillis(50)));

    assertThat(limited.send(TO, alert(EventType.SHORTLIST, "Example Corp")).outcome())
        .isEqualTo(Outcome.SUCCESS);
    assertThat(limited.send(TO, alert(EventType.SHORTLIST, "Example Corp")))
        .isEqualTo(DeliveryResult.retryable(SendRateLimiter.RATE_LIMITED));
    assertThat(ntfy.findAll(postRequestedFor(anyUrl()))).hasSize(1);
  }
}
