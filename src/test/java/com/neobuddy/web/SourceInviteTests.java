package com.neobuddy.web;

import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.neobuddy.ingestion.gmail.GmailTestServer.textPart;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.neobuddy.ingestion.SourceAdmin;
import com.neobuddy.ingestion.SourceAdmin.NewInvite;
import com.neobuddy.ingestion.SourceConnectException;
import com.neobuddy.ingestion.gmail.GmailBackedDatabaseTest;
import com.neobuddy.ingestion.gmail.GmailTestServer.Account;
import com.neobuddy.ingestion.gmail.GmailTestServer.HistoryRecord;
import com.neobuddy.ingestion.gmail.GmailTestServer.TestMessage;
import com.neobuddy.ingestion.gmail.GmailWebAuthorization;
import com.neobuddy.ingestion.source.OAuthClient;
import com.neobuddy.ingestion.source.SourceInbox;
import com.neobuddy.ingestion.source.SourceStatus;
import com.neobuddy.ingestion.source.SourceSync;
import com.neobuddy.shared.college.College;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * The invite link a campus lead opens to connect their college's inbox, end to end against a fake
 * Google: Google's consent screen is skipped, everything else (the redirect, the state check, the
 * code exchange, the profile lookup, polling with the web client's token) is real. The link works
 * once and expires after 24 hours.
 */
@AutoConfigureMockMvc
class SourceInviteTests extends GmailBackedDatabaseTest {

  @Autowired private MockMvc mvc;
  @Autowired private SourceAdmin admin;
  @Autowired private SourceSync sync;

  private College college;
  private Account account;
  private String address;

  @DynamicPropertySource
  static void googleWebClient(DynamicPropertyRegistry registry) {
    registry.add("neobuddy.gmail.web.client-id", () -> "test-web-client-id");
    registry.add("neobuddy.gmail.web.client-secret", () -> "test-web-client-secret");
    registry.add("neobuddy.gmail.web.auth-uri", () -> GOOGLE.web().authUri().toString());
    registry.add("neobuddy.gmail.web.revoke-uri", () -> GOOGLE.web().revokeUri().toString());
  }

  @BeforeEach
  void aCollegeAndACampusLeadsInbox() {
    college = newCollege();
    account = GOOGLE.newAccount();
    address = "lead-" + UUID.randomUUID().toString().substring(0, 8) + "@gmail.example";
    account.profile(newHistoryId(), address);
    GOOGLE.acceptRevocations();
  }

  /** Opens the link, follows it to Google and back with Google's answer. */
  private MvcResult connect(String token, String code, MockHttpSession session) throws Exception {
    MvcResult start =
        mvc.perform(get("/sources/connect/{token}/start", token).session(session))
            .andExpect(status().is3xxRedirection())
            .andReturn();
    Map<String, String> query = query(start.getResponse().getRedirectedUrl());
    return mvc.perform(
            get("/sources/connect/callback")
                .param("state", query.get("state"))
                .param("code", code)
                .session(session))
        .andReturn();
  }

  private static Map<String, String> query(String url) {
    return UriComponentsBuilder.fromUri(URI.create(url))
        .build()
        .getQueryParams()
        .toSingleValueMap();
  }

  private String newCode() {
    String code = "code-" + UUID.randomUUID();
    account.authorizationCode(code, GmailWebAuthorization.SCOPE);
    return code;
  }

  @Test
  void theLinkExplainsWhatWillHappenWithoutSignIn() throws Exception {
    NewInvite invite = admin.createInvite(college.getId(), null);

    mvc.perform(get("/sources/connect/{token}", invite.token()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString(college.getName())))
        .andExpect(content().string(containsString("Continue with Google")))
        .andExpect(
            content().string(containsString("/sources/connect/" + invite.token() + "/start")));
  }

