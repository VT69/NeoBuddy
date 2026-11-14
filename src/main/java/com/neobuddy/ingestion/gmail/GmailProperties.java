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
 * @param web the "Web application" client used by the admin console's connect flow
 */
@ConfigurationProperties(prefix = "neobuddy.gmail")
public record GmailProperties(
    String clientId,
    String clientSecret,
    @DefaultValue("https://gmail.googleapis.com/") String rootUrl,
    @DefaultValue("https://oauth2.googleapis.com/token") URI tokenUri,
    @DefaultValue Retry retry,
    @DefaultValue("10s") Duration connectTimeout,
    @DefaultValue("60s") Duration readTimeout,
    @DefaultValue Web web) {

  /**
   * The OAuth client of type "Web application" in the same Google Cloud project (the ingest
   * project, Gmail API enabled). Its redirect URI is {@code {base-url}/sources/connect/callback}.
   *
   * @param clientId its client id; empty switches the web connect flow off
   * @param clientSecret its client secret
   * @param authUri Google's authorization endpoint; only tests change it
   * @param revokeUri Google's token revocation endpoint; only tests change it
   */
  public record Web(
      String clientId,
      String clientSecret,
      @DefaultValue(GOOGLE_AUTH) URI authUri,
      @DefaultValue(GOOGLE_REVOKE) URI revokeUri) {

    static final String GOOGLE_AUTH = "https://accounts.google.com/o/oauth2/v2/auth";
    static final String GOOGLE_REVOKE = "https://oauth2.googleapis.com/revoke";

    public boolean configured() {
      return clientId != null
          && !clientId.isBlank()
          && clientSecret != null
          && !clientSecret.isBlank();
    }

    /** Never prints the client secret. */
    @Override
    public String toString() {
      return "Web[configured=" + configured() + "]";
    }
  }

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
