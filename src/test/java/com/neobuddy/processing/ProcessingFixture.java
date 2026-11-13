package com.neobuddy.processing;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.neobuddy.ingestion.AttachmentRef;
import com.neobuddy.ingestion.RawEmail;
import com.neobuddy.processing.classify.TestClassifierRules;
import com.neobuddy.processing.extract.CsvExtractor;
import com.neobuddy.processing.extract.DocxExtractor;
import com.neobuddy.processing.extract.ExcelExtractor;
import com.neobuddy.processing.extract.ExtractionProperties;
import com.neobuddy.processing.extract.ExtractorRegistry;
import com.neobuddy.processing.extract.HtmlBodyExtractor;
import com.neobuddy.processing.extract.PdfExtractor;
import com.neobuddy.processing.extract.PlainTextExtractor;
import com.neobuddy.processing.extract.SafeExtractionRunner;
import com.neobuddy.processing.extract.TypeDetector;
import com.neobuddy.processing.match.InMemoryIdentifierIndex;
import com.neobuddy.processing.match.Matcher;
import com.neobuddy.processing.match.Tokenizer;
import com.neobuddy.shared.college.College;
import com.neobuddy.shared.college.CollegeStatus;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.util.unit.DataSize;

/**
 * The whole processing pipeline wired by hand, with no Spring context, no database and no Gmail:
 * the real extractors, the classifier rules shipped in {@code application.yml}, the real tokenizer,
 * index and matcher, and fakes only at the ports (identifier source, result sink, event publisher).
 *
 * <p>Every name, NeoID and registration number in these tests is invented.
 */
final class ProcessingFixture {

  final FakeIdentifierSource identifiers = new FakeIdentifierSource();
  final InMemoryIdentifierIndex index = new InMemoryIdentifierIndex(Optional.of(identifiers));
  final List<Object> publishedEvents = new ArrayList<>();
  final List<ProcessingResult> savedResults = new ArrayList<>();
  final Matcher matcher;
  final ProcessingService service;

  ProcessingFixture() {
    ExtractionProperties properties =
        new ExtractionProperties(
            DataSize.ofMegabytes(25), DataSize.ofMegabytes(5), Duration.ofSeconds(30), 2);
    ExtractorRegistry registry =
        new ExtractorRegistry(
            List.of(
                new ExcelExtractor(properties),
                new PdfExtractor(),
                new DocxExtractor(),
                new HtmlBodyExtractor(),
                new PlainTextExtractor(),
                new CsvExtractor()),
            new TypeDetector(),
            new SafeExtractionRunner(properties),
            properties);
    this.matcher = new Matcher(new Tokenizer(), index);
    this.service =
        new ProcessingService(
            registry,
            TestClassifierRules.classifier(TestClassifierRules.shipped()),
            matcher,
            publishedEvents::add,
            Optional.of(savedResults::add));
  }

  static College college(String slug) {
    return new College(
        UUID.randomUUID(),
        slug,
        "College " + slug,
        List.of(slug + ".example"),
        List.of("placements@" + slug + ".example"),
        College.DEFAULT_NEO_ID_PATTERN,
        CollegeStatus.PILOT,
        Instant.parse("2026-01-01T00:00:00Z"));
  }

  /** Registers an identifier for a user and rebuilds the index, as IdentifiersChanged would. */
  UUID register(College college, UUID userId, String normalizedValue) {
    UUID identifierId = identifiers.add(college.getId(), userId, normalizedValue);
    index.rebuild();
    return identifierId;
  }

  List<MatchesFound> matchesFound() {
    return publishedEvents.stream()
        .filter(MatchesFound.class::isInstance)
        .map(MatchesFound.class::cast)
        .toList();
  }

  /** An email under construction, with its attachment content held in memory. */
  static final class Mail {

    private final String subject;
    private String text;
    private String html;
    private final Map<AttachmentRef, byte[]> attachments = new LinkedHashMap<>();
    private final List<AttachmentRef> unfetchable = new ArrayList<>();

    private Mail(String subject) {
      this.subject = subject;
    }

    static Mail subject(String subject) {
      return new Mail(subject);
    }

    Mail text(String body) {
      this.text = body;
      return this;
    }

    Mail html(String body) {
      this.html = body;
      return this;
    }

    Mail attach(String filename, byte[] content) {
      attachments.put(new AttachmentRef(filename, null, content.length), content);
      return this;
    }

    /** An attachment whose download fails. */
    Mail attachUnfetchable(String filename) {
      AttachmentRef ref = new AttachmentRef(filename, null, 1024);
      attachments.put(ref, new byte[0]);
      unfetchable.add(ref);
      return this;
    }

    RawEmail email() {
      return new RawEmail(
          "<" + UUID.randomUUID() + "@mail.example>",
          "gmail-" + UUID.randomUUID(),
          "placements@college.example",
          subject,
          Instant.parse("2026-11-13T10:00:00Z"),
          text,
          html,
          List.copyOf(attachments.keySet()));
    }

    AttachmentSource content() {
      return ref -> {
        if (unfetchable.contains(ref)) {
          throw new IOException("download failed");
        }
        return new ByteArrayInputStream(attachments.get(ref));
      };
    }
  }

  /** An .xlsx with one sheet; each row is a list of cell values (String or Number). */
  static byte[] xlsx(List<List<Object>> rows) {
    // Shared strings, as Excel and Google Sheets write them (SXSSF defaults to inline strings).
    try (SXSSFWorkbook workbook = new SXSSFWorkbook(new XSSFWorkbook(), 200, false, true)) {
      Sheet sheet = workbook.createSheet("Shortlist");
      for (int r = 0; r < rows.size(); r++) {
        Row row = sheet.createRow(r);
        List<Object> cells = rows.get(r);
        for (int c = 0; c < cells.size(); c++) {
          Object value = cells.get(c);
          if (value instanceof Number number) {
            row.createCell(c).setCellValue(number.doubleValue());
          } else {
            row.createCell(c).setCellValue(String.valueOf(value));
          }
        }
      }
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      workbook.write(out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Header plus {@code count} generated students: NeoID, name, numeric reg no, slot. */
  static List<List<Object>> studentRows(int count) {
    List<List<Object>> rows = new ArrayList<>(count + 1);
    rows.add(List.of("NeoID", "Name", "Reg No", "Slot"));
    for (int i = 1; i <= count; i++) {
      rows.add(List.of(generatedNeoId(i), "Student " + i, 2021000000L + i, "Slot " + (i % 8 + 1)));
    }
    return rows;
  }

  /** A made-up NeoID that can never collide with the hand-written ones in the tests. */
  static String generatedNeoId(int i) {
    return String.format("G%07d", i);
  }

  static byte[] utf8(String text) {
    return text.getBytes(UTF_8);
  }
}
