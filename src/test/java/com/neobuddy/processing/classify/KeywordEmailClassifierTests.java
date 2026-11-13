package com.neobuddy.processing.classify;

import static org.assertj.core.api.Assertions.assertThat;

import com.neobuddy.ingestion.RawEmail;
import com.neobuddy.processing.EventType;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class KeywordEmailClassifierTests {

  private static final String COLLEGE = "my-college";

  /** The rules shipped in application.yml. */
  private final KeywordEmailClassifier classifier =
      TestClassifierRules.classifier(TestClassifierRules.shipped());

  @ParameterizedTest(name = "{0} -> {1}")
  @CsvSource({
    "Example Corp - Shortlist, SHORTLIST",
    "Shortlisted candidates for Example Corp, SHORTLIST",
    "SHORT-LISTED students: Example Corp, SHORTLIST",
    "Example Corp interview schedule, SHORTLIST",
    "Example Corp: candidates for the next round, SHORTLIST",
    "Congratulations! Offers from Example Corp, OFFER",
    "Example Corp - Offer letters, OFFER",
    "Example Corp final selects, OFFER",
    "Final results of Example Corp, OFFER",
    "Students placed in Example Corp, OFFER",
    "Library timings during exams, OTHER",
    "Placement cell office hours, OTHER",
    "Coffers and offerings of the cultural club, OTHER",
  })
  void classifiesBySubject(String subject, EventType expected) {
    assertThat(classifier.classify(COLLEGE, email(subject, "See attachment.", null)).type())
        .isEqualTo(expected);
  }

  @Test
  void offerOutranksShortlistInTheSubject() {
    assertThat(type("Example Corp: offers for the shortlisted students", "Details inside."))
        .isEqualTo(EventType.OFFER);
  }

  @Test
  void offerOutranksShortlistInTheBody() {
    assertThat(
            type(
                "Example Corp update",
                "Of the shortlisted students, these have received offers from Example Corp."))
        .isEqualTo(EventType.OFFER);
  }

  @Test
  void theSubjectOutranksTheBody() {
    // A shortlist mail that mentions the offer on the table must stay a shortlist.
    assertThat(
            type(
                "Example Corp - Shortlist",
                "CTC is 12 LPA. Selected students get an offer letter after the final round."))
        .isEqualTo(EventType.SHORTLIST);
  }

  @Test
  void theBodyDecidesWhenTheSubjectSaysNothing() {
    assertThat(type("Example Corp update", "The following students are shortlisted."))
        .isEqualTo(EventType.SHORTLIST);
    assertThat(type("Example Corp update", "We are pleased to offer positions to:"))
        .isEqualTo(EventType.OFFER);
    assertThat(type("Example Corp update", "The pre-placement talk is at 5 pm."))
        .isEqualTo(EventType.OTHER);
  }

  @Test
  void anHtmlOnlyBodyIsReadAsText() {
    RawEmail email =
        email("Example Corp update", null, "<p>These students are <b>short</b>listed:</p>");

    assertThat(classifier.classify(COLLEGE, email).type()).isEqualTo(EventType.SHORTLIST);
  }

  @Test
  void markupIsNotMistakenForKeywords() {
    RawEmail email =
        email("Example Corp update", null, "<p class=\"shortlist offer\">Talk at 5 pm.</p>");

    assertThat(classifier.classify(COLLEGE, email).type()).isEqualTo(EventType.OTHER);
  }

  @Test
  void missingSubjectAndBodiesAreOther() {
    var classification = classifier.classify(COLLEGE, email(null, null, null));

    assertThat(classification.type()).isEqualTo(EventType.OTHER);
    assertThat(classification.company()).isEmpty();
  }

  @Test
  void theClassificationCarriesTheCompany() {
    assertThat(classifier.classify(COLLEGE, email("Example Corp - Shortlist", "", null)).company())
        .contains("Example Corp");
  }

  @Test
  void aCollegeCanReplaceOneKeywordListAndInheritTheRest() {
    ClassifierProperties properties =
        TestClassifierRules.fromYaml(
            """
            neobuddy:
              classifier:
                rules:
                  offer:
                    subject: [offer]
                  shortlist:
                    subject: [shortlist]
                    body: [shortlisted]
                colleges:
                  other-college:
                    rules:
                      shortlist:
                        subject: [eligible list]
            """);
    KeywordEmailClassifier custom = TestClassifierRules.classifier(properties);
    RawEmail eligible = email("Example Corp eligible list", "", null);
    RawEmail shortlist = email("Example Corp shortlist", "", null);
    RawEmail offer = email("Example Corp offer", "", null);
    RawEmail bodyOnly = email("Example Corp", "You are shortlisted.", null);

    // The override applies to that college only.
    assertThat(custom.classify("other-college", eligible).type()).isEqualTo(EventType.SHORTLIST);
    assertThat(custom.classify(COLLEGE, eligible).type()).isEqualTo(EventType.OTHER);
    // It replaced the shortlist subject list...
    assertThat(custom.classify("other-college", shortlist).type()).isEqualTo(EventType.OTHER);
    assertThat(custom.classify(COLLEGE, shortlist).type()).isEqualTo(EventType.SHORTLIST);
    // ...and inherited everything it did not mention.
    assertThat(custom.classify("other-college", offer).type()).isEqualTo(EventType.OFFER);
    assertThat(custom.classify("other-college", bodyOnly).type()).isEqualTo(EventType.SHORTLIST);
  }

  @Test
  void withoutAnyRulesEverythingIsOther() {
    KeywordEmailClassifier empty =
        TestClassifierRules.classifier(new ClassifierProperties(null, null, null));

    assertThat(empty.classify(COLLEGE, email("Example Corp - Shortlist", "offer", null)).type())
        .isEqualTo(EventType.OTHER);
  }

  private EventType type(String subject, String body) {
    return classifier.classify(COLLEGE, email(subject, body, null)).type();
  }

  private static RawEmail email(String subject, String text, String html) {
    return new RawEmail(
        "<test@mail.example>",
        "gmail-1",
        "placements@college.example",
        subject,
        Instant.parse("2026-11-13T10:00:00Z"),
        text,
        html,
        List.of());
  }
}
