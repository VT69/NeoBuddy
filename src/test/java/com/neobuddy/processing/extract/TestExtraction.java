package com.neobuddy.processing.extract;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.stream.Stream;

/** Calls an extractor directly, the way {@link ExtractorRegistry} does, without the safety net. */
final class TestExtraction {

  private TestExtraction() {}

  static List<TextChunk> chunks(ContentExtractor extractor, String filename, byte[] content)
      throws ExtractionException {
    return chunks(extractor, SyntheticFiles.ref(filename, content), content);
  }

  static List<TextChunk> chunks(ContentExtractor extractor, AttachmentRef ref, byte[] content)
      throws ExtractionException {
    try (Stream<TextChunk> chunks = extractor.extract(new ByteArrayInputStream(content), ref)) {
      return chunks.toList();
    }
  }
}
