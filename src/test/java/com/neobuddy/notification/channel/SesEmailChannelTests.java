package com.neobuddy.notification.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.neobuddy.notification.Alert;
import com.neobuddy.notification.DeliveryResult;
import com.neobuddy.processing.EventType;
import com.neobuddy.subscriber.ChannelAddress;
import com.neobuddy.subscriber.ChannelType;
import com.neobuddy.subscriber.VerificationNotSentException;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.AccountSuspendedException;
import software.amazon.awssdk.services.sesv2.model.BadRequestException;
import software.amazon.awssdk.services.sesv2.model.LimitExceededException;
import software.amazon.awssdk.services.sesv2.model.MessageRejectedException;
import software.amazon.awssdk.services.sesv2.model.NotFoundException;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;
import software.amazon.awssdk.services.sesv2.model.SendEmailResponse;
import software.amazon.awssdk.services.sesv2.model.SesV2Exception;
import software.amazon.awssdk.services.sesv2.model.TooManyRequestsException;

class SesEmailChannelTests {

  private static final ChannelAddress TO = new ChannelAddress("tara@personal.example");
  private static final ChannelProperties.RateLimit FAST =
      new ChannelProperties.RateLimit(1000, Duration.ofSeconds(1), Duration.ofSeconds(1));

  private final SesV2Client ses = mock(SesV2Client.class);
  private final AlertRenderer renderer = new AlertRenderer();
  private final SesEmailChannel channel =
      new SesEmailChannel(
          ses,
          new ChannelProperties.Ses(" NeoBuddy <alerts@neobuddy.example> ", "ap-south-1", FAST),
          renderer);

  private static Alert alert() {
    return new Alert(
        EventType.OFFER,
        "Example Corp",
        "Offer letters",
        "N1B7J5V6 | Tara Target",
        Instant.parse("2026-11-13T10:35:00Z"),
        "<abc@mail.example>");
  }

  private DeliveryResult sendWhenSesThrows(RuntimeException failure) {
    when(ses.sendEmail(any(SendEmailRequest.class))).thenThrow(failure);
    return channel.send(TO, alert());
  }

  @Test
  void isTheEmailChannel() {
    assertThat(channel.type()).isEqualTo(ChannelType.EMAIL);
  }

  @Test
  void sendsFromTheConfiguredAddressWithTextAndHtmlParts() {
    when(ses.sendEmail(any(SendEmailRequest.class)))
        .thenReturn(SendEmailResponse.builder().messageId("id").build());

    assertThat(channel.send(TO, alert())).isEqualTo(DeliveryResult.success());

    ArgumentCaptor<SendEmailRequest> sent = ArgumentCaptor.forClass(SendEmailRequest.class);
    verify(ses).sendEmail(sent.capture());
    SendEmailRequest request = sent.getValue();
    assertThat(request.fromEmailAddress()).isEqualTo("NeoBuddy <alerts@neobuddy.example>");
    assertThat(request.destination().toAddresses()).containsExactly("tara@personal.example");
    assertThat(request.destination().ccAddresses()).isEmpty();
    assertThat(request.destination().bccAddresses()).isEmpty();
    assertThat(request.content().simple().subject().data()).isEqualTo("Offer: Example Corp");
    assertThat(request.content().simple().subject().charset()).isEqualTo("UTF-8");
    assertThat(request.content().simple().body().text().data()).contains("N1B7J5V6 | Tara Target");
    assertThat(request.content().simple().body().html().data())
        .contains("N1B7J5V6 | Tara Target")
        .contains("<a href=");
  }

  @Test
  void throttlingIsRetryable() {
    assertThat(sendWhenSesThrows(TooManyRequestsException.builder().statusCode(429).build()))
        .isEqualTo(DeliveryResult.retryable("SES TooManyRequestsException HTTP 429"));
  }

  @Test
  void anExceededSendingLimitIsRetryable() {
    assertThat(sendWhenSesThrows(LimitExceededException.builder().statusCode(400).build()))
        .isEqualTo(DeliveryResult.retryable("SES LimitExceededException HTTP 400"));
  }

