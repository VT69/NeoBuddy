package com.neobuddy.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.neobuddy.shared.college.College;
import com.neobuddy.subscriber.IdentifierType;
import com.neobuddy.subscriber.TestColleges;
import com.neobuddy.web.auth.SignInHandlers;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The pages: public ones, consent, onboarding, dashboard, settings and the college request form.
 */
class PagesTests extends WebDatabaseTest {

  // ---- Public pages ----------------------------------------------------------------------------

  @ParameterizedTest
  @ValueSource(strings = {"/", "/privacy", "/terms", "/not-supported", "/sign-in-problem"})
  void publicPagesNeedNoSignIn(String path) throws Exception {
    mvc.perform(get(path))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith("text/html"))
        .andExpect(header().string("Content-Security-Policy", containsString("default-src 'self'")))
        .andExpect(header().string("X-Frame-Options", "DENY"));
  }

  @Test
  void theLandingPageOffersGoogleSignIn() throws Exception {
    mvc.perform(get("/"))
        .andExpect(
            content()
                .string(
                    allOf(
                        containsString("/oauth2/authorization/google"),
                        containsString("Your row is deleted after 180 days"))));
  }

  @Test
  void thePrivacyPolicyStatesRetentionAndDeletion() throws Exception {
    mvc.perform(get("/privacy"))
        .andExpect(
            content()
                .string(
                    allOf(
                        containsString("180 days"),
                        containsString("at once"),
                        containsString("30 days"),
                        containsString("ntfy.sh"),
                        containsString("Amazon SES"))));
  }

  @Test
  void studentPagesSendStrangersToTheLandingPage() throws Exception {
    for (String path : new String[] {"/dashboard", "/onboarding", "/settings"}) {
      mvc.perform(get(path)).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/"));
    }
  }

  @Test
  void unknownPathsAreRefused() throws Exception {
    mvc.perform(get("/admin")).andExpect(status().is3xxRedirection());
  }

  @Test
  void aSignInProblemExplainsOnlyKnownReasons() throws Exception {
    mvc.perform(get("/sign-in-problem").param("reason", "hosted_domain_mismatch"))
        .andExpect(content().string(containsString("different organisation")));
    mvc.perform(get("/sign-in-problem").param("reason", "<script>"))
        .andExpect(
            content()
                .string(
                    allOf(
                        containsString("Something went wrong with the Google sign-in"),
                        not(containsString("<script>")))));
  }

  // ---- Consent ---------------------------------------------------------------------------------

  @Test
  void consentCreatesTheAccountAndTurnsTheSessionIntoAStudentSession() throws Exception {
    College college = newCollege();
    String email = TestColleges.studentEmail(college);

    mvc.perform(get("/consent").with(pending(email)))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(
                    allOf(
                        containsString(email), containsString("Nothing about you is stored yet"))));
    assertThat(subscribers.findByEmail(email)).isEmpty();

    MvcResult accepted =
        mvc.perform(post("/consent").with(pending(email)).with(csrf()).param("agree", "true"))
            .andExpect(redirectedUrl("/onboarding"))
            .andReturn();

    assertThat(subscribers.findByEmail(email))
        .hasValueSatisfying(
            account -> assertThat(account.consentedAt()).isCloseTo(Instant.now(), within()));
    MockHttpSession session = (MockHttpSession) accepted.getRequest().getSession();
    SecurityContext context =
        (SecurityContext)
            session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
    assertThat(context.getAuthentication().getAuthorities())
        .extracting(Object::toString)
        .containsExactly("ROLE_USER");
    mvc.perform(get("/onboarding").session(session))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Your NeoID")));
  }

  @Test
  void consentNeedsTheBoxTicked() throws Exception {
    String email = TestColleges.studentEmail(newCollege());

    mvc.perform(post("/consent").with(pending(email)).with(csrf()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Tick the box")));
    assertThat(subscribers.findByEmail(email)).isEmpty();
  }

  @Test
  void beforeConsentEveryStudentPageLeadsToTheConsentStep() throws Exception {
    String email = TestColleges.studentEmail(newCollege());

    mvc.perform(get("/dashboard").with(pending(email))).andExpect(redirectedUrl("/consent"));
  }

  @Test
  void consentWithoutTheCsrfTokenIsRefused() throws Exception {
    String email = TestColleges.studentEmail(newCollege());

    mvc.perform(post("/consent").with(pending(email)).param("agree", "true"))
        .andExpect(status().isForbidden());
    assertThat(subscribers.findByEmail(email)).isEmpty();
  }

  @Test
  void aStudentWithAnAccountHasNoConsentStep() throws Exception {
    Student student = newStudent(newCollege());

    mvc.perform(get("/consent").with(as(student))).andExpect(status().isForbidden());
  }

  // ---- Onboarding ------------------------------------------------------------------------------

  @Test
  void onboardingWalksFromNeoIdToChannels() throws Exception {
    Student student = newStudentWithoutNeoId(newCollege());
    String neoId = TestColleges.neoId();

    mvc.perform(get("/onboarding").param("step", "3").with(as(student)))
        .andExpect(content().string(containsString("Your NeoID")));
    mvc.perform(
            post("/onboarding/neo-id")
                .with(as(student))
                .with(csrf())
                .param("neoId", neoId.toLowerCase()))
        .andExpect(redirectedUrl("/onboarding?step=2"));
    mvc.perform(get("/onboarding").param("step", "2").with(as(student)))
        .andExpect(
            content()
                .string(
                    allOf(
                        containsString("More ways to find you"), containsString(student.email()))));
    mvc.perform(
            post("/onboarding/more-identifiers")
                .with(as(student))
                .with(csrf())
                .param("regNo", "21BCE1234")
                .param("collegeEmail", student.email()))
        .andExpect(redirectedUrl("/onboarding?step=3"));
    mvc.perform(get("/onboarding").param("step", "3").with(as(student)))
        .andExpect(
            content()
                .string(
                    allOf(
                        containsString("Where should we tell you?"),
                        containsString("Set up push"))));

    assertThat(subscribers.get(student.id()).identifiers())
        .extracting(i -> i.type())
        .containsExactlyInAnyOrder(
            IdentifierType.NEO_ID, IdentifierType.REG_NO, IdentifierType.COLLEGE_EMAIL);
  }

  @Test
  void aBadNeoIdShowsWhyAndStaysOnStepOne() throws Exception {
    Student student = newStudentWithoutNeoId(newCollege());

    mvc.perform(post("/onboarding/neo-id").with(as(student)).with(csrf()).param("neoId", "short"))
        .andExpect(redirectedUrl("/onboarding?step=1"))
        .andExpect(
            flash()
                .attribute(
                    "errors",
                    java.util.List.of(
                        "That is not in the format your college uses. Check it and try again.")));
  }

  @Test
  void theSecondStepIsOptional() throws Exception {
    Student student = newStudent(newCollege());

    mvc.perform(post("/onboarding/more-identifiers").with(as(student)).with(csrf()))
        .andExpect(redirectedUrl("/onboarding?step=3"));
    assertThat(subscribers.get(student.id()).identifiers()).hasSize(1);
  }

  // ---- Dashboard and settings ------------------------------------------------------------------

  @Test
  void theDashboardShowsIdentifiersChannelsAndAlertsWithTheCsrfHeaderForHtmx() throws Exception {
    Student student = newStudent(newCollege());

    mvc.perform(get("/dashboard").with(as(student)))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(
                    allOf(
                        containsString(student.neoId()),
                        containsString("No channel is on"),
                        containsString("id=\"channels\""),
                        containsString("id=\"alerts\""),
                        containsString("hx-headers=\"{&quot;X-CSRF-TOKEN&quot;"),
                        containsString("/js/htmx-2.0.11.min.js"))));
  }

  @Test
  void theDashboardSendsAStudentWithoutANeoIdToOnboarding() throws Exception {
    mvc.perform(get("/dashboard").with(as(newStudentWithoutNeoId(newCollege()))))
        .andExpect(redirectedUrl("/onboarding"));
  }

  @Test
  void pauseAllSwitchesEveryChannelOffAndResumeBackOn() throws Exception {
    Student student = newStudent(newCollege());
    mvc.perform(post("/api/v1/me/channels/ntfy").with(as(student)).with(csrf()));

    mvc.perform(post("/settings/pause-all").with(as(student)).with(csrf()))
        .andExpect(redirectedUrl("/settings"));
    assertThat(subscribers.get(student.id()).channels())
        .allSatisfy(c -> assertThat(c.enabled()).isFalse());

    mvc.perform(post("/settings/resume-all").with(as(student)).with(csrf()));
    assertThat(subscribers.get(student.id()).channels())
        .allSatisfy(c -> assertThat(c.enabled()).isTrue());
  }

  @Test
  void settingsOffersDeletionWithConfirmation() throws Exception {
    mvc.perform(get("/settings").with(as(newStudent(newCollege()))))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(
                    allOf(
                        containsString("hx-delete=\"/api/v1/me\""),
                        containsString("hx-confirm="),
                        containsString("required"))));
  }

  @Test
  void aDeletedAccountsSessionIsSignedOut() throws Exception {
    Student student = newStudent(newCollege());
    subscribers.deleteAccount(student.id());

    mvc.perform(get("/dashboard").with(as(student))).andExpect(redirectedUrl("/"));
  }

  // ---- Request your college --------------------------------------------------------------------

  @Test
  void theRequestFormIsPrefilledWithTheRefusedDomainAndStoresTheRequest() throws Exception {
    MockHttpSession session = new MockHttpSession();
    session.setAttribute(SignInHandlers.REFUSED_DOMAIN, "faraway.example");
    String college = "Faraway Institute " + UUID.randomUUID();

    mvc.perform(get("/not-supported").session(session))
        .andExpect(
            content()
                .string(
                    allOf(
                        containsString("value=\"faraway.example\""),
                        containsString("Request your college"))));
    mvc.perform(
            post("/college-requests")
                .session(session)
                .with(csrf())
                .param("collegeName", college)
                .param("emailDomain", "@Faraway.Example")
                .param("contactEmail", ""))
        .andExpect(redirectedUrl("/not-supported?requested"));

    assertThat(jdbc.queryForMap("select * from college_request where college_name = ?", college))
        .containsEntry("email_domain", "faraway.example")
        .containsEntry("contact_email", null);
  }

  @Test
  void anInvalidRequestShowsItsErrors() throws Exception {
    mvc.perform(
            post("/college-requests")
                .with(csrf())
                .param("collegeName", "")
                .param("emailDomain", "not a domain")
                .param("contactEmail", "nope"))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(
                    allOf(
                        containsString("Enter your college&#39;s name."),
                        containsString("Enter a domain such as college.edu.in."),
                        containsString("Enter a valid email address"))));
  }

  @Test
  void requestsAreLimitedPerClient() throws Exception {
    String ip = "203.0.113." + (int) (Math.random() * 200);
    for (int i = 0; i < 5; i++) {
      mvc.perform(
              post("/college-requests")
                  .with(csrf())
                  .with(r -> {
                    r.setRemoteAddr(ip);
                    return r;
                  })
                  .param("collegeName", "Spam " + i)
                  .param("emailDomain", "spam.example"))
          .andExpect(redirectedUrl("/not-supported?requested"));
    }

    mvc.perform(
            post("/college-requests")
                .with(csrf())
                .with(r -> {
                  r.setRemoteAddr(ip);
                  return r;
                })
                .param("collegeName", "Spam 6")
                .param("emailDomain", "spam.example"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Too many requests")));
    assertThat(
            jdbc.queryForObject(
                "select count(*) from college_request where college_name = 'Spam 6'",
                Integer.class))
        .isZero();
  }

  @Test
  void theRequestFormNeedsTheCsrfToken() throws Exception {
    mvc.perform(
            post("/college-requests").param("collegeName", "X").param("emailDomain", "x.example"))
        .andExpect(status().isForbidden());
  }

  @Test
  void thePausedCollegePageSaysSo() throws Exception {
    mvc.perform(get("/not-supported").param("paused", ""))
        .andExpect(content().string(containsString("Sign-ups are paused")));
  }

  private static org.assertj.core.data.TemporalUnitOffset within() {
    return new org.assertj.core.data.TemporalUnitWithinOffset(
        1, java.time.temporal.ChronoUnit.MINUTES);
  }
}
