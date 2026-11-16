package com.neobuddy.notification.channel;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.neobuddy.notification.Alert;
import com.neobuddy.notification.DeliveryResult;
import com.neobuddy.notification.NotificationChannel;
import com.neobuddy.subscriber.ChannelAddress;
import com.neobuddy.subscriber.ChannelType;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Email through Brevo's transactional API ({@code POST /v3/smtp/email}) over HTTPS, for hosts that
 * block SMTP. One verified sender; a plain-text and an HTML part, like {@link SesEmailChannel}.
 *
 * <p>Failures follow the same rule as SES: permanent only when the message itself is at fault (HTTP
 * 400, or any other 4xx not listed below). Retryable: 429 and 5xx, network failures, and refusals
 * about our account rather than this message, which an operator can fix while the retries last: 401
 * (wrong key, or Brevo blocking an unknown IP address), 402 (out of credits, such as the free
 * plan's daily limit) and 403. A failure reason carries the HTTP status and Brevo's error code (for
 * example {@code invalid_parameter}), never Brevo's message, which can name the recipient. The API
 * key is sent in a header and never logged.
 */
class BrevoEmailChannel implements NotificationChannel, EmailTransport {

  private static final String SEND_PATH = "/v3/smtp/email";
  private static final Pattern NAMED_ADDRESS =
      Pattern.compile("^\\s*(.*?)\\s*<\\s*([^<>\\s]+)\\s*>\\s*$");
  private static final Pattern ERROR_CODE =
      Pattern.compile("\"code\"\\s*:\\s*\"([A-Za-z_]{1,40})\"");
  private static final int MAX_ERROR_BODY = 4096;

  private final RestClient http;
  private final URI endpoint;
  private final String apiKey;
  private final Party sender;
  private final AlertRenderer renderer;
  private final SendRateLimiter rateLimiter;

  BrevoEmailChannel(ChannelProperties.Brevo settings, AlertRenderer renderer) {
    if (settings.apiKey() == null || settings.apiKey().isBlank()) {
      throw new IllegalStateException(
          "neobuddy.channels.brevo.api-key (NEOBUDDY_BREVO_API_KEY) must be set to send email"
              + " through Brevo");
    }
    if (settings.from() == null || settings.from().isBlank()) {
      throw new IllegalStateException(
          "neobuddy.channels.brevo.from (NEOBUDDY_BREVO_FROM) must be set to send email through"
              + " Brevo");
    }
    JdkClientHttpRequestFactory requests =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder()
                .connectTimeout(settings.connectTimeout())
                // One small request at a time needs nothing from HTTP/2, and the JDK's attempt to
                // upgrade a plain-http connection with a streamed JSON body fails on some servers.
                .version(HttpClient.Version.HTTP_1_1)
                .build());
    requests.setReadTimeout(settings.readTimeout());
    this.http = RestClient.builder().requestFactory(requests).build();
    this.endpoint = URI.create(settings.baseUrl().strip().replaceAll("/+$", "") + SEND_PATH);
    this.apiKey = settings.apiKey().strip();
    this.sender = sender(settings.from());
    this.renderer = renderer;
    this.rateLimiter = new SendRateLimiter("brevo", settings.rateLimit());
  }

  /** {@code Name <address>} or a bare address. */
  static Party sender(String from) {
    Matcher named = NAMED_ADDRESS.matcher(from);
    if (named.matches()) {
      String name = named.group(1).replaceAll("^\"|\"$", "").strip();
      return new Party(named.group(2), name.isEmpty() ? null : name);
    }
    return new Party(from.strip(), null);
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
    BrevoEmail email =
        new BrevoEmail(
            sender,
            List.of(new Party(to.value(), null)),
            content.subject(),
            content.text(),
            content.html());
    return rateLimiter.send(() -> post(email));
  }

  private DeliveryResult post(BrevoEmail email) {
    Answer answer;
    try {
      answer =
          http.post()
              .uri(endpoint)
              .header("api-key", apiKey)
              .accept(MediaType.APPLICATION_JSON)
              .contentType(MediaType.APPLICATION_JSON)
              .body(email)
              .exchange((request, response) -> {
                int status = response.getStatusCode().value();
                return new Answer(status, status >= 400 ? errorCode(response.getBody()) : null);
              });
    } catch (RestClientException e) {
      // The class only: a message could carry the address.
      return DeliveryResult.retryable("Brevo IO " + e.getClass().getSimpleName());
    }
    return answer.toResult();
  }

  /**
   * Brevo's error {@code code}, read from the first few KB of the answer; null if there is none.
   */
  static String errorCode(InputStream body) {
    try (body) {
      String text = new String(body.readNBytes(MAX_ERROR_BODY), StandardCharsets.UTF_8);
      Matcher code = ERROR_CODE.matcher(text);
      return code.find() ? code.group(1) : null;
    } catch (IOException e) {
      return null;
    }
  }

  private record Answer(int status, String errorCode) {

    DeliveryResult toResult() {
      if (status >= 200 && status < 300) {
        return DeliveryResult.success();
      }
      String reason = "Brevo HTTP " + status + (errorCode == null ? "" : " " + errorCode);
      boolean retryable =
          status == 401 || status == 402 || status == 403 || status == 429 || status >= 500;
      return retryable ? DeliveryResult.retryable(reason) : DeliveryResult.permanent(reason);
    }
  }

  /** A sender or recipient as Brevo's API names them; a null name is left out. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  record Party(String email, String name) {}

  /** The request body of {@code POST /v3/smtp/email}. */
  record BrevoEmail(
      Party sender, List<Party> to, String subject, String textContent, String htmlContent) {

    /** Never prints the content or the recipient. */
    @Override
    public String toString() {
      return "BrevoEmail[hidden]";
    }
  }
}
