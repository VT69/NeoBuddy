package com.neobuddy.processing;

import static org.assertj.core.api.Assertions.assertThat;

import com.neobuddy.processing.ProcessingFixture.Mail;
import com.neobuddy.shared.college.College;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Golden tests: whole synthetic emails in, classification and matches out, through the real
 * pipeline. Each test is one scenario from the T4 brief.
 */
class ProcessingServiceGoldenTests {

  private static final String NEO_ID = "N1B7J5V6";
  private static final String REG_NO = "21BCE1234";

  private final ProcessingFixture fixture = new ProcessingFixture();
  private final College college = ProcessingFixture.college("golden-college");
  private final College otherCollege = ProcessingFixture.college("other-college");
  private final UUID tara = UUID.randomUUID();

  private UUID taraNeoId;
  private UUID taraRegNo;

  @BeforeEach
  void subscribeTara() {
    taraNeoId = fixture.register(college, tara, NEO_ID);
    taraRegNo = fixture.register(college, tara, REG_NO);
    // Another subscriber who appears in none of the emails.
    fixture.register(college, UUID.randomUUID(), "A0B0C0D0");
  }

  @Test
  @DisplayName("a) 500-row .xlsx shortlist with the NeoID once -> 1 hit, that row as snippet")
  void xlsxShortlistWithFiveHundredRows() {
    List<List<Object>> rows = new ArrayList<>(ProcessingFixture.studentRows(500));
    rows.set(317, List.of(NEO_ID, "Tara Target", 2021000317L, "Slot 3"));
    Mail mail =
        Mail.subject("Example Corp - Shortlist")
            .text("Dear students,\nPlease find the shortlist attached.")
            .attach("shortlist.xlsx", ProcessingFixture.xlsx(rows));

    ProcessingResult result = fixture.service.process(college, mail.email(), mail.content());

    assertThat(result.classification().type()).isEqualTo(EventType.SHORTLIST);
    assertThat(result.classification().company()).contains("Example Corp");
    assertThat(result.hits())
        .containsExactly(
            new MatchHit(
                tara,
                taraNeoId,
                EventType.SHORTLIST,
                "N1B7J5V6 | Tara Target | 2021000317 | Slot 3"));
    assertThat(result.extraction()).isEqualTo(new ProcessingResult.ExtractionSummary(1, 1, 0, 0));
  }

  @Test
  @DisplayName("b) inline HTML table shortlist -> 1 hit")
  void inlineHtmlTableShortlist() {
    Mail mail =
        Mail.subject("Shortlisted candidates for Example Corp")
            .html(
                """
                <html><body>
                <table width="100%"><tr><td>
                  <p>Dear students, the following are shortlisted:</p>
                  <table border="1">
                    <tr><th>S.No</th><th>NeoID</th><th>Name</th><th>Slot</th></tr>
                    <tr><td>1</td><td>Z9X8C7V6</td><td>Asha Example</td><td>10:00</td></tr>
                    <tr><td>2</td><td>N1B7J5V6</td><td>Tara Target</td><td>10:30</td></tr>
                    <tr><td>3</td><td>Q1W2E3R4</td><td>Ravi Sample</td><td>11:00</td></tr>
                  </table>
                </td></tr></table>
                </body></html>
                """);

    ProcessingResult result = fixture.service.process(college, mail.email(), mail.content());

    assertThat(result.classification())
        .isEqualTo(new Classification(EventType.SHORTLIST, java.util.Optional.of("Example Corp")));
    assertThat(result.hits())
        .containsExactly(
            new MatchHit(
                tara, taraNeoId, EventType.SHORTLIST, "2 | N1B7J5V6 | Tara Target | 10:30"));
  }

  @Test
  @DisplayName(
      "c) 'Congratulations' offer mail listing names and NeoIDs in the body -> OFFER + hit")
  void congratulationsOfferMail() {
    Mail mail =
        Mail.subject("Congratulations! Offers from Example Corp")
            .text(
                """
                Dear students,

                Congratulations to the following students who have received offers:

                1. Asha Example - Z9X8C7V6
                2. Tara Target - N1B7J5V6
                3. Ravi Sample - Q1W2E3R4

                Regards,
                Placement Cell
                """);

    ProcessingResult result = fixture.service.process(college, mail.email(), mail.content());

    assertThat(result.classification().type()).isEqualTo(EventType.OFFER);
    assertThat(result.classification().company()).contains("Example Corp");
    assertThat(result.hits())
        .containsExactly(
            new MatchHit(tara, taraNeoId, EventType.OFFER, "2. Tara Target - N1B7J5V6"));
  }

