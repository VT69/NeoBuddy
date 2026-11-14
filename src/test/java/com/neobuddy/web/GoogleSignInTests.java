package com.neobuddy.web;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.neobuddy.shared.college.College;
import com.neobuddy.shared.college.CollegeStatus;
import com.neobuddy.subscriber.TestColleges;
import com.neobuddy.web.auth.SignInHandlers;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.json.JsonMapper;

/**
 * The real Google sign-in flow, with Google replaced by WireMock: Spring Security redirects to the
 * authorization endpoint, the "browser" comes back with a code, Spring exchanges it for an ID token
 * signed with a test key, checks it against the published key, fetches the user info, and
 * NeoBuddy's domain check decides where the student lands.
 */
class GoogleSignInTests extends WebDatabaseTest {

  private static final WireMockServer GOOGLE = new WireMockServer(wireMockConfig().dynamicPort());
  private static final RSAKey KEY = newKey();
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String CLIENT_ID = "test-client-id.apps.googleusercontent.com";

  static {
    GOOGLE.start();
  }

  private static RSAKey newKey() {
    try {
      return new RSAKeyGenerator(2048).keyID("test-key").generate();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @DynamicPropertySource
  static void googleIsWireMock(DynamicPropertyRegistry registry) {
    String base = "http://localhost:" + GOOGLE.port();
    registry.add(
        "spring.security.oauth2.client.provider.google.authorization-uri",
        () -> base + "/o/oauth2/v2/auth");
    registry.add("spring.security.oauth2.client.provider.google.token-uri", () -> base + "/token");
    registry.add(
        "spring.security.oauth2.client.provider.google.jwk-set-uri",
        () -> base + "/oauth2/v3/certs");
    registry.add(
        "spring.security.oauth2.client.provider.google.user-info-uri",
        () -> base + "/oauth2/v3/userinfo");
  }

  @BeforeEach
  void publishTheKey() {
    GOOGLE.resetAll();
    GOOGLE.stubFor(
        get(urlPathEqualTo("/oauth2/v3/certs"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(new JWKSet(KEY.toPublicJWK()).toString())));
  }

  /** Signs in as a Google user and returns where NeoBuddy sent the browser, with the session. */
  private record Landing(String location, MockHttpSession session) {}

  private Landing signIn(Map<String, Object> claims) throws Exception {
    MvcResult start =
        mvc.perform(MockMvcRequestBuilders.get("/oauth2/authorization/google"))
            .andExpect(status().is3xxRedirection())
            .andReturn();
    MockHttpSession session = (MockHttpSession) start.getRequest().getSession();
    UriComponents authorization =
        UriComponentsBuilder.fromUriString(start.getResponse().getRedirectedUrl()).build();
    assertThat(authorization.getPath()).isEqualTo("/o/oauth2/v2/auth");
    assertThat(authorization.getQueryParams().getFirst("scope"))
        .isEqualTo("openid%20email%20profile");
    String state = decode(authorization.getQueryParams().getFirst("state"));
    String nonce = decode(authorization.getQueryParams().getFirst("nonce"));

    Map<String, Object> idClaims = new HashMap<>(claims);
    idClaims.put("nonce", nonce);
    GOOGLE.stubFor(
        post(urlPathEqualTo("/token"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        JSON.writeValueAsString(
                            Map.of(
                                "access_token",
                                "access-" + UUID.randomUUID(),
                                "token_type",
                                "Bearer",
                                "expires_in",
                                3600,
                                "scope",
                                "openid email profile",
                                "id_token",
                                idToken(idClaims))))));
    GOOGLE.stubFor(
        get(urlPathEqualTo("/oauth2/v3/userinfo"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(JSON.writeValueAsString(claims))));

    MvcResult callback =
        mvc.perform(
                MockMvcRequestBuilders.get("/login/oauth2/code/google")
                    .param("code", "code-" + UUID.randomUUID())
                    .param("state", state)
                    .session(session))
            .andExpect(status().is3xxRedirection())
            .andReturn();
    return new Landing(
        callback.getResponse().getRedirectedUrl(),
        (MockHttpSession) callback.getRequest().getSession());
  }

  private static String idToken(Map<String, Object> claims) throws Exception {
    JWTClaimsSet.Builder builder =
        new JWTClaimsSet.Builder()
            .issuer("https://accounts.google.com")
            .audience(List.of(CLIENT_ID))
            .issueTime(Date.from(Instant.now()))
            .expirationTime(Date.from(Instant.now().plusSeconds(3600)));
    claims.forEach(builder::claim);
    SignedJWT jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256)
                .keyID(KEY.getKeyID())
                .type(JOSEObjectType.JWT)
                .build(),
            builder.build());
    jwt.sign(new RSASSASigner(KEY));
    return jwt.serialize();
  }

  private static Map<String, Object> google(String email, String hd) {
    Map<String, Object> claims = new HashMap<>();
    claims.put("sub", "google-" + UUID.randomUUID());
    claims.put("email", email);
    claims.put("email_verified", true);
    claims.put("name", "Tara Target");
    if (hd != null) {
      claims.put("hd", hd);
    }
    return claims;
  }

  private static String decode(String value) {
    return java.net.URLDecoder.decode(value, java.nio.charset.StandardCharsets.UTF_8);
  }

  @Test
  void aNewStudentOfASupportedCollegeIsAskedForConsentAndNothingIsStoredYet() throws Exception {
    College college = newCollege();
    String email = TestColleges.studentEmail(college);

    Landing landing = signIn(google(email, TestColleges.domainOf(college)));

    assertThat(landing.location()).isEqualTo("/consent");
    assertThat(subscribers.findByEmail(email)).isEmpty();
    mvc.perform(MockMvcRequestBuilders.get("/consent").session(landing.session()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString(email)));
  }

  @Test
  void aStudentWithAnAccountGoesToTheDashboard() throws Exception {
    College college = newCollege();
    Student student = newStudent(college);
    Map<String, Object> claims = google(student.email(), null);
    claims.put("sub", student.sub());

    Landing landing = signIn(claims);

    assertThat(landing.location()).isEqualTo("/dashboard");
    mvc.perform(MockMvcRequestBuilders.get("/dashboard").session(landing.session()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString(student.neoId())));
  }

  @Test
  void aStudentWithoutANeoIdGoesToOnboarding() throws Exception {
    Student student = newStudentWithoutNeoId(newCollege());
    Map<String, Object> claims = google(student.email(), null);
    claims.put("sub", student.sub());

    assertThat(signIn(claims).location()).isEqualTo("/onboarding");
  }

  @Test
  void aGmailAccountIsRefusedPolitelyAndMayRequestItsCollege() throws Exception {
    String email = "someone" + UUID.randomUUID().toString().substring(0, 6) + "@gmail.com";

    Landing landing = signIn(google(email, null));

    assertThat(landing.location()).isEqualTo("/not-supported");
    assertThat(landing.session().getAttribute(SignInHandlers.REFUSED_DOMAIN))
        .isEqualTo("gmail.com");
    assertThat(subscribers.findByEmail(email)).isEmpty();
    mvc.perform(MockMvcRequestBuilders.get("/not-supported").session(landing.session()))
        .andExpect(content().string(containsString("Request your college")));
    // Still signed out: student pages send them away.
    mvc.perform(MockMvcRequestBuilders.get("/dashboard").session(landing.session()))
        .andExpect(redirectedUrl("/"));
  }

  @Test
  void aWorkspaceAccountOfAnotherOrganisationIsRefused() throws Exception {
    College college = newCollege();

    Landing landing = signIn(google(TestColleges.studentEmail(college), "other-org.example"));

    assertThat(landing.location()).isEqualTo("/sign-in-problem?reason=hosted_domain_mismatch");
  }

  @Test
  void aPausedCollegeRefusesSignIns() throws Exception {
    College paused = colleges.save(TestColleges.college(CollegeStatus.PAUSED));

    assertThat(signIn(google(TestColleges.studentEmail(paused), null)).location())
        .isEqualTo("/not-supported?paused");
  }

  @Test
  void anUnverifiedEmailIsRefused() throws Exception {
    Map<String, Object> claims = google(TestColleges.studentEmail(newCollege()), null);
    claims.put("email_verified", false);

    assertThat(signIn(claims).location()).isEqualTo("/sign-in-problem?reason=email_not_verified");
  }

  @Test
  void anActiveCollegeIsSupportedToo() throws Exception {
    College active = colleges.save(TestColleges.college(CollegeStatus.ACTIVE));

    assertThat(signIn(google(TestColleges.studentEmail(active), null)).location())
        .isEqualTo("/consent");
  }
}
