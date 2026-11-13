package com.neobuddy.notification.channel;

import com.neobuddy.notification.Alert;
import com.neobuddy.notification.DeliveryResult;
import com.neobuddy.notification.NotificationChannel;
import com.neobuddy.subscriber.ChannelAddress;
import com.neobuddy.subscriber.ChannelType;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.BadRequestException;
import software.amazon.awssdk.services.sesv2.model.Body;
import software.amazon.awssdk.services.sesv2.model.Content;
import software.amazon.awssdk.services.sesv2.model.Destination;
import software.amazon.awssdk.services.sesv2.model.EmailContent.Builder;
import software.amazon.awssdk.services.sesv2.model.Message;
import software.amazon.awssdk.services.sesv2.model.MessageRejectedException;
import software.amazon.awssdk.services.sesv2.model.NotFoundException;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;
import software.amazon.awssdk.services.sesv2.model.SesV2Exception;

/**
 * Email through Amazon SES (API v2), with a plain-text and a simple HTML part.
 *
 * <p>A failure is permanent only when the message itself is at fault: SES rejected it (for example
 * an unverified recipient while the account is in the SES sandbox) or the request was malformed.
 * Everything else is retryable: throttling, SES server errors, anything that never reached SES, and
 * refusals that are about our account rather than this message (bad credentials, missing
 * permission, sending paused), which an operator can fix while the retries last. Failure reasons
 * name the exception class and HTTP status only; SES's messages can name the recipient.
 */
class SesEmailChannel implements NotificationChannel, EmailTransport {

  private static final String UTF_8 = "UTF-8";

  private final SesV2Client ses;
  private final String from;
  private final AlertRenderer renderer;
  private final SendRateLimiter rateLimiter;

  SesEmailChannel(SesV2Client ses, ChannelProperties.Ses settings, AlertRenderer renderer) {
    if (settings.from() == null || settings.from().isBlank()) {
      throw new IllegalStateException(
          "neobuddy.channels.ses.from (NEOBUDDY_SES_FROM) must be set to send email through SES");
    }
    this.ses = ses;
    this.from = settings.from().strip();
    this.renderer = renderer;
    this.rateLimiter = new SendRateLimiter("ses", settings.rateLimit());
  }

  @Override
  public ChannelType type() {
    return ChannelType.EMAIL;
  }

  @Override
  public DeliveryResult send(ChannelAddress to, Alert alert) {
    return send(to, renderer.email(alert));
  }

  @Override
  public DeliveryResult send(ChannelAddress to, EmailContent content) {
    return rateLimiter.send(() -> sendNow(to, content));
  }

  private DeliveryResult sendNow(ChannelAddress to, EmailContent content) {
    SendEmailRequest request =
        SendEmailRequest.builder()
            .fromEmailAddress(from)
            .destination(Destination.builder().toAddresses(to.value()).build())
            .content((Builder builder) ->
                builder.simple(
                    Message.builder()
                        .subject(utf8(content.subject()))
                        .body(
                            Body.builder()
                                .text(utf8(content.text()))
                                .html(utf8(content.html()))
                                .build())
                        .build()))
            .build();
    try {
      ses.sendEmail(request);
      return DeliveryResult.success();
    } catch (MessageRejectedException | BadRequestException | NotFoundException e) {
      return DeliveryResult.permanent(reason(e));
    } catch (SesV2Exception e) {
      return DeliveryResult.retryable(reason(e) + " HTTP " + e.statusCode());
    } catch (SdkException e) {
      return DeliveryResult.retryable(reason(e));
    }
  }

  private static Content utf8(String data) {
    return Content.builder().data(data).charset(UTF_8).build();
  }

  private static String reason(Exception e) {
    return "SES " + e.getClass().getSimpleName();
  }
}
