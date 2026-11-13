package com.neobuddy.ingestion.tools;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * Authorises a source inbox with Google and prints its refresh token. Run it by hand:
 *
 * <pre>
 * NEOBUDDY_GMAIL_CLIENT_ID=... NEOBUDDY_GMAIL_CLIENT_SECRET=... ./gradlew authorizeSource --console=plain
 * </pre>
 *
 * <p>It is Google's flow for installed apps: this program listens on a random port on the loopback
 * interface, you approve read-only Gmail access in your browser, Google redirects the browser back
 * to that port with a one-time code, and the code is exchanged for tokens. PKCE and a random state
 * value protect the exchange.
 *
 * <p>The refresh token goes to standard output exactly once and nowhere else: this class has no
 * logger on purpose. Treat it like a password.
 */
public final class SourceAuthorizer {

  static final String SCOPE = "https://www.googleapis.com/auth/gmail.readonly";

  private static final URI GOOGLE_AUTH_URI =
      URI.create("https://accounts.google.com/o/oauth2/v2/auth");
  private static final URI GOOGLE_TOKEN_URI = URI.create("https://oauth2.googleapis.com/token");
  private static final Duration WAIT_FOR_APPROVAL = Duration.ofMinutes(5);

  private final String clientId;
  private final String clientSecret;
  private final URI authUri;
  private final URI tokenUri;
  private final PrintStream out;
  private final Duration timeout;
  private final SecureRandom random = new SecureRandom();

  SourceAuthorizer(
      String clientId,
      String clientSecret,
      URI authUri,
      URI tokenUri,
      PrintStream out,
      Duration timeout) {
    this.clientId = clientId;
    this.clientSecret = clientSecret;
    this.authUri = authUri;
    this.tokenUri = tokenUri;
    this.out = out;
    this.timeout = timeout;
  }

  public static void main(String[] args) {
    String clientId = System.getenv("NEOBUDDY_GMAIL_CLIENT_ID");
    String clientSecret = System.getenv("NEOBUDDY_GMAIL_CLIENT_SECRET");
    if (isBlank(clientId) || isBlank(clientSecret)) {
      System.err.println(
          "Set NEOBUDDY_GMAIL_CLIENT_ID and NEOBUDDY_GMAIL_CLIENT_SECRET to the OAuth client"
              + " (type \"Desktop app\") of your Google Cloud project, then run this again.");
      System.exit(2);
    }
    SourceAuthorizer authorizer =
        new SourceAuthorizer(
            clientId,
            clientSecret,
            GOOGLE_AUTH_URI,
            GOOGLE_TOKEN_URI,
            System.out,
            WAIT_FOR_APPROVAL);
    try {
      authorizer.run();
    } catch (AuthorizationFailed e) {
      System.err.println("Authorisation failed: " + e.getMessage());
      System.exit(1);
    }
  }

  /** Runs the whole flow and prints the refresh token. */
  void run() {
    String state = randomToken(24);
    String verifier = randomToken(48);
    CompletableFuture<String> code = new CompletableFuture<>();
    HttpServer server = startCallbackServer(state, code);
    String redirectUri = "http://127.0.0.1:" + server.getAddress().getPort();
    try {
      out.println("Open this address in a browser signed in to the source inbox, and approve:");
      out.println();
      out.println(authorizationUrl(redirectUri, state, verifier));
      out.println();
      out.println("Waiting for the approval (up to " + timeout.toMinutes() + " minutes)...");
      String refreshToken = exchange(awaitCode(code), redirectUri, verifier);
      out.println();
      out.println("Refresh token (shown once; set it as NEOBUDDY_BOOTSTRAP_SOURCE_REFRESH_TOKEN):");
      out.println();
      out.println(refreshToken);
      out.println();
    } finally {
      // Give an in-flight response a moment to reach the browser.
      server.stop(1);
    }
  }

  String authorizationUrl(String redirectUri, String state, String verifier) {
    Map<String, String> query = new LinkedHashMap<>();
    query.put("client_id", clientId);
    query.put("redirect_uri", redirectUri);
    query.put("response_type", "code");
    query.put("scope", SCOPE);
    // offline + consent: Google then always returns a refresh token.
    query.put("access_type", "offline");
    query.put("prompt", "consent");
    query.put("state", state);
    query.put("code_challenge", challengeFor(verifier));
    query.put("code_challenge_method", "S256");
    return authUri + "?" + form(query);
  }

  private HttpServer startCallbackServer(String expectedState, CompletableFuture<String> code) {
    try {
      HttpServer server =
          HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
      server.createContext("/", exchange -> handleCallback(exchange, expectedState, code));
      server.start();
      return server;
    } catch (IOException e) {
      throw new AuthorizationFailed("could not listen on the loopback interface");
    }
  }

