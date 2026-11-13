package com.neobuddy.processing;

import static org.assertj.core.api.Assertions.assertThat;

import com.neobuddy.ingestion.RawEmail;
import com.neobuddy.processing.ProcessingFixture.Mail;
import com.neobuddy.processing.ProcessingResult.ExtractionSummary;
import com.neobuddy.shared.college.College;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** The plumbing around the pipeline: events, the sink port, failure isolation and logging. */
@ExtendWith(OutputCaptureExtension.class)
class ProcessingServiceTests {

  private final ProcessingFixture fixture = new ProcessingFixture();
  private final College college = ProcessingFixture.college("my-college");
  private final UUID tara = UUID.randomUUID();

  private UUID taraNeoId;

  @BeforeEach
  void subscribeTara() {
    taraNeoId = fixture.register(college, tara, "N1B7J5V6");
  }

  @Test
  void publishesMatchesFoundWithTheSameContentAsTheResult() {
    Mail mail = Mail.subject("Example Corp - Shortlist").text("N1B7J5V6 - Tara Target");
    RawEmail email = mail.email();

    ProcessingResult result = fixture.service.process(college, email, mail.content());

    assertThat(fixture.matchesFound())
        .containsExactly(
            new MatchesFound(
                college.getId(), email.rfcMessageId(), result.classification(), result.hits()));
    assertThat(result.collegeId()).isEqualTo(college.getId());
    assertThat(result.rfcMessageId()).isEqualTo(email.rfcMessageId());
    assertThat(result.hits()).extracting(MatchHit::identifierId).containsExactly(taraNeoId);
  }

  @Test
  void publishesNothingWhenNobodyMatched() {
    Mail mail = Mail.subject("Example Corp - Shortlist").text("Z9X8C7V6 - Asha Example");

    ProcessingResult result = fixture.service.process(college, mail.email(), mail.content());

    assertThat(result.hits()).isEmpty();
    assertThat(fixture.publishedEvents).isEmpty();
  }

  @Test
  void everyResultGoesToTheSinkMatchedOrNot() {
    Mail matched = Mail.subject("Example Corp - Shortlist").text("N1B7J5V6");
    Mail unmatched = Mail.subject("Library timings").text("Open until midnight.");

    ProcessingResult first = fixture.service.process(college, matched.email(), matched.content());
    ProcessingResult second =
        fixture.service.process(college, unmatched.email(), unmatched.content());

    assertThat(fixture.savedResults).containsExactly(first, second);
  }

  @Test
  void anIdentifierInTheSubjectLineMatches() {
    Mail mail =
        Mail.subject("Offer letter for N1B7J5V6").text("Please collect it from the office.");

    ProcessingResult result = fixture.service.process(college, mail.email(), mail.content());

    assertThat(result.hits())
        .containsExactly(
            new MatchHit(tara, taraNeoId, EventType.OFFER, "Offer letter for N1B7J5V6"));
  }

  @Test
  void aMatchInAnOtherMailIsStillReportedWithItsType() {
    Mail mail = Mail.subject("Document verification").text("N1B7J5V6 report to room 12.");

    ProcessingResult result = fixture.service.process(college, mail.email(), mail.content());

    assertThat(result.classification().type()).isEqualTo(EventType.OTHER);
    assertThat(result.hits()).extracting(MatchHit::eventType).containsExactly(EventType.OTHER);
  }

  @Test
  void badAttachmentsFailAloneAndTheRestOfTheEmailStillMatches(CapturedOutput output) {
    byte[] good =
        ProcessingFixture.xlsx(
            List.of(List.of("NeoID", "Name"), List.of("N1B7J5V6", "Tara Target")));
    byte[] corrupt = java.util.Arrays.copyOf(good, good.length / 2);
    Mail mail =
        Mail.subject("Example Corp - Shortlist")
            .attach("corrupt.xlsx", corrupt)
            .attachUnfetchable("missing.xlsx")
            .attach("photo.jpg", new byte[] {0, 1, 2, 3, 4, 5, 6, 7})
            .attach("shortlist.xlsx", good);
    RawEmail email = mail.email();

    ProcessingResult result = fixture.service.process(college, email, mail.content());

    assertThat(result.hits()).extracting(MatchHit::userId).containsExactly(tara);
    assertThat(result.extraction()).isEqualTo(new ExtractionSummary(4, 1, 1, 2));
    assertThat(output)
        .contains("Could not fetch attachment 'missing.xlsx' of email " + email.rfcMessageId())
        .contains("Extraction failed for attachment 'corrupt.xlsx'");
  }

  @Test
  void logsTheMessageIdAndCountsButNeverContent(CapturedOutput output) {
    Mail mail =
        Mail.subject("Example Corp - Shortlist")
            .text("N1B7J5V6 - Tara Target\nZ9X8C7V6 - Asha Example");
    RawEmail email = mail.email();

    fixture.service.process(college, email, mail.content());

    assertThat(output)
        .contains("Processed email " + email.rfcMessageId() + ": type SHORTLIST, 1 matches");
    assertThat(output.toString())
        .doesNotContain("N1B7J5V6")
        .doesNotContain("Tara")
        .doesNotContain("Asha")
        .doesNotContain("Example Corp");
  }

  @Test
  void anEmailWithNothingInItIsOtherWithNoHits() {
    Mail mail = Mail.subject(null);

    ProcessingResult result = fixture.service.process(college, mail.email(), AttachmentSource.NONE);

    assertThat(result.classification())
        .isEqualTo(new Classification(EventType.OTHER, java.util.Optional.empty()));
    assertThat(result.hits()).isEmpty();
    assertThat(result.extraction()).isEqualTo(new ExtractionSummary(0, 0, 0, 0));
  }

  @Test
  void resultsAndEmailsNeverPrintContent() {
    Mail mail = Mail.subject("Example Corp - Shortlist").text("N1B7J5V6 - Tara Target");
    RawEmail email = mail.email();

    ProcessingResult result = fixture.service.process(college, email, mail.content());

    assertThat(email.toString()).doesNotContain("Shortlist").doesNotContain("N1B7J5V6");
    assertThat(result.toString()).doesNotContain("N1B7J5V6").doesNotContain("Tara");
    assertThat(fixture.matchesFound().get(0).toString())
        .doesNotContain("N1B7J5V6")
        .doesNotContain("Tara");
  }
}
