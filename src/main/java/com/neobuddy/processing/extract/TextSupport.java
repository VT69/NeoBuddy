package com.neobuddy.processing.extract;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collection;

/** Small text helpers shared by the extractors. */
final class TextSupport {

  private TextSupport() {}

  private static final char NO_BREAK_SPACE = (char) 0xA0;

  /** Collapses every run of whitespace (including non-breaking spaces) to one space and trims. */
  static String clean(String value) {
    if (value == null || value.isEmpty()) {
      return "";
    }
    StringBuilder out = new StringBuilder(value.length());
    appendClean(out, value);
    return out.toString();
  }

  /** Joins the non-blank cells of a row with {@link TextChunk#CELL_SEPARATOR}. */
  static String joinCells(Collection<String> cells) {
    StringBuilder row = new StringBuilder();
    for (String cell : cells) {
      appendCell(row, cell);
    }
    return row.toString();
  }

  /**
   * Appends one cell to a row being built: cleaned, and preceded by the separator unless it is the
   * first non-blank cell. A blank cell appends nothing.
   */
  static void appendCell(StringBuilder row, String cell) {
    if (cell == null || cell.isEmpty()) {
      return;
    }
    int rowLength = row.length();
    if (rowLength > 0) {
      row.append(TextChunk.CELL_SEPARATOR);
    }
    int cellStart = row.length();
    appendClean(row, cell);
    if (row.length() == cellStart) {
      row.setLength(rowLength);
    }
  }

  /**
   * One pass, no regular expression and no intermediate strings: this runs once per cell of every
   * row of every file.
   */
  private static void appendClean(StringBuilder out, String value) {
    int start = out.length();
    boolean pendingSpace = false;
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c == NO_BREAK_SPACE || Character.isWhitespace(c)) {
        pendingSpace = out.length() > start;
      } else {
        if (pendingSpace) {
          out.append(' ');
          pendingSpace = false;
        }
        out.append(c);
      }
    }
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
