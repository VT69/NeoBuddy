package com.neobuddy.processing.extract;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

/**
 * Reads CSV attachments: one chunk per row.
 *
 * <h2>Contract</h2>
 *
 * <ul>
 *   <li><b>Type.</b> {@link #supports} is true for {@link DetectedType#CSV} only.
 *   <li><b>Encoding.</b> The content is UTF-8. A leading byte order mark is dropped and never
 *       appears in a chunk ({@link TextSupport#decode} does this).
 *   <li><b>Delimiter.</b> Comma or semicolon, detected per file: whichever of the two occurs more
 *       often outside quotes in the first non-blank line. Comma wins a tie, including zero-zero (a
 *       single-column file).
 *   <li><b>Quoting.</b> RFC 4180: a field in double quotes may contain the delimiter, line breaks
 *       and doubled quotes ({@code ""} is one {@code "}). The quotes are not part of the value.
 *   <li><b>Line endings.</b> LF, CRLF and CR all end a row. No chunk text contains CR or LF; a line
 *       break inside a quoted field becomes a single space.
 *   <li><b>Rows to chunks.</b> One chunk per row that has at least one non-blank cell. Each cell is
 *       trimmed, blank cells are dropped, and the rest are joined with {@link
 *       TextChunk#CELL_SEPARATOR}, exactly as {@link TextSupport#joinCells} does. The header row is
 *       an ordinary row.
 *   <li><b>Location.</b> {@code "R" + n}, where n is the 1-based row number as a spreadsheet would
 *       show it: blank lines and rows with only blank cells still use up a number, they just emit
 *       no chunk. So in {@code "a\n\nb\n"} the chunks are at R1 and R3.
 *   <li><b>Source.</b> {@link TextChunk#source()} is {@link AttachmentRef#filename()}.
 *   <li><b>Empty input.</b> Zero bytes, or only blank lines, yields an empty stream.
 *   <li><b>Malformed input.</b> A quoted field that is never closed throws {@link
 *       ExtractionException}. So does any I/O failure. Exception messages never contain content.
 *   <li><b>Size.</b> A 50,000-row file extracts in well under a second.
 *   <li><b>State.</b> The class is stateless and safe to call from several threads.
 * </ul>
 */
@Component
public class CsvExtractor implements ContentExtractor {

  private static final char COMMA = ',';
  private static final char SEMICOLON = ';';
  private static final char QUOTE = '"';

  /** RFC 4180 already keeps blank lines as records, which is what keeps row numbers honest. */
  private static final CSVFormat COMMA_FORMAT = CSVFormat.RFC4180;

  private static final CSVFormat SEMICOLON_FORMAT =
      CSVFormat.RFC4180.builder().setDelimiter(SEMICOLON).get();

  @Override
  public boolean supports(DetectedType type) {
    return type == DetectedType.CSV;
  }

  @Override
  public Stream<TextChunk> extract(InputStream in, AttachmentRef attachment)
      throws ExtractionException {
    try {
      String text = TextSupport.decode(in.readAllBytes());
      CSVFormat format = detectDelimiter(text) == SEMICOLON ? SEMICOLON_FORMAT : COMMA_FORMAT;
      List<TextChunk> chunks = new ArrayList<>();
      StringBuilder joined = new StringBuilder();
      try (CSVParser parser = format.parse(new StringReader(text))) {
        for (CSVRecord row : parser) {
          joined.setLength(0);
          for (int i = 0; i < row.size(); i++) {
            TextSupport.appendCell(joined, row.get(i));
          }
          if (joined.length() > 0) {
            // Every row is a record, blank ones included, so the record number is the row number.
            chunks.add(
                new TextChunk(
                    attachment.filename(), "R" + row.getRecordNumber(), joined.toString()));
          }
        }
      }
      return chunks.stream();
    } catch (IOException | RuntimeException e) {
      // Commons CSV reports malformed input (such as an unclosed quote) from the iterator.
      throw new ExtractionException("Could not read the CSV", e);
    }
  }

  /**
   * Counts commas and semicolons outside quotes in the first non-blank line. Only that line is
   * scanned, so this costs nothing on a large file.
   */
  static char detectDelimiter(String text) {
    int commas = 0;
    int semicolons = 0;
    boolean quoted = false;
    boolean content = false;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == QUOTE) {
        quoted = !quoted;
        content = true;
      } else if (quoted) {
        continue;
      } else if (c == '\n' || c == '\r') {
        if (content) {
          break;
        }
      } else if (c == COMMA) {
        commas++;
        content = true;
      } else if (c == SEMICOLON) {
        semicolons++;
        content = true;
      } else if (!Character.isWhitespace(c)) {
        content = true;
      }
    }
    return semicolons > commas ? SEMICOLON : COMMA;
  }
}
