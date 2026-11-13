package com.neobuddy.processing.extract;

import static org.assertj.core.api.Assertions.assertThat;

import com.neobuddy.processing.extract.ExtractionResult.Status;
import com.neobuddy.testsupport.DatabaseTest;
import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.unit.DataSize;

/** The extraction beans as Spring wires them in the real application context. */
@DatabaseTest
class ExtractionWiringTests {

  @Autowired private ExtractorRegistry registry;
  @Autowired private ExtractionProperties properties;
  @Autowired private List<ContentExtractor> extractors;

  @Test
  void safetyLimitsDefaultToTheDesignedValues() {
    assertThat(properties.maxAttachmentSize()).isEqualTo(DataSize.ofMegabytes(25));
    assertThat(properties.excelStreamingThreshold()).isEqualTo(DataSize.ofMegabytes(5));
    assertThat(properties.timeout()).isEqualTo(Duration.ofSeconds(30));
    assertThat(properties.maxConcurrent()).isEqualTo(2);
  }

  @Test
  void everyExtractorIsRegistered() {
    assertThat(extractors)
        .extracting(extractor -> extractor.getClass().getSimpleName())
        .containsExactlyInAnyOrder(
            "ExcelExtractor",
            "PdfExtractor",
            "DocxExtractor",
            "HtmlBodyExtractor",
            "PlainTextExtractor",
            "CsvExtractor");
  }

  @Test
  void theWiredRegistryExtractsASpreadsheet() {
    byte[] content = SyntheticFiles.xlsx();

    ExtractionResult result =
        registry.extract(
            SyntheticFiles.ref("shortlist.xlsx", content), new ByteArrayInputStream(content));

    assertThat(result.status()).isEqualTo(Status.EXTRACTED);
    assertThat(result.chunks()).hasSize(7);
  }
}
