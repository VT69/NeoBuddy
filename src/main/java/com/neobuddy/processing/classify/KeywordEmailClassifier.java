package com.neobuddy.processing.classify;

import com.neobuddy.ingestion.RawEmail;
import com.neobuddy.processing.Classification;
import com.neobuddy.processing.EventType;
import com.neobuddy.processing.classify.ClassifierProperties.Keywords;
import com.neobuddy.processing.classify.ClassifierProperties.Rules;
import com.neobuddy.shared.college.ClassifierOverrides;
import com.neobuddy.shared.college.College;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Component;

/**
 * Rule-based classifier driven by keyword lists from configuration.
 *
 * <p>The subject is stronger evidence than the body, and OFFER outranks SHORTLIST when both show up
 * in the same place. So the order of checks is: offer in subject, shortlist in subject, offer in
 * body, shortlist in body, otherwise OTHER. Checking the subject first matters because shortlist
 * emails routinely mention the offer on the table ("CTC offered: 12 LPA") in their body.
 */
@Component
public class KeywordEmailClassifier implements EmailClassifier {

  /** Enough of the body to see what the email is about, without scanning a huge forward. */
  private static final int BODY_SCAN_LIMIT = 20_000;

  private static final String DEFAULT_RULES = "";

  private final ClassifierProperties properties;
  private final CompanyNameResolver companies;
  private static final int MAX_CACHED = 256;

  private final Map<String, CompiledRules> compiled = new ConcurrentHashMap<>();
  private final Map<Rules, CompiledRules> byRules = new ConcurrentHashMap<>();

  public KeywordEmailClassifier(ClassifierProperties properties, CompanyNameResolver companies) {
    this.properties = properties;
    this.companies = companies;
  }

  @Override
  public Classification classify(String collegeSlug, RawEmail email) {
    CompiledRules rules = rulesFor(collegeSlug);
    String subject = email.subject() == null ? "" : email.subject();
    return new Classification(type(rules, subject, email), companies.resolve(collegeSlug, subject));
  }

  /**
   * Like {@link #classify(String, RawEmail)}, with the keyword lists the college set in the admin
   * console replacing the configured ones (each list separately) and its recruiters added.
   */
  @Override
  public Classification classify(College college, RawEmail email) {
    ClassifierOverrides overrides = college.getClassifierOverrides();
    if (overrides.isEmpty()) {
      return classify(college.getSlug(), email);
    }
    Rules configured = properties.rulesFor(college.getSlug());
    Rules effective =
        new Rules(
            configured
                .offer()
                .overriddenBy(new Keywords(overrides.offerSubject(), overrides.offerBody())),
            configured
                .shortlist()
                .overriddenBy(
                    new Keywords(overrides.shortlistSubject(), overrides.shortlistBody())));
    if (byRules.size() > MAX_CACHED) {
      byRules.clear();
    }
    CompiledRules rules = byRules.computeIfAbsent(effective, CompiledRules::new);
    String subject = email.subject() == null ? "" : email.subject();
    return new Classification(
        type(rules, subject, email),
        companies.resolve(college.getSlug(), overrides.knownRecruiters(), subject));
  }

  private static EventType type(CompiledRules rules, String subject, RawEmail email) {
    if (rules.offerSubject.matches(subject)) {
      return EventType.OFFER;
    }
    if (rules.shortlistSubject.matches(subject)) {
      return EventType.SHORTLIST;
    }
    String body = bodyText(email);
    if (rules.offerBody.matches(body)) {
      return EventType.OFFER;
    }
    if (rules.shortlistBody.matches(body)) {
      return EventType.SHORTLIST;
    }
    return EventType.OTHER;
  }

  private static String bodyText(RawEmail email) {
    String text = email.textBody();
    if ((text == null || text.isBlank()) && email.htmlBody() != null) {
      text = Jsoup.parse(email.htmlBody()).text();
    }
    if (text == null) {
      return "";
    }
    return text.length() <= BODY_SCAN_LIMIT ? text : text.substring(0, BODY_SCAN_LIMIT);
  }

  private CompiledRules rulesFor(String collegeSlug) {
    boolean overridden = collegeSlug != null && properties.colleges().containsKey(collegeSlug);
    String key = overridden ? collegeSlug : DEFAULT_RULES;
    return compiled.computeIfAbsent(
        key, k -> new CompiledRules(properties.rulesFor(overridden ? collegeSlug : null)));
  }

  private static final class CompiledRules {

    private final KeywordMatcher offerSubject;
    private final KeywordMatcher offerBody;
    private final KeywordMatcher shortlistSubject;
    private final KeywordMatcher shortlistBody;

    private CompiledRules(Rules rules) {
      this.offerSubject = new KeywordMatcher(rules.offer().subject());
      this.offerBody = new KeywordMatcher(rules.offer().body());
      this.shortlistSubject = new KeywordMatcher(rules.shortlist().subject());
      this.shortlistBody = new KeywordMatcher(rules.shortlist().body());
    }
  }
}
