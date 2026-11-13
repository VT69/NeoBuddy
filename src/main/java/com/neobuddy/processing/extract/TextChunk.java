package com.neobuddy.processing.extract;

/**
 * One matchable piece of text: a spreadsheet or table row, or a line or block of prose.
 *
 * @param source where the text came from: the attachment filename, or {@code "body"}
 * @param location position inside the source, for example {@code "Sheet1!R42"} or {@code "p3:L17"}
 * @param text the text; for rows, the non-blank cells joined with {@link #CELL_SEPARATOR}
 */
public record TextChunk(String source, String location, String text) {

  /** Separator between the cells of a row chunk. */
  public static final String CELL_SEPARATOR = " | ";

  /** Never prints the text: it can hold the data of other students. */
  @Override
  public String toString() {
    return "TextChunk[source=" + source + ", location=" + location + "]";
  }
}
