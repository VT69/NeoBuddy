package com.neobuddy.processing.classify;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Finds any of a fixed list of words or phrases in text. Compiled once per list.
 *
 * <p>Matching is case-insensitive and whole-word: {@code offer} matches "Offer!" but not "offering"
 * or "coffers". Inside a phrase, any run of spaces or hyphens matches any other, so {@code short
 * list} also finds "short-list" and "short list".
 */
final class KeywordMatcher {

  private static final Pattern GAP = Pattern.compile("[\\s\\-]+");
  private static final String NOT_AFTER_WORD = "(?<![\\p{L}\\p{N}])";
  private static final String NOT_BEFORE_WORD = "(?![\\p{L}\\p{N}])";

  private final Pattern pattern;
  private final List<String> keywords;

  KeywordMatcher(Collection<String> keywords) {
    // Longest first, so "offer letter" is preferred over "offer" where both could match.
    this.keywords =
        keywords.stream()
            .filter(keyword -> keyword != null && !keyword.isBlank())
            .map(String::strip)
            .distinct()
            .sorted(Comparator.comparingInt(String::length).reversed())
            .toList();
    this.pattern =
        this.keywords.isEmpty()
            ? null
            : Pattern.compile(
                this.keywords.stream()
                    .map(KeywordMatcher::phrase)
                    .collect(
                        Collectors.joining("|", NOT_AFTER_WORD + "(?:", ")" + NOT_BEFORE_WORD)),
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
  }

  boolean matches(String text) {
    return pattern != null && text != null && pattern.matcher(text).find();
  }

  /** The configured keyword (in its configured spelling) that occurs first in the text. */
  Optional<String> firstMatch(String text) {
    if (pattern == null || text == null) {
      return Optional.empty();
    }
    Matcher matcher = pattern.matcher(text);
    if (!matcher.find()) {
      return Optional.empty();
    }
    String found = GAP.matcher(matcher.group()).replaceAll(" ");
    return keywords.stream()
        .filter(keyword -> GAP.matcher(keyword).replaceAll(" ").equalsIgnoreCase(found))
        .findFirst();
  }

  private static String phrase(String keyword) {
    return GAP.splitAsStream(keyword).map(Pattern::quote).collect(Collectors.joining("[\\s\\-]+"));
  }
}
