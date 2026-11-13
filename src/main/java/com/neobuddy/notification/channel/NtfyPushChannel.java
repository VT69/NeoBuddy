package com.neobuddy.notification.channel;

import com.neobuddy.notification.Alert;
import com.neobuddy.notification.DeliveryResult;
import com.neobuddy.notification.NotificationChannel;
import com.neobuddy.subscriber.ChannelAddress;
import com.neobuddy.subscriber.ChannelType;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Push through ntfy: one POST to {@code {base-url}/{topic}} with the text as the body and the
 * headline, tags, priority and tap-link in headers.
 *
 * <p>HTTP 429 and 5xx, timeouts and connection failures are retryable; any other answer that is not
 * a success is permanent. The topic is the student's secret, so neither it nor the request address
 * is ever logged or put into a failure reason.
 */
@Component
class NtfyPushChannel implements NotificationChannel {

  private static final Pattern TOPIC = Pattern.compile("[-_A-Za-z0-9]{1,64}");

  private final RestClient http;
  private final String baseUrl;
  private final AlertRenderer renderer;
  private final SendRateLimiter rateLimiter;

  NtfyPushChannel(ChannelProperties properties, AlertRenderer renderer) {
    ChannelProperties.Ntfy ntfy = properties.ntfy();
    JdkClientHttpRequestFactory requests =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(ntfy.connectTimeout()).build());
    requests.setReadTimeout(ntfy.readTimeout());
    this.http = RestClient.builder().requestFactory(requests).build();
    this.baseUrl = ntfy.baseUrl().strip().replaceAll("/+$", "");
    this.renderer = renderer;
    this.rateLimiter = new SendRateLimiter("ntfy", ntfy.rateLimit());
  }

  @Override
  public ChannelType type() {
    return ChannelType.NTFY;
  }

  @Override
  public DeliveryResult send(ChannelAddress to, Alert alert) {
    if (!TOPIC.matcher(to.value()).matches()) {
      return DeliveryResult.permanent("INVALID_TOPIC");
    }
    NtfyMessage message = renderer.ntfy(alert);
    return rateLimiter.send(() -> post(URI.create(baseUrl + "/" + to.value()), message));
  }

  private DeliveryResult post(URI uri, NtfyMessage message) {
    HttpStatusCode status;
    try {
      status =
          http.post()
              .uri(uri)
              .contentType(new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8))
              .header("Title", headerValue(message.title()))
              .header("Tags", message.tags())
              .header("Priority", message.priority())
              .headers(headers -> {
                if (message.click() != null) {
                  headers.set("Click", message.click());
                }
              })
              .body(message.body())
              .exchange((request, response) -> response.getStatusCode());
    } catch (RestClientException e) {
      // The class only: the message contains the address, and with it the topic.
      return DeliveryResult.retryable("IO " + e.getClass().getSimpleName());
    }
    if (status.is2xxSuccessful()) {
      return DeliveryResult.success();
    }
    String reason = "HTTP " + status.value();
    return status.value() == 429 || status.is5xxServerError()
        ? DeliveryResult.retryable(reason)
        : DeliveryResult.permanent(reason);
  }

  /** Headers are ASCII; anything else goes in RFC 2047 form, which ntfy decodes. */
  static String headerValue(String value) {
    boolean ascii = value.chars().allMatch(c -> c >= 0x20 && c < 0x7f);
    return ascii
        ? value
        : "=?UTF-8?B?"
            + Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8))
            + "?=";
  }
}
