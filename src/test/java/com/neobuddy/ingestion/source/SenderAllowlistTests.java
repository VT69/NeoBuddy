package com.neobuddy.ingestion.source;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class SenderAllowlistTests {

  private static final List<String> ALLOWLIST =
      List.of("placements@college.example", "@cell.college.example", " TPO@College.Example ");

  @ParameterizedTest
  @ValueSource(
      strings = {
        "placements@college.example",
        "Placements@College.EXAMPLE",
        "  placements@college.example  ",
        "anyone@cell.college.example",
        "Head.Of.Placements@Cell.College.Example",
        "tpo@college.example",
      })
  void allowsAnExactAddressOrAWholeDomain(String sender) {
    assertThat(SenderAllowlist.allows(ALLOWLIST, sender)).isTrue();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "student@college.example",
        "placements@evilcollege.example",
        "placements@college.example.attacker.example",
        "xplacements@college.example",
        "anyone@sub.cell.college.example",
        "anyone@evilcell.college.example",
        "cell.college.example",
        "@cell.college.example",
        "anyone@",
        "placements",
      })
  void rejectsEverythingElse(String sender) {
    assertThat(SenderAllowlist.allows(ALLOWLIST, sender)).isFalse();
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"   "})
  void rejectsAMissingSender(String sender) {
    assertThat(SenderAllowlist.allows(ALLOWLIST, sender)).isFalse();
  }

  @Test
  void anEmptyAllowlistAllowsNobody() {
    assertThat(SenderAllowlist.allows(List.of(), "placements@college.example")).isFalse();
    assertThat(SenderAllowlist.allows(List.of("", " "), "placements@college.example")).isFalse();
  }

  @ParameterizedTest(name = "[{0}] -> {1}")
  @CsvSource(
      delimiter = '|',
      value = {
        "placements@college.example | placements@college.example",
        "Placement Cell <Placements@College.Example> | placements@college.example",
        "\"Cell, Placement\" <placements@college.example> | placements@college.example",
        "'  <placements@college.example>  ' | placements@college.example",
        "A <fake@evil.example> B <placements@college.example> | placements@college.example",
      })
  void takesTheBareAddressFromAFromHeader(String header, String expected) {
    assertThat(SenderAllowlist.addressOf(header)).isEqualTo(expected);
  }

  @Test
  void aMissingFromHeaderHasNoAddress() {
    assertThat(SenderAllowlist.addressOf(null)).isEmpty();
    assertThat(SenderAllowlist.allows(ALLOWLIST, SenderAllowlist.addressOf(null))).isFalse();
  }
}