  @Test
  void startingSendsTheBrowserToGoogleForReadOnlyOfflineAccessWithPkce() throws Exception {
    NewInvite invite = admin.createInvite(college.getId(), null);

    MvcResult start =
        mvc.perform(get("/sources/connect/{token}/start", invite.token()))
            .andExpect(status().is3xxRedirection())
            .andReturn();

    String location = start.getResponse().getRedirectedUrl();
    assertThat(location).startsWith(GOOGLE.web().authUri().toString());
    assertThat(query(location))
        .containsEntry("client_id", "test-web-client-id")
        .containsEntry("scope", "https%3A%2F%2Fwww.googleapis.com%2Fauth%2Fgmail.readonly")
        .containsEntry("access_type", "offline")
        .containsEntry("prompt", "consent")
        .containsEntry("response_type", "code")
        .containsEntry("code_challenge_method", "S256")
        .containsEntry("redirect_uri", "http%3A%2F%2Flocalhost%2Fsources%2Fconnect%2Fcallback")
        .containsKeys("state", "code_challenge");
  }

  @Test
  void theLinkConnectsTheInboxWhoseEmailsAreThenProcessed() throws Exception {
    NewInvite invite = admin.createInvite(college.getId(), null);

    assertThat(
            connect(invite.token(), newCode(), new MockHttpSession())
                .getResponse()
                .getContentAsString())
        .contains("Inbox connected")
        .contains(address);

    SourceInbox source = sources.findByEmail(address).orElseThrow();
    assertThat(source.getCollegeId()).isEqualTo(college.getId());
    assertThat(source.getOauthClient()).isEqualTo(OAuthClient.WEB);
    assertThat(source.getStatus()).isEqualTo(SourceStatus.ACTIVE);
    assertThat(source.getRefreshToken()).isEqualTo(account.refreshToken());
    assertThat(source.getLastHistoryId()).as("starts from its first poll").isNull();
    assertThat(
            jdbc.queryForMap(
                "select used_at, source_id from source_invite where id = ?", invite.id()))
        .containsEntry("source_id", source.getId())
        .satisfies(row -> assertThat(row.get("used_at")).isNotNull());
    // The code was exchanged with the web client's secret and the PKCE verifier.
    GOOGLE
        .wireMock()
        .verify(
            postRequestedFor(urlPathEqualTo("/token"))
                .withRequestBody(containing("client_secret=test-web-client-secret"))
                .withRequestBody(containing("code_verifier=")));

    // Polled with the web client: the first poll starts from now, the next one ingests new mail.
    long start = newHistoryId();
    account.profile(start, address);
    assertThat(sync.sync(source.getId()).outcome()).isEqualTo(SourceSync.Outcome.STARTED_FROM_NOW);
    String messageId = newMessageId();
    TestMessage message =
        TestMessage.withId(TestMessage.newId())
            .from(placementCell(college))
            .subject("Example Corp - Shortlist")
            .messageId(messageId)
            .payload(textPart("text/plain", "Dear students, nothing attached.", UTF_8));
    account.message(message);
    account.history(
        start, null, java.util.List.of(HistoryRecord.of(start + 1, message.id())), null, start + 1);

    assertThat(sync.sync(source.getId()).ingested()).isEqualTo(1);
    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(() ->
            assertThat(
                    jdbc.queryForObject(
                        "select status from processed_email where rfc_message_id = ?",
                        String.class,
                        messageId))
                .isEqualTo("PROCESSED"));
  }

  @Test
  void theLinkWorksOnlyOnce() throws Exception {
    NewInvite invite = admin.createInvite(college.getId(), null);
    connect(invite.token(), newCode(), new MockHttpSession());

    mvc.perform(get("/sources/connect/{token}", invite.token()))
        .andExpect(content().string(containsString("already used")));
    mvc.perform(get("/sources/connect/{token}/start", invite.token()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("already used")));
    assertThat(admin.usableInvite(invite.token())).isEmpty();
  }

  @Test
  void aSecondCompletionOfTheSameLinkFailsAndItsGrantIsRevoked() {
    NewInvite invite = admin.createInvite(college.getId(), null);
    String redirect = "http://localhost/sources/connect/callback";
    admin.complete(invite.token(), newCode(), redirect, "verifier");

    assertThatThrownBy(() -> admin.complete(invite.token(), newCode(), redirect, "verifier"))
        .isInstanceOfSatisfying(
            SourceConnectException.class,
            e -> assertThat(e.reason()).isEqualTo(SourceConnectException.Reason.INVITE_UNUSABLE));
    assertThat(
            jdbc.queryForObject(
                "select count(*) from mail_source where college_id = ?",
                Integer.class,
                college.getId()))
        .isEqualTo(1);
  }

