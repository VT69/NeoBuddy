package com.neobuddy.processing.extract;

import static com.neobuddy.processing.extract.SyntheticFiles.SHORTLIST;
import static com.neobuddy.processing.extract.SyntheticFiles.rowText;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class HtmlBodyExtractorTests {

  private static final String FILE = "body";

  private final HtmlBodyExtractor extractor = new HtmlBodyExtractor();

  @ParameterizedTest
  @EnumSource(DetectedType.class)
  void supportsHtmlOnly(DetectedType type) {
    assertThat(extractor.supports(type)).isEqualTo(type == DetectedType.HTML);
  }

  @Test
  void tableRowsAndTextBlocksOfARealisticEmail() throws Exception {
    assertThat(extract(SyntheticFiles.htmlEmail()))
        .containsExactly(
            new TextChunk(FILE, "B1", "Dear students,"),
            new TextChunk(FILE, "B2", "The following are shortlisted for Example Corp."),
            new TextChunk(FILE, "T1!R1", "NeoID | Name | Reg No | Slot"),
            new TextChunk(FILE, "T1!R2", rowText(SHORTLIST.get(0))),
            new TextChunk(FILE, "T1!R3", rowText(SHORTLIST.get(1))),
            new TextChunk(FILE, "T1!R4", rowText(SHORTLIST.get(2))),
            new TextChunk(FILE, "B3", "Waitlist:"),
            new TextChunk(FILE, "B4", "K3J4H5G6 - Dev Fictional"),
            new TextChunk(FILE, "B5", "Slots may change."),
            // The footer is a plain row of the outer layout table, so it is a row chunk.
            new TextChunk(FILE, "T2!R1", "Regards, Placement Cell"));
  }

  @Test
  void layoutTablesNeverProduceAChunkHoldingSeveralStudents() throws Exception {
    assertThat(extract(SyntheticFiles.htmlEmail()))
        .noneMatch(chunk ->
            chunk.text().contains(SHORTLIST.get(0).neoId())
                && chunk.text().contains(SHORTLIST.get(1).neoId()));
  }

  @Test
  void headScriptAndStyleAreIgnored() throws Exception {
    assertThat(extract(SyntheticFiles.htmlEmail()))
        .extracting(TextChunk::text)
        .noneMatch(text ->
            text.contains("not body text")
                || text.contains("script-text")
                || text.contains("secret-style"));
  }

  @Test
  void inlineMarkupDoesNotSplitAToken() throws Exception {
    assertThat(extract("<p>ID <b>Z9X8</b><i>C7V6</i> is <span>short</span>listed</p>"))
        .containsExactly(new TextChunk(FILE, "B1", "ID Z9X8C7V6 is shortlisted"));
  }

  @Test
  void listItemsAndHeadingsAreSeparateBlocks() throws Exception {
    assertThat(extract("<h2>Shortlist</h2><ul><li>Z9X8C7V6</li><li>Q1W2E3R4</li></ul>trailing"))
        .extracting(TextChunk::text)
        .containsExactly("Shortlist", "Z9X8C7V6", "Q1W2E3R4", "trailing");
  }

  @Test
  void rowsAreNumberedPerTableAndBlankRowsStillCount() throws Exception {
    String html =
        """
        <table><tr><td>a1</td><td>a2</td></tr><tr><td> </td><td></td></tr><tr><td>a3</td></tr></table>
        <table><tbody><tr><th>b1</th></tr></tbody></table>
        """;

    assertThat(extract(html))
        .containsExactly(
            new TextChunk(FILE, "T1!R1", "a1 | a2"),
            new TextChunk(FILE, "T1!R3", "a3"),
            new TextChunk(FILE, "T2!R1", "b1"));
  }

  @Test
  void plainTextWithoutTagsIsOneBlock() throws Exception {
    assertThat(extract("Z9X8C7V6 is shortlisted"))
        .containsExactly(new TextChunk(FILE, "B1", "Z9X8C7V6 is shortlisted"));
  }

  @Test
  void emptyOrTextlessHtmlYieldsNoChunks() throws Exception {
    assertThat(extract("")).isEmpty();
    assertThat(extract("<html><head><title>t</title></head><body>  <p> </p><br></body></html>"))
        .isEmpty();
  }

  @Test
  void brokenMarkupAndBinaryNoiseNeverThrow() {
    assertThatCode(() -> extract("<table><tr><td>Z9X8C7V6<td>Asha</tr><p>unclosed <b>bold"))
        .doesNotThrowAnyException();
    assertThatCode(() -> TestExtraction.chunks(extractor, FILE, SyntheticFiles.noise(4096)))
        .doesNotThrowAnyException();
  }

  @Test
  void brokenTableMarkupStillYieldsTheRow() throws Exception {
    assertThat(extract("<table><tr><td>Z9X8C7V6<td>Asha Example</table>"))
        .containsExactly(new TextChunk(FILE, "T1!R1", "Z9X8C7V6 | Asha Example"));
  }

  private List<TextChunk> extract(String html) throws ExtractionException {
    return TestExtraction.chunks(extractor, FILE, html.getBytes(UTF_8));
  }
}
