package com.neobuddy.processing.extract;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/** The contract in {@link CsvExtractor}'s Javadoc, as tests. All data is synthetic. */
@Disabled("T3 - Vaibhav implements")
class CsvExtractorTests {

  private static final String FILE = "shortlist.csv";

  private final CsvExtractor extractor = new CsvExtractor();

  @ParameterizedTest
  @EnumSource(DetectedType.class)
  void supportsCsvOnly(DetectedType type) {
    assertThat(extractor.supports(type)).isEqualTo(type == DetectedType.CSV);
  }

  @Test
  void oneChunkPerRowWithCellsJoinedAndRowLocations() throws Exception {
    List<TextChunk> chunks =
        extract(
            """
            NeoID,Name,Reg No,Slot
            Z9X8C7V6,Asha Example,2021000123,10:00
            Q1W2E3R4,Ravi Sample,2021000456,10:30
            """);

    assertThat(chunks)
        .containsExactly(
            new TextChunk(FILE, "R1", "NeoID | Name | Reg No | Slot"),
            new TextChunk(FILE, "R2", "Z9X8C7V6 | Asha Example | 2021000123 | 10:00"),
            new TextChunk(FILE, "R3", "Q1W2E3R4 | Ravi Sample | 2021000456 | 10:30"));
  }

  @Test
  void quotedFieldsMayContainCommasAndDoubledQuotes() throws Exception {
    List<TextChunk> chunks =
        extract(
            """
            NeoID,Name,Remark
            Z9X8C7V6,"Example, Asha","said ""yes"", twice"
            """);

    assertThat(chunks)
        .element(1)
        .isEqualTo(new TextChunk(FILE, "R2", "Z9X8C7V6 | Example, Asha | said \"yes\", twice"));
  }

  @Test
  void aLineBreakInsideAQuotedFieldBecomesASpace() throws Exception {
    List<TextChunk> chunks = extract("Z9X8C7V6,\"Asha\nExample\",10:00\n");

    assertThat(chunks)
        .containsExactly(new TextChunk(FILE, "R1", "Z9X8C7V6 | Asha Example | 10:00"));
  }

  @Test
  void utf8ByteOrderMarkIsDropped() throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    bytes.write(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
    bytes.write("NeoID,Name\nZ9X8C7V6,Asha Example\n".getBytes(UTF_8));

    List<TextChunk> chunks = extract(bytes.toByteArray());

    assertThat(chunks)
        .containsExactly(
            new TextChunk(FILE, "R1", "NeoID | Name"),
            new TextChunk(FILE, "R2", "Z9X8C7V6 | Asha Example"));
    assertThat(chunks.get(0).text()).doesNotContain("﻿");
  }

  @Test
  void nonAsciiTextIsReadAsUtf8() throws Exception {
    assertThat(extract("Z9X8C7V6,Zoë Próba\n"))
        .containsExactly(new TextChunk(FILE, "R1", "Z9X8C7V6 | Zoë Próba"));
  }

  @Test
  void semicolonDelimiterIsDetected() throws Exception {
    List<TextChunk> chunks =
        extract(
            """
            NeoID;Name;CGPA
            Z9X8C7V6;Asha Example;8,75
            Q1W2E3R4;"Sample; Ravi";9,10
            """);

    assertThat(chunks)
        .containsExactly(
            new TextChunk(FILE, "R1", "NeoID | Name | CGPA"),
            new TextChunk(FILE, "R2", "Z9X8C7V6 | Asha Example | 8,75"),
            new TextChunk(FILE, "R3", "Q1W2E3R4 | Sample; Ravi | 9,10"));
  }

  @Test
  void delimiterInsideQuotesDoesNotCountForDetection() throws Exception {
    // One comma outside quotes, three semicolons inside: this is a comma file.
    assertThat(extract("\"a;b;c;d\",Z9X8C7V6\n"))
        .containsExactly(new TextChunk(FILE, "R1", "a;b;c;d | Z9X8C7V6"));
  }

