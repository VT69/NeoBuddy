package com.neobuddy.ingestion.gmail;

import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.gmail.Gmail;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.UserCredentials;
import com.neobuddy.ingestion.source.MailSource;
import com.neobuddy.ingestion.source.MailSourceException;
import com.neobuddy.ingestion.source.MailSourceFactory;
import com.neobuddy.ingestion.source.OAuthClient;
import com.neobuddy.ingestion.source.SourceInbox;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.io.IOException;
import java.util.UUID;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Builds a Gmail-backed {@link MailSource} for a source inbox from its stored refresh token and the
 * project's OAuth client. Access tokens are obtained and refreshed by Google's auth library and
 * live in memory only.
 */
@Component
@EnableConfigurationProperties(GmailProperties.class)
public class GmailMailSourceFactory implements MailSourceFactory {

  private static final String APPLICATION_NAME = "NeoBuddy";

  private final GmailProperties properties;
  private final HttpTransport transport = new NetHttpTransport();
  private final Retry retry;

  public GmailMailSourceFactory(GmailProperties properties) {
    this.properties = properties;
    this.retry =
        Retry.of(
            "gmail",
            RetryConfig.custom()
                .maxAttempts(Math.max(1, properties.retry().maxAttempts()))
                .intervalFunction(
                    IntervalFunction.ofExponentialBackoff(properties.retry().initialBackoff(), 2.0))
                .retryOnException(GmailMailSource::isRetryable)
                .build());
  }

  @Override
  public MailSource open(SourceInbox inbox) {
    if (inbox.getRefreshToken() == null || inbox.getRefreshToken().isBlank()) {
      throw new MailSourceException("Source inbox is disconnected");
    }
    return open(inbox.getId(), inbox.getRefreshToken(), inbox.getOauthClient());
  }

  /** For callers that hold a refresh token from the desktop client but no stored inbox. */
  public MailSource open(UUID sourceId, String refreshToken) {
    return open(sourceId, refreshToken, OAuthClient.DESKTOP);
  }

  /** For callers that hold a refresh token but no stored inbox. */
  public MailSource open(UUID sourceId, String refreshToken, OAuthClient client) {
    return new GmailMailSource(sourceId, gmail(refreshToken, client), retry);
  }

  /** The address of the mailbox a refresh token gives access to. */
  public String mailboxAddress(String refreshToken, OAuthClient client) {
    try {
      return gmail(refreshToken, client).users().getProfile("me").execute().getEmailAddress();
    } catch (IOException e) {
      throw new MailSourceException(
          "Could not read the mailbox profile: " + e.getClass().getName());
    }
  }

  private Gmail gmail(String refreshToken, OAuthClient client) {
    String clientId;
    String clientSecret;
    if (client == OAuthClient.WEB) {
      if (!properties.web().configured()) {
        throw new MailSourceException(
            "The Gmail web client is not configured: set NEOBUDDY_GMAIL_WEB_CLIENT_ID and"
                + " NEOBUDDY_GMAIL_WEB_CLIENT_SECRET");
      }
      clientId = properties.web().clientId();
      clientSecret = properties.web().clientSecret();
    } else {
      if (isBlank(properties.clientId()) || isBlank(properties.clientSecret())) {
        throw new MailSourceException(
            "Gmail is not configured: set NEOBUDDY_GMAIL_CLIENT_ID and"
                + " NEOBUDDY_GMAIL_CLIENT_SECRET");
      }
      clientId = properties.clientId();
      clientSecret = properties.clientSecret();
    }
    UserCredentials credentials =
        UserCredentials.newBuilder()
            .setClientId(clientId)
            .setClientSecret(clientSecret)
            .setRefreshToken(refreshToken)
            .setTokenServerUri(properties.tokenUri())
            .setHttpTransportFactory(() -> transport)
            .build();
    HttpCredentialsAdapter authorize = new HttpCredentialsAdapter(credentials);
    HttpRequestInitializer initializer =
        request -> {
          authorize.initialize(request);
          request.setConnectTimeout((int) properties.connectTimeout().toMillis());
          request.setReadTimeout((int) properties.readTimeout().toMillis());
        };
    return new Gmail.Builder(transport, GsonFactory.getDefaultInstance(), initializer)
        .setRootUrl(properties.rootUrl())
        .setApplicationName(APPLICATION_NAME)
        .build();
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
