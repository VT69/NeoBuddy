package com.neobuddy.ingestion.gmail;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.neobuddy.ingestion.SourceConnectException;
import com.neobuddy.ingestion.SourceConnectException.Reason;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Google's OAuth flow for web servers, with the ingest project's "Web application" client: send the
 * browser to Google for {@code gmail.readonly} with {@code access_type=offline} and {@code
 * prompt=consent} (so a refresh token always comes back), then exchange the returned code. PKCE
 * protects the exchange on top of the client secret.
 *
 * <p>Tokens, codes and Google's error descriptions are never logged; only Google's error code is.
 */
@Component
public class GmailWebAuthorization {

  public static final String SCOPE = "https://www.googleapis.com/auth/gmail.readonly";

  private static final Logger log = LoggerFactory.getLogger(GmailWebAuthorization.class);

  private final GmailProperties properties;
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

  GmailWebAuthorization(GmailProperties properties) {
    this.properties = properties;
  }

  public boolean configured() {
    return properties.web().configured();
  }

  /** Where to send the browser. */
  public URI authorizationUrl(String redirectUri, String state, String codeChallenge) {
    requireConfigured();
    Map<String, String> query = new LinkedHashMap<>();
    query.put("client_id", properties.web().clientId());
    query.put("redirect_uri", redirectUri);
    query.put("response_type", "code");
    query.put("scope", SCOPE);
    query.put("access_type", "offline");
    query.put("prompt", "consent");
    query.put("include_granted_scopes", "false");
    query.put("state", state);
    query.put("code_challenge", codeChallenge);
    query.put("code_challenge_method", "S256");
    return URI.create(properties.web().authUri() + "?" + form(query));
  }

  /**
   * Exchanges the code Google sent back for a refresh token.
   *
   * @throws SourceConnectException if Google refuses, read access was not granted or no refresh
   *     token came back
   */
  public String exchange(String code, String redirectUri, String codeVerifier) {
    requireConfigured();
    Map<String, String> body = new LinkedHashMap<>();
    body.put("code", code);
    body.put("client_id", properties.web().clientId());
    body.put("client_secret", properties.web().clientSecret());
    body.put("redirect_uri", redirectUri);
    body.put("grant_type", "authorization_code");
    body.put("code_verifier", codeVerifier);
    HttpResponse<String> response = post(properties.tokenUri(), body);
    JsonObject json = parse(response.body());
    if (response.statusCode() != 200) {
      log.warn(
          "Google refused the code exchange: HTTP {} ({})",
          response.statusCode(),
          json.has("error") ? json.get("error").getAsString() : "no error code");
      throw new SourceConnectException(Reason.GOOGLE_REFUSED);
    }
    String scopes = json.has("scope") ? json.get("scope").getAsString() : "";
    if (Arrays.stream(scopes.split(" ")).noneMatch(SCOPE::equals)) {
      String refreshToken = string(json, "refresh_token");
      if (refreshToken != null) {
        revoke(refreshToken);
      }
      throw new SourceConnectException(Reason.SCOPE_NOT_GRANTED);
    }
    String refreshToken = string(json, "refresh_token");
    if (refreshToken == null) {
      throw new SourceConnectException(Reason.NO_REFRESH_TOKEN);
    }
    return refreshToken;
  }

  /** Best effort: tells Google to forget the grant. Failures are logged by status only. */
  public void revoke(String refreshToken) {
    if (refreshToken == null || refreshToken.isBlank()) {
      return;
    }
    try {
      HttpResponse<String> response =
          post(properties.web().revokeUri(), Map.of("token", refreshToken));
      if (response.statusCode() != 200) {
        log.warn("Google did not revoke a source token: HTTP {}", response.statusCode());
      }
    } catch (SourceConnectException e) {
      log.warn("Could not reach Google to revoke a source token");
    }
  }

  private HttpResponse<String> post(URI uri, Map<String, String> body) {
    HttpRequest request =
        HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form(body)))
            .build();
    try {
      return http.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new SourceConnectException(Reason.GOOGLE_UNREACHABLE);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new SourceConnectException(Reason.GOOGLE_UNREACHABLE);
    }
  }

  private void requireConfigured() {
    if (!configured()) {
      throw new SourceConnectException(Reason.NOT_CONFIGURED);
    }
  }

  private static String string(JsonObject json, String name) {
    if (!json.has(name) || json.get(name).isJsonNull()) {
      return null;
    }
    String value = json.get(name).getAsString();
    return value.isBlank() ? null : value;
  }

  private static JsonObject parse(String body) {
    try {
      return JsonParser.parseString(body).getAsJsonObject();
    } catch (RuntimeException e) {
      return new JsonObject();
    }
  }

  private static String form(Map<String, String> values) {
    return values.entrySet().stream()
        .map(e ->
            URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)
                + "="
                + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
        .collect(Collectors.joining("&"));
  }
}
