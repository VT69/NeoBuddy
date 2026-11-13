package com.neobuddy.ingestion.gmail;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Gmail access settings ({@code neobuddy.gmail.*}).
 *
 * @param clientId OAuth client id of the Google Cloud project (type "Desktop app")
 * @param clientSecret its client secret
 * @param rootUrl Gmail API root; only tests change it
 * @param tokenUri Google's token endpoint; only tests change it
 * @param retry retry policy for HTTP 429 and 5xx
 * @param connectTimeout connect timeout per request
 * @param readTimeout read timeout per request
 */
@ConfigurationProperties(prefix = "neobuddy.gmail")
public record GmailProperties(
    String clientId,
    String clientSecret,
    @DefaultValue("https://gmail.googleapis.com/") String rootUrl,
    @DefaultValue("https://oauth2.googleapis.com/token") URI tokenUri,
    @DefaultValue Retry retry,
    @DefaultValue("10s") Duration connectTimeout,
    @DefaultValue("60s") Duration readTimeout) {

  /**
   * @param maxAttempts total tries, the first one included
   * @param initialBackoff wait before the second try; doubles for each further try
   */
  public record Retry(
      @DefaultValue("4") int maxAttempts, @DefaultValue("1s") Duration initialBackoff) {}

  /** Never prints the client secret. */
  @Override
  public String toString() {
    return "GmailProperties[rootUrl=" + rootUrl + "]";
  }
}
