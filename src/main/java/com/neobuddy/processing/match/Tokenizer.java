package com.neobuddy.processing.match;

import com.neobuddy.processing.extract.TextChunk;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Turns the text of one {@link com.neobuddy.processing.extract.TextChunk} into the set of tokens
 * that are looked up in the identifier index.
 *
 * <h2>Contract</h2>
 *
 * The result is the union of three independent passes over the same input. Order is irrelevant.
 * "Alphanumeric" means ASCII letters and digits only ({@code [A-Za-z0-9]}); every other character,
 * including accented letters and underscores, is a separator.
 *
 * <ol type="a">
 *   <li><b>Email addresses.</b> Every email address in the text is emitted whole and lower-cased:
 *       {@code "Write to Asha.Example@College.EDU."} yields {@code asha.example@college.edu}. An
 *       address is {@code local@domain} where local is one or more of {@code [A-Za-z0-9._%+-]}, and
 *       domain is dot-separated labels of {@code [A-Za-z0-9-]} ending in a label of at least two
 *       letters. Punctuation around the address (angle brackets, a trailing full stop or comma) is
 *       not part of it.
 *   <li><b>Words.</b> The whole text, email addresses included, is split on every non-alphanumeric
 *       character. Each piece of length 4 or more is emitted upper-cased. Shorter pieces are
 *       dropped. There is no upper length limit. {@code "neo-id: z9x8c7v6, cse"} yields {@code
 *       Z9X8C7V6} only.
 *   <li><b>Cells.</b> The text is split on the cell separator {@code " | "} ({@link
 *       com.neobuddy.processing.extract.TextChunk#CELL_SEPARATOR}); text without a separator is one
 *       cell. From each cell, all spaces and hyphens are removed and the rest is upper-cased. If
 *       what remains is 4 to 20 characters long and entirely alphanumeric it is emitted. This is
 *       what makes {@code "n1b7 j5v6"} and {@code "N1B7-J5V6"} both yield {@code N1B7J5V6}. A cell
 *       with any other character left in it (a dot, a slash, a colon) emits nothing in this pass.
 * </ol>
 *
 * <p>Upper- and lower-casing use {@link java.util.Locale#ROOT}. Null, empty or blank input yields
 * an empty set. The returned set never contains null or an empty string. The class is stateless and
 * safe to call from several threads.
 *
 * <p>Tokens are matched whole against normalised identifiers, never as substrings, so the tokenizer
 * must not emit fragments of a longer alphanumeric run: {@code "Z9X8C7V6X"} yields {@code
 * Z9X8C7V6X} and not {@code Z9X8C7V6}.
 */
@Component
public class Tokenizer {

  private static final int MIN_WORD_LENGTH = 4;
  private static final int MIN_CELL_LENGTH = 4;
  private static final int MAX_CELL_LENGTH = 20;

  /**
   * The local part is possessive ({@code ++}): it can never give characters back to find an
   * {@code @}, so a long run without one is scanned once instead of once per start position.
   */
  private static final Pattern EMAIL =
      Pattern.compile("[A-Za-z0-9._%+-]++@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}");

  private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^A-Za-z0-9]+");

  private static final Pattern CELL_SEPARATOR =
      Pattern.compile(TextChunk.CELL_SEPARATOR, Pattern.LITERAL);

  /**
   * Tokenizes the text of one chunk, following the class contract.
   *
   * @param text chunk text, may be null
   * @return the tokens; never null, possibly empty
   */
  public Set<String> tokenize(String text) {
    Set<String> tokens = new HashSet<>();
    if (text == null || text.isBlank()) {
      return tokens;
    }
    addEmailAddresses(text, tokens);
    addWords(text, tokens);
    addCells(text, tokens);
    return tokens;
  }

  private static void addEmailAddresses(String text, Set<String> tokens) {
    if (text.indexOf('@') < 0) {
      return;
    }
    Matcher matcher = EMAIL.matcher(text);
    while (matcher.find()) {
      tokens.add(matcher.group().toLowerCase(Locale.ROOT));
    }
  }

  private static void addWords(String text, Set<String> tokens) {
    for (String word : NON_ALPHANUMERIC.split(text)) {
      if (word.length() >= MIN_WORD_LENGTH) {
        tokens.add(word.toUpperCase(Locale.ROOT));
      }
    }
  }

  private static void addCells(String text, Set<String> tokens) {
    for (String cell : CELL_SEPARATOR.split(text)) {
      String normalized = normalizeCell(cell);
      if (normalized != null) {
        tokens.add(normalized);
      }
    }
  }

  /**
   * The cell without spaces and hyphens, upper-cased, or null if what is left is not 4 to 20
   * alphanumerics. One pass over the characters, no regular expression.
   */
  private static String normalizeCell(String cell) {
    StringBuilder kept = new StringBuilder(Math.min(cell.length(), MAX_CELL_LENGTH));
    for (int i = 0; i < cell.length(); i++) {
      char c = cell.charAt(i);
      if (c == ' ' || c == '-') {
        continue;
      }
      if (!isAsciiAlphanumeric(c) || kept.length() == MAX_CELL_LENGTH) {
        return null;
      }
      kept.append(c);
    }
    return kept.length() < MIN_CELL_LENGTH ? null : kept.toString().toUpperCase(Locale.ROOT);
  }

  private static boolean isAsciiAlphanumeric(char c) {
    return (c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
  }
}
