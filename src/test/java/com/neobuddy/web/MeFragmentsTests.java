package com.neobuddy.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.neobuddy.shared.college.College;
import com.neobuddy.subscriber.ChannelType;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** The htmx side of /api/v1/me: same paths, form fields in, page fragments out. */
class MeFragmentsTests extends WebDatabaseTest {

  private College college;
  private Student student;

  @BeforeEach
  void aStudent() {
    college = newCollege();
    student = newStudent(college);
  }

  private ResultActions htmx(MockHttpServletRequestBuilder request) throws Exception {
    return mvc.perform(request.header("HX-Request", "true").with(as(student)).with(csrf()));
  }

  private static void flashError(ResultActions result, int status, String text) throws Exception {
    result
        .andExpect(status().is(status))
        .andExpect(header().string("HX-Retarget", "#flash"))
        .andExpect(header().string("HX-Reswap", "innerHTML"))
        .andExpect(content().string(allOf(containsString("notice error"), containsString(text))));
  }

  private String address() {
    return "tara-" + UUID.randomUUID().toString().substring(0, 8) + "@personal.example";
  }

  @Test
  void addingAnIdentifierReturnsTheIdentifiersSection() throws Exception {
    htmx(post("/api/v1/me/identifiers").param("type", "REG_NO").param("value", "21bce1234"))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(
                    allOf(
                        containsString("id=\"identifiers\""),
                        containsString("21BCE1234"),
                        containsString(student.neoId()),
                        containsString("Saved."))))
        .andExpect(content().string(not(containsString("<html"))));
  }

  @Test
  void aClaimedIdentifierShowsAMessageInTheFlashArea() throws Exception {
    Student fresh = newStudentWithoutNeoId(college);

    flashError(
        mvc.perform(
            post("/api/v1/me/identifiers")
                .header("HX-Request", "true")
                .with(as(fresh))
                .with(csrf())
                .param("type", "NEO_ID")
                .param("value", student.neoId())),
        409,
        "Another account already uses this identifier");
  }

  @Test
  void aMissingFieldShowsItsMessage() throws Exception {
    flashError(htmx(post("/api/v1/me/identifiers").param("type", "REG_NO")), 400, "Enter a value.");
  }

  @Test
  void withoutTheCsrfTokenNothingChanges() throws Exception {
    mvc.perform(
            post("/api/v1/me/identifiers")
                .header("HX-Request", "true")
                .with(as(student))
                .param("type", "REG_NO")
                .param("value", "21BCE1234"))
        .andExpect(status().isForbidden());

    assertThat(subscribers.get(student.id()).identifiers()).hasSize(1);
  }

  @Test
  void removingAnIdentifierReturnsTheSectionWithoutIt() throws Exception {
    UUID id = subscribers.get(student.id()).identifiers().get(0).id();

    htmx(delete("/api/v1/me/identifiers/" + id))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(
                    allOf(
                        containsString("No identifiers yet."),
                        not(containsString(student.neoId())))));
  }

  @Test
  void theEmailFlowAsksForTheCodeThenShowsTheChannelVerified() throws Exception {
    String address = address();

    htmx(post("/api/v1/me/channels/email").param("address", address))
        .andExpect(status().isAccepted())
        .andExpect(
            content()
                .string(
                    allOf(
                        containsString("id=\"channels\""),
                        containsString("Enter the 6-digit code"),
                        containsString(address))));
    htmx(post("/api/v1/me/channels/email/verify").param("code", codes.codeFor(address)))
        .andExpect(status().isOk())
        .andExpect(
            content().string(allOf(containsString("Email verified"), containsString("Send test"))));
  }

  @Test
  void aWrongCodeAndALockedCodeShowMessages() throws Exception {
    String address = address();
    htmx(post("/api/v1/me/channels/email").param("address", address));
    String wrong = codes.codeFor(address).equals("000000") ? "000001" : "000000";

    for (int i = 0; i < 4; i++) {
      flashError(
          htmx(post("/api/v1/me/channels/email/verify").param("code", wrong)),
          422,
          "That code is not right");
    }
    flashError(
        htmx(post("/api/v1/me/channels/email/verify").param("code", wrong)),
        429,
        "Ask for a new code");
  }

  @Test
  void theFourthCodeInAnHourIsRefusedWithRetryAfter() throws Exception {
    for (int i = 0; i < 3; i++) {
      htmx(post("/api/v1/me/channels/email").param("address", address()))
          .andExpect(status().isAccepted());
    }

    ResultActions fourth = htmx(post("/api/v1/me/channels/email").param("address", address()));

    flashError(fourth, 429, "too often");
    fourth.andExpect(header().exists("Retry-After"));
  }

  @Test
  void settingUpPushShowsTheTopicAndAQrCode() throws Exception {
    htmx(post("/api/v1/me/channels/ntfy"))
        .andExpect(status().isCreated())
        .andExpect(
            content()
                .string(
                    allOf(
                        containsString("<svg"),
                        containsString("https://ntfy.sh/nb-"),
                        containsString("How to subscribe on your phone"),
                        containsString("open=\"open\""))));
  }

  @Test
  void pausingAChannelReturnsTheSectionWithResume() throws Exception {
    htmx(post("/api/v1/me/channels/ntfy"));
    UUID push = subscribers.get(student.id()).channels().get(0).id();

    htmx(patch("/api/v1/me/channels/" + push).param("enabled", "false"))
        .andExpect(status().isOk())
        .andExpect(
            content().string(allOf(containsString("Channel paused."), containsString("Resume"))));
    assertThat(subscribers.get(student.id()).channels().get(0).enabled()).isFalse();
  }

  @Test
  void aTestAlertAnswersWithAFlashMessage() throws Exception {
    htmx(post("/api/v1/me/channels/ntfy"));
    UUID push = subscribers.get(student.id()).channels().get(0).id();

    htmx(post("/api/v1/me/channels/" + push + "/test"))
        .andExpect(status().isAccepted())
        .andExpect(
            content().string(allOf(containsString("notice ok"), containsString("on its way"))));
    flashError(
        htmx(post("/api/v1/me/channels/" + UUID.randomUUID() + "/test")), 404, "no such channel");
  }

  @Test
  void theAlertsSectionListsTheTestAlert() throws Exception {
    htmx(post("/api/v1/me/channels/ntfy"));
    UUID push =
        subscribers.get(student.id()).channels().stream()
            .filter(c -> c.type() == ChannelType.NTFY)
            .findFirst()
            .orElseThrow()
            .id();
    htmx(post("/api/v1/me/channels/" + push + "/test"));

    htmx(get("/api/v1/me/alerts"))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(
                    allOf(
                        containsString("id=\"alerts\""),
                        containsString("Test alert"),
                        containsString("Push: sending"),
                        containsString("hx-trigger=\"every 30s\""))));
  }

  @Test
  void deletingTheAccountRedirectsTheBrowserHome() throws Exception {
    htmx(delete("/api/v1/me"))
        .andExpect(status().isNoContent())
        .andExpect(header().string("HX-Redirect", "/?deleted"));

    assertThat(
            jdbc.queryForObject(
                "select count(*) from app_user where id = ?", Integer.class, student.id()))
        .isZero();
  }

  @Test
  void anExpiredSessionSendsTheBrowserHome() throws Exception {
    mvc.perform(get("/api/v1/me/alerts").header("HX-Request", "true"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string("HX-Redirect", "/"));
  }
}
