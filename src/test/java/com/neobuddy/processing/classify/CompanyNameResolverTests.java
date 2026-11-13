package com.neobuddy.processing.classify;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class CompanyNameResolverTests {

  private final CompanyNameResolver resolver =
      new CompanyNameResolver(new ClassifierProperties(null, null, null));

  @ParameterizedTest(name = "{0} -> {1}")
  @CsvSource(
      delimiter = '=',
      value = {
        "Example Corp - Shortlist = Example Corp",
        "Example Corp – Shortlisted candidates for interview = Example Corp",
        "Example Corp | SDE Intern | Shortlist for round 2 = Example Corp",
        "Example Corp: Offer letters = Example Corp",
        "Shortlisted candidates for Example Corp = Example Corp",
        "Shortlist of students for Example Corp = Example Corp",
        "Shortlisted candidates for Example Corp - SDE Intern (2027 batch) = Example Corp",
        "Shortlisted students for Example Corp campus drive = Example Corp",
        "Short-listed candidates for Example Corp = Example Corp",
        "Congratulations! Offers from Example Corp = Example Corp",
        "Final results of Example Corp = Example Corp",
        "Example Corp shortlist = Example Corp",
        "Example Corp final selects = Example Corp",
        "Placement Cell: Shortlisted candidates for Example Corp = Example Corp",
        "Re: Fwd: [Placements] Example Corp - Shortlist = Example Corp",
        "FW:   Example   Corp  -  Shortlist = Example Corp",
      })
  void resolvesTheCompanyFromSubjectPatterns(String subject, String expected) {
    assertThat(resolver.resolve(null, subject)).contains(expected);
  }

  @ParameterizedTest(name = "{0} -> {1}")
  @CsvSource(
      delimiter = '=',
      value = {
        "Library timings during exams = Library timings during exams",
        "Re: [Notice]  Hostel   fee reminder = Hostel fee reminder",
        "Shortlist = Shortlist",
        "Shortlist for round 2 = Shortlist for round 2",
      })
  void fallsBackToTheCleanedSubject(String subject, String expected) {
    assertThat(resolver.resolve(null, subject)).contains(expected);
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"   ", "Re: ", "[Placements]"})
  void aBlankSubjectHasNoCompany(String subject) {
    assertThat(resolver.resolve(null, subject)).isEmpty();
  }

  @Test
  void aKnownRecruiterWinsOverPatternsAndKeepsItsConfiguredSpelling() {
    CompanyNameResolver withList =
        new CompanyNameResolver(
            TestClassifierRules.fromYaml(
                """
                neobuddy:
                  classifier:
                    known-recruiters: [Example Corp, "Sample & Sons", Example Corp Labs]
                """));

    assertThat(withList.resolve(null, "Drive update - shortlist (EXAMPLE CORP, Pune)"))
        .contains("Example Corp");
    // The longer name wins where both could match.
    assertThat(withList.resolve(null, "Shortlist: example corp labs interns"))
        .contains("Example Corp Labs");
    assertThat(withList.resolve(null, "sample & sons hiring update")).contains("Sample & Sons");
    // Whole words only.
    assertThat(withList.resolve(null, "Counterexample Corporation - Shortlist"))
        .contains("Counterexample Corporation");
  }

  @Test
  void aCollegeAddsItsOwnRecruitersToTheGlobalList() {
    CompanyNameResolver withList =
        new CompanyNameResolver(
            TestClassifierRules.fromYaml(
                """
                neobuddy:
                  classifier:
                    known-recruiters: [Example Corp]
                    colleges:
                      my-college:
                        known-recruiters: [Local Startup]
                """));
    String subject = "Hiring news about Local Startup";

    assertThat(withList.resolve("my-college", subject)).contains("Local Startup");
    assertThat(withList.resolve("other-college", subject)).contains(subject);
    assertThat(withList.resolve("my-college", "News from Example Corp")).contains("Example Corp");
  }

  @Test
  void aVeryLongSubjectIsTruncated() {
    String subject = "Notice ".repeat(40);

    assertThat(resolver.resolve(null, subject))
        .hasValueSatisfying(c -> assertThat(c).hasSizeLessThanOrEqualTo(80));
  }
}
