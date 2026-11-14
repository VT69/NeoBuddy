package com.neobuddy.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.neobuddy.shared.college.College;
import com.neobuddy.subscriber.ChannelType;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** The JSON side of /api/v1/me: every endpoint, its errors as RFC 9457 problems, and its limits. */
class MeApiTests extends WebDatabaseTest {

  private College college;
  private Student student;

  @BeforeEach
  void aStudent() {
    college = newCollege();
    student = newStudent(college);
  }

  private ResultActions call(MockHttpServletRequestBuilder request) throws Exception {
    return mvc.perform(request.with(as(student)).with(csrf()).accept(MediaType.APPLICATION_JSON));
  }

  private ResultActions send(MockHttpServletRequestBuilder request, String json) throws Exception {
    return call(request.contentType(MediaType.APPLICATION_JSON).content(json));
  }

  private static void problem(ResultActions result, int status, String type) throws Exception {
    result
        .andExpect(status().is(status))
        .andExpect(header().string("Content-Type", startsWith("application/problem+json")))
        .andExpect(jsonPath("$.type").value("/problems/" + type))
        .andExpect(jsonPath("$.status").value(status))
        .andExpect(jsonPath("$.detail").isNotEmpty());
  }

  private UUID emailChannel(String address) throws Exception {
    send(post("/api/v1/me/channels/email"), "{\"address\":\"" + address + "\"}")
        .andExpect(status().isAccepted());
    send(post("/api/v1/me/channels/email/verify"), "{\"code\":\"" + codes.codeFor(address) + "\"}")
        .andExpect(status().isOk());
    return channelId(ChannelType.EMAIL);
  }

  private UUID channelId(ChannelType type) {
    return subscribers.get(student.id()).channels().stream()
        .filter(c -> c.type() == type)
        .findFirst()
        .orElseThrow()
        .id();
  }

  private String address() {
    return "tara-" + UUID.randomUUID().toString().substring(0, 8) + "@personal.example";
  }

  // ---- Access ----------------------------------------------------------------------------------

  @Test
  void notSignedInIsAProblemNotARedirect() throws Exception {
    problem(
        mvc.perform(get("/api/v1/me").accept(MediaType.APPLICATION_JSON)), 401, "not-signed-in");
  }

