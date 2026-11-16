package com.neobuddy.notification.channel;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.neobuddy.notification.Alert;
import com.neobuddy.notification.DeliveryResult;
import com.neobuddy.notification.DeliveryResult.Outcome;
import com.neobuddy.processing.EventType;
import com.neobuddy.subscriber.ChannelAddress;
import com.neobuddy.subscriber.ChannelType;
import com.neobuddy.subscriber.VerificationNotSentException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class BrevoEmailChannelTests {

  private static final String API_KEY = "xkeysib-test-key";
  private static final ChannelAddress TO = new ChannelAddress("tara@personal.example");
  private static final ChannelProperties.RateLimit FAST =
      new ChannelProperties.RateLimit(1000, Duration.ofSeconds(1), Duration.ofSeconds(1));

  private static WireMockServer brevo;

  private final AlertRenderer renderer = new AlertRenderer();

  @BeforeAll
  static void startBrevo() {
    brevo = new WireMockServer(wireMockConfig().dynamicPort());
    brevo.start();
  }

  @AfterAll
  static void stopBrevo() {
    brevo.stop();
  }

  @BeforeEach
  void reset() {
    brevo.resetAll();
  }

  private static ChannelProperties.Brevo settings(String apiKey, String from) {
    return new ChannelProperties.Brevo(
        apiKey, from, brevo.baseUrl() + "/", Duration.ofSeconds(2), Duration.ofMillis(500), FAST);
  }

  private BrevoEmailChannel channel() {
    return new BrevoEmailChannel(
        settings(" " + API_KEY + " ", " NeoBuddy <alerts@neobuddy.example> "), renderer);
  }

  private static Alert alert() {
    return new Alert(
        EventType.OFFER,
        "Example Corp",
        "Offer letters",
        "N1B7J5V6 | Tara Target",
        Instant.parse("2026-11-13T10:35:00Z"),
        "<abc@mail.example>");
  }

  private static void brevoAnswers(ResponseDefinitionBuilder response) {
    brevo.stubFor(post(anyUrl()).willReturn(response));
  }

  private static LoggedRequest onlyRequest() {
    return brevo.findAll(postRequestedFor(anyUrl())).getFirst();
  }

  @Test
  void isTheEmailChannel() {
    assertThat(channel().type()).isEqualTo(ChannelType.EMAIL);
  }

  @Test
  void postsTheEmailToBrevoWithTheKeyInAHeader() {
    brevoAnswers(aResponse().withStatus(201).withBody("{\"messageId\":\"<id@smtp-relay>\"}"));

    assertThat(channel().send(TO, alert())).isEqualTo(DeliveryResult.success());

    assertThat(brevo.findAll(postRequestedFor(anyUrl()))).hasSize(1);
    LoggedRequest request = onlyRequest();
    assertThat(request.getUrl()).isEqualTo("/v3/smtp/email");
    assertThat(request.getHeader("api-key")).isEqualTo(API_KEY);
    assertThat(request.getHeader("Content-Type")).startsWith("application/json");
    EmailContent expected = renderer.email(alert());
    brevo.verify(
        postRequestedFor(anyUrl())
            .withRequestBody(
                equalToJson(
                    """
                    {
                      "sender": {"email": "alerts@neobuddy.example", "name": "NeoBuddy"},
                      "to": [{"email": "tara@personal.example"}],
                      "subject": "%s",
                      "textContent": "${json-unit.any-string}",
                      "htmlContent": "${json-unit.any-string}"
                    }
                    """
                        .formatted(expected.subject()))));
    assertThat(request.getBodyAsString()).contains("N1B7J5V6 | Tara Target");
  }

  @Test
  void aBareSenderAddressHasNoName() {
    brevoAnswers(aResponse().withStatus(201));

    new BrevoEmailChannel(settings(API_KEY, "alerts@neobuddy.example"), renderer).send(TO, alert());

    assertThat(onlyRequest().getBodyAsString())
        .contains("\"sender\":{\"email\":\"alerts@neobuddy.example\"}")
        .doesNotContain("null");
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "NeoBuddy <a@b.example>      | a@b.example | NeoBuddy",
        "\"Neo Buddy\" <a@b.example> | a@b.example | Neo Buddy",
        "<a@b.example>               | a@b.example |",
        "a@b.example                 | a@b.example |",
      })
  void readsTheSender(String from, String email, String name) {
    assertThat(BrevoEmailChannel.sender(from)).isEqualTo(new BrevoEmailChannel.Party(email, name));
  }

  @ParameterizedTest
  @ValueSource(ints = {429, 500, 502, 503})
  void throttlingAndServerErrorsAreRetryable(int status) {
    brevoAnswers(aResponse().withStatus(status));

    assertThat(channel().send(TO, alert()))
        .isEqualTo(DeliveryResult.retryable("Brevo HTTP " + status));
  }

  @ParameterizedTest
  @CsvSource({
    "401, unauthorized",
    "402, not_enough_credits",
    "403, permission_denied",
  })
  void refusalsAboutOurAccountAreRetryable(int status, String code) {
    brevoAnswers(
        aResponse()
            .withStatus(status)
            .withBody("{\"code\":\"" + code + "\",\"message\":\"Key not found\"}"));

    assertThat(channel().send(TO, alert()))
        .isEqualTo(DeliveryResult.retryable("Brevo HTTP " + status + " " + code));
  }

  @Test
  void aRejectedMessageIsPermanentAndItsReasonHidesTheAddress() {
    brevoAnswers(
        aResponse()
            .withStatus(400)
            .withBody(
                "{\"code\":\"invalid_parameter\","
                    + "\"message\":\"email is not valid in to: tara@personal.example\"}"));

    DeliveryResult result = channel().send(TO, alert());

    assertThat(result).isEqualTo(DeliveryResult.permanent("Brevo HTTP 400 invalid_parameter"));
    assertThat(result.reason()).doesNotContain("personal.example");
  }

  @Test
  void anErrorWithoutAJsonBodyStillHasAReason() {
    brevoAnswers(aResponse().withStatus(404).withBody("<html>not found</html>"));

    assertThat(channel().send(TO, alert())).isEqualTo(DeliveryResult.permanent("Brevo HTTP 404"));
  }

  @Test
  void aBrokenConnectionIsRetryable() {
    brevoAnswers(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER));

    DeliveryResult result = channel().send(TO, alert());

    assertThat(result.outcome()).isEqualTo(Outcome.RETRYABLE);
    assertThat(result.reason()).startsWith("Brevo IO ").doesNotContain("personal.example");
  }

  @Test
  void aSlowAnswerIsRetryable() {
    brevoAnswers(aResponse().withStatus(201).withFixedDelay(2_000));

    assertThat(channel().send(TO, alert()).outcome()).isEqualTo(Outcome.RETRYABLE);
  }

  @Test
  void errorCodesAreOnlyTakenWhenTheyLookLikeCodes() {
    assertThat(errorCode("{\"code\":\"duplicate_parameter\"}")).isEqualTo("duplicate_parameter");
    assertThat(errorCode("{\"code\":\"tara@personal.example\"}")).isNull();
    assertThat(errorCode("")).isNull();
  }

  private static String errorCode(String body) {
    return BrevoEmailChannel.errorCode(
        new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void refusesToStartWithoutAKeyOrASender() {
    assertThatThrownBy(() -> new BrevoEmailChannel(settings(" ", "a@b.example"), renderer))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("NEOBUDDY_BREVO_API_KEY");
    assertThatThrownBy(() -> new BrevoEmailChannel(settings(API_KEY, null), renderer))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("NEOBUDDY_BREVO_FROM");
  }

  @Test
  void theSettingsNeverPrintTheKey() {
    assertThat(settings(API_KEY, "a@b.example").toString()).doesNotContain(API_KEY);
  }

  @Test
  void oneTimeCodesGoOutThroughTheSameTransport() {
    brevoAnswers(aResponse().withStatus(201));

    new OtpMailer(channel(), renderer).send(TO, "042917", Duration.ofMinutes(10));

    assertThat(onlyRequest().getBodyAsString())
        .contains("tara@personal.example")
        .contains("042917");
  }

  @Test
  void aCodeThatCouldNotBeSentIsReportedWithoutTheCodeOrAddress() {
    brevoAnswers(aResponse().withStatus(400).withBody("{\"code\":\"invalid_parameter\"}"));

    assertThatThrownBy(
            () -> new OtpMailer(channel(), renderer).send(TO, "042917", Duration.ofMinutes(10)))
        .isInstanceOf(VerificationNotSentException.class)
        .hasMessageContaining("Brevo HTTP 400")
        .message()
        .doesNotContain("042917")
        .doesNotContain("personal.example");
  }
}
