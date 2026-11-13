package com.neobuddy.processing.extract;

import static com.neobuddy.processing.extract.SyntheticFiles.SHORTLIST;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.neobuddy.processing.extract.ExtractionResult.Status;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.util.unit.DataSize;

@Timeout(30)
@ExtendWith(OutputCaptureExtension.class)
class ExtractorRegistryTests {

  private final List<SafeExtractionRunner> runners = new ArrayList<>();
  private final ExtractorRegistry registry = registry(SyntheticFiles.properties());

  @AfterEach
  void stopWorkers() {
    runners.forEach(SafeExtractionRunner::shutdown);
  }

  static Stream<Arguments> supportedFiles() {
    return Stream.of(
        Arguments.of("shortlist.xlsx", SyntheticFiles.xlsx(), DetectedType.XLSX),
        Arguments.of("shortlist.xls", SyntheticFiles.xls(), DetectedType.XLS),
        Arguments.of("shortlist.pdf", SyntheticFiles.pdfWithTable(), DetectedType.PDF),
        Arguments.of("shortlist.docx", SyntheticFiles.docxWithTable(), DetectedType.DOCX),
        Arguments.of("mail.html", SyntheticFiles.htmlEmail().getBytes(UTF_8), DetectedType.HTML),
        Arguments.of(
            "mail.txt", SyntheticFiles.plainTextEmail().getBytes(UTF_8), DetectedType.PLAIN_TEXT));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("supportedFiles")
  void extractsEverySupportedType(String filename, byte[] content, DetectedType type) {
    ExtractionResult result = extract(registry, filename, content);

    assertThat(result.status()).isEqualTo(Status.EXTRACTED);
    assertThat(result.type()).isEqualTo(type);
    assertThat(result.chunks())
        .isNotEmpty()
        .allMatch(chunk -> chunk.source().equals(filename))
        .anyMatch(chunk -> chunk.text().contains(SHORTLIST.get(0).neoId()));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("supportedFiles")
  void picksTheExtractorByContentNotByName(String filename, byte[] content, DetectedType type) {
    // Binary formats are recognised under any name; text formats need no particular name either.
    ExtractionResult result = extract(registry, "attachment.dat", content);

    assertThat(result.status()).isEqualTo(Status.EXTRACTED);
    assertThat(result.type()).isEqualTo(type);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("supportedFiles")
  void oversizedFilesAreSkippedWithAWarning(
      String filename, byte[] content, DetectedType type, CapturedOutput output) {
    CountingExtractor counting = new CountingExtractor();
    ExtractorRegistry small =
        registry(
            SyntheticFiles.properties(
                DataSize.ofBytes(content.length - 1), DataSize.ofMegabytes(5)),
            counting);

    ExtractionResult result = extract(small, filename, content);

    assertThat(result.status()).isEqualTo(Status.SKIPPED_TOO_LARGE);
    assertThat(result.chunks()).isEmpty();
    assertThat(counting.calls).hasValue(0);
    assertThat(output).contains("WARN").contains("Skipping attachment '" + filename + "'");
    assertNoContentLogged(output);
  }

  @Test
  void aFileExactlyAtTheLimitIsExtracted() {
    byte[] content = SyntheticFiles.xlsx();
    ExtractorRegistry exact =
        registry(
            SyntheticFiles.properties(DataSize.ofBytes(content.length), DataSize.ofMegabytes(5)));

    assertThat(extract(exact, "shortlist.xlsx", content).status()).isEqualTo(Status.EXTRACTED);
  }

  @Test
  void aFileThatUnderstatesItsSizeIsStillCaught(CapturedOutput output) {
    byte[] content = SyntheticFiles.xlsx();
    ExtractorRegistry small =
        registry(SyntheticFiles.properties(DataSize.ofBytes(1024), DataSize.ofMegabytes(5)));
    AttachmentRef lying = new AttachmentRef("shortlist.xlsx", null, 10);

    ExtractionResult result = small.extract(lying, new ByteArrayInputStream(content));

    assertThat(result.status()).isEqualTo(Status.SKIPPED_TOO_LARGE);
    assertThat(output).contains("Skipping attachment 'shortlist.xlsx'");
  }

  @Test
  void anOversizedDeclaredSizeIsSkippedWithoutReadingTheStream() {
    InputStream untouchable =
        new InputStream() {
          @Override
          public int read() throws IOException {
            throw new AssertionError("the stream must not be read");
          }
        };
    AttachmentRef huge = new AttachmentRef("huge.xlsx", null, DataSize.ofMegabytes(26).toBytes());

    assertThat(registry.extract(huge, untouchable).status()).isEqualTo(Status.SKIPPED_TOO_LARGE);
  }

  @Test
  void unknownTypesAreSkippedWithAWarningThatNamesOnlyTheFile(CapturedOutput output) {
    byte[] noise = SyntheticFiles.noise(4096);

    ExtractionResult result = extract(registry, "photo.jpg", noise);

    assertThat(result.status()).isEqualTo(Status.SKIPPED_UNSUPPORTED);
    assertThat(result.type()).isEqualTo(DetectedType.UNKNOWN);
    assertThat(result.chunks()).isEmpty();
    assertThat(output)
        .contains("WARN")
        .contains("Skipping attachment 'photo.jpg': no extractor for detected type UNKNOWN");
  }

  @Test
  void csvIsSkippedUntilT3RegistersItsExtractor(CapturedOutput output) {
    byte[] csv = "NeoID,Name\nZ9X8C7V6,Asha Example\n".getBytes(UTF_8);

    ExtractionResult result = extract(registry, "shortlist.csv", csv);

    assertThat(result.status()).isEqualTo(Status.SKIPPED_UNSUPPORTED);
    assertThat(result.type()).isEqualTo(DetectedType.CSV);
    assertThat(output).contains("no extractor for detected type CSV");
    assertNoContentLogged(output);
  }

  @Test
  void aCorruptFileFailsAloneAndLogsNoContent(CapturedOutput output) {
    byte[] corrupt = SyntheticFiles.truncated(SyntheticFiles.xlsx());

    ExtractionResult broken = extract(registry, "broken.xlsx", corrupt);
    ExtractionResult fine = extract(registry, "shortlist.xlsx", SyntheticFiles.xlsx());

    assertThat(broken.status()).isEqualTo(Status.FAILED);
    assertThat(broken.chunks()).isEmpty();
    assertThat(fine.status()).isEqualTo(Status.EXTRACTED);
    assertThat(output)
        .contains("Extraction failed for attachment 'broken.xlsx': Could not read the spreadsheet");
    assertNoContentLogged(output);
  }

  @Test
  void aSlowFileTimesOutAndTheNextOneStillWorks(CapturedOutput output) {
    ContentExtractor slowPdf =
        new ContentExtractor() {
          @Override
          public boolean supports(DetectedType type) {
            return type == DetectedType.PDF;
          }

          @Override
          public Stream<TextChunk> extract(InputStream in, AttachmentRef attachment) {
            try {
              Thread.sleep(10_000);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
            return Stream.empty();
          }
        };
    ExtractionProperties quick =
        new ExtractionProperties(
            DataSize.ofMegabytes(25), DataSize.ofMegabytes(5), Duration.ofMillis(300), 2);
    ExtractorRegistry impatient =
        registry(quick, slowPdf, new ExcelExtractor(quick), new PdfExtractor());

    ExtractionResult slow = extract(impatient, "slow.pdf", SyntheticFiles.pdfWithTable());
    ExtractionResult fine = extract(impatient, "shortlist.xlsx", SyntheticFiles.xlsx());

    assertThat(slow.status()).isEqualTo(Status.FAILED);
    assertThat(fine.status()).isEqualTo(Status.EXTRACTED);
    assertThat(output)
        .contains("Extraction failed for attachment 'slow.pdf': Extraction timed out");
  }

  @Test
  void aLazyStreamThatFailsLateIsStillContained() {
    ContentExtractor lazy =
        new ContentExtractor() {
          @Override
          public boolean supports(DetectedType type) {
            return type == DetectedType.PLAIN_TEXT;
          }

          @Override
          public Stream<TextChunk> extract(InputStream in, AttachmentRef attachment) {
            return Stream.of(1, 2, 3)
                .map(i -> {
                  if (i == 3) {
                    throw new IllegalStateException("late failure");
                  }
                  return new TextChunk(attachment.filename(), "L" + i, "line " + i);
                });
          }
        };
    ExtractorRegistry withLazy = registry(SyntheticFiles.properties(), lazy);

    ExtractionResult result = extract(withLazy, "notes.txt", "a\nb\nc\n".getBytes(UTF_8));

    assertThat(result.status()).isEqualTo(Status.FAILED);
    assertThat(result.chunks()).isEmpty();
  }

  @Test
  void aZeroByteAttachmentIsExtractedAsNothing() {
    ExtractionResult result = extract(registry, "empty.xlsx", new byte[0]);

    assertThat(result.status()).isEqualTo(Status.EXTRACTED);
    assertThat(result.chunks()).isEmpty();
  }

  @Test
  void anUnreadableStreamFails(CapturedOutput output) {
    InputStream broken =
        new InputStream() {
          @Override
          public int read() throws IOException {
            throw new IOException("connection reset");
          }
        };

    ExtractionResult result = registry.extract(new AttachmentRef("list.xlsx", null, 100), broken);

    assertThat(result.status()).isEqualTo(Status.FAILED);
    assertThat(output).contains("Could not read attachment 'list.xlsx'");
  }

  @Test
  void filenamesAreSanitisedBeforeLogging(CapturedOutput output) {
    String hostile = "evil\r\nFAKE LOG LINE.bin";

    extract(registry, hostile, SyntheticFiles.noise(512));

    assertThat(output).contains("Skipping attachment 'evil??FAKE LOG LINE.bin'");
    assertThat(output.toString()).doesNotContain("\nFAKE LOG LINE");
  }

  @Test
  void bodyPrefersHtmlAndFallsBackToPlainText() {
    List<TextChunk> html =
        registry.extractBody(SyntheticFiles.plainTextEmail(), SyntheticFiles.htmlEmail());
    List<TextChunk> text = registry.extractBody(SyntheticFiles.plainTextEmail(), " ");

    assertThat(html)
        .allMatch(chunk -> chunk.source().equals(ExtractorRegistry.BODY_SOURCE))
        .anyMatch(chunk -> chunk.location().equals("T1!R2"));
    assertThat(text)
        .contains(new TextChunk(ExtractorRegistry.BODY_SOURCE, "L4", "Z9X8C7V6 - Asha Example"));
    assertThat(registry.extractBody(null, null)).isEmpty();
    assertThat(registry.extractBody("", "  ")).isEmpty();
  }

  @Test
  void aPlainTextBodyThatLooksLikeHtmlIsStillReadAsText() {
    assertThat(registry.extractBody("<html> is how the form starts\nZ9X8C7V6", null))
        .extracting(TextChunk::location)
        .containsExactly("L1", "L2");
  }

  private ExtractorRegistry registry(ExtractionProperties properties, ContentExtractor... first) {
    SafeExtractionRunner runner = new SafeExtractionRunner(properties);
    runners.add(runner);
    List<ContentExtractor> extractors = new ArrayList<>(List.of(first));
    if (first.length == 0 || first[0] instanceof CountingExtractor) {
      extractors.addAll(
          List.of(
              new ExcelExtractor(properties),
              new PdfExtractor(),
              new DocxExtractor(),
              new HtmlBodyExtractor(),
              new PlainTextExtractor()));
    }
    return new ExtractorRegistry(extractors, new TypeDetector(), runner, properties);
  }

  private static ExtractionResult extract(
      ExtractorRegistry registry, String filename, byte[] content) {
    return registry.extract(
        SyntheticFiles.ref(filename, content), new ByteArrayInputStream(content));
  }

  private static void assertNoContentLogged(CapturedOutput output) {
    assertThat(output.toString())
        .doesNotContain(SHORTLIST.get(0).neoId())
        .doesNotContain(SHORTLIST.get(0).name())
        .doesNotContain("2021000123");
  }

  /** Supports everything and counts calls, to prove an extractor was never reached. */
  private static final class CountingExtractor implements ContentExtractor {

    private final AtomicInteger calls = new AtomicInteger();

    @Override
    public boolean supports(DetectedType type) {
      return true;
    }

    @Override
    public Stream<TextChunk> extract(InputStream in, AttachmentRef attachment) {
      calls.incrementAndGet();
      return Stream.empty();
    }
  }
}
