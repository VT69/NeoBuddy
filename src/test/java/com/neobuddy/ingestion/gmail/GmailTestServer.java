package com.neobuddy.ingestion.gmail;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder;
import com.google.gson.Gson;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A stand-in for Google: the Gmail REST API and the OAuth token endpoint, served by WireMock. One
 * server is shared by every test in the JVM; tests stay apart because each inbox ("account") has
 * its own refresh token and therefore its own access token, and every stub is tied to it.
 *
 * <p>All mail in it is invented.
 */
public final class GmailTestServer {

  private static final GmailTestServer SHARED = new GmailTestServer();
  private static final Gson GSON = new Gson();
  private static final String USER = "/gmail/v1/users/me";

  private final WireMockServer server = new WireMockServer(options().dynamicPort());

  private GmailTestServer() {
    server.start();
  }

  public static GmailTestServer shared() {
    return SHARED;
  }

  public String rootUrl() {
    return server.baseUrl() + "/";
  }

  public String tokenUri() {
    return server.baseUrl() + "/token";
  }

  public WireMockServer wireMock() {
    return server;
  }

  /** Properties that point a {@link GmailMailSourceFactory} at this server, with fast retries. */
  public GmailProperties properties(int maxAttempts) {
    return new GmailProperties(
        "test-client-id",
        "test-client-secret",
        rootUrl(),
        java.net.URI.create(tokenUri()),
        new GmailProperties.Retry(maxAttempts, java.time.Duration.ofMillis(5)),
        java.time.Duration.ofSeconds(5),
        java.time.Duration.ofSeconds(10));
  }

  /** A new inbox with a fresh refresh token that the token endpoint accepts. */
  public Account newAccount() {
    return new Account("refresh-" + UUID.randomUUID());
  }

  /** One inbox as Google sees it. Every stub added through it answers only that inbox. */
  public final class Account {

    private final String refreshToken;
    private final String accessToken = "access-" + UUID.randomUUID();

    private Account(String refreshToken) {
      this.refreshToken = refreshToken;
      server.stubFor(
          tokenRequest()
              .willReturn(
                  json(
                      Map.of(
                          "access_token",
                          accessToken,
                          "expires_in",
                          3599,
                          "token_type",
                          "Bearer",
                          "scope",
                          "https://www.googleapis.com/auth/gmail.readonly"))));
    }

    public String refreshToken() {
      return refreshToken;
    }

    /** From now on Google rejects the refresh token, as after the user revokes access. */
    public void revoke() {
      server.stubFor(
          tokenRequest()
              .willReturn(
                  aResponse()
                      .withStatus(400)
                      .withHeader("Content-Type", "application/json")
                      .withBody(
                          GSON.toJson(
                              Map.of(
                                  "error", "invalid_grant",
                                  "error_description", "Token has been expired or revoked.")))));
    }

    public void profile(long historyId) {
      server.stubFor(
          authorized(get(urlPathEqualTo(USER + "/profile")))
              .willReturn(
                  json(
                      Map.of(
                          "emailAddress", "inbox@college.example", "historyId", "" + historyId))));
    }

    /**
     * One page of history.
     *
     * @param pageToken the token that asks for this page; null for the first page
     * @param records history records, each an id and the Gmail ids of the messages it added
     * @param nextPageToken null if this is the last page
     * @param mailboxHistoryId the mailbox's current history id, as Gmail reports on every page
     */
    public void history(
        long startHistoryId,
        String pageToken,
        List<HistoryRecord> records,
        String nextPageToken,
        long mailboxHistoryId) {
      Map<String, Object> body = new LinkedHashMap<>();
      List<Map<String, Object>> history = new ArrayList<>();
      for (HistoryRecord record : records) {
        List<Map<String, Object>> added = new ArrayList<>();
        for (String id : record.messageIds()) {
          added.add(Map.of("message", Map.of("id", id, "threadId", id)));
        }
        history.add(Map.of("id", "" + record.id(), "messagesAdded", added));
      }
      if (!history.isEmpty()) {
        body.put("history", history);
      }
      if (nextPageToken != null) {
        body.put("nextPageToken", nextPageToken);
      }
      body.put("historyId", "" + mailboxHistoryId);
      server.stubFor(historyRequest(startHistoryId, pageToken).willReturn(json(body)));
    }

