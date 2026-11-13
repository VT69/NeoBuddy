package com.neobuddy.ingestion.source;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides whether a sender is one of a college's placement-cell senders. An allowlist entry is
 * either a full address ({@code placements@college.edu}) or a whole domain written with a leading
 * {@code @} ({@code @college.edu}). Matching is exact and case-insensitive: {@code @college.edu}
 * does not admit {@code x@evilcollege.edu} or {@code x@mail.college.edu}.
 *
 * <p>The From header is whatever the sender wrote, so this is a relevance filter, not a proof of
 * origin.
 */
public final class SenderAllowlist {

  private static final Pattern ANGLE_ADDRESS = Pattern.compile("<([^<>]*)>\\s*$");

  private SenderAllowlist() {}

  public static boolean allows(List<String> allowlist, String fromAddress) {
    if (fromAddress == null || fromAddress.isBlank()) {
      return false;
    }
    String address = fromAddress.strip().toLowerCase(Locale.ROOT);
    int at = address.lastIndexOf('@');
    if (at <= 0 || at == address.length() - 1) {
      return false;
    }
    String domain = address.substring(at);
    for (String raw : allowlist) {
      if (raw == null || raw.isBlank()) {
        continue;
      }
      String entry = raw.strip().toLowerCase(Locale.ROOT);
      if (entry.startsWith("@") ? entry.equals(domain) : entry.equals(address)) {
        return true;
      }
    }
    return false;
  }

  /**
   * The bare address from a From header: {@code "Placement Cell <Placements@College.edu>"} gives
   * {@code placements@college.edu}. Empty if there is none.
   */
  public static String addressOf(String fromHeader) {
    if (fromHeader == null) {
      return "";
    }
    String value = fromHeader.strip();
    Matcher angle = ANGLE_ADDRESS.matcher(value);
    if (angle.find()) {
      value = angle.group(1).strip();
    }
    return value.toLowerCase(Locale.ROOT);
  }
}
