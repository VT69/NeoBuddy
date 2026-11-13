package com.neobuddy.processing.extract;

import static com.neobuddy.processing.extract.SyntheticFiles.SHORTLIST;
import static com.neobuddy.processing.extract.SyntheticFiles.WAITLISTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neobuddy.processing.extract.SyntheticFiles.Student;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class PdfExtractorTests {

  private static final String FILE = "shortlist.pdf";

  private final PdfExtractor extractor = new PdfExtractor();

  @ParameterizedTest
  @EnumSource(DetectedType.class)
  void supportsPdfOnly(DetectedType type) {
    assertThat(extractor.supports(type)).isEqualTo(type == DetectedType.PDF);
  }

  @Test
  void oneChunkPerLineWithPageAndLineLocations() throws Exception {
    List<TextChunk> chunks = TestExtraction.chunks(extractor, FILE, SyntheticFiles.pdfWithTable());

    assertThat(chunks).hasSize(6).allMatch(chunk -> chunk.source().equals(FILE));
    assertThat(chunks)
        .extracting(TextChunk::location)
        .containsExactly("p1:L1", "p1:L2", "p1:L3", "p1:L4", "p1:L5", "p2:L1");
    assertThat(chunks.get(0).text()).isEqualTo("Example Corp shortlist");
    assertThat(chunks.get(1).text()).isEqualTo("NeoID Name Reg No Slot");
  }

  @Test
  void eachTableRowStaysTogetherOnOneLine() throws Exception {
    List<TextChunk> chunks = TestExtraction.chunks(extractor, FILE, SyntheticFiles.pdfWithTable());

    for (int i = 0; i < SHORTLIST.size(); i++) {
      Student student = SHORTLIST.get(i);
      assertThat(chunks.get(i + 2).text())
          .isEqualTo(
              student.neoId()
                  + " "
                  + student.name()
                  + " "
                  + student.regNo()
                  + " "
                  + student.slot());
    }
    assertThat(chunks.get(5).text()).startsWith(WAITLISTED.neoId()).contains(WAITLISTED.name());
    // No line mixes two students.
    assertThat(chunks)
        .noneMatch(chunk ->
            chunk.text().contains(SHORTLIST.get(0).neoId())
                && chunk.text().contains(SHORTLIST.get(1).neoId()));
  }

  @Test
  void pdfWithoutTextYieldsNoChunks() throws Exception {
    assertThat(TestExtraction.chunks(extractor, "blank.pdf", SyntheticFiles.pdfWithBlankPage()))
        .isEmpty();
  }

  @Test
  void zeroByteFileFails() {
    assertThatThrownBy(() -> TestExtraction.chunks(extractor, "empty.pdf", new byte[0]))
        .isInstanceOf(ExtractionException.class);
  }

  @Test
  void corruptFileFailsWithoutLeakingContent() {
    assertThatThrownBy(
            () -> TestExtraction.chunks(extractor, "broken.pdf", SyntheticFiles.corruptPdf()))
        .isInstanceOf(ExtractionException.class)
        .hasMessage("Could not read the PDF");
  }

  @Test
  void contentThatIsNotAPdfFails() {
    assertThatThrownBy(() -> TestExtraction.chunks(extractor, "really.pdf", SyntheticFiles.xlsx()))
        .isInstanceOf(ExtractionException.class);
  }
}
