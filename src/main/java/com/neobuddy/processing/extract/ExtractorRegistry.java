package com.neobuddy.processing.extract;

import com.neobuddy.ingestion.AttachmentRef;
import com.neobuddy.processing.extract.ExtractionResult.Status;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Entry point of the extraction engine. Holds every {@link ContentExtractor}, picks one by the
 * detected type of the content, and applies the safety limits: size cap, time limit, and failure
 * isolation per file.
 *
 * <p>Nothing here throws for a bad file. Log lines carry the filename only, never content.
 */
@Component
public class ExtractorRegistry {

  private static final Logger log = LoggerFactory.getLogger(ExtractorRegistry.class);

  /** {@link TextChunk#source()} of chunks that come from the email body. */
  public static final String BODY_SOURCE = "body";

  private final List<ContentExtractor> extractors;
  private final TypeDetector detector;
  private final SafeExtractionRunner runner;
  private final long maxBytes;

  public ExtractorRegistry(
      List<ContentExtractor> extractors,
      TypeDetector detector,
      SafeExtractionRunner runner,
      ExtractionProperties properties) {
    this.extractors = List.copyOf(extractors);
    this.detector = detector;
    this.runner = runner;
    this.maxBytes = properties.maxAttachmentSize().toBytes();
  }

  /** The extractor for a type, if one is registered. */
  public Optional<ContentExtractor> find(DetectedType type) {
    return extractors.stream().filter(extractor -> extractor.supports(type)).findFirst();
  }

  /**
   * Extracts one attachment. Oversized and unsupported files are skipped with a WARN; corrupt,
   * encrypted or slow files are reported as {@link Status#FAILED}. The caller closes the stream.
   */
  public ExtractionResult extract(AttachmentRef attachment, InputStream content) {
    if (attachment.sizeBytes() > maxBytes) {
      return tooLarge(attachment);
    }
    byte[] bytes;
    try {
      // One byte more than allowed, so a file that lies about its size is still caught.
      bytes = content.readNBytes((int) maxBytes + 1);
    } catch (IOException e) {
      log.warn("Could not read attachment '{}'", attachment.filenameForLog());
      return ExtractionResult.without(attachment, Status.FAILED, DetectedType.UNKNOWN);
    }
    if (bytes.length > maxBytes) {
      return tooLarge(attachment);
    }
    if (bytes.length == 0) {
      return ExtractionResult.extracted(attachment, DetectedType.UNKNOWN, List.of());
    }
    try {
      return runner.run(() -> detectAndExtract(attachment, bytes));
    } catch (ExtractionException e) {
      log.warn(
          "Extraction failed for attachment '{}': {}", attachment.filenameForLog(), e.getMessage());
      return ExtractionResult.without(attachment, Status.FAILED, DetectedType.UNKNOWN);
    }
  }

  /**
   * Extracts the email body: the HTML part when there is one, otherwise the plain-text part. A body
   * that cannot be read yields no chunks.
   */
  public List<TextChunk> extractBody(String textBody, String htmlBody) {
    boolean html = htmlBody != null && !htmlBody.isBlank();
    String body = html ? htmlBody : textBody;
    if (body == null || body.isBlank()) {
      return List.of();
    }
    DetectedType type = html ? DetectedType.HTML : DetectedType.PLAIN_TEXT;
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    AttachmentRef ref =
        new AttachmentRef(BODY_SOURCE, html ? "text/html" : "text/plain", bytes.length);
    Optional<ContentExtractor> extractor = find(type);
    if (extractor.isEmpty()) {
      log.warn("No extractor registered for the email body (type {})", type);
      return List.of();
    }
    try {
      return runner.run(() -> readAll(extractor.get(), bytes, ref));
    } catch (ExtractionException e) {
      log.warn("Extraction failed for the email body: {}", e.getMessage());
      return List.of();
    }
  }

  private ExtractionResult detectAndExtract(AttachmentRef attachment, byte[] bytes)
      throws ExtractionException {
    DetectedType type = detector.detect(bytes, attachment);
    Optional<ContentExtractor> extractor = find(type);
    if (extractor.isEmpty()) {
      log.warn(
          "Skipping attachment '{}': no extractor for detected type {}",
          attachment.filenameForLog(),
          type);
      return ExtractionResult.without(attachment, Status.SKIPPED_UNSUPPORTED, type);
    }
    return ExtractionResult.extracted(
        attachment, type, readAll(extractor.get(), bytes, attachment));
  }

  /** Drains the stream here, inside the timed task, so lazy extractors are covered too. */
  private static List<TextChunk> readAll(
      ContentExtractor extractor, byte[] bytes, AttachmentRef attachment)
      throws ExtractionException {
    try (Stream<TextChunk> chunks =
        extractor.extract(new ByteArrayInputStream(bytes), attachment)) {
      return chunks.toList();
    }
  }

  private ExtractionResult tooLarge(AttachmentRef attachment) {
    log.warn(
        "Skipping attachment '{}': larger than the {} byte limit",
        attachment.filenameForLog(),
        maxBytes);
    return ExtractionResult.without(attachment, Status.SKIPPED_TOO_LARGE, DetectedType.UNKNOWN);
  }
}
