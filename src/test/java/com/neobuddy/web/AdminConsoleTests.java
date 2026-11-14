package com.neobuddy.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.neobuddy.ingestion.source.SourceInbox;
import com.neobuddy.ingestion.source.SourceInboxRepository;
import com.neobuddy.shared.college.College;
import com.neobuddy.shared.college.CollegeStatus;
import com.neobuddy.shared.observability.Metrics;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** The admin console and the admin API, signed in as an admin. */
class AdminConsoleTests extends WebDatabaseTest {

  @Autowired private SourceInboxRepository sources;
  @Autowired private MeterRegistry meters;

  private College college;
  private RequestPostProcessor admin;

  @BeforeEach
  void anAdmin() {
    college = newCollege();
    Student student = newStudent(college);
    jdbc.update("update app_user set role = 'ADMIN' where id = ?", student.id());
    admin = as(new Student(subscribers.get(student.id()), student.sub(), student.neoId()));
  }

  private static String uniqueSlug() {
    return "adm-" + UUID.randomUUID().toString().substring(0, 8);
  }

  // ---- Pages -----------------------------------------------------------------------------------

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/admin",
        "/admin/colleges",
        "/admin/colleges/new",
        "/admin/requests",
        "/admin/sources",
        "/admin/emails",
        "/admin/emails?status=FAILED",
        "/admin/deliveries"
      })
  void everyPageRendersForAnAdmin(String path) throws Exception {
    mvc.perform(get(path).with(admin))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith("text/html"))
        .andExpect(content().string(containsString("Dead deliveries")));
  }

  @Test
  void theHeaderLinksToTheConsoleForAdminsOnly() throws Exception {
    mvc.perform(get("/settings").with(admin))
        .andExpect(content().string(containsString("href=\"/admin\"")));
    Student plain = newStudent(college);
    mvc.perform(get("/settings").with(as(plain)))
        .andExpect(content().string(not(containsString("href=\"/admin\""))));
  }

  @Test
  void anAdminCreatesACollegeFromTheForm() throws Exception {
    String slug = uniqueSlug();

    mvc.perform(
            post("/admin/colleges")
                .with(admin)
                .with(csrf())
                .param("name", "Test College " + slug)
                .param("slug", slug)
                .param("emailDomains", slug + ".example\nstudents." + slug + ".example")
                .param(
                    "senderAllowlist", "placements@" + slug + ".example, @tpo." + slug + ".example")
                .param("neoIdPattern", "^[A-Z0-9]{8}$")
                .param("status", "PILOT")
                .param("shortlistSubject", "eligible list\n\nnext round")
                .param("knownRecruiters", "Local Startup"))
        .andExpect(redirectedUrlPattern("/admin/colleges/*"))
        .andExpect(flash().attribute("notice", containsString("created as PILOT")));

    College created = colleges.findBySlug(slug).orElseThrow();
    assertThat(created.getAllowedEmailDomains())
        .containsExactly(slug + ".example", "students." + slug + ".example");
    assertThat(created.getSenderAllowlist())
        .containsExactly("placements@" + slug + ".example", "@tpo." + slug + ".example");
    assertThat(created.getStatus()).isEqualTo(CollegeStatus.PILOT);
    assertThat(created.getClassifierOverrides().shortlistSubject())
        .containsExactly("eligible list", "next round");
    assertThat(created.getClassifierOverrides().knownRecruiters()).containsExactly("Local Startup");
  }

  @Test
  void anInvalidCollegeIsNotSavedAndEveryProblemIsListed() throws Exception {
    mvc.perform(
            post("/admin/colleges")
                .with(admin)
                .with(csrf())
                .param("name", "")
                .param("slug", "Not A Slug")
                .param("emailDomains", "not a domain")
                .param("senderAllowlist", "nobody")
                .param("neoIdPattern", "[unclosed")
                .param("status", "SOMETIMES"))
        .andExpect(redirectedUrl("/admin/colleges/new"))
        .andExpect(flash().attribute("errors", org.hamcrest.Matchers.hasSize(6)));
    assertThat(colleges.findBySlug("not a slug")).isEmpty();
  }

  @Test
  void aDomainOfAnotherCollegeIsRefused() throws Exception {
    mvc.perform(
            post("/admin/colleges")
                .with(admin)
                .with(csrf())
                .param("name", "Copycat")
                .param("slug", uniqueSlug())
                .param("emailDomains", college.getAllowedEmailDomains().get(0))
                .param("senderAllowlist", "@copy.example")
                .param("status", "PILOT"))
        .andExpect(
            flash()
                .attribute(
                    "errors",
                    org.hamcrest.Matchers.contains(
                        containsString("already belongs to " + college.getSlug()))));
  }

  @Test
  void anAdminEditsSlugStatusAndOverrides() throws Exception {
    String slug = uniqueSlug();

    mvc.perform(
            post("/admin/colleges/{id}", college.getId())
                .with(admin)
                .with(csrf())
                .param("name", "Renamed")
                .param("slug", slug)
                .param("emailDomains", String.join("\n", college.getAllowedEmailDomains()))
                .param("senderAllowlist", String.join("\n", college.getSenderAllowlist()))
                .param("neoIdPattern", college.getNeoIdPattern())
                .param("status", "ACTIVE")
                .param("offerSubject", "final list"))
        .andExpect(redirectedUrl("/admin/colleges/" + college.getId()))
        .andExpect(flash().attribute("notice", containsString(slug)));

    College updated = colleges.findById(college.getId()).orElseThrow();
    assertThat(updated.getSlug()).isEqualTo(slug);
    assertThat(updated.getName()).isEqualTo("Renamed");
    assertThat(updated.getStatus()).isEqualTo(CollegeStatus.ACTIVE);
    assertThat(updated.getClassifierOverrides().offerSubject()).containsExactly("final list");
  }

  @Test
  void aCollegeRequestPrefillsTheFormAndIsAnsweredByTheNewCollege() throws Exception {
    String slug = uniqueSlug();
    String domain = slug + ".example";
    UUID requestId = UUID.randomUUID();
    jdbc.update(
        "insert into college_request (id, college_name, email_domain) values (?, ?, ?)",
        requestId,
        "Requested College",
        domain);

    mvc.perform(get("/admin/requests").with(admin))
        .andExpect(content().string(containsString("/admin/colleges/new?request=" + requestId)));
    mvc.perform(get("/admin/colleges/new").param("request", requestId.toString()).with(admin))
        .andExpect(
            content()
                .string(
                    allOf(
                        containsString("value=\"Requested College\""),
                        containsString(">" + domain + "</textarea>"),
                        containsString(">@" + domain + "</textarea>"))));

    mvc.perform(
            post("/admin/colleges")
                .with(admin)
                .with(csrf())
                .param("name", "Requested College")
                .param("slug", slug)
                .param("emailDomains", domain)
                .param("senderAllowlist", "@" + domain)
                .param("status", "PILOT"))
        .andExpect(redirectedUrlPattern("/admin/colleges/*"));

    assertThat(
            jdbc.queryForObject(
                "select count(*) from college_request where id = ?", Integer.class, requestId))
        .isZero();
  }

  @Test
  void anInviteLinkIsShownOnceAfterCreatingIt() throws Exception {
    mvc.perform(
            post("/admin/sources/invites")
                .with(admin)
                .with(csrf())
                .param("collegeId", college.getId().toString()))
        .andExpect(redirectedUrl("/admin/sources"))
        .andExpect(flash().attribute("inviteLink", containsString("/sources/connect/")));
  }

  // ---- API -------------------------------------------------------------------------------------

  @Test
  void theApiCreatesAndUpdatesColleges() throws Exception {
    String slug = uniqueSlug();
    String body =
        """
        {"name":"Api College","slug":"%s","emailDomains":"%s.example",
         "senderAllowlist":"@%s.example","neoIdPattern":"","status":"PILOT"}
        """
            .formatted(slug, slug, slug);

    mvc.perform(
            post("/api/v1/admin/colleges")
                .with(admin)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.slug").value(slug))
        .andExpect(jsonPath("$.neoIdPattern").value(College.DEFAULT_NEO_ID_PATTERN));
    UUID id = colleges.findBySlug(slug).orElseThrow().getId();

    mvc.perform(
            put("/api/v1/admin/colleges/{id}", id)
                .with(admin)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.replace("\"PILOT\"", "\"PAUSED\"")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PAUSED"));

    mvc.perform(
            post("/api/v1/admin/colleges")
                .with(admin)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.type").value("/problems/invalid-college"))
        .andExpect(jsonPath("$.errors[0]").value(containsString("Slug")));
    mvc.perform(get("/api/v1/admin/colleges").with(admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.slug == '" + slug + "')].status").value("PAUSED"));
  }

  @Test
  void theApiListsDeadDeliveriesOnly() throws Exception {
    mvc.perform(get("/api/v1/admin/deliveries").param("status", "DEAD").with(admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isArray());
    mvc.perform(get("/api/v1/admin/deliveries").param("status", "SENT").with(admin))
        .andExpect(status().isBadRequest());
  }

  @Test
  void reprocessingAnUnknownEmailIsA404Problem() throws Exception {
    mvc.perform(
            post("/api/v1/admin/emails/{id}/reprocess", "<nothing@mail.example>")
                .with(admin)
                .with(csrf()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.type").value("/problems/email-not-reprocessable"));
  }

  @Test
  void theConnectEndpointSendsTheAdminThroughASingleUseInvite() throws Exception {
    mvc.perform(
            get("/api/v1/admin/sources/connect").param("college", college.getSlug()).with(admin))
        .andExpect(status().isFound())
        .andExpect(header().string("Location", containsString("/sources/connect/")))
        .andExpect(header().string("Location", org.hamcrest.Matchers.endsWith("/start")));
    assertThat(
            jdbc.queryForObject(
                "select count(*) from source_invite where college_id = ?",
                Integer.class,
                college.getId()))
        .isEqualTo(1);
  }

  // ---- Health and metrics ----------------------------------------------------------------------

  @Test
  void aSourceThatLostItsAuthorisationDegradesTheOpsGroupButNotThePublicHealth() throws Exception {
    SourceInbox source =
        new SourceInbox(college.getId(), "inbox-" + UUID.randomUUID() + "@mail.example", "t");
    source.markAuthExpired();
    sources.save(source);
    String name = college.getSlug() + "-" + source.getId().toString().substring(0, 8);

    mvc.perform(get("/actuator/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"))
        .andExpect(jsonPath("$.components").doesNotExist());
    mvc.perform(get("/actuator/health/ops").with(admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("DEGRADED"))
        .andExpect(
            jsonPath("$.components.sources.components['" + name + "'].status").value("DEGRADED"))
        .andExpect(
            jsonPath("$.components.sources.components['" + name + "'].details.state")
                .value("AUTH_EXPIRED"))
        .andExpect(jsonPath("$.components.channels.components.ntfy.status").exists())
        .andExpect(jsonPath("$.components.channels.components.email.status").exists())
        .andExpect(content().string(not(containsString(source.getEmail()))));

    sources.delete(sources.findById(source.getId()).orElseThrow());
  }

  @Test
  void metricsAreExportedInPrometheusFormatUnderTheirDocumentedNames() throws Exception {
    String slug = college.getSlug();
    meters.counter(Metrics.EMAILS_INGESTED, Metrics.COLLEGE, slug).increment();
    meters.counter(Metrics.EXTRACTION_FAILURES, Metrics.COLLEGE, slug, "type", "xlsx").increment();
    meters.counter(Metrics.MATCHES, Metrics.COLLEGE, slug, "event", "SHORTLIST").increment();
    meters.counter(Metrics.DELIVERIES_DEAD, Metrics.COLLEGE, slug, "channel", "ntfy").increment();
    io.micrometer.core.instrument.Timer.builder(Metrics.DELIVERY_LATENCY)
        .tag(Metrics.COLLEGE, slug)
        .tag("channel", "ntfy")
        .serviceLevelObjectives(java.time.Duration.ofMinutes(5))
        .register(meters)
        .record(java.time.Duration.ofSeconds(90));

    String college = "college=\"" + slug + "\"";
    String body =
        mvc.perform(get("/actuator/prometheus").with(admin))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(body)
        .contains(
            "emails_ingested_total{",
            "extraction_failures_total{",
            "matches_total{",
            "deliveries_dead_total{",
            "delivery_latency_seconds_bucket{",
            college);
  }
}