    /** Gmail no longer has history from this id: HTTP 404. */
    public void historyGone(long startHistoryId) {
      server.stubFor(historyRequest(startHistoryId, null).willReturn(error(404, "notFound")));
    }

    public void historyFails(long startHistoryId, int status) {
      server.stubFor(
          historyRequest(startHistoryId, null).willReturn(error(status, "backendError")));
    }

    /** The answer to {@code messages.list q=newer_than:7d}, in one page. */
    public void recentMessages(String... gmailIds) {
      Map<String, Object> body = new LinkedHashMap<>();
      body.put(
          "messages", Arrays.stream(gmailIds).map(id -> Map.of("id", id, "threadId", id)).toList());
      body.put("resultSizeEstimate", gmailIds.length);
      server.stubFor(
          authorized(get(urlPathEqualTo(USER + "/messages")))
              .withQueryParam("q", equalTo("newer_than:7d"))
              .willReturn(json(body)));
    }

    /** Makes a message available in both the metadata and the full format. */
    public void message(TestMessage message) {
      server.stubFor(
          authorized(get(urlPathEqualTo(USER + "/messages/" + message.id)))
              .willReturn(json(message.toJson())));
    }

    public void messageMissing(String gmailId) {
      server.stubFor(
          authorized(get(urlPathEqualTo(USER + "/messages/" + gmailId)))
              .willReturn(error(404, "notFound")));
    }

    /** The message exists in the metadata format but fetching it in full fails. */
    public void fullMessageFails(String gmailId, int status) {
      server.stubFor(
          authorized(get(urlPathEqualTo(USER + "/messages/" + gmailId)))
              .withQueryParam("format", equalTo("full"))
              .atPriority(1)
              .willReturn(error(status, "backendError")));
    }

    public void attachment(String gmailId, String attachmentId, byte[] content) {
      server.stubFor(
          authorized(
                  get(
                      urlPathEqualTo(
                          USER + "/messages/" + gmailId + "/attachments/" + attachmentId)))
              .willReturn(
                  json(
                      Map.of(
                          "size",
                          content.length,
                          "data",
                          Base64.getUrlEncoder().encodeToString(content)))));
    }

    public int historyRequests(long startHistoryId) {
      return server
          .findAll(
              authorizedRequest(getRequestedFor(urlPathEqualTo(USER + "/history")))
                  .withQueryParam("startHistoryId", equalTo("" + startHistoryId)))
          .size();
    }

    public int requestsTo(String pathAfterUser) {
      return server
          .findAll(authorizedRequest(getRequestedFor(urlPathEqualTo(USER + pathAfterUser))))
          .size();
    }

    private MappingBuilder historyRequest(long startHistoryId, String pageToken) {
      return authorized(get(urlPathEqualTo(USER + "/history")))
          .withQueryParam("startHistoryId", equalTo("" + startHistoryId))
          .withQueryParam("historyTypes", equalTo("messageAdded"))
          .withQueryParam("pageToken", pageToken == null ? absent() : equalTo(pageToken));
    }

    private MappingBuilder tokenRequest() {
      return post(urlPathEqualTo("/token"))
          .withRequestBody(
              containing(
                  "refresh_token=" + URLEncoder.encode(refreshToken, StandardCharsets.UTF_8)));
    }

    private MappingBuilder authorized(MappingBuilder request) {
      return request.withHeader("Authorization", equalTo("Bearer " + accessToken));
    }

    private RequestPatternBuilder authorizedRequest(RequestPatternBuilder request) {
      return request.withHeader("Authorization", equalTo("Bearer " + accessToken));
    }
  }

  public record HistoryRecord(long id, List<String> messageIds) {

    public static HistoryRecord of(long id, String... messageIds) {
      return new HistoryRecord(id, List.of(messageIds));
    }
  }

  /** A Gmail API message under construction. */
  public static final class TestMessage {

    private final String id;
    private final Map<String, String> headers = new LinkedHashMap<>();
    private final List<String> labels = new ArrayList<>(List.of("INBOX", "UNREAD"));
    private Instant receivedAt = Instant.parse("2026-11-13T10:00:00Z");
    private Map<String, Object> payload = textPart("text/plain", "Hello.", StandardCharsets.UTF_8);

    private TestMessage(String id) {
      this.id = id;
    }

