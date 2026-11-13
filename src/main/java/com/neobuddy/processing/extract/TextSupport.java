package com.neobuddy.processing.extract;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.stream.Collectors;

/** Small text helpers shared by the extractors. */
final class TextSupport {

  private TextSupport() {}

  /** Collapses every run of whitespace (including non-breaking spaces) to one space and trims. */
  static String clean(String value) {
    return value == null ? "" : value.replaceAll("[\\s\\u00A0]+", " ").strip();
  }

  /** Joins the non-blank cells of a row with {@link TextChunk#CELL_SEPARATOR}. */
  static String joinCells(Collection<String> cells) {
    return cells.stream()
        .map(TextSupport::clean)
        .filter(cell -> !cell.isEmpty())
        .collect(Collectors.joining(TextChunk.CELL_SEPARATOR));
  }

  /**
   * Decodes text as UTF-8, or UTF-16 when it starts with a UTF-16 byte order mark. The byte order
   * mark is dropped and malformed bytes become U+FFFD instead of failing.
   */
  static String decode(byte[] bytes) {
    if (startsWith(bytes, 0xEF, 0xBB, 0xBF)) {
      return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
    }
    if (startsWith(bytes, 0xFE, 0xFF)) {
      return decodeFrom(bytes, 2, StandardCharsets.UTF_16BE);
    }
    if (startsWith(bytes, 0xFF, 0xFE)) {
      return decodeFrom(bytes, 2, StandardCharsets.UTF_16LE);
    }
    return new String(bytes, StandardCharsets.UTF_8);
  }

  private static String decodeFrom(byte[] bytes, int offset, Charset charset) {
    return new String(bytes, offset, bytes.length - offset, charset);
  }

  private static boolean startsWith(byte[] bytes, int... prefix) {
    if (bytes.length < prefix.length) {
      return false;
    }
    for (int i = 0; i < prefix.length; i++) {
      if ((bytes[i] & 0xFF) != prefix[i]) {
        return false;
      }
    }
    return true;
  }
}
