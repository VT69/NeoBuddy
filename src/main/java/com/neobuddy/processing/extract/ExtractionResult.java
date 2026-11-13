package com.neobuddy.processing.extract;

import java.util.List;

/**
 * Outcome of extracting one attachment. Only {@link Status#EXTRACTED} carries chunks; every other
 * status means the file contributed nothing and the rest of the email carries on.
 */
public record ExtractionResult(
    AttachmentRef attachment, Status status, DetectedType type, List<TextChunk> chunks) {

  public enum Status {
    EXTRACTED,
    SKIPPED_TOO_LARGE,
    SKIPPED_UNSUPPORTED,
    FAILED
  }

  public ExtractionResult {
    chunks = List.copyOf(chunks);
  }

  static ExtractionResult extracted(AttachmentRef att, DetectedType type, List<TextChunk> chunks) {
    return new ExtractionResult(att, Status.EXTRACTED, type, chunks);
  }

  static ExtractionResult without(AttachmentRef att, Status status, DetectedType type) {
    return new ExtractionResult(att, status, type, List.of());
  }
}