  @Test
  @DisplayName("d) unrelated mail -> no hit, type OTHER")
  void unrelatedMail() {
    Mail mail =
        Mail.subject("Library timings during exams")
            .text("The library stays open until midnight from 20 November.\nBring your ID card.");

    ProcessingResult result = fixture.service.process(college, mail.email(), mail.content());

    assertThat(result.classification().type()).isEqualTo(EventType.OTHER);
    assertThat(result.hits()).isEmpty();
    assertThat(fixture.matchesFound()).isEmpty();
  }

  @Test
  @DisplayName("e) NeoID typed as 'N1B7 J5V6' in a cell -> hit")
  void neoIdTypedWithASpace() {
    Mail mail =
        Mail.subject("Example Corp - Shortlist")
            .attach(
                "shortlist.xlsx",
                ProcessingFixture.xlsx(
                    List.of(
                        List.of("NeoID", "Name"),
                        List.of("Z9X8 C7V6", "Asha Example"),
                        List.of("N1B7 J5V6", "Tara Target"),
                        List.of("n1b7-j5v6", "Tara Target (duplicate, lower case with hyphen)"))));

    ProcessingResult result = fixture.service.process(college, mail.email(), mail.content());

    assertThat(result.hits())
        .containsExactly(
            new MatchHit(tara, taraNeoId, EventType.SHORTLIST, "N1B7 J5V6 | Tara Target"));
  }

  @Test
  @DisplayName("f) near miss 'N1B7J5V6X' -> NO hit")
  void nearMiss() {
    Mail mail =
        Mail.subject("Example Corp - Shortlist")
            .text("Also shortlisted: N1B7J5V6X, XN1B7J5V6 and N1B7J5V.")
            .attach(
                "shortlist.xlsx",
                ProcessingFixture.xlsx(
                    List.of(
                        List.of("NeoID", "Name"),
                        List.of("N1B7J5V6X", "Not Tara"),
                        List.of("0N1B7J5V6", "Also Not Tara"),
                        List.of("N1B7J5V", "Nearly Tara"),
                        List.of("N1B7", "J5V6"))));

    ProcessingResult result = fixture.service.process(college, mail.email(), mail.content());

    assertThat(result.classification().type()).isEqualTo(EventType.SHORTLIST);
    assertThat(result.hits()).isEmpty();
    assertThat(fixture.matchesFound()).isEmpty();
  }

  @Test
  @DisplayName("g) same user listed by NeoID and by reg no -> exactly 1 hit")
  void sameUserByNeoIdAndRegNo() {
    Mail mail =
        Mail.subject("Example Corp - Shortlist")
            .text("Reg no 21BCE1234 please bring your laptop.")
            .attach(
                "round1.xlsx",
                ProcessingFixture.xlsx(
                    List.of(List.of("NeoID", "Name"), List.of(NEO_ID, "Tara Target"))))
            .attach("round2.csv", ProcessingFixture.utf8("Reg No,Name\n21BCE1234,Tara Target\n"));

    ProcessingResult result = fixture.service.process(college, mail.email(), mail.content());

    // One hit, for the first place she appears: the body line with her registration number.
    assertThat(result.hits())
        .containsExactly(
            new MatchHit(
                tara,
                taraRegNo,
                EventType.SHORTLIST,
                "Reg no 21BCE1234 please bring your laptop."));
    assertThat(fixture.matchesFound())
        .singleElement()
        .satisfies(e -> assertThat(e.hits()).hasSize(1));
  }

  @Test
  @DisplayName("h) same NeoID registered in another college -> no cross-college hit")
  void sameNeoIdInAnotherCollege() {
    UUID namesake = UUID.randomUUID();
    UUID namesakeNeoId = fixture.register(otherCollege, namesake, NEO_ID);
    UUID onlyThere = UUID.randomUUID();
    fixture.register(otherCollege, onlyThere, "Q1W2E3R4");
    Mail mail =
        Mail.subject("Example Corp - Shortlist")
            .attach(
                "shortlist.xlsx",
                ProcessingFixture.xlsx(
                    List.of(
                        List.of("NeoID", "Name"),
                        List.of(NEO_ID, "Tara Target"),
                        List.of("Q1W2E3R4", "Ravi Sample"))));

    ProcessingResult here = fixture.service.process(college, mail.email(), mail.content());
    ProcessingResult there = fixture.service.process(otherCollege, mail.email(), mail.content());

    // The same sheet, received for each college: only that college's own subscribers match.
    assertThat(here.hits()).extracting(MatchHit::userId).containsExactly(tara);
    assertThat(here.hits()).extracting(MatchHit::identifierId).containsExactly(taraNeoId);
    assertThat(there.hits()).extracting(MatchHit::userId).containsExactly(namesake, onlyThere);
    assertThat(there.hits()).extracting(MatchHit::identifierId).contains(namesakeNeoId);
    assertThat(fixture.matchesFound())
        .extracting(MatchesFound::collegeId)
        .containsExactly(college.getId(), otherCollege.getId());
  }
}
