package com.neobuddy.web.admin;

import com.neobuddy.shared.college.ClassifierOverrides;
import com.neobuddy.shared.college.College;
import com.neobuddy.shared.college.CollegeRepository;
import com.neobuddy.shared.college.CollegeStatus;
import com.neobuddy.shared.observability.CollegeTags;
import com.neobuddy.web.pages.CollegeRequestStore;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creating and editing colleges. Everything is validated before anything is saved; error messages
 * name the field, never the value.
 */
@Service
public class AdminColleges {

  static final Pattern SLUG = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,38}[a-z0-9])?");
  static final Pattern DOMAIN =
      Pattern.compile("(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}");
  private static final Pattern LOCAL_PART = Pattern.compile("[a-z0-9._%+'-]{1,64}");
  private static final int MAX_KEYWORDS = 100;
  private static final int MAX_KEYWORD_LENGTH = 100;

  private static final Logger log = LoggerFactory.getLogger(AdminColleges.class);

  private final CollegeRepository colleges;
  private final CollegeRequestStore requests;
  private final CollegeTags collegeTags;
  private final Clock clock;

  AdminColleges(
      CollegeRepository colleges,
      CollegeRequestStore requests,
      CollegeTags collegeTags,
      Clock clock) {
    this.colleges = colleges;
    this.requests = requests;
    this.collegeTags = collegeTags;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public List<College> all() {
    return colleges.findAll().stream().sorted(Comparator.comparing(College::getSlug)).toList();
  }

  @Transactional(readOnly = true)
  public Optional<College> find(UUID id) {
    return colleges.findById(id);
  }

  /**
   * Creates a college. College requests for any of its domains are answered, so they are removed.
   *
   * @throws AdminFormException if anything is invalid
   */
  @Transactional
  public College create(CollegeForm form) {
    Valid valid = validate(form, null);
    College college =
        new College(
            UUID.randomUUID(),
            valid.slug,
            valid.name,
            valid.domains,
            valid.senders,
            valid.pattern,
            valid.status,
            clock.instant());
    college.updateClassifierOverrides(valid.overrides);
    College saved = colleges.save(college);
    int answered = requests.deleteForDomains(valid.domains);
    log.info(
        "College {} created by an admin; {} college requests answered", saved.getSlug(), answered);
    return saved;
  }

  /**
   * @throws AdminFormException if anything is invalid
   * @throws java.util.NoSuchElementException if there is no such college
   */
  @Transactional
  public College update(UUID id, CollegeForm form) {
    College college = colleges.findById(id).orElseThrow();
    Valid valid = validate(form, college);
    college.updateSettings(valid.name, valid.domains, valid.senders, valid.pattern);
    if (!college.getSlug().equals(valid.slug)) {
      college.changeSlug(valid.slug);
    }
    college.changeStatus(valid.status);
    college.updateClassifierOverrides(valid.overrides);
    collegeTags.forget(id);
    log.info("College {} updated by an admin (status {})", valid.slug, valid.status);
    return college;
  }

  private record Valid(
      String name,
      String slug,
      List<String> domains,
      List<String> senders,
      String pattern,
      CollegeStatus status,
      ClassifierOverrides overrides) {}

  private Valid validate(CollegeForm form, College existing) {
    List<String> errors = new ArrayList<>();

    String name = form.name() == null ? "" : form.name().strip();
    if (name.isEmpty() || name.length() > 200) {
      errors.add("Name: enter the college's name (at most 200 characters).");
    }

    String slug = form.slug() == null ? "" : form.slug().strip().toLowerCase(Locale.ROOT);
    if (!SLUG.matcher(slug).matches()) {
      errors.add(
          "Slug: use 1 to 40 lower-case letters, digits and hyphens, starting and ending with a"
              + " letter or digit.");
    } else {
      colleges
          .findBySlug(slug)
          .filter(other -> existing == null || !other.getId().equals(existing.getId()))
          .ifPresent(other -> errors.add("Slug: another college already uses it."));
    }

    List<String> domains =
        CollegeForm.items(form.emailDomains()).stream()
            .map(d -> d.toLowerCase(Locale.ROOT).replaceFirst("^@", ""))
            .distinct()
            .toList();
    if (domains.isEmpty()) {
      errors.add("Email domains: enter at least one, e.g. college.edu.in.");
    } else if (!domains.stream().allMatch(d -> DOMAIN.matcher(d).matches())) {
      errors.add("Email domains: each must be a domain such as college.edu.in.");
    } else {
      for (String domain : domains) {
        colleges.findByAllowedEmailDomain(domain).stream()
            .filter(other -> existing == null || !other.getId().equals(existing.getId()))
            .findFirst()
            .ifPresent(other ->
                errors.add(
                    "Email domains: one of them already belongs to " + other.getSlug() + "."));
      }
    }

    List<String> senders =
        CollegeForm.items(form.senderAllowlist()).stream()
            .map(s -> s.toLowerCase(Locale.ROOT))
            .distinct()
            .toList();
    if (senders.isEmpty()) {
      errors.add("Sender allowlist: enter at least one address or @domain.");
    } else if (!senders.stream().allMatch(AdminColleges::validSender)) {
      errors.add(
          "Sender allowlist: each entry must be an address (placements@college.edu) or a whole"
              + " domain with a leading @ (@college.edu).");
    }

    String pattern = form.neoIdPattern() == null ? "" : form.neoIdPattern().strip();
    if (pattern.isEmpty()) {
      pattern = College.DEFAULT_NEO_ID_PATTERN;
    }
    try {
      Pattern.compile(pattern);
      if (pattern.length() > 200) {
        errors.add("NeoID pattern: at most 200 characters.");
      }
    } catch (PatternSyntaxException e) {
      errors.add("NeoID pattern: not a valid regular expression.");
    }

    CollegeStatus status = null;
    try {
      status = CollegeStatus.valueOf(form.status() == null ? "" : form.status().strip());
    } catch (IllegalArgumentException e) {
      errors.add("Status: choose PILOT, ACTIVE or PAUSED.");
    }

    ClassifierOverrides overrides =
        new ClassifierOverrides(
            CollegeForm.lines(form.offerSubject()),
            CollegeForm.lines(form.offerBody()),
            CollegeForm.lines(form.shortlistSubject()),
            CollegeForm.lines(form.shortlistBody()),
            CollegeForm.lines(form.knownRecruiters()));
    List<List<String>> lists =
        List.of(
            overrides.offerSubject(),
            overrides.offerBody(),
            overrides.shortlistSubject(),
            overrides.shortlistBody(),
            overrides.knownRecruiters());
    boolean tooMany = lists.stream().anyMatch(l -> l.size() > MAX_KEYWORDS);
    boolean tooLong =
        lists.stream().flatMap(List::stream).anyMatch(k -> k.length() > MAX_KEYWORD_LENGTH);
    if (tooMany || tooLong) {
      errors.add(
          "Classifier overrides: at most "
              + MAX_KEYWORDS
              + " entries per list, each at most "
              + MAX_KEYWORD_LENGTH
              + " characters.");
    }

    if (!errors.isEmpty()) {
      throw new AdminFormException(errors);
    }
    return new Valid(name, slug, domains, senders, pattern, status, overrides);
  }

  private static boolean validSender(String entry) {
    if (entry.startsWith("@")) {
      return DOMAIN.matcher(entry.substring(1)).matches();
    }
    int at = entry.lastIndexOf('@');
    return at > 0
        && LOCAL_PART.matcher(entry.substring(0, at)).matches()
        && DOMAIN.matcher(entry.substring(at + 1)).matches();
  }
}