  @Test
  void aChangeWithoutTheCsrfTokenIsRefused() throws Exception {
    problem(
        mvc.perform(
            post("/api/v1/me/identifiers")
                .with(as(student))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"REG_NO\",\"value\":\"21BCE1234\"}")),
        403,
        "csrf-failed");
    assertThat(subscribers.get(student.id()).identifiers()).hasSize(1);
  }

  @Test
  void aStudentWhoHasNotConsentedHasNoAccessYet() throws Exception {
    problem(
        mvc.perform(
            get("/api/v1/me")
                .with(pending(TestEmail.of(college)))
                .accept(MediaType.APPLICATION_JSON)),
        403,
        "forbidden");
  }

  // ---- Profile ---------------------------------------------------------------------------------

  @Test
  void getReturnsTheProfileIdentifiersAndChannels() throws Exception {
    String address = address();
    emailChannel(address);

    call(get("/api/v1/me"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(student.id().toString()))
        .andExpect(jsonPath("$.email").value(student.email()))
        .andExpect(jsonPath("$.role").value("USER"))
        .andExpect(jsonPath("$.identifiers", hasSize(1)))
        .andExpect(jsonPath("$.identifiers[0].type").value("NEO_ID"))
        .andExpect(jsonPath("$.identifiers[0].value").value(student.neoId()))
        .andExpect(jsonPath("$.channels[0].type").value("EMAIL"))
        .andExpect(jsonPath("$.channels[0].verified").value(true))
        .andExpect(jsonPath("$.channels[0].address").value(address));
  }

  @Test
  void deleteRemovesTheAccountAndSignsOut() throws Exception {
    call(delete("/api/v1/me")).andExpect(status().isNoContent());

    assertThat(
            jdbc.queryForObject(
                "select count(*) from app_user where id = ?", Integer.class, student.id()))
        .isZero();
  }

  // ---- Identifiers -----------------------------------------------------------------------------

  @Test
  void addingAnIdentifierReturns201WithItsLocation() throws Exception {
    send(post("/api/v1/me/identifiers"), "{\"type\":\"REG_NO\",\"value\":\" 21bce 1234 \"}")
        .andExpect(status().isCreated())
        .andExpect(header().string("Location", startsWith("/api/v1/me/identifiers/")))
        .andExpect(jsonPath("$.type").value("REG_NO"))
        .andExpect(jsonPath("$.value").value("21BCE1234"));
  }

  @Test
  void anIdentifierAnotherStudentHoldsIs409() throws Exception {
    Student fresh = newStudentWithoutNeoId(college);

    problem(
        mvc.perform(
            post("/api/v1/me/identifiers")
                .with(as(fresh))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"type\":\"NEO_ID\",\"value\":\"" + student.neoId().toLowerCase() + "\"}")),
        409,
        "identifier-already-claimed");
    assertThat(subscribers.get(fresh.id()).identifiers()).isEmpty();
  }

  @Test
  void aSecondIdentifierOfTheSameTypeIs422() throws Exception {
    problem(
        send(post("/api/v1/me/identifiers"), "{\"type\":\"NEO_ID\",\"value\":\"ZZZZ9999\"}"),
        422,
        "invalid-identifier");
  }

  @Test
  void aNeoIdInTheWrongFormatIs422AndNotEchoed() throws Exception {
    Student fresh = newStudentWithoutNeoId(college);

    mvc.perform(
            post("/api/v1/me/identifiers")
                .with(as(fresh))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"NEO_ID\",\"value\":\"not-a-neo-id-SECRET\"}"))
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.type").value("/problems/invalid-identifier"))
        .andExpect(content ->
            assertThat(content.getResponse().getContentAsString()).doesNotContain("SECRET"));
  }

  @Test
  void missingFieldsAre400WithAMessagePerField() throws Exception {
    problem(send(post("/api/v1/me/identifiers"), "{\"value\":\"\"}"), 400, "validation-failed");
    send(post("/api/v1/me/identifiers"), "{\"value\":\"\"}")
        .andExpect(jsonPath("$.errors.type").value("Choose the type of identifier."))
        .andExpect(jsonPath("$.errors.value").value("Enter a value."));
  }

  @Test
  void anUnknownIdentifierTypeIs400() throws Exception {
    send(post("/api/v1/me/identifiers"), "{\"type\":\"PASSPORT\",\"value\":\"x\"}")
        .andExpect(status().isBadRequest())
        .andExpect(header().string("Content-Type", startsWith("application/problem+json")));
  }

  @Test
  void removingAnIdentifierIs204AndSomeoneElsesIs404() throws Exception {
    Student other = newStudent(college);
    UUID mine = subscribers.get(student.id()).identifiers().get(0).id();
    UUID theirs = subscribers.get(other.id()).identifiers().get(0).id();

    problem(call(delete("/api/v1/me/identifiers/" + theirs)), 404, "not-found");
    call(delete("/api/v1/me/identifiers/" + mine)).andExpect(status().isNoContent());

    assertThat(subscribers.get(student.id()).identifiers()).isEmpty();
    assertThat(subscribers.get(other.id()).identifiers()).hasSize(1);
  }

  // ---- Email channel ---------------------------------------------------------------------------

  @Test
  void anEmailChannelIsVerifiedWithTheEmailedCode() throws Exception {
    String address = address();

    send(post("/api/v1/me/channels/email"), "{\"address\":\"" + address + "\"}")
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.alreadyVerified").value(false))
        .andExpect(jsonPath("$.expiresAt").isNotEmpty());
    send(post("/api/v1/me/channels/email/verify"), "{\"code\":\"" + codes.codeFor(address) + "\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.verified").value(true));

    assertThat(subscribers.get(student.id()).channels())
        .singleElement()
        .satisfies(c -> assertThat(c.verified()).isTrue());
  }

  @Test
  void anInvalidAddressIs400() throws Exception {
    problem(
        send(post("/api/v1/me/channels/email"), "{\"address\":\"not an address\"}"),
        400,
        "validation-failed");
  }

  @Test
  void aCodeThatCannotBeSentIs503() throws Exception {
    String address = address();
    codes.failFor(address);

    problem(
        send(post("/api/v1/me/channels/email"), "{\"address\":\"" + address + "\"}"),
        503,
        "email-not-sent");
  }

  @Test
  void atMostThreeCodesAnHour() throws Exception {
    for (int i = 0; i < 3; i++) {
      send(post("/api/v1/me/channels/email"), "{\"address\":\"" + address() + "\"}")
          .andExpect(status().isAccepted());
    }

    ResultActions fourth =
        send(post("/api/v1/me/channels/email"), "{\"address\":\"" + address() + "\"}");

    problem(fourth, 429, "rate-limited");
    fourth.andExpect(header().string("Retry-After", matchesPattern("\\d+")));
  }

  @Test
  void aWrongCodeIs422() throws Exception {
    String address = address();
    send(post("/api/v1/me/channels/email"), "{\"address\":\"" + address + "\"}");

    problem(
        send(
            post("/api/v1/me/channels/email/verify"),
            "{\"code\":\"" + wrong(codes.codeFor(address)) + "\"}"),
        422,
        "wrong-code");
  }

  @Test
  void afterFiveWrongCodesTheCodeIsLockedEvenForTheRightOne() throws Exception {
    String address = address();
    send(post("/api/v1/me/channels/email"), "{\"address\":\"" + address + "\"}");
    String code = codes.codeFor(address);

    for (int i = 0; i < 4; i++) {
      problem(
          send(post("/api/v1/me/channels/email/verify"), "{\"code\":\"" + wrong(code) + "\"}"),
          422,
          "wrong-code");
    }
    problem(
        send(post("/api/v1/me/channels/email/verify"), "{\"code\":\"" + wrong(code) + "\"}"),
        429,
        "code-locked");
    problem(
        send(post("/api/v1/me/channels/email/verify"), "{\"code\":\"" + code + "\"}"),
        429,
        "code-locked");

    assertThat(subscribers.get(student.id()).channels().get(0).verified()).isFalse();
  }

  @Test
  void verifyingWithoutACodeIs409AndAMalformedCodeIs400() throws Exception {
    problem(
        send(post("/api/v1/me/channels/email/verify"), "{\"code\":\"123456\"}"),
        409,
        "no-pending-code");
    problem(
        send(post("/api/v1/me/channels/email/verify"), "{\"code\":\"12ab\"}"),
        400,
        "validation-failed");
  }

  @Test
  void anExpiredCodeIs410() throws Exception {
    String address = address();
    send(post("/api/v1/me/channels/email"), "{\"address\":\"" + address + "\"}");
    jdbc.update(
        "update link_code set expires_at = now() - interval '1 second' where user_id = ?",
        student.id());

    problem(
        send(
            post("/api/v1/me/channels/email/verify"),
            "{\"code\":\"" + codes.codeFor(address) + "\"}"),
        410,
        "code-expired");
  }

  // ---- Push channel ----------------------------------------------------------------------------

  @Test
  void aPushChannelComesWithItsTopicSubscribeLinkAndQrCode() throws Exception {
    call(post("/api/v1/me/channels/ntfy"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.topic", matchesPattern("nb-[a-z2-7]{26}")))
        .andExpect(jsonPath("$.subscribeUrl", startsWith("https://ntfy.sh/nb-")))
        .andExpect(jsonPath("$.qrSvg", startsWith("<svg")))
        .andExpect(jsonPath("$.qrSvg", endsWith("</svg>")));

    assertThat(subscribers.get(student.id()).channels())
        .singleElement()
        .satisfies(c -> assertThat(c.verified()).isTrue());
  }

  // ---- Switching channels ----------------------------------------------------------------------

  @Test
  void aChannelCanBePausedAndResumed() throws Exception {
    UUID id = emailChannel(address());

    send(patch("/api/v1/me/channels/" + id), "{\"enabled\":false}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.enabled").value(false))
        .andExpect(jsonPath("$.verified").value(true));
    send(patch("/api/v1/me/channels/" + id), "{\"enabled\":true}")
        .andExpect(jsonPath("$.enabled").value(true));
  }

  @Test
  void patchingSomeoneElsesChannelIs404AndAnEmptyBodyIs400() throws Exception {
    problem(
        send(patch("/api/v1/me/channels/" + UUID.randomUUID()), "{\"enabled\":false}"),
        404,
        "not-found");
    problem(
        send(patch("/api/v1/me/channels/" + UUID.randomUUID()), "{}"), 400, "validation-failed");
  }

  // ---- Test alerts and history -----------------------------------------------------------------

  @Test
  void aTestAlertIsQueuedAsATestMatchForThatChannelOnly() throws Exception {
    UUID email = emailChannel(address());
    call(post("/api/v1/me/channels/ntfy")).andExpect(status().isCreated());

    call(post("/api/v1/me/channels/" + email + "/test"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.deliveryId").isNotEmpty())
        .andExpect(jsonPath("$.message", containsString("on its way")));

    Map<String, Object> match =
        jdbc.queryForMap("select * from match_hit where user_id = ?", student.id());
    assertThat(match).containsEntry("is_test", true).containsEntry("event_type", "OTHER");
    assertThat(
            jdbc.queryForList(
                "select channel_id, status from delivery where match_id = ?", match.get("id")))
        .singleElement()
        .satisfies(row ->
            assertThat(row).containsEntry("channel_id", email).containsEntry("status", "PENDING"));
    assertThat(
            jdbc.queryForMap(
                "select is_test, source_id from processed_email where rfc_message_id = ?",
                match.get("email_id")))
        .containsEntry("is_test", true)
        .containsEntry("source_id", null);
  }

  @Test
  void atMostFiveTestAlertsAnHour() throws Exception {
    UUID email = emailChannel(address());
    for (int i = 0; i < 5; i++) {
      call(post("/api/v1/me/channels/" + email + "/test")).andExpect(status().isAccepted());
    }

    problem(call(post("/api/v1/me/channels/" + email + "/test")), 429, "rate-limited");
  }

  @Test
  void aTestToAnUnverifiedOrPausedChannelIs409() throws Exception {
    send(post("/api/v1/me/channels/email"), "{\"address\":\"" + address() + "\"}");
    UUID unverified = channelId(ChannelType.EMAIL);

    problem(call(post("/api/v1/me/channels/" + unverified + "/test")), 409, "channel-not-ready");
    problem(
        call(post("/api/v1/me/channels/" + UUID.randomUUID() + "/test")), 404, "channel-not-found");
  }

  @Test
  void aTestNeedsAnIdentifier() throws Exception {
    Student fresh = newStudentWithoutNeoId(college);
    mvc.perform(post("/api/v1/me/channels/ntfy").with(as(fresh)).with(csrf()))
        .andExpect(status().isCreated());
    UUID push = subscribers.get(fresh.id()).channels().get(0).id();

    mvc.perform(post("/api/v1/me/channels/" + push + "/test").with(as(fresh)).with(csrf()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.type").value("/problems/identifier-required"));
  }

  @Test
  void theHistoryShowsTheTestAlertWithItsDeliveryStatus() throws Exception {
    UUID email = emailChannel(address());
    call(post("/api/v1/me/channels/" + email + "/test"));

    call(get("/api/v1/me/alerts"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.page").value(0))
        .andExpect(jsonPath("$.size").value(20))
        .andExpect(jsonPath("$.items", hasSize(1)))
        .andExpect(jsonPath("$.items[0].test").value(true))
        .andExpect(jsonPath("$.items[0].title").value("Test alert"))
        .andExpect(jsonPath("$.items[0].deliveries[0].channelType").value("EMAIL"))
        .andExpect(jsonPath("$.items[0].deliveries[0].status").value("PENDING"))
        .andExpect(jsonPath("$.items[0].receivedAtIst", endsWith(" IST")));
    call(get("/api/v1/me/alerts").param("page", "1")).andExpect(jsonPath("$.items", hasSize(0)));
  }

  @Test
  void aStudentNeverSeesSomeoneElsesAlerts() throws Exception {
    Student other = newStudent(college);
    mvc.perform(post("/api/v1/me/channels/ntfy").with(as(other)).with(csrf()));
    UUID theirs = subscribers.get(other.id()).channels().get(0).id();
    mvc.perform(post("/api/v1/me/channels/" + theirs + "/test").with(as(other)).with(csrf()))
        .andExpect(status().isAccepted());

    call(get("/api/v1/me/alerts")).andExpect(jsonPath("$.items", hasSize(0)));
    problem(call(post("/api/v1/me/channels/" + theirs + "/test")), 404, "channel-not-found");
  }

  private static String wrong(String code) {
    return code.equals("000000") ? "000001" : "000000";
  }

  /** A not-yet-registered address on the college's domain. */
  static final class TestEmail {
    static String of(College college) {
      return com.neobuddy.subscriber.TestColleges.studentEmail(college);
    }
  }
}
