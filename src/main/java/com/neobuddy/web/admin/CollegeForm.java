package com.neobuddy.web.admin;

import com.neobuddy.shared.college.ClassifierOverrides;
import com.neobuddy.shared.college.College;
import java.util.Arrays;
import java.util.List;

/**
 * The college form of the admin console and the body of the admin API's college endpoints. Lists
 * are text with one entry per line (commas also separate the domain and sender lists).
 *
 * @param status PILOT, ACTIVE or PAUSED
 */
public record CollegeForm(
    String name,
    String slug,
    String emailDomains,
    String senderAllowlist,
    String neoIdPattern,
    String status,
    String offerSubject,
    String offerBody,
    String shortlistSubject,
    String shortlistBody,
    String knownRecruiters) {

  /** The form for an existing college, as it is stored. */
  public static CollegeForm of(College college) {
    ClassifierOverrides overrides = college.getClassifierOverrides();
    return new CollegeForm(
        college.getName(),
        college.getSlug(),
        String.join("\n", college.getAllowedEmailDomains()),
        String.join("\n", college.getSenderAllowlist()),
        college.getNeoIdPattern(),
        college.getStatus().name(),
        String.join("\n", overrides.offerSubject()),
        String.join("\n", overrides.offerBody()),
        String.join("\n", overrides.shortlistSubject()),
        String.join("\n", overrides.shortlistBody()),
        String.join("\n", overrides.knownRecruiters()));
  }

  /** An empty form, optionally prefilled from a college request. */
  public static CollegeForm blank(String name, String domain) {
    return new CollegeForm(
        name == null ? "" : name,
        "",
        domain == null ? "" : domain,
        domain == null ? "" : "@" + domain,
        College.DEFAULT_NEO_ID_PATTERN,
        "PILOT",
        "",
        "",
        "",
        "",
        "");
  }

  /** One entry per line; blank lines dropped. */
  static List<String> lines(String text) {
    return text == null
        ? List.of()
        : Arrays.stream(text.split("\\R")).map(String::strip).filter(s -> !s.isEmpty()).toList();
  }

  /** One entry per line or per comma; blank entries dropped. */
  static List<String> items(String text) {
    return text == null
        ? List.of()
        : Arrays.stream(text.split("[,\\r\\n]+"))
            .map(String::strip)
            .filter(s -> !s.isEmpty())
            .toList();
  }
}