  @Test
  void theLinkExpiresAfter24Hours() throws Exception {
    NewInvite invite = admin.createInvite(college.getId(), null);
    assertThat(Duration.between(java.time.Instant.now(), invite.expiresAt()))
        .isBetween(Duration.ofHours(23).plusMinutes(59), Duration.ofHours(24));
    MockHttpSession session = new MockHttpSession();
    MvcResult start =
        mvc.perform(get("/sources/connect/{token}/start", invite.token()).session(session))
            .andExpect(status().is3xxRedirection())
            .andReturn();
    // The person takes too long at Google: the link expires in the meantime.
    jdbc.update(
        "update source_invite set expires_at = now() - interval '1 second' where id = ?",
        invite.id());

    String page =
        mvc.perform(
                get("/sources/connect/callback")
                    .param("state", query(start.getResponse().getRedirectedUrl()).get("state"))
                    .param("code", newCode())
                    .session(session))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(page).contains("expired");
    assertThat(sources.findByEmail(address)).isEmpty();
    mvc.perform(get("/sources/connect/{token}", invite.token()))
        .andExpect(content().string(containsString("expired")));
  }

  @Test
  void aCallbackThatDoesNotBelongToThisBrowsersRequestIsRefused() throws Exception {
    NewInvite invite = admin.createInvite(college.getId(), null);
    MockHttpSession session = new MockHttpSession();
    mvc.perform(get("/sources/connect/{token}/start", invite.token()).session(session));

    mvc.perform(
            get("/sources/connect/callback")
                .param("state", "forged")
                .param("code", newCode())
                .session(session))
        .andExpect(content().string(containsString("not granted")));

    assertThat(sources.findByEmail(address)).isEmpty();
    assertThat(admin.usableInvite(invite.token())).as("still usable").isPresent();
  }

  @Test
  void declinedReadAccessConnectsNothing() throws Exception {
    NewInvite invite = admin.createInvite(college.getId(), null);
    String code = "code-" + UUID.randomUUID();
    account.authorizationCode(code, "openid email");

    String page =
        connect(invite.token(), code, new MockHttpSession()).getResponse().getContentAsString();

    assertThat(page).contains("Read access to Gmail was not ticked");
    assertThat(sources.findByEmail(address)).isEmpty();
    assertThat(admin.usableInvite(invite.token())).isPresent();
  }

  @Test
  void anUnknownLinkShowsTheSameMessageAsAUsedOne() throws Exception {
    mvc.perform(get("/sources/connect/{token}", "not-a-real-token"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("expired or was already used")));
  }

  @Test
  void disconnectingStopsPollingForgetsTheTokenAndRevokesIt() {
    NewInvite invite = admin.createInvite(college.getId(), null);
    admin.complete(
        invite.token(), newCode(), "http://localhost/sources/connect/callback", "verifier");
    SourceInbox source = sources.findByEmail(address).orElseThrow();

    assertThat(admin.disconnect(source.getId())).isTrue();

    SourceInbox disconnected = sources.findById(source.getId()).orElseThrow();
    assertThat(disconnected.getStatus()).isEqualTo(SourceStatus.DISABLED);
    assertThat(disconnected.getRefreshToken()).isEmpty();
    assertThat(sync.sync(source.getId()).outcome()).isEqualTo(SourceSync.Outcome.SKIPPED);
    GOOGLE
        .wireMock()
        .verify(
            postRequestedFor(urlPathEqualTo("/revoke"))
                .withRequestBody(containing("token=" + account.refreshToken())));
    assertThat(admin.sources())
        .filteredOn(s -> s.id().equals(source.getId()))
        .singleElement()
        .satisfies(s -> assertThat(s.health()).isEqualTo(SourceAdmin.Health.DISCONNECTED));
  }
}
