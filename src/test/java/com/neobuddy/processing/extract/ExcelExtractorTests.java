package com.neobuddy.processing.extract;

import static com.neobuddy.processing.extract.SyntheticFiles.SHORTLIST;
import static com.neobuddy.processing.extract.SyntheticFiles.WAITLISTED;
import static com.neobuddy.processing.extract.SyntheticFiles.rowText;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.stream.Stream;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.util.unit.DataSize;

class ExcelExtractorTests {

  private final ExcelExtractor extractor = new ExcelExtractor(SyntheticFiles.properties());

  static Stream<Arguments> workbooks() {
    return Stream.of(
        Arguments.of("shortlist.xlsx", SyntheticFiles.xlsx()),
        Arguments.of("shortlist.xls", SyntheticFiles.xls()));
  }

  @ParameterizedTest
  @EnumSource(DetectedType.class)
  void supportsBothExcelFormatsOnly(DetectedType type) {
    assertThat(extractor.supports(type))
        .isEqualTo(type == DetectedType.XLSX || type == DetectedType.XLS);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("workbooks")
  void oneChunkPerNonEmptyRowAcrossAllSheets(String filename, byte[] content) throws Exception {
    List<TextChunk> chunks = TestExtraction.chunks(extractor, filename, content);

    assertThat(chunks)
        .containsExactly(
            new TextChunk(filename, "Shortlist!R1", "NeoID | Name | Reg No | Slot"),
            new TextChunk(filename, "Shortlist!R2", rowText(SHORTLIST.get(0))),
            new TextChunk(filename, "Shortlist!R3", rowText(SHORTLIST.get(1))),
            // Row 4 holds only spaces and is skipped, but row 5 keeps its real number.
            new TextChunk(filename, "Shortlist!R5", rowText(SHORTLIST.get(2))),
            new TextChunk(filename, "Shortlist!R6", "Total | 3"),
            new TextChunk(filename, "Waitlist!R1", "NeoID | Name | Reg No | Slot"),
            new TextChunk(filename, "Waitlist!R2", rowText(WAITLISTED)));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("workbooks")
  void numericRegistrationNumbersKeepTheirDisplayedText(String filename, byte[] content)
      throws Exception {
    List<TextChunk> chunks = TestExtraction.chunks(extractor, filename, content);

    assertThat(chunks)
        .extracting(TextChunk::text)
        .anyMatch(text -> text.contains(" | 2021000123 | "))
        // Twelve digits: must not turn into 2.12346E+11 or 212345678901.0.
        .anyMatch(text -> text.contains(" | 212345678901 | "))
        .noneMatch(text -> text.contains("E+") || text.contains(".0 "));
  }

  @Test
  void largeXlsxGoesThroughTheStreamingReader() throws Exception {
    byte[] content = SyntheticFiles.largeXlsx(3000);
    ExcelExtractor streaming =
        new ExcelExtractor(
            SyntheticFiles.properties(DataSize.ofMegabytes(25), DataSize.ofBytes(1024)));
    assertThat(content.length).isGreaterThan(1024);

    List<TextChunk> streamed = TestExtraction.chunks(streaming, "big.xlsx", content);
    List<TextChunk> inMemory = TestExtraction.chunks(extractor, "big.xlsx", content);

    assertThat(streamed).hasSize(3000).isEqualTo(inMemory);
    assertThat(streamed.get(0))
        .isEqualTo(new TextChunk("big.xlsx", "Big!R1", "T0000001 | Student 1 | 2021000001"));
    assertThat(streamed.get(2999).location()).isEqualTo("Big!R3000");
  }

  @Test
  void streamingReaderHandlesTheMultiSheetFixtureTheSameWay() throws Exception {
    byte[] content = SyntheticFiles.xlsx();
    ExcelExtractor streaming =
        new ExcelExtractor(
            SyntheticFiles.properties(DataSize.ofMegabytes(25), DataSize.ofBytes(1)));

    assertThat(TestExtraction.chunks(streaming, "shortlist.xlsx", content))
        .isEqualTo(TestExtraction.chunks(extractor, "shortlist.xlsx", content));
  }

  @Test
  void workbookWithoutRowsYieldsNoChunks() throws Exception {
    assertThat(TestExtraction.chunks(extractor, "empty.xlsx", SyntheticFiles.xlsxWithoutRows()))
        .isEmpty();
    assertThat(TestExtraction.chunks(extractor, "empty.xls", SyntheticFiles.xlsWithoutRows()))
        .isEmpty();
  }

  @Test
  void zeroByteFileFails() {
    assertThatThrownBy(() -> TestExtraction.chunks(extractor, "empty.xlsx", new byte[0]))
        .isInstanceOf(ExtractionException.class);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("workbooks")
  void truncatedFileFailsWithoutLeakingContent(String filename, byte[] content) {
    assertThatThrownBy(
            () -> TestExtraction.chunks(extractor, filename, SyntheticFiles.truncated(content)))
        .isInstanceOf(ExtractionException.class)
        .hasMessage("Could not read the spreadsheet");
  }

  @Test
  void contentThatIsNotAWorkbookFails() {
    assertThatThrownBy(
            () -> TestExtraction.chunks(extractor, "noise.xlsx", SyntheticFiles.noise(4096)))
        .isInstanceOf(ExtractionException.class);
    assertThatThrownBy(
            () -> TestExtraction.chunks(extractor, "really.xlsx", SyntheticFiles.docxWithTable()))
        .isInstanceOf(ExtractionException.class);
  }

  @Test
  void zipBombIsRejectedByPoisDefaultGuard() {
    // The defaults must stay untouched for the guard to mean anything.
    assertThat(ZipSecureFile.getMinInflateRatio()).isEqualTo(0.01d);

    assertThatThrownBy(
            () -> TestExtraction.chunks(extractor, "bomb.xlsx", SyntheticFiles.zipBombXlsx()))
        .isInstanceOf(ExtractionException.class)
        .rootCause()
        .hasMessageContaining("Zip bomb");
  }
}