    public static TestMessage withId(String id) {
      return new TestMessage(id);
    }

    public static String newId() {
      return "m" + UUID.randomUUID().toString().replace("-", "").substring(0, 15);
    }

    public String id() {
      return id;
    }

    public TestMessage from(String value) {
      return header("From", value);
    }

    public TestMessage subject(String value) {
      return header("Subject", value);
    }

    /** The Message-ID header as set, or null. */
    public String messageIdHeader() {
      return headers.get("Message-ID");
    }

    public TestMessage messageId(String value) {
      return header("Message-ID", value);
    }

    public TestMessage header(String name, String value) {
      headers.put(name, value);
      return this;
    }

    public TestMessage label(String label) {
      labels.add(label);
      return this;
    }

    public TestMessage receivedAt(Instant instant) {
      this.receivedAt = instant;
      return this;
    }

    /** Replaces the body with a single part or a multipart built from the static helpers. */
    public TestMessage payload(Map<String, Object> part) {
      this.payload = part;
      return this;
    }

    public TestMessage text(String body) {
      return payload(textPart("text/plain", body, StandardCharsets.UTF_8));
    }

    private Map<String, Object> toJson() {
      Map<String, Object> top = new LinkedHashMap<>(payload);
      List<Map<String, String>> all = new ArrayList<>();
      headers.forEach((name, value) -> all.add(Map.of("name", name, "value", value)));
      @SuppressWarnings("unchecked")
      List<Map<String, String>> own = (List<Map<String, String>>) top.get("headers");
      if (own != null) {
        all.addAll(own);
      }
      top.put("headers", all);
      Map<String, Object> message = new LinkedHashMap<>();
      message.put("id", id);
      message.put("threadId", id);
      message.put("labelIds", labels);
      message.put("internalDate", "" + receivedAt.toEpochMilli());
      message.put("payload", top);
      return message;
    }
  }

  // ---- MIME parts --------------------------------------------------------------------------

  public static Map<String, Object> textPart(String mimeType, String text, Charset charset) {
    byte[] bytes = text.getBytes(charset);
    Map<String, Object> part = new LinkedHashMap<>();
    part.put("mimeType", mimeType);
    part.put("filename", "");
    part.put(
        "headers",
        List.of(
            Map.of(
                "name",
                "Content-Type",
                "value",
                mimeType + "; charset=\"" + charset.name() + "\"")));
    part.put(
        "body", Map.of("size", bytes.length, "data", Base64.getUrlEncoder().encodeToString(bytes)));
    return part;
  }

  public static Map<String, Object> multipart(String subtype, List<Map<String, Object>> parts) {
    Map<String, Object> part = new LinkedHashMap<>();
    part.put("mimeType", "multipart/" + subtype);
    part.put("filename", "");
    part.put("body", Map.of("size", 0));
    part.put("parts", parts);
    return part;
  }

  /** An attachment whose content has to be fetched separately, as Gmail does for larger files. */
  public static Map<String, Object> attachmentPart(
      String filename, String mimeType, String attachmentId, long size) {
    Map<String, Object> part = new LinkedHashMap<>();
    part.put("mimeType", mimeType);
    part.put("filename", filename);
    part.put("body", Map.of("attachmentId", attachmentId, "size", size));
    return part;
  }

  /** An attachment whose content comes along inside the message. */
  public static Map<String, Object> inlineAttachmentPart(
      String filename, String mimeType, byte[] content) {
    Map<String, Object> part = new LinkedHashMap<>();
    part.put("mimeType", mimeType);
    part.put("filename", filename);
    part.put(
        "body",
        Map.of("size", content.length, "data", Base64.getUrlEncoder().encodeToString(content)));
    return part;
  }

  private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(
      Object body) {
    return aResponse()
        .withStatus(200)
        .withHeader("Content-Type", "application/json; charset=UTF-8")
        .withBody(GSON.toJson(body));
  }

  private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder error(
      int status, String reason) {
    return aResponse()
        .withStatus(status)
        .withHeader("Content-Type", "application/json; charset=UTF-8")
        .withBody(
            GSON.toJson(
                Map.of(
                    "error",
                    Map.of(
                        "code",
                        status,
                        "message",
                        "Simulated " + reason,
                        "errors",
                        List.of(Map.of("reason", reason, "message", "Simulated " + reason))))));
  }
}