  @Test
  void aServerErrorIsRetryable() {
    assertThat(
            sendWhenSesThrows(
                SesV2Exception.builder().statusCode(503).message("unavailable").build()))
        .isEqualTo(DeliveryResult.retryable("SES SesV2Exception HTTP 503"));
  }

  @Test
  void aFailureBeforeReachingSesIsRetryable() {
    assertThat(sendWhenSesThrows(SdkClientException.create("Unable to load credentials")))
        .isEqualTo(DeliveryResult.retryable("SES SdkClientException"));
  }

  @Test
  void aRejectedMessageIsPermanentAndItsReasonHidesTheAddress() {
    DeliveryResult result =
        sendWhenSesThrows(
            MessageRejectedException.builder()
                .statusCode(400)
                .message("Email address is not verified: tara@personal.example")
                .build());

    assertThat(result).isEqualTo(DeliveryResult.permanent("SES MessageRejectedException"));
  }

  @Test
  void aMalformedRequestIsPermanent() {
    assertThat(sendWhenSesThrows(BadRequestException.builder().statusCode(400).build()))
        .isEqualTo(DeliveryResult.permanent("SES BadRequestException"));
  }

  @Test
  void aRefusalAboutOurAccountRatherThanTheMessageIsRetryable() {
    assertThat(sendWhenSesThrows(AccountSuspendedException.builder().statusCode(400).build()))
        .isEqualTo(DeliveryResult.retryable("SES AccountSuspendedException HTTP 400"));
  }

  @Test
  void badCredentialsOrAMissingPermissionAreRetryable() {
    assertThat(
            sendWhenSesThrows(
                SesV2Exception.builder().statusCode(403).message("not authorized").build()))
        .isEqualTo(DeliveryResult.retryable("SES SesV2Exception HTTP 403"));
  }

  @Test
  void anUnknownSesResourceIsPermanent() {
    assertThat(sendWhenSesThrows(NotFoundException.builder().statusCode(404).build()))
        .isEqualTo(DeliveryResult.permanent("SES NotFoundException"));
  }

  @Test
  void refusesToStartWithoutASender() {
    assertThatThrownBy(() ->
            new SesEmailChannel(ses, new ChannelProperties.Ses(" ", "ap-south-1", FAST), renderer))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("neobuddy.channels.ses.from");
  }

  @Test
  void oneTimeCodesGoOutThroughTheSameTransport() {
    when(ses.sendEmail(any(SendEmailRequest.class)))
        .thenReturn(SendEmailResponse.builder().build());

    new OtpMailer(channel, renderer).send(TO, "042917", Duration.ofMinutes(10));

    ArgumentCaptor<SendEmailRequest> sent = ArgumentCaptor.forClass(SendEmailRequest.class);
    verify(ses).sendEmail(sent.capture());
    assertThat(sent.getValue().destination().toAddresses())
        .containsExactly("tara@personal.example");
    assertThat(sent.getValue().content().simple().body().text().data()).contains("042917");
  }

  @Test
  void aCodeThatCouldNotBeSentIsReportedWithoutTheCodeOrAddress() {
    when(ses.sendEmail(any(SendEmailRequest.class)))
        .thenThrow(MessageRejectedException.builder().statusCode(400).message("no").build());

    assertThatThrownBy(
            () -> new OtpMailer(channel, renderer).send(TO, "042917", Duration.ofMinutes(10)))
        .isInstanceOf(VerificationNotSentException.class)
        .hasMessageContaining("SES MessageRejectedException")
        .message()
        .doesNotContain("042917")
        .doesNotContain("personal.example");
  }

  @Test
  void theLoggingChannelSendsNothingAndReportsSuccess() {
    LoggingEmailChannel logging = new LoggingEmailChannel();

    assertThat(logging.type()).isEqualTo(ChannelType.EMAIL);
    assertThat(logging.send(TO, alert())).isEqualTo(DeliveryResult.success());
    assertThat(logging.send(TO, renderer.otpEmail("042917", Duration.ofMinutes(10))))
        .isEqualTo(DeliveryResult.success());
  }
}
