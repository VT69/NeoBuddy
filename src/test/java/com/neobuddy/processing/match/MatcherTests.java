package com.neobuddy.processing.match;

import static org.assertj.core.api.Assertions.assertThat;

import com.neobuddy.processing.EventType;
import com.neobuddy.processing.FakeIdentifierSource;
import com.neobuddy.processing.MatchHit;
import com.neobuddy.processing.extract.TextChunk;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MatcherTests {

  private static final UUID COLLEGE = UUID.randomUUID();
  private static final UUID OTHER_COLLEGE = UUID.randomUUID();
  private static final UUID TARA = UUID.randomUUID();
  private static final UUID RAVI = UUID.randomUUID();

  private final FakeIdentifierSource source = new FakeIdentifierSource();
  private final InMemoryIdentifierIndex index = new InMemoryIdentifierIndex(Optional.of(source));
  private final Matcher matcher = new Matcher(new Tokenizer(), index);

  private UUID taraNeoId;
  private UUID taraRegNo;
  private UUID taraEmail;
  private UUID raviNeoId;

  @BeforeEach
  void registerIdentifiers() {
    taraNeoId = source.add(COLLEGE, TARA, "N1B7J5V6");
    taraRegNo = source.add(COLLEGE, TARA, "21BCE1234");
    taraEmail = source.add(COLLEGE, TARA, "tara.target@college.example");
    raviNeoId = source.add(COLLEGE, RAVI, "Q1W2E3R4");
    index.rebuild();
  }

  @Test
  void aMatchedRowBecomesAHitWithThatRowAsSnippet() {
    List<MatchHit> hits =
        match(
            chunk("R1", "NeoID | Name | Slot"),
            chunk("R2", "Z9X8C7V6 | Asha Example | 10:00"),
            chunk("R3", "N1B7J5V6 | Tara Target | 10:30"));

    assertThat(hits)
        .containsExactly(
            new MatchHit(TARA, taraNeoId, EventType.SHORTLIST, "N1B7J5V6 | Tara Target | 10:30"));
  }

  @Test
  void everyIdentifierTypeMatches() {
    assertThat(match(chunk("R1", "n1b7 j5v6 | Tara")))
        .extracting(MatchHit::identifierId)
        .containsExactly(taraNeoId);
    assertThat(match(chunk("R1", "Tara | 21-BCE-1234")))
        .extracting(MatchHit::identifierId)
        .containsExactly(taraRegNo);
    assertThat(match(chunk("R1", "Write to <Tara.Target@College.Example>.")))
        .extracting(MatchHit::identifierId)
        .containsExactly(taraEmail);
  }

  @Test
  void nearMissesDoNotMatch() {
    assertThat(
            match(
                chunk("R1", "N1B7J5V6X | Someone Else"),
                chunk("R2", "XN1B7J5V6"),
                chunk("R3", "N1B7J5V | N1B7 | J5V6"),
                chunk("R4", "N1B7J5V6_2"),
                chunk("R5", "tara.target@college.example.org.fake-tld-9")))
        .extracting(MatchHit::contextSnippet)
        // "N1B7J5V6_2" does contain the whole token N1B7J5V6 next to a separator: that is a match.
        .containsExactly("N1B7J5V6_2");
  }

  @Test
  void severalUsersInOneEmailEachGetTheirOwnRow() {
    List<MatchHit> hits =
        match(
            chunk("R2", "Q1W2E3R4 | Ravi Sample"),
            chunk("R3", "Z9X8C7V6 | Asha Example"),
            chunk("R4", "N1B7J5V6 | Tara Target"));

    assertThat(hits)
        .containsExactly(
            new MatchHit(RAVI, raviNeoId, EventType.SHORTLIST, "Q1W2E3R4 | Ravi Sample"),
            new MatchHit(TARA, taraNeoId, EventType.SHORTLIST, "N1B7J5V6 | Tara Target"));
  }

  @Test
  void aUserListedSeveralTimesGetsOneHitForTheFirstAppearance() {
    List<MatchHit> hits =
        match(
            chunk("L3", "Reg no 21BCE1234 is through to round 2"),
            chunk("R7", "N1B7J5V6 | Tara Target"),
            chunk("R9", "tara.target@college.example"));

    assertThat(hits)
        .containsExactly(
            new MatchHit(
                TARA, taraRegNo, EventType.SHORTLIST, "Reg no 21BCE1234 is through to round 2"));
  }

  @Test
  void twoIdentifiersOfOneUserInOneRowGiveOneDeterministicHit() {
    UUID lower = taraNeoId.compareTo(taraRegNo) < 0 ? taraNeoId : taraRegNo;

    for (int i = 0; i < 20; i++) {
      assertThat(match(chunk("R2", "N1B7J5V6 | Tara Target | 21BCE1234")))
          .extracting(MatchHit::identifierId)
          .containsExactly(lower);
    }
  }

  @Test
  void aChunkNamingTwoUsersGivesEachAHitWithThatChunk() {
    String line = "Selected: Tara (N1B7J5V6) and Ravi (Q1W2E3R4)";

    assertThat(match(chunk("L1", line)))
        .extracting(MatchHit::userId, MatchHit::contextSnippet)
        .containsExactlyInAnyOrder(
            org.assertj.core.groups.Tuple.tuple(TARA, line),
            org.assertj.core.groups.Tuple.tuple(RAVI, line));
  }

  @Test
  void matchingIsScopedToTheCollege() {
    assertThat(
            matcher.match(
                OTHER_COLLEGE, EventType.SHORTLIST, List.of(chunk("R1", "N1B7J5V6 | Tara"))))
        .isEmpty();
  }

  @Test
  void theEventTypeIsCopiedOntoTheHit() {
    assertThat(matcher.match(COLLEGE, EventType.OFFER, List.of(chunk("R1", "N1B7J5V6"))))
        .extracting(MatchHit::eventType)
        .containsExactly(EventType.OFFER);
  }

  @Test
  void aLongChunkIsCutToThreeHundredCharacters() {
    String longRow = "N1B7J5V6 | " + "x".repeat(400);
    String exactlyAtLimit = "N1B7J5V6 | " + "y".repeat(MatchHit.MAX_SNIPPET_LENGTH - 11);

    String cut = match(chunk("R1", longRow)).get(0).contextSnippet();
    String kept = match(chunk("R1", exactlyAtLimit)).get(0).contextSnippet();

    assertThat(cut).hasSize(MatchHit.MAX_SNIPPET_LENGTH).startsWith("N1B7J5V6 | xxx").endsWith("…");
    assertThat(kept).hasSize(MatchHit.MAX_SNIPPET_LENGTH).isEqualTo(exactlyAtLimit);
  }

  @Test
  void theSnippetNeverIncludesNeighbouringChunks() {
    List<MatchHit> hits =
        match(
            chunk("R1", "Z9X8C7V6 | Asha Example | 2021000123"),
            chunk("R2", "N1B7J5V6 | Tara Target"),
            chunk("R3", "M5N6B7V8 | Meera Placeholder"));

    assertThat(hits)
        .singleElement()
        .extracting(MatchHit::contextSnippet)
        .isEqualTo("N1B7J5V6 | Tara Target");
  }

  @Test
  void noChunksOrNoMatchesGiveNoHits() {
    assertThat(match()).isEmpty();
    assertThat(match(chunk("R1", "nothing to see"), chunk("R2", ""))).isEmpty();
  }

  @Test
  void hitsNeverPrintTheirSnippet() {
    assertThat(match(chunk("R1", "N1B7J5V6 | Tara Target")).get(0).toString())
        .doesNotContain("Tara")
        .doesNotContain("N1B7J5V6");
  }

  private List<MatchHit> match(TextChunk... chunks) {
    return matcher.match(COLLEGE, EventType.SHORTLIST, List.of(chunks));
  }

  private static TextChunk chunk(String location, String text) {
    return new TextChunk("shortlist.xlsx", location, text);
  }
}