  private static void handleCallback(
      HttpExchange exchange, String expectedState, CompletableFuture<String> code)
      throws IOException {
    Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
    if (query.isEmpty()) {
      // A favicon request or similar: not the redirect.
      respond(exchange, 404, "Not found.");
      return;
    }
    String message;
    AuthorizationFailed failure = null;
    if (!expectedState.equals(query.get("state"))) {
      message = "This response does not belong to the request that was started. Nothing was done.";
      failure = new AuthorizationFailed("the state value did not match");
    } else if (query.containsKey("error")) {
      message = "Access was not granted. You can close this tab.";
      failure = new AuthorizationFailed("Google returned the error '" + query.get("error") + "'");
    } else if (isBlank(query.get("code"))) {
      message = "No authorisation code was received.";
      failure = new AuthorizationFailed("no authorisation code was received");
    } else {
      message = "NeoBuddy has what it needs. You can close this tab and return to the terminal.";
    }
    // Answer the browser first: finishing the flow shuts this server down.
    respond(exchange, 200, message);
    if (failure != null) {
      code.completeExceptionally(failure);
    } else {
      code.complete(query.get("code"));
    }
  }

  private String awaitCode(CompletableFuture<String> code) {
    try {
      return code.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      throw new AuthorizationFailed("no approval arrived in time");
    } catch (ExecutionException e) {
      if (e.getCause() instanceof AuthorizationFailed failed) {
        throw failed;
      }
      throw new AuthorizationFailed("the approval could not be received");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AuthorizationFailed("interrupted while waiting for the approval");
    }
  }

  private String exchange(String code, String redirectUri, String verifier) {
    Map<String, String> form = new LinkedHashMap<>();
    form.put("code", code);
    form.put("client_id", clientId);
    form.put("client_secret", clientSecret);
    form.put("redirect_uri", redirectUri);
    form.put("grant_type", "authorization_code");
    form.put("code_verifier", verifier);
    HttpRequest request =
        HttpRequest.newBuilder(tokenUri)
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form(form)))
            .build();
    HttpResponse<String> response;
    try {
      response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new AuthorizationFailed("the token endpoint could not be reached");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AuthorizationFailed("interrupted while exchanging the code");
    }
    JsonObject body = parse(response.body());
    if (response.statusCode() != 200) {
      String error = body.has("error") ? body.get("error").getAsString() : "unknown";
      throw new AuthorizationFailed(
          "the token endpoint answered HTTP " + response.statusCode() + " (" + error + ")");
    }
    if (!body.has("refresh_token") || isBlank(body.get("refresh_token").getAsString())) {
      throw new AuthorizationFailed(
          "Google returned no refresh token; remove NeoBuddy's access at"
              + " myaccount.google.com/permissions and run this again");
    }
    return body.get("refresh_token").getAsString();
  }

  private static JsonObject parse(String json) {
    try {
      return JsonParser.parseString(json).getAsJsonObject();
    } catch (RuntimeException e) {
      return new JsonObject();
    }
  }

  private static void respond(HttpExchange exchange, int status, String message)
      throws IOException {
    byte[] body =
        ("<!doctype html><meta charset=\"utf-8\"><title>NeoBuddy</title><p>" + message + "</p>")
            .getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
    exchange.sendResponseHeaders(status, body.length);
    try (var stream = exchange.getResponseBody()) {
      stream.write(body);
    }
  }

  private static Map<String, String> parseQuery(String rawQuery) {
    Map<String, String> query = new LinkedHashMap<>();
    if (rawQuery == null || rawQuery.isBlank()) {
      return query;
    }
    for (String pair : rawQuery.split("&")) {
      int equals = pair.indexOf('=');
      String name = equals < 0 ? pair : pair.substring(0, equals);
      String value = equals < 0 ? "" : pair.substring(equals + 1);
      query.put(
          URLDecoder.decode(name, StandardCharsets.UTF_8),
          URLDecoder.decode(value, StandardCharsets.UTF_8));
    }
    return query;
  }

  private static String form(Map<String, String> values) {
    return values.entrySet().stream()
        .map(entry ->
            URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8)
                + "="
                + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
        .collect(Collectors.joining("&"));
  }

  private String randomToken(int bytes) {
    byte[] buffer = new byte[bytes];
    random.nextBytes(buffer);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer);
  }

  private static String challengeFor(String verifier) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  /** Why the flow stopped. The message is safe to show: it never contains a code or a token. */
  static final class AuthorizationFailed extends RuntimeException {

    private static final long serialVersionUID = 1L;

    AuthorizationFailed(String message) {
      super(message);
    }
  }
}