  @Test
  void aSingleColumnFileIsOneCellPerRow() throws Exception {
    assertThat(extract("NeoID\nZ9X8C7V6\nQ1W2E3R4\n"))
        .extracting(TextChunk::text)
        .containsExactly("NeoID", "Z9X8C7V6", "Q1W2E3R4");
  }

  @Test
  void blankLinesEmitNothingButStillCountAsRows() throws Exception {
    List<TextChunk> chunks =
        extract("\nNeoID,Name\n\n\nZ9X8C7V6,Asha Example\n   \n,,\nQ1W2E3R4,Ravi\n\n");

    assertThat(chunks)
        .containsExactly(
            new TextChunk(FILE, "R2", "NeoID | Name"),
            new TextChunk(FILE, "R5", "Z9X8C7V6 | Asha Example"),
            new TextChunk(FILE, "R8", "Q1W2E3R4 | Ravi"));
  }

  @Test
  void blankCellsAreDroppedAndCellsAreTrimmed() throws Exception {
    assertThat(extract("  Z9X8C7V6 ,, Asha Example ,   ,10:00\n"))
        .containsExactly(new TextChunk(FILE, "R1", "Z9X8C7V6 | Asha Example | 10:00"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"\r\n", "\n", "\r"})
  void everyLineEndingWorksAndNeverLeaksIntoText(String eol) throws Exception {
    List<TextChunk> chunks =
        extract("NeoID,Name" + eol + "Z9X8C7V6,Asha Example" + eol + "Q1W2E3R4,Ravi Sample" + eol);

    assertThat(chunks)
        .containsExactly(
            new TextChunk(FILE, "R1", "NeoID | Name"),
            new TextChunk(FILE, "R2", "Z9X8C7V6 | Asha Example"),
            new TextChunk(FILE, "R3", "Q1W2E3R4 | Ravi Sample"));
    assertThat(chunks)
        .noneMatch(chunk -> chunk.text().contains("\r") || chunk.text().contains("\n"));
  }

  @Test
  void aLastRowWithoutALineEndingIsStillRead() throws Exception {
    assertThat(extract("NeoID,Name\r\nZ9X8C7V6,Asha Example"))
        .last()
        .isEqualTo(new TextChunk(FILE, "R2", "Z9X8C7V6 | Asha Example"));
  }

  @Test
  void fiftyThousandRows() {
    int rows = 50_000;
    StringBuilder csv = new StringBuilder("NeoID,Name,Reg No\r\n");
    for (int i = 1; i <= rows; i++) {
      csv.append(String.format("T%07d,Student %d,%d\r\n", i, i, 2021000000L + i));
    }
    byte[] bytes = csv.toString().getBytes(UTF_8);

    List<TextChunk> chunks =
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> extract(bytes));

    assertThat(chunks).hasSize(rows + 1);
    assertThat(chunks.get(0)).isEqualTo(new TextChunk(FILE, "R1", "NeoID | Name | Reg No"));
    assertThat(chunks.get(rows))
        .isEqualTo(new TextChunk(FILE, "R50001", "T0050000 | Student 50000 | 2021050000"));
    assertThat(chunks.get(12_345).location()).isEqualTo("R12346");
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "\n", "\r\n\r\n", "   \n\t\n", ",,,\n,,\n"})
  void emptyOrBlankInputYieldsNoChunks(String csv) throws Exception {
    assertThat(extract(csv)).isEmpty();
  }

  @Test
  void anUnterminatedQuoteIsMalformed() {
    assertThatThrownBy(() -> extract("NeoID,Name\nZ9X8C7V6,\"Asha Example\nQ1W2E3R4,Ravi\n"))
        .isInstanceOf(ExtractionException.class)
        .hasMessageNotContaining("Z9X8C7V6")
        .hasMessageNotContaining("Asha");
  }

  private List<TextChunk> extract(String csv) throws ExtractionException {
    return extract(csv.getBytes(UTF_8));
  }

  private List<TextChunk> extract(byte[] csv) throws ExtractionException {
    AttachmentRef attachment = new AttachmentRef(FILE, "text/csv", csv.length);
    try (Stream<TextChunk> chunks = extractor.extract(new ByteArrayInputStream(csv), attachment)) {
      return chunks.toList();
    }
  }
}
