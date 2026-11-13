package com.neobuddy.subscriber;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * A NeoID in normalised form (upper-case, no spaces or hyphens) that is valid for one college's
 * pattern.
 */
public record NeoId(String value) {

  /**
   * Normalises what the student typed and checks it against the college's pattern.
   *
   * @param raw as typed, for example {@code "n1b7 j5v6"}
   * @param collegePattern the college's {@code neo_id_pattern}, for example {@code ^[A-Z0-9]{8}$}
   * @throws InvalidIdentifierException if it does not fit the pattern
   */
  public static NeoId parse(String raw, String collegePattern) {
    String normalized = IdentifierNormalizer.normalize(IdentifierType.NEO_ID, raw);
    Pattern pattern;
    try {
      pattern = Pattern.compile(collegePattern);
    } catch (PatternSyntaxException e) {
      throw new IllegalStateException("The college's NeoID pattern is not a valid expression");
    }
    if (!pattern.matcher(normalized).matches()) {
      throw new InvalidIdentifierException(InvalidIdentifierException.Reason.WRONG_FORMAT);
    }
    return new NeoId(normalized);
  }

  /** Never prints the id. */
  @Override
  public String toString() {
    return "NeoId[hidden]";
  }
}
