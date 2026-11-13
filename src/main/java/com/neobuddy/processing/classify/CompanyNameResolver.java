package com.neobuddy.processing.classify;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Guesses the recruiting company from the subject line. In order:
 *
 * <ol>
 *   <li>a name from the configured known-recruiter list found anywhere in the subject;
 *   <li>a subject pattern: "Shortlisted candidates for &lt;Company&gt;", "Offers from
 *       &lt;Company&gt;", "&lt;Company&gt; - Shortlist", "&lt;Company&gt; shortlist";
 *   <li>the cleaned subject itself (reply and forward prefixes and bracketed tags removed).
 * </ol>
 *
 * <p>This is a best guess for the alert text, never used for matching.
 */
@Component
public class CompanyNameResolver {

  private static final int MAX_LENGTH = 80;
  private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

  private static final String KEYWORD =
      "(?:short[\\s\\-]?list(?:ed|s)?|offers?|offer\\s+letters?|results?|final\\s+selects?"
          + "|final\\s+selections?|selections?|selected|interview\\s+schedule)";

  /** "Shortlisted candidates for X", "Shortlist of students for X", "Offers from X". */
  private static final Pattern KEYWORD_THEN_COMPANY =
      Pattern.compile(
          "\\b"
              + KEYWORD
              + "\\b(?:\\s+(?:of|list|candidates|students|names|the))*"
              + "\\s+(?:for|from|by|at|of)\\s+(?<company>.+)$",
          FLAGS);

  /** "X - Shortlist", "X | SDE Intern | Shortlist for round 2", "X: Offer letters". */
  private static final Pattern COMPANY_SEPARATOR_KEYWORD =
      Pattern.compile(
          "^(?<company>[^\\-\\u2013\\u2014|:]+?)\\s*[\\-\\u2013\\u2014|:]\\s*.*\\b"
              + KEYWORD
              + "\\b",
          FLAGS);

  /** "X shortlist", "X final selects". */
  private static final Pattern COMPANY_THEN_KEYWORD =
      Pattern.compile("^(?<company>.+?)\\s+" + KEYWORD + "\\b", FLAGS);

  private static final List<Pattern> PATTERNS =
      List.of(KEYWORD_THEN_COMPANY, COMPANY_SEPARATOR_KEYWORD, COMPANY_THEN_KEYWORD);

  private static final Pattern REPLY_PREFIX =
      Pattern.compile("^(?:\\s*(?:re|fwd?|fw)\\s*:\\s*|\\s*\\[[^\\]]*\\]\\s*)+", FLAGS);
  private static final Pattern WHITESPACE = Pattern.compile("\\s+");

  /** Where a company name ends: a separator or an opening bracket. */
  private static final Pattern CUT = Pattern.compile("\\s[\\-\\u2013\\u2014]\\s|[|:(\\[,;]");

  private static final Pattern LEADING_NOISE =
      Pattern.compile(
          "^(?:(?:congratulations|congrats|announcement|notice|reminder|update|important|urgent"
              + "|the|placement\\s+cell)\\b[\\s!:,.\\-]*)+",
          FLAGS);
  private static final Pattern TRAILING_NOISE =
      Pattern.compile(
          "(?:[\\s!.,\\-]+|\\b(?:campus|recruitment|placements?|drive|hiring|interviews?"
              + "|internships?|round(?:\\s*\\d+)?|test|assessment|process|final|batch|\\d{4}))+$",
          FLAGS);
  private static final Pattern HAS_LETTER = Pattern.compile("\\p{L}");

  private final ClassifierProperties properties;
  private final Map<String, KeywordMatcher> recruiters = new ConcurrentHashMap<>();

  public CompanyNameResolver(ClassifierProperties properties) {
    this.properties = properties;
  }

  /**
   * @param collegeSlug the college, for its own recruiter list; may be null
   * @param subject the raw subject line; may be null
   * @return the company, or empty only if the subject is blank
   */
  public Optional<String> resolve(String collegeSlug, String subject) {
    String cleaned = cleanSubject(subject);
    if (cleaned.isEmpty()) {
      return Optional.empty();
    }
    Optional<String> known = recruitersFor(collegeSlug).firstMatch(cleaned);
    if (known.isPresent()) {
      return known;
    }
    for (Pattern pattern : PATTERNS) {
      Matcher matcher = pattern.matcher(cleaned);
      if (matcher.find()) {
        String company = tidy(matcher.group("company"));
        if (HAS_LETTER.matcher(company).find()) {
          return Optional.of(company);
        }
      }
    }
    return Optional.of(truncate(cleaned));
  }

  static String cleanSubject(String subject) {
    if (subject == null) {
      return "";
    }
    String collapsed = WHITESPACE.matcher(subject).replaceAll(" ").strip();
    return REPLY_PREFIX.matcher(collapsed).replaceFirst("").strip();
  }

  private static String tidy(String candidate) {
    String company = candidate.strip();
    company = LEADING_NOISE.matcher(company).replaceFirst("");
    Matcher cut = CUT.matcher(company);
    if (cut.find()) {
      company = company.substring(0, cut.start());
    }
    company = TRAILING_NOISE.matcher(company).replaceFirst("");
    return truncate(company.strip());
  }

  private static String truncate(String value) {
    return value.length() <= MAX_LENGTH ? value : value.substring(0, MAX_LENGTH).strip();
  }

  private KeywordMatcher recruitersFor(String collegeSlug) {
    String key =
        collegeSlug != null && properties.colleges().containsKey(collegeSlug) ? collegeSlug : "";
    return recruiters.computeIfAbsent(
        key, k -> new KeywordMatcher(properties.recruitersFor(k.isEmpty() ? null : k)));
  }
}
