package com.neobuddy.processing.match;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** The contract in {@link Tokenizer}'s Javadoc, as tests. All data is synthetic. */
@Disabled("T3 - Vaibhav implements")
class TokenizerTests {

  private final Tokenizer tokenizer = new Tokenizer();

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" ", "\t\n", " | ", "-- | --", "a b c", "..."})
  void nothingUsefulYieldsAnEmptySet(String text) {
    assertThat(tokenizer.tokenize(text)).isEmpty();
  }

  @Nested
  class EmailAddresses {

    @Test
    void areEmittedWholeAndLowerCased() {
      assertThat(tokenizer.tokenize("Asha.Example@College.EDU"))
          .contains("asha.example@college.edu");
    }

    @Test
    void surroundingPunctuationIsNotPartOfTheAddress() {
      assertThat(tokenizer.tokenize("Mail <ravi.sample@college.edu>, or meera@college.edu."))
          .contains("ravi.sample@college.edu", "meera@college.edu")
          .noneMatch(token -> token.contains("<") || token.endsWith(".") || token.endsWith(","));
    }

    @Test
    void plusTagsAndSubdomainsAreKept() {
      assertThat(tokenizer.tokenize("asha+placements@mail.cse.college.edu"))
          .contains("asha+placements@mail.cse.college.edu");
    }

    @Test
    void severalAddressesInOneChunkAreAllFound() {
      assertThat(tokenizer.tokenize("asha@college.edu | ravi@college.edu;meera@college.edu"))
          .contains("asha@college.edu", "ravi@college.edu", "meera@college.edu");
    }

    @Test
    void theWordsInsideAnAddressAreStillTokenized() {
      // "example" and "college" are 4+ characters; "asha" is exactly 4; "edu" is too short.
      assertThat(tokenizer.tokenize("asha.example@college.edu"))
          .containsExactlyInAnyOrder("asha.example@college.edu", "ASHA", "EXAMPLE", "COLLEGE");
    }

    @Test
    void anAtSignWithoutADomainIsNotAnAddress() {
      assertThat(tokenizer.tokenize("meet @ noon, asha@localhost"))
          .noneMatch(token -> token.contains("@"));
    }
  }

  @Nested
  class Words {

    @Test
    void splitOnEveryNonAlphanumericCharacterAndUpperCased() {
      assertThat(tokenizer.tokenize("neo-id: z9x8c7v6, cse")).containsExactlyInAnyOrder("Z9X8C7V6");
    }

    @Test
    void piecesShorterThanFourAreDropped() {
      assertThat(tokenizer.tokenize("abc/abcd/ab/1234/123"))
          .containsExactlyInAnyOrder("ABCD", "1234");
    }

    @Test
    void thereIsNoUpperLengthLimit() {
      String longRun = "A1".repeat(30);
      assertThat(tokenizer.tokenize(longRun.toLowerCase() + "!")).containsExactly(longRun);
    }

    @Test
    void aLongerRunIsNeverCutIntoAShorterToken() {
      assertThat(tokenizer.tokenize("Z9X8C7V6X"))
          .containsExactly("Z9X8C7V6X")
          .doesNotContain("Z9X8C7V6");
    }

    @Test
    void underscoresAndNonAsciiLettersAreSeparators() {
      assertThat(tokenizer.tokenize("reg_no:2021000123")).containsExactly("2021000123");
      // "caf", "r", "sum" are too short once the accented letters split the words.
      assertThat(tokenizer.tokenize("cafés1234 résumé")).containsExactly("S1234");
    }

    @Test
    void duplicatesCollapseBecauseTheResultIsASet() {
      assertThat(tokenizer.tokenize("Z9X8C7V6 z9x8c7v6 Z9x8C7v6")).containsExactly("Z9X8C7V6");
    }
  }

  @Nested
  class Cells {

    @Test
    void spacesInsideACellAreRemoved() {
      assertThat(tokenizer.tokenize("n1b7 j5v6"))
          .containsExactlyInAnyOrder("N1B7J5V6", "N1B7", "J5V6");
    }

    @Test
    void hyphensInsideACellAreRemoved() {
      assertThat(tokenizer.tokenize("N1B7-J5V6")).contains("N1B7J5V6");
      assertThat(tokenizer.tokenize("21-BCE-1234")).containsExactlyInAnyOrder("21BCE1234", "1234");
    }

    @Test
    void eachCellOfARowIsHandledOnItsOwn() {
      assertThat(tokenizer.tokenize("Z9X8 C7V6 | Asha Example | 2021 000 123 | 10:00"))
          .containsExactlyInAnyOrder(
              // cells
              "Z9X8C7V6",
              "ASHAEXAMPLE",
              "2021000123",
              // words
              "Z9X8",
              "C7V6",
              "ASHA",
              "EXAMPLE",
              "2021");
    }

    @Test
    void cellsAreNotJoinedAcrossTheSeparator() {
      assertThat(tokenizer.tokenize("Z9X8 | C7V6")).containsExactlyInAnyOrder("Z9X8", "C7V6");
    }

    @Test
    void aCellShorterThanFourOrLongerThanTwentyIsNotEmitted() {
      String twenty = "A".repeat(10) + " " + "B".repeat(10);
      String twentyOne = "A".repeat(10) + " " + "B".repeat(11);

      assertThat(tokenizer.tokenize("a b c")).isEmpty();
      assertThat(tokenizer.tokenize("ab-cd")).containsExactly("ABCD");
      assertThat(tokenizer.tokenize(twenty)).contains("A".repeat(10) + "B".repeat(10));
      assertThat(tokenizer.tokenize(twentyOne))
          .containsExactlyInAnyOrder("A".repeat(10), "B".repeat(11));
    }

    @Test
    void aCellWithOtherPunctuationEmitsNoCellToken() {
      assertThat(tokenizer.tokenize("N1B7.J5V6 | N1B7/J5V6 | slot: 10"))
          .containsExactlyInAnyOrder("N1B7", "J5V6", "SLOT");
    }

    @Test
    void aBarWithoutSpacesIsNotACellSeparator() {
      assertThat(tokenizer.tokenize("Z9X8|C7V6")).containsExactlyInAnyOrder("Z9X8", "C7V6");
    }
  }

  @Test
  void aRealisticRowYieldsEveryIdentifierForm() {
    assertThat(
            tokenizer.tokenize(
                "3 | z9x8c7v6 | Asha Example | 21BCE1234 | asha.example@college.edu | Round 2"))
        .contains("Z9X8C7V6", "21BCE1234", "asha.example@college.edu", "ASHAEXAMPLE", "ROUND2")
        .doesNotContain("3", "Z9X8", "21BCE", "EDU");
  }
}
