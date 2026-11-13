package com.neobuddy.ingestion.tools;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.neobuddy.ingestion.tools.SourceAuthorizer.AuthorizationFailed;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The authoriser with Google replaced by WireMock and the browser by an HTTP client that follows
 * the redirect Google would send.
 */
@Timeout(60)
class SourceAuthorizerTests {

  private static final String REFRESH_TOKEN = "1//fictional-refresh-token";
  private static final Pattern URL =
      Pattern.compile("^(http://\\S+/auth\\?\\S+)$", Pattern.MULTILINE);

  private final WireMockServer google = new WireMockServer(options().dynamicPort());
  private final ByteArrayOutputStream printed = new ByteArrayOutputStream();

  @BeforeEach
  void startGoogle() {
    google.start();
  }

  @AfterEach
  void stopGoogle() {
    google.stop();
  }

  @Test
  void runsTheLoopbackFlowAndPrintsTheRefreshTokenOnce() throws Exception {
    tokenEndpointReturns(
        200, "{\"access_token\":\"at\",\"refresh_token\":\"" + REFRESH_TOKEN + "\"}");
    CompletableFuture<Void> flow =
        CompletableFuture.runAsync(authorizer(Duration.ofSeconds(30))::run);

    Map<String, String> request = awaitAuthorizationRequest();
    assertThat(request)
        .containsEntry("client_id", "client-123")
        .containsEntry("response_type", "code")
        .containsEntry("scope", "https://www.googleapis.com/auth/gmail.readonly")
        .containsEntry("access_type", "offline")
        .containsEntry("prompt", "consent")
        .containsEntry("code_challenge_method", "S256");
    assertThat(request.get("redirect_uri")).startsWith("http://127.0.0.1:");
    assertThat(request.get("state")).hasSizeGreaterThan(20);
    assertThat(request.get("code_challenge")).hasSizeGreaterThan(40);

    HttpResponse<String> page =
        browse(
            request.get("redirect_uri")
                + "/?state="
                + request.get("state")
                + "&code=one-time-code");
    flow.get(30, TimeUnit.SECONDS);

    assertThat(page.statusCode()).isEqualTo(200);
    assertThat(page.body()).contains("You can close this tab").doesNotContain(REFRESH_TOKEN);
    assertThat(output().split(Pattern.quote(REFRESH_TOKEN), -1))
        .as("printed exactly once")
        .hasSize(2);
    google.verify(
        postRequestedFor(urlPathEqualTo("/token"))
            .withRequestBody(containing("grant_type=authorization_code"))
            .withRequestBody(containing("code=one-time-code"))
            .withRequestBody(containing("client_id=client-123"))
            .withRequestBody(containing("client_secret=secret-456"))
            .withRequestBody(containing("code_verifier=")));
    assertThat(output()).doesNotContain("secret-456").doesNotContain("one-time-code");
  }

  @Test
  void aResponseWithTheWrongStateIsRefused() throws Exception {
    tokenEndpointReturns(200, "{\"refresh_token\":\"" + REFRESH_TOKEN + "\"}");
    CompletableFuture<Void> flow =
        CompletableFuture.runAsync(authorizer(Duration.ofSeconds(30))::run);
    Map<String, String> request = awaitAuthorizationRequest();

    HttpResponse<String> page =
        browse(request.get("redirect_uri") + "/?state=forged&code=attacker-code");

    assertThat(page.body()).contains("does not belong to the request");
    assertThat(flow)
        .failsWithin(Duration.ofSeconds(30))
        .withThrowableOfType(java.util.concurrent.ExecutionException.class)
        .withCauseInstanceOf(AuthorizationFailed.class)
        .withMessageContaining("state value did not match");
    assertThat(google.findAll(postRequestedFor(urlPathEqualTo("/token")))).isEmpty();
    assertThat(output()).doesNotContain(REFRESH_TOKEN);
  }

  @Test
  void aDeniedConsentFailsWithGooglesReason() throws Exception {
    CompletableFuture<Void> flow =
        CompletableFuture.runAsync(authorizer(Duration.ofSeconds(30))::run);
    Map<String, String> request = awaitAuthorizationRequest();

    browse(
        request.get("redirect_uri") + "/?state=" + request.get("state") + "&error=access_denied");

    assertThat(flow)
        .failsWithin(Duration.ofSeconds(30))
        .withThrowableOfType(java.util.concurrent.ExecutionException.class)
        .withMessageContaining("access_denied");
  }

  @Test
  void aTokenEndpointErrorIsReportedWithoutSecrets() throws Exception {
    tokenEndpointReturns(400, "{\"error\":\"invalid_client\"}");
    CompletableFuture<Void> flow =
        CompletableFuture.runAsync(authorizer(Duration.ofSeconds(30))::run);
    Map<String, String> request = awaitAuthorizationRequest();

    browse(request.get("redirect_uri") + "/?state=" + request.get("state") + "&code=one-time-code");

    assertThat(flow)
        .failsWithin(Duration.ofSeconds(30))
        .withThrowableOfType(java.util.concurrent.ExecutionException.class)
        .withMessageContaining("HTTP 400 (invalid_client)")
        .withMessageNotContaining("secret-456");
  }

  @Test
  void aResponseWithoutARefreshTokenExplainsWhatToDo() throws Exception {
    tokenEndpointReturns(200, "{\"access_token\":\"at\"}");
    CompletableFuture<Void> flow =
        CompletableFuture.runAsync(authorizer(Duration.ofSeconds(30))::run);
    Map<String, String> request = awaitAuthorizationRequest();

    browse(request.get("redirect_uri") + "/?state=" + request.get("state") + "&code=one-time-code");

    assertThat(flow)
        .failsWithin(Duration.ofSeconds(30))
        .withThrowableOfType(java.util.concurrent.ExecutionException.class)
        .withMessageContaining("no refresh token");
  }

  @Test
  void givesUpWhenNobodyApproves() {
    SourceAuthorizer impatient = authorizer(Duration.ofMillis(300));

    org.assertj.core.api.Assertions.assertThatThrownBy(impatient::run)
        .isInstanceOf(AuthorizationFailed.class)
        .hasMessageContaining("no approval arrived in time");
  }

  private SourceAuthorizer authorizer(Duration timeout) {
    return new SourceAuthorizer(
        "client-123",
        "secret-456",
        URI.create(google.baseUrl() + "/auth"),
        URI.create(google.baseUrl() + "/token"),
        new PrintStream(printed, true, UTF_8),
        timeout);
  }

  private void tokenEndpointReturns(int status, String body) {
    google.stubFor(
        post(urlPathEqualTo("/token"))
            .willReturn(
                aResponse()
                    .withStatus(status)
                    .withHeader("Content-Type", "application/json")
                    .withBody(body)));
  }

  /** Waits for the tool to print the address it wants opened, and returns its query parameters. */
  private Map<String, String> awaitAuthorizationRequest() {
    await().atMost(Duration.ofSeconds(20)).until(() -> URL.matcher(output()).find());
    Matcher matcher = URL.matcher(output());
    assertThat(matcher.find()).isTrue();
    URI uri = URI.create(matcher.group(1));
    Map<String, String> query = new LinkedHashMap<>();
    for (String pair : uri.getRawQuery().split("&")) {
      String[] parts = pair.split("=", 2);
      query.put(URLDecoder.decode(parts[0], UTF_8), URLDecoder.decode(parts[1], UTF_8));
    }
    return query;
  }

  private static HttpResponse<String> browse(String url) throws Exception {
    return HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder(URI.create(url)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
  }

  private String output() {
    return printed.toString(UTF_8);
  }
}
