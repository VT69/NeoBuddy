package com.neobuddy.subscriber;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neobuddy.shared.college.College;
import com.neobuddy.shared.college.CollegeStatus;
import com.neobuddy.subscriber.InvalidIdentifierException.Reason;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class IdentifierNormalizerTests {

  private final College college = college("^[A-Z0-9]{8}$", "college.example", "alumni.example");

  @ParameterizedTest(name = "[{0}] -> {1}")
  @CsvSource({
    "N1B7J5V6, N1B7J5V6",
    "n1b7j5v6, N1B7J5V6",
    "'  n1b7 j5v6  ', N1B7J5V6",
    "N1B7-J5V6, N1B7J5V6",
    "'n1 b7-j5\tv6', N1B7J5V6",
  })
  void neoIdsAndRegNosAreTrimmedUpperCasedAndStrippedOfSpacesAndHyphens(String raw, String want) {
    assertThat(IdentifierNormalizer.normalize(IdentifierType.NEO_ID, raw)).isEqualTo(want);
    assertThat(IdentifierNormalizer.normalize(IdentifierType.REG_NO, raw)).isEqualTo(want);
  }

  @Test
  void emailsAreTrimmedAndLowerCasedButKeepTheirPunctuation() {
    assertThat(
            IdentifierNormalizer.normalize(
                IdentifierType.COLLEGE_EMAIL, "  Tara.Target-01@College.Example "))
        .isEqualTo("tara.target-01@college.example");
  }

  @Test
  void nullNormalisesToEmpty() {
    assertThat(IdentifierNormalizer.normalize(IdentifierType.NEO_ID, null)).isEmpty();
  }

  @Test
  void aNeoIdMustMatchTheCollegePattern() {
    assertThat(validate(IdentifierType.NEO_ID, "n1b7 j5v6")).isEqualTo("N1B7J5V6");

    for (String wrong : new String[] {"N1B7J5V", "N1B7J5V6X", "N1B7_5V6", "N1B7J5VÖ"}) {
      assertRejected(IdentifierType.NEO_ID, wrong, Reason.WRONG_FORMAT);
    }
  }

  @Test
  void eachCollegeHasItsOwnNeoIdPattern() {
    College numeric = college("^[0-9]{6}$", "numeric.example");

    assertThat(IdentifierNormalizer.normalizeAndValidate(numeric, IdentifierType.NEO_ID, "123 456"))
        .isEqualTo("123456");
    assertThatThrownBy(() ->
            IdentifierNormalizer.normalizeAndValidate(numeric, IdentifierType.NEO_ID, "N1B7J5V6"))
        .isInstanceOf(InvalidIdentifierException.class);
    assertRejected(IdentifierType.NEO_ID, "123456", Reason.WRONG_FORMAT);
  }

  @Test
  void aRegNoMustBeSomethingTheTokenizerCanFind() {
    assertThat(validate(IdentifierType.REG_NO, "21-bce-1234")).isEqualTo("21BCE1234");
    assertThat(validate(IdentifierType.REG_NO, "2021000123")).isEqualTo("2021000123");

    assertRejected(IdentifierType.REG_NO, "123", Reason.WRONG_FORMAT);
    assertRejected(IdentifierType.REG_NO, "A".repeat(21), Reason.WRONG_FORMAT);
    assertRejected(IdentifierType.REG_NO, "21/BCE/1234", Reason.WRONG_FORMAT);
    assertRejected(IdentifierType.REG_NO, "21.BCE.1234", Reason.WRONG_FORMAT);
  }

  @Test
  void aCollegeEmailMustBeOnOneOfTheCollegeDomains() {
    assertThat(validate(IdentifierType.COLLEGE_EMAIL, "Tara.Target@College.Example"))
        .isEqualTo("tara.target@college.example");
    assertThat(validate(IdentifierType.COLLEGE_EMAIL, "tara@alumni.example"))
        .isEqualTo("tara@alumni.example");

    assertRejected(IdentifierType.COLLEGE_EMAIL, "tara@gmail.example", Reason.NOT_A_COLLEGE_EMAIL);
    // A domain that merely ends with, or contains, the college domain is not the college domain.
    assertRejected(
        IdentifierType.COLLEGE_EMAIL, "tara@evilcollege.example", Reason.NOT_A_COLLEGE_EMAIL);
    assertRejected(
        IdentifierType.COLLEGE_EMAIL, "tara@mail.college.example", Reason.NOT_A_COLLEGE_EMAIL);
    assertRejected(
        IdentifierType.COLLEGE_EMAIL,
        "tara@college.example.attacker.example",
        Reason.NOT_A_COLLEGE_EMAIL);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"tara", "tara@", "@college.example", "tara target@college.example", "a@b"})
  void aMalformedEmailIsRejected(String email) {
    assertRejected(IdentifierType.COLLEGE_EMAIL, email, Reason.WRONG_FORMAT);
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"   ", " - - "})
  void blankValuesAreRejectedForEveryType(String blank) {
    assertRejected(IdentifierType.NEO_ID, blank, Reason.BLANK);
    assertRejected(IdentifierType.REG_NO, blank, Reason.BLANK);
  }

  @Test
  void rejectionsNeverCarryTheValue() {
    assertThatThrownBy(() -> validate(IdentifierType.NEO_ID, "SECRETID9X"))
        .hasMessageNotContaining("SECRETID9X");
    assertThat(new NeoId("N1B7J5V6").toString()).doesNotContain("N1B7J5V6");
    assertThat(new ChannelAddress("topic-123").toString()).doesNotContain("topic-123");
  }

  @Test
  void neoIdParseNormalisesAndValidates() {
    assertThat(NeoId.parse(" n1b7-j5v6 ", college.getNeoIdPattern()))
        .isEqualTo(new NeoId("N1B7J5V6"));
    assertThatThrownBy(() -> NeoId.parse("short", college.getNeoIdPattern()))
        .isInstanceOf(InvalidIdentifierException.class);
  }

  @Test
  void domainOfTakesThePartAfterTheLastAtSign() {
    assertThat(IdentifierNormalizer.domainOf("Tara@College.Example")).isEqualTo("college.example");
    assertThat(IdentifierNormalizer.domainOf("\"odd@local\"@college.example"))
        .isEqualTo("college.example");
    assertThat(IdentifierNormalizer.domainOf("no-at-sign")).isEmpty();
    assertThat(IdentifierNormalizer.domainOf(null)).isEmpty();
  }

  private String validate(IdentifierType type, String raw) {
    return IdentifierNormalizer.normalizeAndValidate(college, type, raw);
  }

  private void assertRejected(IdentifierType type, String raw, Reason reason) {
    assertThatThrownBy(() -> validate(type, raw))
        .isInstanceOfSatisfying(
            InvalidIdentifierException.class, e -> assertThat(e.reason()).isEqualTo(reason));
  }

  private static College college(String neoIdPattern, String... domains) {
    return new College(
        UUID.randomUUID(),
        "test-college",
        "Test College",
        List.of(domains),
        List.of(),
        neoIdPattern,
        CollegeStatus.PILOT,
        Instant.parse("2026-01-01T00:00:00Z"));
  }
}
