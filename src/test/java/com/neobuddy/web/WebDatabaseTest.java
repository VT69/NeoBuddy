package com.neobuddy.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;

import com.neobuddy.shared.college.College;
import com.neobuddy.shared.college.CollegeRepository;
import com.neobuddy.subscriber.ChannelAddress;
import com.neobuddy.subscriber.EmailOtpSender;
import com.neobuddy.subscriber.IdentifierType;
import com.neobuddy.subscriber.OidcClaims;
import com.neobuddy.subscriber.SubscriberService;
import com.neobuddy.subscriber.SubscriberView;
import com.neobuddy.subscriber.TestColleges;
import com.neobuddy.subscriber.VerificationNotSentException;
import com.neobuddy.testsupport.DatabaseTest;
import com.neobuddy.web.auth.NeoBuddyUser;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Base for web tests: the real application and database behind MockMvc with the real security
 * filters. Emailed one-time codes are captured instead of sent. Each test makes its own college and
 * students, so rate limits and data never leak between tests.
 */
@DatabaseTest
@AutoConfigureMockMvc
@Import(WebDatabaseTest.CapturedCodes.class)
public abstract class WebDatabaseTest {

  /** Stands in for the email channel and remembers the last code per address. */
  @Primary
  public static class CapturedCodes implements EmailOtpSender {

    private final Map<String, String> lastCode = Collections.synchronizedMap(new HashMap<>());
    private final List<String> failingAddresses = Collections.synchronizedList(new ArrayList<>());

    @Override
    public void send(ChannelAddress to, String code, Duration validFor) {
      if (failingAddresses.contains(to.value())) {
        throw new VerificationNotSentException("SES MessageRejectedException");
      }
      lastCode.put(to.value(), code);
    }

    public String codeFor(String address) {
      return lastCode.get(address);
    }

    public void failFor(String address) {
      failingAddresses.add(address);
    }
  }

  @Autowired protected MockMvc mvc;
  @Autowired protected SubscriberService subscribers;
  @Autowired protected CollegeRepository colleges;
  @Autowired protected JdbcTemplate jdbc;
  @Autowired protected CapturedCodes codes;

  protected College newCollege() {
    return colleges.save(TestColleges.pilot());
  }

  /** A registered student with a NeoID. */
  protected Student newStudent(College college) {
    Student student = newStudentWithoutNeoId(college);
    String neoId = TestColleges.neoId();
    subscribers.addIdentifier(student.id(), IdentifierType.NEO_ID, neoId);
    return new Student(subscribers.get(student.id()), student.sub(), neoId);
  }

  protected Student newStudentWithoutNeoId(College college) {
    String sub = "sub-" + UUID.randomUUID();
    SubscriberView view =
        subscribers.registerFromOidc(
            new OidcClaims(sub, TestColleges.studentEmail(college), true, "Tara Target", null));
    return new Student(view, sub, null);
  }

  protected record Student(SubscriberView view, String sub, String neoId) {

    public UUID id() {
      return view.id();
    }

    public String email() {
      return view.email();
    }
  }

  protected static OidcIdToken idToken(String sub, String email, String hd) {
    Map<String, Object> claims = new HashMap<>();
    claims.put("sub", sub);
    claims.put("email", email);
    claims.put("email_verified", true);
    claims.put("name", "Tara Target");
    if (hd != null) {
      claims.put("hd", hd);
    }
    return new OidcIdToken("id-token", Instant.now(), Instant.now().plusSeconds(3600), claims);
  }

  /** Signed in as the student. */
  protected static RequestPostProcessor as(Student student) {
    return oidcLogin()
        .oidcUser(
            NeoBuddyUser.account(
                student.view(), idToken(student.sub(), student.email(), null), null));
  }

  /** Signed in with Google as a student of the college who has not consented yet. */
  protected static RequestPostProcessor pending(String email) {
    return oidcLogin()
        .oidcUser(
            NeoBuddyUser.pendingConsent(idToken("sub-" + UUID.randomUUID(), email, null), null));
  }
}
