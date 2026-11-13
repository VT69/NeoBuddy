package com.neobuddy.subscriber;

import com.neobuddy.shared.college.College;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Brings identifiers into the one form they are stored and matched in, and checks them against the
 * rules of the student's college.
 *
 * <p>The form mirrors what the tokenizer produces from email text, so a stored identifier and a
 * token can be compared for equality: NeoIDs and registration numbers are trimmed, upper-cased and
 * stripped of spaces and hyphens ({@code "n1b7 j5v6"} becomes {@code N1B7J5V6}); college email
 * addresses are trimmed and lower-cased.
 */
public final class IdentifierNormalizer {

  /**
   * What the tokenizer can find: 4 to 20 ASCII letters and digits. An identifier outside this could
   * be stored but would never match anything, so it is rejected up front.
   */
  private static final Pattern MATCHABLE = Pattern.compile("[A-Z0-9]{4,20}");

  private static final Pattern EMAIL =
      Pattern.compile("[a-z0-9._%+-]+@[a-z0-9-]+(?:\\.[a-z0-9-]+)*\\.[a-z]{2,}");

  private IdentifierNormalizer() {}

  /** Normalises without validating. Null becomes the empty string. */
  public static String normalize(IdentifierType type, String raw) {
    if (raw == null) {
      return "";
    }
    String trimmed = raw.strip();
    if (type == IdentifierType.COLLEGE_EMAIL) {
      return trimmed.toLowerCase(Locale.ROOT);
    }
    StringBuilder out = new StringBuilder(trimmed.length());
    for (int i = 0; i < trimmed.length(); i++) {
      char c = trimmed.charAt(i);
      if (!Character.isWhitespace(c) && c != '-') {
        out.append(c);
      }
    }
    return out.toString().toUpperCase(Locale.ROOT);
  }

  /**
   * Normalises and validates an identifier for a student of this college.
   *
   * @return the normalised value to store
   * @throws InvalidIdentifierException if it breaks a rule; the exception never holds the value
   */
  public static String normalizeAndValidate(College college, IdentifierType type, String raw) {
    String value = normalize(type, raw);
    if (value.isEmpty()) {
      throw new InvalidIdentifierException(InvalidIdentifierException.Reason.BLANK);
    }
    switch (type) {
      case NEO_ID -> {
        NeoId.parse(value, college.getNeoIdPattern());
        requireMatchable(value);
      }
      case REG_NO -> requireMatchable(value);
      case COLLEGE_EMAIL -> requireCollegeEmail(college, value);
    }
    return value;
  }

  private static void requireMatchable(String value) {
    if (!MATCHABLE.matcher(value).matches()) {
      throw new InvalidIdentifierException(InvalidIdentifierException.Reason.WRONG_FORMAT);
    }
  }

  private static void requireCollegeEmail(College college, String email) {
    if (!EMAIL.matcher(email).matches()) {
      throw new InvalidIdentifierException(InvalidIdentifierException.Reason.WRONG_FORMAT);
    }
    if (!isCollegeEmail(college, email)) {
      throw new InvalidIdentifierException(InvalidIdentifierException.Reason.NOT_A_COLLEGE_EMAIL);
    }
  }

  /**
   * True if the address is on one of the college's sign-in domains (exact domain, not a suffix).
   */
  public static boolean isCollegeEmail(College college, String email) {
    String domain = domainOf(email);
    return !domain.isEmpty()
        && college.getAllowedEmailDomains().stream()
            .anyMatch(allowed -> allowed.strip().equalsIgnoreCase(domain));
  }

  /** The part after the last {@code @}, lower-cased; empty if there is none. */
  public static String domainOf(String email) {
    if (email == null) {
      return "";
    }
    int at = email.lastIndexOf('@');
    return at < 0 ? "" : email.substring(at + 1).strip().toLowerCase(Locale.ROOT);
  }
}
