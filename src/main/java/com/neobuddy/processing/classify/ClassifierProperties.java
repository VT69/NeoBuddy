package com.neobuddy.processing.classify;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Classification rules ({@code neobuddy.classifier.*}).
 *
 * <pre>
 * neobuddy:
 *   classifier:
 *     rules:
 *       offer:
 *         subject: [offer, offers]
 *         body: [offer letter]
 *       shortlist:
 *         subject: [shortlist, shortlisted]
 *         body: [shortlisted]
 *     known-recruiters: [Example Corp]
 *     colleges:
 *       my-college:                 # college slug
 *         rules:
 *           shortlist:
 *             subject: [eligible list]   # replaces only this one list for this college
 *         known-recruiters: [Local Startup]   # added to the global list
 * </pre>
 *
 * @param rules default keyword rules
 * @param knownRecruiters company names recognised anywhere in a subject
 * @param colleges per-college overrides, keyed by college slug
 */
@ConfigurationProperties(prefix = "neobuddy.classifier")
public record ClassifierProperties(
    Rules rules, List<String> knownRecruiters, Map<String, CollegeOverride> colleges) {

  public ClassifierProperties {
    rules = rules == null ? Rules.EMPTY : rules;
    knownRecruiters = knownRecruiters == null ? List.of() : List.copyOf(knownRecruiters);
    colleges = colleges == null ? Map.of() : Map.copyOf(colleges);
  }

  /** The rules for one college: each keyword list it overrides, the defaults for the rest. */
  public Rules rulesFor(String collegeSlug) {
    CollegeOverride override = collegeSlug == null ? null : colleges.get(collegeSlug);
    if (override == null || override.rules() == null) {
      return rules;
    }
    Rules theirs = override.rules();
    return new Rules(
        rules.offer().overriddenBy(theirs.offer()),
        rules.shortlist().overriddenBy(theirs.shortlist()));
  }

  /** The global recruiter list plus the college's own. */
  public List<String> recruitersFor(String collegeSlug) {
    CollegeOverride override = collegeSlug == null ? null : colleges.get(collegeSlug);
    if (override == null || override.knownRecruiters().isEmpty()) {
      return knownRecruiters;
    }
    List<String> all = new ArrayList<>(knownRecruiters);
    all.addAll(override.knownRecruiters());
    return List.copyOf(all);
  }

  /**
   * @param offer keywords that mark an offer or final-selection email
   * @param shortlist keywords that mark a shortlist email
   */
  public record Rules(Keywords offer, Keywords shortlist) {

    static final Rules EMPTY = new Rules(null, null);

    public Rules {
      offer = offer == null ? Keywords.NONE : offer;
      shortlist = shortlist == null ? Keywords.NONE : shortlist;
    }
  }

  /**
   * Words or phrases, matched whole and case-insensitively.
   *
   * @param subject looked for in the subject line
   * @param body looked for in the body
   */
  public record Keywords(List<String> subject, List<String> body) {

    static final Keywords NONE = new Keywords(null, null);

    public Keywords {
      subject = subject == null ? List.of() : List.copyOf(subject);
      body = body == null ? List.of() : List.copyOf(body);
    }

    Keywords overriddenBy(Keywords other) {
      return new Keywords(
          other.subject().isEmpty() ? subject : other.subject(),
          other.body().isEmpty() ? body : other.body());
    }
  }

  /**
   * @param rules keyword lists that replace the defaults for this college; absent lists inherit
   * @param knownRecruiters recruiters added to the global list for this college
   */
  public record CollegeOverride(Rules rules, List<String> knownRecruiters) {

    public CollegeOverride {
      knownRecruiters = knownRecruiters == null ? List.of() : List.copyOf(knownRecruiters);
    }
  }
}
