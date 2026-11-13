package com.neobuddy.subscriber;

import com.neobuddy.shared.college.College;
import com.neobuddy.shared.college.CollegeStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Made-up colleges for tests. Each gets a unique slug and email domain, so tests never collide. */
public final class TestColleges {

  private TestColleges() {}

  public static College college(CollegeStatus status) {
    String slug = "college-" + UUID.randomUUID().toString().substring(0, 8);
    return new College(
        UUID.randomUUID(),
        slug,
        "College " + slug,
        List.of(domainOf(slug)),
        List.of("placements@" + domainOf(slug)),
        College.DEFAULT_NEO_ID_PATTERN,
        status,
        Instant.parse("2026-01-01T00:00:00Z"));
  }

  public static College pilot() {
    return college(CollegeStatus.PILOT);
  }

  public static String domainOf(College college) {
    return college.getAllowedEmailDomains().get(0);
  }

  /** A unique student email on the college's domain. */
  public static String studentEmail(College college) {
    return "student-" + UUID.randomUUID().toString().substring(0, 8) + "@" + domainOf(college);
  }

  /** A random value that fits the default NeoID pattern (8 upper-case letters and digits). */
  public static String neoId() {
    return UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
  }

  private static String domainOf(String slug) {
    return slug + ".example";
  }
}
