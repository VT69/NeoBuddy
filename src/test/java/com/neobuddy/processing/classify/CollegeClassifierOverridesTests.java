package com.neobuddy.processing.classify;

import static org.assertj.core.api.Assertions.assertThat;

import com.neobuddy.ingestion.RawEmail;
import com.neobuddy.processing.EventType;
import com.neobuddy.shared.college.ClassifierOverrides;
import com.neobuddy.shared.college.College;
import com.neobuddy.shared.college.CollegeStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Keyword overrides a college gets in the admin console, on top of the shipped rules. */
class CollegeClassifierOverridesTests {

  private final KeywordEmailClassifier classifier =
      TestClassifierRules.classifier(TestClassifierRules.shipped());

  private static College college(ClassifierOverrides overrides) {
    College college =
        new College(
            UUID.randomUUID(),
            "override-college",
            "Override College",
            List.of("override.example"),
            List.of("@override.example"),
            College.DEFAULT_NEO_ID_PATTERN,
            CollegeStatus.PILOT,
            Instant.parse("2026-01-01T00:00:00Z"));
    college.updateClassifierOverrides(overrides);
    return college;
  }

  private static RawEmail email(String subject, String text) {
    return new RawEmail(
        "<override@mail.example>",
        "gmail-1",
        "placements@override.example",
        subject,
        Instant.parse("2026-11-13T10:00:00Z"),
        text,
        null,
        List.of());
  }

  @Test
  void withoutOverridesTheShippedRulesApply() {
    College plain = college(ClassifierOverrides.NONE);

    assertThat(classifier.classify(plain, email("Example Corp - Shortlist", "")).type())
        .isEqualTo(EventType.SHORTLIST);
    assertThat(classifier.classify(plain, email("Eligible list for Example Corp", "")).type())
        .isEqualTo(EventType.OTHER);
  }

  @Test
  void anOverriddenListReplacesTheDefaultForThatCollegeOnly() {
    College custom =
        college(
            new ClassifierOverrides(
                List.of(), List.of(), List.of("eligible list"), List.of(), List.of()));

    assertThat(classifier.classify(custom, email("Eligible list for Example Corp", "")).type())
        .isEqualTo(EventType.SHORTLIST);
    // The subject list was replaced, so the default subject keyword no longer applies here...
    assertThat(classifier.classify(custom, email("Example Corp - Shortlist", "")).type())
        .isEqualTo(EventType.OTHER);
    // ...while the lists it did not override (the shortlist body list) still do.
    assertThat(classifier.classify(custom, email("Example Corp", "You are shortlisted.")).type())
        .isEqualTo(EventType.SHORTLIST);
    // Offers are untouched.
    assertThat(classifier.classify(custom, email("Offer letters - Example Corp", "")).type())
        .isEqualTo(EventType.OFFER);
    // Another college still uses the defaults.
    assertThat(
            classifier
                .classify(college(ClassifierOverrides.NONE), email("Eligible list", ""))
                .type())
        .isEqualTo(EventType.OTHER);
  }

  @Test
  void aCollegesRecruitersAreRecognisedInTheSubject() {
    College custom =
        college(
            new ClassifierOverrides(
                List.of(), List.of(), List.of(), List.of(), List.of("Local Startup")));

    assertThat(
            classifier
                .classify(custom, email("Drive update: Local Startup interviews tomorrow", ""))
                .company())
        .contains("Local Startup");
  }

  @Test
  void anEditedOverrideTakesEffectAtOnce() {
    College custom = college(ClassifierOverrides.NONE);
    assertThat(classifier.classify(custom, email("Eligible list", "")).type())
        .isEqualTo(EventType.OTHER);

    custom.updateClassifierOverrides(
        new ClassifierOverrides(
            List.of(), List.of(), List.of("eligible list"), List.of(), List.of()));

    assertThat(classifier.classify(custom, email("Eligible list", "")).type())
        .isEqualTo(EventType.SHORTLIST);
  }

  @Test
  void blankAndDuplicateEntriesAreDropped() {
    ClassifierOverrides overrides =
        new ClassifierOverrides(
            List.of(" offer ", "", "offer"), null, List.of("  "), List.of(), List.of());

    assertThat(overrides.offerSubject()).containsExactly("offer");
    assertThat(overrides.offerBody()).isEmpty();
    assertThat(overrides.shortlistSubject()).isEmpty();
    assertThat(ClassifierOverrides.NONE.isEmpty()).isTrue();
  }
}
