package com.neobuddy.processing.extract;

import static com.neobuddy.processing.extract.SyntheticFiles.SHORTLIST;
import static com.neobuddy.processing.extract.SyntheticFiles.rowText;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class DocxExtractorTests {

  private static final String FILE = "shortlist.docx";

  private final DocxExtractor extractor = new DocxExtractor();

  @ParameterizedTest
  @EnumSource(DetectedType.class)
  void supportsDocxOnly(DetectedType type) {
    assertThat(extractor.supports(type)).isEqualTo(type == DetectedType.DOCX);
  }

  @Test
  void paragraphsAndTableRowsInDocumentOrder() throws Exception {
    assertThat(TestExtraction.chunks(extractor, FILE, SyntheticFiles.docxWithTable()))
        .containsExactly(
            new TextChunk(FILE, "P1", "Example Corp shortlist"),
            // P2 is blank and skipped, but the next paragraph keeps its real number.
            new TextChunk(FILE, "T1!R1", "NeoID | Name | Reg No | Slot"),
            new TextChunk(FILE, "T1!R2", rowText(SHORTLIST.get(0))),
            new TextChunk(FILE, "T1!R3", rowText(SHORTLIST.get(1))),
            new TextChunk(FILE, "T1!R4", rowText(SHORTLIST.get(2))),
            new TextChunk(FILE, "P3", "Report to the placement cell by 9:30."));
  }

  @Test
  void documentWithoutContentYieldsNoChunks() throws Exception {
    assertThat(TestExtraction.chunks(extractor, "empty.docx", SyntheticFiles.docxWithoutContent()))
        .isEmpty();
  }

  @Test
  void zeroByteFileFails() {
    assertThatThrownBy(() -> TestExtraction.chunks(extractor, "empty.docx", new byte[0]))
        .isInstanceOf(ExtractionException.class);
  }

  @Test
  void truncatedFileFailsWithoutLeakingContent() {
    byte[] truncated = SyntheticFiles.truncated(SyntheticFiles.docxWithTable());

    assertThatThrownBy(() -> TestExtraction.chunks(extractor, "broken.docx", truncated))
        .isInstanceOf(ExtractionException.class)
        .hasMessage("Could not read the Word document");
  }

  @Test
  void contentThatIsNotAWordDocumentFails() {
    assertThatThrownBy(() -> TestExtraction.chunks(extractor, "really.docx", SyntheticFiles.xlsx()))
        .isInstanceOf(ExtractionException.class);
    assertThatThrownBy(
            () -> TestExtraction.chunks(extractor, "noise.docx", SyntheticFiles.noise(4096)))
        .isInstanceOf(ExtractionException.class);
  }
}
